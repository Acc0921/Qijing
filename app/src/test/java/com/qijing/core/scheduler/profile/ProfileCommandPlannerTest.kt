package com.qijing.core.scheduler.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCommandPlannerTest {
    @Test fun `maps policy cpuset governor and limiter to structured commands`() {
        val origin = OperationOrigin("test", index = 0)
        val limiter = ProfileFeatureValue.ObjectValue(mapOf(
            "ddr_boost" to ProfileFeatureValue.BooleanValue(false),
            "limiters" to ProfileFeatureValue.ObjectValue(mapOf(
                "p1" to ProfileFeatureValue.ObjectValue(mapOf(
                    "core_ctl" to integerArray(0, 1),
                    "cpus" to ProfileFeatureValue.ArrayValue(listOf(
                        cpu("300000", "1000000", "150 600000:100", excludes = listOf(0), prefer = 1),
                        cpu("500000", "2000000", "200 1000000:130")
                    ))
                ))
            ))
        ))
        val program = CompiledProfileProgram(emptyMap(), mapOf("limiter" to limiter), emptyList(), emptyMap(), emptyList())
        val operations = listOf(
            ProfileOperation.Governor("walt", origin),
            ProfileOperation.CpuSet(ProfileValue("0-1"), ProfileValue("0-3"), ProfileValue("0-5"), ProfileValue("0-7"), origin),
            ProfileOperation.CpuFrequenciesMin(listOf(ProfileValue("300000"), ProfileValue("500000")), origin),
            ProfileOperation.Limiter("p1", origin)
        )

        val result = ProfileCommandPlanner().plan(program, operations, ProfileDeviceBinding(listOf(0, 6)))
            as ProfileCommandPlan.Planned

        assertEquals(10, result.commands.size)
        assertTrue(result.commands.any { it.capability == "scheduler.node.write" })
        val cluster = result.commands.first { it.capability == "scheduler.profile.limiter.cluster.set" }
        assertEquals("p1", cluster.arguments["profile"])
        assertEquals("0", cluster.arguments["policy"])
        assertEquals("0", cluster.arguments["core_ctl"])
        assertEquals("150 600000:100", cluster.arguments["margins"])
        assertEquals("0", cluster.arguments["excludes"])
        assertEquals("1", cluster.arguments["prefer"])
        assertEquals("false", cluster.arguments["ddr_boost"])
    }

    @Test fun `NONE emits an explicit limiter clear contract`() {
        val origin = OperationOrigin("test", index = 0)
        val empty = CompiledProfileProgram(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyList())

        val result = ProfileCommandPlanner().plan(
            empty,
            listOf(ProfileOperation.Limiter("NONE", origin)),
            ProfileDeviceBinding(listOf(0, 6))
        ) as ProfileCommandPlan.Planned

        assertEquals(
            listOf(com.qijing.core.execution.CapabilityCommand("scheduler.profile.limiter.clear", mapOf("scope" to "cpu_ddr"))),
            result.commands
        )
    }

    @Test fun `rejects limiter metadata instead of silently dropping unsupported fields`() {
        val origin = OperationOrigin("test", index = 0)
        val limiter = ProfileFeatureValue.ObjectValue(mapOf(
            "ddr_boost" to ProfileFeatureValue.BooleanValue(true),
            "limiters" to ProfileFeatureValue.ObjectValue(mapOf(
                "p1" to ProfileFeatureValue.ObjectValue(mapOf(
                    "cpus" to ProfileFeatureValue.ArrayValue(listOf(
                        cpu("300000", "1000000", "150", unknown = true),
                        cpu("500000", "2000000", "150")
                    ))
                ))
            ))
        ))
        val program = CompiledProfileProgram(emptyMap(), mapOf("limiter" to limiter), emptyList(), emptyMap(), emptyList())

        val result = ProfileCommandPlanner().plan(
            program,
            listOf(ProfileOperation.Limiter("p1", origin)),
            ProfileDeviceBinding(listOf(0, 6))
        ) as ProfileCommandPlan.Rejected

        assertEquals("PROFILE_LIMITER_CLUSTER_FIELD_UNSUPPORTED", result.code)
    }

    @Test fun `rejects malformed limiter margins with a stable code`() {
        val origin = OperationOrigin("test", index = 0)
        val limiter = ProfileFeatureValue.ObjectValue(mapOf(
            "ddr_boost" to ProfileFeatureValue.BooleanValue(false),
            "limiters" to ProfileFeatureValue.ObjectValue(mapOf(
                "p1" to ProfileFeatureValue.ObjectValue(mapOf(
                    "cpus" to ProfileFeatureValue.ArrayValue(listOf(
                        cpu("300000", "1000000", "150 broken"),
                        cpu("500000", "2000000", "150")
                    ))
                ))
            ))
        ))
        val program = CompiledProfileProgram(emptyMap(), mapOf("limiter" to limiter), emptyList(), emptyMap(), emptyList())

        val result = ProfileCommandPlanner().plan(
            program,
            listOf(ProfileOperation.Limiter("p1", origin)),
            ProfileDeviceBinding(listOf(0, 6))
        ) as ProfileCommandPlan.Rejected

        assertEquals("PROFILE_LIMITER_MARGINS_INVALID", result.code)
    }

    @Test fun `rejects a profile whose policy count does not match topology`() {
        val origin = OperationOrigin("test", index = 0)
        val empty = CompiledProfileProgram(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyList())
        val result = ProfileCommandPlanner().plan(
            empty,
            listOf(ProfileOperation.CpuFrequenciesMax(listOf(ProfileValue("1000000")), origin)),
            ProfileDeviceBinding(listOf(0, 6))
        )
        assertTrue(result is ProfileCommandPlan.Rejected)
    }

    @Test fun `accepts valid WALT hispeed thresholds outside the CPU OPP table`() {
        val origin = OperationOrigin("test", index = 0)
        val empty = CompiledProfileProgram(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyList())
        val binding = ProfileDeviceBinding(
            policyIds = listOf(0, 6),
            availableFrequenciesKHz = mapOf(
                0 to listOf(1152000, 1363200),
                6 to listOf(2246400, 2438400)
            )
        )

        val result = ProfileCommandPlanner().plan(
            empty,
            listOf(
                ProfileOperation.HispeedFrequencies(
                    listOf(ProfileValue("1344000"), ProfileValue("2380800")),
                    origin
                )
            ),
            binding
        ) as ProfileCommandPlan.Planned

        assertEquals(listOf("1344000", "2380800"), result.commands.map { it.arguments["value"] })
    }

    @Test fun `keeps CPU policy bounds strict against the OPP table`() {
        val origin = OperationOrigin("test", index = 0)
        val empty = CompiledProfileProgram(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyList())
        val result = ProfileCommandPlanner().plan(
            empty,
            listOf(ProfileOperation.CpuFrequenciesMin(listOf(ProfileValue("1344000")), origin)),
            ProfileDeviceBinding(listOf(0), mapOf(0 to listOf(1152000, 1363200)))
        ) as ProfileCommandPlan.Rejected

        assertEquals("PROFILE_FREQUENCY_UNSUPPORTED", result.code)
    }

    private fun cpu(
        min: String,
        max: String,
        margins: String,
        excludes: List<Int>? = null,
        prefer: Int? = null,
        unknown: Boolean = false
    ) = ProfileFeatureValue.ObjectValue(buildMap {
        put("min", ProfileFeatureValue.NumberValue(min))
        put("max", ProfileFeatureValue.NumberValue(max))
        put("margins", ProfileFeatureValue.StringValue(margins))
        excludes?.let { values -> put("excludes", integerArray(*values.toIntArray())) }
        prefer?.let { put("prefer", ProfileFeatureValue.NumberValue(it.toString())) }
        if (unknown) put("unmapped", ProfileFeatureValue.BooleanValue(true))
    })

    private fun integerArray(vararg values: Int) = ProfileFeatureValue.ArrayValue(
        values.map { ProfileFeatureValue.NumberValue(it.toString()) }
    )
}
