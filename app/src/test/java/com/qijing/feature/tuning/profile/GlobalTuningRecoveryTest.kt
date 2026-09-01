package com.qijing.feature.tuning.profile

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scene.InMemorySceneTransactionJournalStore
import com.qijing.core.scene.SceneJournalLoad
import com.qijing.core.scene.SceneJournalPhase
import com.qijing.core.scene.SceneJournalRecord
import com.qijing.core.scene.SceneTransactionJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertTrue(commitVerifiedGlobalTransaction(journalStore, recovery, "性能"))
        assertTrue(journalStore.load() is SceneJournalLoad.None)
        val target = recovery.plan!!.toSceneProfile()
        assertEquals("schedutil", target.cpu.policies.single().governor)
    }

    private class MemoryRecoveryStore : GlobalTuningRecoveryStore {
        var plan: GlobalTuningRecoveryPlan? = null
        override fun load(): GlobalTuningRecoveryLoad = plan?.let(GlobalTuningRecoveryLoad::Loaded) ?: GlobalTuningRecoveryLoad.None
        override fun save(plan: GlobalTuningRecoveryPlan): Boolean { this.plan = plan; return true }
    }
}
