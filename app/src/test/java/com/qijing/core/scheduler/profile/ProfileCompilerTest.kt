package com.qijing.core.scheduler.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCompilerTest {
    @Test
    fun `compiles all modes workloads phases and typed macros`() {
        val program = compile(baseProfile())

        assertEquals("/sys/devices/system/cpu/cpu0/online", program.aliases.getValue("cpu0_online").absolutePath)
        assertEquals(24, program.routes.size)
        assertTrue(program.features.getValue("enabled") is ProfileFeatureValue.BooleanValue)

        val active = program.routes.getValue(route(ProfileMode.POWER_SAVE, ProfileWorkload.APP, ProfilePhase.ACTIVE))
        assertTrue(active[0] is ProfileOperation.Write)
        assertTrue(active[1] is ProfileOperation.Values)
        assertTrue(active[2] is ProfileOperation.CpuSet)
        assertTrue(active[3] is ProfileOperation.CpuFrequenciesMin)
        assertTrue(active[4] is ProfileOperation.CpuFrequenciesMax)
        assertTrue(active[5] is ProfileOperation.TargetLoads)
        assertTrue(active[6] is ProfileOperation.HispeedFrequencies)
        assertTrue(active[7] is ProfileOperation.Governor)
        assertTrue(active[8] is ProfileOperation.Limiter)

        val aliasedWrite = active.first() as ProfileOperation.Write
        assertEquals("/sys/devices/system/cpu/cpu0/online", aliasedWrite.target.absolutePath)
        assertEquals(ProfileValue("1", ValueNotation.HASH_PREFIXED), aliasedWrite.value)

        val values = active[1] as ProfileOperation.Values
        assertEquals("targets", values.sourcePreset)
        assertEquals(listOf("/sys/a", "/proc/b"), values.targets.map(KernelNode::absolutePath))
        assertEquals(listOf("10", "20"), values.values.map(ProfileValue::text))

        val reset = program.reset
        assertTrue(reset[0] is ProfileOperation.PlatformReset)
        assertEquals("walt", (reset[1] as ProfileOperation.Governor).name)

        val game = program.routes.getValue(route(ProfileMode.POWER_SAVE, ProfileWorkload.GAME, ProfilePhase.ACTIVE))
        assertEquals(ProfileValue("60"), (game.single() as ProfileOperation.FrameRate).value)
        val call = program.routes.getValue(route(ProfileMode.POWER_SAVE, ProfileWorkload.CALL, ProfilePhase.ACTIVE))
        assertEquals("NONE", (call.single() as ProfileOperation.Limiter).profileName)
    }

    @Test
    fun `inactive route expands mode inactive preset for every workload`() {
        val program = compile(baseProfile())

        ProfileWorkload.entries.forEach { workload ->
            val operations = program.routes.getValue(route(ProfileMode.BALANCED, workload, ProfilePhase.INACTIVE))
            assertEquals(1, operations.size)
            val write = operations.single() as ProfileOperation.Write
            assertEquals("/sys/devices/system/cpu/cpu0/online", write.target.absolutePath)
            assertEquals("0", write.value.text)
        }
        assertTrue(
            program.routes.getValue(route(ProfileMode.BALANCED, ProfileWorkload.CALL, ProfilePhase.ACTIVE)).isEmpty()
        )
    }

    @Test
    fun `recursively expands presets and keeps operation origin chain`() {
        val program = compile(baseProfile())
        val active = program.routes.getValue(route(ProfileMode.POWER_SAVE, ProfileWorkload.APP, ProfilePhase.ACTIVE))
        val write = active.first() as ProfileOperation.Write

        // Origin describes the defining preset, independently of how often a cached preset is referenced.
        assertEquals(listOf("common"), write.origin.presetChain)
        assertEquals("presets.common", write.origin.section)
    }

    @Test
    fun `merges imported call and state across explicitly injected modes`() {
        val raw = profileWithImports()
        val imported = """
            {
              "friendly": "Camera rules",
              "call": [["@limiter", "NONE"]],
              "state": {
                "active": [["@cpu_freq", "policy0", "min", "1200000"]],
                "inactive": [["@cpu_freq", "policy0", "min", "600000"]]
              },
              "modes": [
                {
                  "mode": ["balance"],
                  "call": [["@governor", "walt"]],
                  "state": { "active": [["@fps", "120"]] },
                  "fas": { "freq": ["1000000", "2000000"] }
                },
                { "mode": ["pedestal"], "call": [["@fps", "144"]] }
              ]
            }
        """.trimIndent()
        val result = ProfileCompiler().compile(raw, mapOf("_Camera.json" to imported))
        assertTrue("expected compiled, got $result", result is ProfileCompileResult.Compiled)
        val rule = (result as ProfileCompileResult.Compiled).program.applicationRules.single()

        assertEquals(ProfileWorkload.APP, rule.workload)
        assertEquals(setOf("Camera"), rule.packageSelectors)
        val balance = rule.modes.getValue(ProfileMode.BALANCED)
        assertEquals(2, balance.call.size)
        assertTrue(balance.call[0] is ProfileOperation.Limiter)
        assertTrue(balance.call[1] is ProfileOperation.Governor)
        assertEquals(2, balance.active.size)
        val cpuFrequency = balance.active[0] as ProfileOperation.CpuFrequency
        assertEquals("policy0", cpuFrequency.policy)
        assertEquals(CpuFrequencyBound.MINIMUM, cpuFrequency.bound)
        assertEquals("1200000", cpuFrequency.value.text)
        assertTrue(balance.active[1] is ProfileOperation.FrameRate)
        assertEquals(1, balance.inactive.size)
        assertTrue(balance.metadata.containsKey("freq"))

        val powersave = rule.modes.getValue(ProfileMode.POWER_SAVE)
        assertEquals(1, powersave.call.size)
        assertEquals(1, powersave.active.size)
        assertEquals(1, powersave.inactive.size)
    }

    @Test
    fun `imports are explicit bounded and fail closed`() {
        assertRejected(profileWithImports(), "required import is missing")
        val safe = """{"friendly":"x","call":[],"state":{}}"""
        val unused = ProfileCompiler().compile(baseProfile(), mapOf("unused.json" to safe))
        assertTrue(unused is ProfileCompileResult.Rejected)
        assertTrue((unused as ProfileCompileResult.Rejected).reason.contains("unused imports"))

        val unknownMacro = """{"call":[["@exec","id"]]}"""
        assertRejected(profileWithImports(), "unknown macro", mapOf("_Camera.json" to unknownMacro))

        val unknownMode = """{"modes":[{"mode":["turbo"],"call":[]}]}"""
        assertRejected(profileWithImports(), "unknown mode", mapOf("_Camera.json" to unknownMode))

        val invalidPolicy = """{"state":{"active":[["@cpu_freq","cpu0","min","1"]]}}"""
        assertRejected(profileWithImports(), "invalid policy", mapOf("_Camera.json" to invalidPolicy))
    }

    @Test
    fun `retains feature tree as bounded typed data`() {
        val program = compile(baseProfile())
        val limiter = program.features.getValue("limiter") as ProfileFeatureValue.ObjectValue
        val entries = limiter.entries.getValue("profiles") as ProfileFeatureValue.ArrayValue

        assertEquals(ProfileFeatureValue.StringValue("p1"), entries.entries[0])
        assertEquals(ProfileFeatureValue.NumberValue("2"), entries.entries[1])
        assertEquals(ProfileFeatureValue.NullValue, entries.entries[2])
    }

    @Test
    fun `rejects preset cycles even when preset is unused`() {
        val raw = baseProfile().replace(
            "\"common\": [[\"${'$'}cpu0_online\", \"#1\"]]",
            "\"common\": [[\"@preset\", \"loop\"]], \"loop\": [[\"@preset\", \"common\"]]"
        )

        assertRejected(raw, "preset cycle")
    }

    @Test
    fun `rejects unknown macro in unused preset`() {
        val raw = baseProfile().replace(
            "\"common\": [[\"${'$'}cpu0_online\", \"#1\"]]",
            "\"common\": [[\"${'$'}cpu0_online\", \"#1\"]], \"dead\": [[\"@shell\", \"id\"]]"
        )

        assertRejected(raw, "unknown macro")
    }

    @Test
    fun `rejects paths outside bounded kernel roots and traversal`() {
        assertRejected(baseProfile().replace("/sys/a", "/data/local/tmp/a"), "outside allowed")
        assertRejected(baseProfile().replace("/sys/a", "/sys/a/../b"), "invalid path")
    }

    @Test
    fun `rejects unresolved alias and malformed alias name`() {
        assertRejected(baseProfile().replace("${'$'}cpu0_online", "${'$'}missing"), "unknown alias")
        assertRejected(baseProfile().replace("\"cpu0_online\":", "\"bad alias\":"), "invalid alias")
    }

    @Test
    fun `rejects duplicate aliases`() {
        val raw = baseProfile().replace(
            "\"cpu0_online\": \"/sys/devices/system/cpu/cpu0/online\"",
            "\"cpu0_online\": \"/sys/a\", \"cpu0_online\": \"/sys/b\""
        )

        assertTrue(ProfileCompiler().compile(raw) is ProfileCompileResult.Rejected)
    }

    @Test
    fun `values macro requires capture-only preset and matching arity`() {
        val mismatch = baseProfile().replace(
            "[\"@values\", \"targets\", \"10\", \"20\"]",
            "[\"@values\", \"targets\", \"10\"]"
        )
        assertRejected(mismatch, "value count")

        val writableTarget = baseProfile().replace("[\"/sys/a\"]", "[\"/sys/a\", \"1\"]")
        assertRejected(writableTarget, "capture targets")
    }

    @Test
    fun `rejects missing mode inactive contract and unknown schema keys`() {
        val missing = baseProfile().replace("\"fast_inactive\": [[\"${'$'}cpu0_online\", \"0\"]],", "")
        assertRejected(missing, "inactive preset fast_inactive")

        val unknown = baseProfile().replaceFirst("\"alias\":", "\"command\": \"id\", \"alias\":")
        assertRejected(unknown, "unknown key")
    }

    @Test
    fun `rejects control characters invalid macro arity and oversized input`() {
        assertRejected(baseProfile().replace("#1", "line\\nvalue"), "control characters")
        assertRejected(baseProfile().replace("[\"@msm_reset\"]", "[\"@msm_reset\", \"extra\"]"), "expects 0")
        val oversized = " ".repeat(1024 * 1024 + 1)
        assertRejected(oversized, "exceeds")
    }

    private fun compile(raw: String): CompiledProfileProgram {
        val result = ProfileCompiler().compile(raw)
        assertTrue("expected compiled, got $result", result is ProfileCompileResult.Compiled)
        return (result as ProfileCompileResult.Compiled).program
    }

    private fun assertRejected(raw: String, reasonFragment: String, imports: Map<String, String> = emptyMap()) {
        val result = ProfileCompiler().compile(raw, imports)
        assertTrue("expected rejection, got $result", result is ProfileCompileResult.Rejected)
        assertTrue(
            "expected rejection containing '$reasonFragment', got '${(result as ProfileCompileResult.Rejected).reason}'",
            result.reason.contains(reasonFragment)
        )
    }

    private fun route(mode: ProfileMode, workload: ProfileWorkload, phase: ProfilePhase) =
        ProfileRoute(mode, workload, phase)

    private fun profileWithImports(): String = baseProfile().replace(
        "\"schemes\": {",
        """
          "apps": [
            { "friendly": "Camera", "packages": ["Camera"], "import": "_Camera.json" }
          ],
          "schemes": {
        """.trimIndent()
    )

    private fun baseProfile(): String = """
        {
          "alias": {
            "cpu0_online": "/sys/devices/system/cpu/cpu0/online"
          },
          "features": {
            "enabled": true,
            "limiter": { "profiles": ["p1", 2, null] }
          },
          "reset": [
            ["@msm_reset"],
            ["@governor", "walt"]
          ],
          "presets": {
            "targets": [["/sys/a"], ["/proc/b"]],
            "common": [["${'$'}cpu0_online", "#1"]],
            "nested": [
              ["@preset", "common"],
              ["@values", "targets", "10", "20"],
              ["@cpuset", "0-1", "0-2", "0-3", "0-7"],
              ["@cpu_freqs_min", "300000", "500000"],
              ["@cpu_freqs_max", "#1800000", "^2000000"],
              ["@target_loads", "80 1000000:90", "85"],
              ["@hispeed_freq", "900000", "1200000"],
              ["@governor", "walt"],
              ["@limiter", "p1"]
            ],
            "powersave_inactive": [["${'$'}cpu0_online", "0"]],
            "balance_inactive": [["${'$'}cpu0_online", "0"]],
            "performance_inactive": [["${'$'}cpu0_online", "0"]],
            "fast_inactive": [["${'$'}cpu0_online", "0"]],
            "unused_capture": [["/dev/cpuset/top-app/cpus"]]
          },
          "schemes": {
            "powersave": {
              "app": [["@preset", "nested"]],
              "game": [["@fps", "60"]],
              "call": [["@limiter", "NONE"]]
            },
            "balance": { "app": [], "game": [] },
            "performance": { "app": [], "game": [] },
            "fast": { "app": [], "game": [] }
          }
        }
    """.trimIndent()
}
