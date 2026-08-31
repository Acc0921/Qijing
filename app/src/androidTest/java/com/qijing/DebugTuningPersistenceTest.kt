package com.qijing

import androidx.test.platform.app.InstrumentationRegistry
import com.qijing.core.execution.CapabilityCommand
import com.qijing.debug.tuning.DebugJournalLoad
import com.qijing.debug.tuning.DebugRecoveryResult
import com.qijing.debug.tuning.DebugRecoveryRunner
import com.qijing.debug.tuning.DebugSharedPreferencesStateStore
import com.qijing.debug.tuning.DebugTuningExecutionBroker
import com.qijing.debug.tuning.DebugTuningValues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTuningPersistenceTest {
    @Test
    fun pendingDebugTransactionSurvivesStoreRecreationAndRecovers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstStore = DebugSharedPreferencesStateStore(context).also { it.clear() }
        val broker = DebugTuningExecutionBroker(firstStore)

        broker.execute(CapabilityCommand("cpu.governor.set", mapOf("value" to "performance")))
        broker.execute(CapabilityCommand("memory.swappiness.set", mapOf("value" to "80")))

        val recreatedStore = DebugSharedPreferencesStateStore(context)
        assertTrue(recreatedStore.loadJournal() is DebugJournalLoad.Loaded)
        assertEquals("performance", recreatedStore.loadValues().governor)
        assertEquals(80, recreatedStore.loadValues().swappiness)

        assertTrue(DebugRecoveryRunner(recreatedStore).recoverPending() is DebugRecoveryResult.Recovered)
        assertEquals(DebugTuningValues(), recreatedStore.loadValues())
        assertTrue(recreatedStore.loadJournal() is DebugJournalLoad.None)
        assertTrue(recreatedStore.clear())
    }
}
