package com.qijing

import androidx.test.platform.app.InstrumentationRegistry
import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.ExecutionResult
import com.qijing.debug.tuning.DebugFailureInjector
import com.qijing.debug.tuning.DebugFailurePhase
import com.qijing.debug.tuning.DebugFailureRule
import com.qijing.debug.tuning.DebugJournalLoad
import com.qijing.debug.tuning.DebugRecoveryResult
import com.qijing.debug.tuning.DebugRecoveryRunner
import com.qijing.debug.tuning.DebugSharedPreferencesStateStore
import com.qijing.debug.tuning.DebugTuningEventRecorder
import com.qijing.debug.tuning.DebugTuningExecutionBroker
import com.qijing.debug.tuning.DebugTuningValues
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugTuningPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearBefore() = clearStore()
    @After fun clearAfter() = clearStore()

    @Test
    fun pendingDebugTransactionSurvivesStoreRecreationAndRecovers() = runBlocking {
        val firstStore = DebugSharedPreferencesStateStore(context)
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
    }

    @Test
    fun everySimulatedWriteIsReadBackAndRecoveryRestoresTheInitialSnapshot() = runBlocking {
        val store = DebugSharedPreferencesStateStore(context)
        val events = DebugTuningEventRecorder()
        val broker = DebugTuningExecutionBroker(store, events = events)
        val commands = listOf(
            CapabilityCommand("cpu.governor.set", mapOf("value" to "performance")),
            CapabilityCommand("cpu.max_frequency.set", mapOf("khz" to "2800000")),
            CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to "600000")),
            CapabilityCommand("memory.swappiness.set", mapOf("value" to "80"))
        )

        commands.forEach { command ->
            assertTrue("Expected simulated apply for ${command.capability}", broker.execute(command) is ExecutionResult.Applied)
        }
        assertEquals(DebugTuningValues("performance", 600_000L, 2_800_000L, 80), broker.values())
        assertEquals(1, events.events.count { it.action == "snapshot" })
        assertEquals(commands.size, events.events.count { it.action == "readback" })

        val recovery = DebugRecoveryRunner(store, events = events).recoverPending()
        assertTrue(recovery is DebugRecoveryResult.Recovered)
        assertEquals(commands.map { it.capability }.reversed(), (recovery as DebugRecoveryResult.Recovered).restoredCapabilities)
        assertEquals(DebugTuningValues(), store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
    }

    @Test
    fun readbackMismatchPersistsEvidenceAndBlocksFurtherWritesUntilRecovery() = runBlocking {
        val store = DebugSharedPreferencesStateStore(context)
        val failures = DebugFailureInjector(mutableListOf(
            DebugFailureRule("memory.swappiness.set", DebugFailurePhase.READBACK_MISMATCH)
        ))
        val command = CapabilityCommand("memory.swappiness.set", mapOf("value" to "90"))

        val failure = DebugTuningExecutionBroker(store, failures).execute(command)
        assertEquals("SIM_INJECTED_READBACK_MISMATCH", (failure as ExecutionResult.Failed).code)
        assertEquals(90, store.loadValues().swappiness)
        assertTrue(store.loadJournal() is DebugJournalLoad.Loaded)

        val restarted = DebugTuningExecutionBroker(DebugSharedPreferencesStateStore(context))
        val blocked = restarted.execute(CapabilityCommand("memory.swappiness.set", mapOf("value" to "70")))
        assertEquals("SIM_RECOVERY_REQUIRED", (blocked as ExecutionResult.Failed).code)

        assertTrue(DebugRecoveryRunner(store).recoverPending() is DebugRecoveryResult.Recovered)
        assertEquals(DebugTuningValues(), store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
    }

    @Test
    fun incompleteRecoveryKeepsJournalAndCanBeRetried() = runBlocking {
        val store = DebugSharedPreferencesStateStore(context)
        val command = CapabilityCommand("cpu.governor.set", mapOf("value" to "performance"))
        assertTrue(DebugTuningExecutionBroker(store).execute(command) is ExecutionResult.Applied)

        val failures = DebugFailureInjector(mutableListOf(
            DebugFailureRule("cpu.governor.set", DebugFailurePhase.DURING_ROLLBACK)
        ))
        val interrupted = DebugRecoveryRunner(store, failures).recoverPending()
        assertEquals("SIM_RECOVERY_INCOMPLETE", (interrupted as DebugRecoveryResult.Failed).code)
        assertEquals("performance", store.loadValues().governor)
        assertTrue(store.loadJournal() is DebugJournalLoad.Loaded)

        assertTrue(DebugRecoveryRunner(store).recoverPending() is DebugRecoveryResult.Recovered)
        assertEquals(DebugTuningValues(), store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
    }

    @Test
    fun corruptPersistedJournalFailsClosedWithoutChangingValues() = runBlocking {
        context.getSharedPreferences("qijing_debug_tuning_sim_v1", 0)
            .edit().putString("journal", "corrupt").commit()
        val store = DebugSharedPreferencesStateStore(context)
        val before = store.loadValues()

        val result = DebugTuningExecutionBroker(store).execute(
            CapabilityCommand("memory.swappiness.set", mapOf("value" to "100"))
        )

        assertEquals("SIM_JOURNAL_CORRUPT", (result as ExecutionResult.Failed).code)
        assertEquals(before, store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.Corrupt)
    }

    private fun clearStore() {
        DebugSharedPreferencesStateStore(context).clear()
    }
}
