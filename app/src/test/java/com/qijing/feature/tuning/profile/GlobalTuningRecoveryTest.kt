package com.qijing.feature.tuning.profile

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import com.qijing.core.scene.InMemorySceneTransactionJournalStore
import com.qijing.core.scene.SceneJournalLoad
import com.qijing.core.scene.SceneJournalPhase
import com.qijing.core.scene.SceneJournalRecord
import com.qijing.core.scene.SceneTransactionJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class GlobalTuningRecoveryTest {
    @Test fun `verified journal is committed as reversible baseline`() {
        val journalStore = InMemorySceneTransactionJournalStore()
        journalStore.current = SceneJournalLoad.Loaded(
            SceneTransactionJournal(
                "tx", "scene", "全局性能", null, ExecutionBackend.ROOT,
                listOf(
                    SceneJournalRecord(
                        "cpu.policy.0.governor.set",
                        CapabilityCommand("cpu.policy.0.governor.set.restore", mapOf("value" to "schedutil")),
                        SceneJournalPhase.APPLIED
                    )
                ),
                1L,
                revision = 2L
            )
        )
        val recovery = MemoryRecoveryStore()
        val previous = GlobalTuningConfiguration(
            selected = TuningProfileReference.BuiltIn(SchedulerMode.BALANCED),
            provider = SchedulerProviderId.SYSTEM,
            revision = 4L,
            updatedAtMs = 10L
        )

        assertTrue(commitVerifiedGlobalTransaction(journalStore, recovery, "性能", previous))
        assertTrue(journalStore.load() is SceneJournalLoad.None)
        val target = recovery.plan!!.toSceneProfile()
        assertEquals("schedutil", target.cpu.policies.single().governor)
        assertEquals(previous, recovery.plan!!.previousConfiguration)
    }

    @Test fun `generic node recovery becomes a forward typed command`() {
        val plan = GlobalTuningRecoveryPlan(
            ExecutionBackend.ROOT,
            listOf(
                CapabilityCommand(
                    "scheduler.node.write.restore",
                    mapOf(
                        "path" to "/sys/devices/system/cpu/cpufreq/boost",
                        "expected" to "1",
                        "value" to "0"
                    )
                )
            ),
            1L,
            "恢复"
        )

        val command = plan.toForwardCommands().single()

        assertEquals("scheduler.node.write", command.capability)
        assertEquals(
            mapOf("path" to "/sys/devices/system/cpu/cpufreq/boost", "value" to "0"),
            command.arguments
        )
    }

    @Test fun `schema two round trip retains previous global configuration`() {
        val previous = GlobalTuningConfiguration(
            selected = TuningProfileReference.BuiltIn(SchedulerMode.POWER_SAVE),
            provider = SchedulerProviderId.QIJING_PROFILE,
            revision = 8L,
            updatedAtMs = 9L
        )
        val source = GlobalTuningRecoveryPlan(
            ExecutionBackend.ROOT,
            listOf(CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "60"))),
            11L,
            "恢复均衡",
            previous
        )

        val decoded = GlobalTuningRecoveryCodec.decode(GlobalTuningRecoveryCodec.encode(source))

        assertEquals(previous, decoded.previousConfiguration)
        assertEquals(source.commands, decoded.commands)
    }

    @Test fun `legacy schema one remains loadable and declares configuration unknown`() {
        val legacy = JSONObject(
            """{
              "schema":1,
              "backend":"ROOT",
              "created":11,
              "label":"旧恢复计划",
              "commands":[{
                "capability":"memory.swappiness.set.restore",
                "arguments":{"value":"60"}
              }]
            }""".trimIndent()
        )

        val decoded = GlobalTuningRecoveryCodec.decode(legacy)

        assertEquals(null, decoded.previousConfiguration)
    }

    private class MemoryRecoveryStore : GlobalTuningRecoveryStore {
        var plan: GlobalTuningRecoveryPlan? = null
        override fun load(): GlobalTuningRecoveryLoad = plan?.let(GlobalTuningRecoveryLoad::Loaded) ?: GlobalTuningRecoveryLoad.None
        override fun save(plan: GlobalTuningRecoveryPlan): Boolean { this.plan = plan; return true }
    }
}
