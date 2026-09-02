package com.qijing.debug.tuning

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.logging.InMemoryTaskLogStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.scene.ActiveScene
import com.qijing.core.scene.BrokerSceneRestoreExecutor
import com.qijing.core.scene.SceneEngine
import com.qijing.core.scene.SceneSnapshotManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTuningExecutionBrokerTest {
    private val original = DebugTuningValues()

    @Test
    fun `snapshot captures governor min max and swappiness before first write`() = runBlocking {
        val store = InMemoryDebugTuningStateStore()
        val broker = DebugTuningExecutionBroker(store)
        val commands = commandsForTunedState()
        val snapshot = SceneSnapshotManager(broker).capture(commands)

        assertEquals("schedutil", snapshot.values["cpu.governor.set"])
        assertEquals("300000", snapshot.values["cpu.min_frequency.set"])
        assertEquals("2400000", snapshot.values["cpu.max_frequency.set"])
        assertEquals("60", snapshot.values["memory.swappiness.set"])

        commands.forEach { assertTrue(broker.execute(it) is ExecutionResult.Applied) }
        assertEquals(DebugTuningValues("performance", 600_000L, 1_800_000L, 80), broker.values())
    }

    @Test
    fun `invalid extra and inconsistent arguments never mutate state`() = runBlocking {
        val store = InMemoryDebugTuningStateStore()
        val broker = DebugTuningExecutionBroker(store)

        val injection = broker.execute(
            CapabilityCommand("cpu.governor.set", mapOf("value" to "schedutil; reboot"))
        )
        val extra = broker.execute(
            CapabilityCommand("memory.swappiness.set", mapOf("value" to "80", "shell" to "reboot"))
        )
        val inconsistent = broker.execute(
            CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to "3000000"))
        )

        assertTrue(injection is ExecutionResult.Failed)
        assertTrue(extra is ExecutionResult.Failed)
        assertEquals("SIM_INVALID_STATE", (inconsistent as ExecutionResult.Failed).code)
        assertEquals(original, store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
    }

    @Test
    fun `full scene applies and successful leave restores original state`() = runBlocking {
        val store = InMemoryDebugTuningStateStore()
        val broker = DebugTuningExecutionBroker(store)
        val engine = SceneEngine(broker, InMemoryTaskLogStore(), SceneSnapshotManager(broker))
        val transaction = engine.apply(tunedScene())

        assertEquals(null, transaction.failure)
        assertEquals(4, transaction.applied.size)
        assertEquals(DebugTuningValues("performance", 600_000L, 1_800_000L, 80), store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.Loaded)

        val restore = BrokerSceneRestoreExecutor(broker).restore(ActiveScene(tunedScene(), transaction))
        assertTrue(restore.succeeded)
        assertEquals(original, store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
    }

    @Test
    fun `after-write failure restores failing command then prior commands in reverse order`() = runBlocking {
        val failures = DebugFailureInjector(mutableListOf(
            DebugFailureRule("cpu.max_frequency.set", DebugFailurePhase.AFTER_WRITE_BEFORE_READBACK)
        ))
        val events = DebugTuningEventRecorder()
        val store = InMemoryDebugTuningStateStore()
        val broker = DebugTuningExecutionBroker(store, failures, events)
        val result = SceneEngine(broker, InMemoryTaskLogStore(), SceneSnapshotManager(broker)).apply(tunedScene())

        assertEquals("SIM_INJECTED_AFTER_WRITE_BEFORE_READBACK", (result.failure as ExecutionResult.Failed).code)
        assertTrue(result.rolledBack)
        assertEquals(original, store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
        assertEquals(
            listOf(
                "cpu.max_frequency.set.restore",
                "cpu.min_frequency.set.restore",
                "cpu.governor.set.restore"
            ),
            events.events.filter { it.action == "write" && it.capability.endsWith(".restore") }.map { it.capability }
        )
    }

    @Test
    fun `restart with pending journal restores in reverse order and clears journal`() = runBlocking {
        val store = InMemoryDebugTuningStateStore()
        val broker = DebugTuningExecutionBroker(store)
        commandsForTunedState().forEach { broker.execute(it) }
        val events = DebugTuningEventRecorder()

        val recovered = DebugRecoveryRunner(store, events = events).recoverPending() as DebugRecoveryResult.Recovered

        assertEquals(
            listOf(
                "memory.swappiness.set",
                "cpu.max_frequency.set",
                "cpu.min_frequency.set",
                "cpu.governor.set"
            ),
            recovered.restoredCapabilities
        )
        assertEquals(original, store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
        assertEquals(DebugRecoveryResult.NothingToRecover, DebugRecoveryRunner(store).recoverPending())
    }

    @Test
    fun `restart from write-started phase restores conservatively`() {
        val changed = original.copy(governor = "performance")
        val journal = DebugTuningJournal(
            "pending",
            original,
            listOf(DebugWriteRecord("cpu.governor.set", DebugWritePhase.WRITE_STARTED))
        )
        val store = InMemoryDebugTuningStateStore(changed, DebugJournalLoad.Loaded(journal))

        assertTrue(DebugRecoveryRunner(store).recoverPending() is DebugRecoveryResult.Recovered)
        assertEquals(original, store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
    }

    @Test
    fun `rollback failure retains journal for restart recovery`() = runBlocking {
        val failures = DebugFailureInjector(mutableListOf(
            DebugFailureRule("memory.swappiness.set.restore", DebugFailurePhase.DURING_ROLLBACK)
        ))
        val store = InMemoryDebugTuningStateStore()
        val broker = DebugTuningExecutionBroker(store, failures)
        val transaction = SceneEngine(broker, InMemoryTaskLogStore(), SceneSnapshotManager(broker)).apply(tunedScene())

        val result = BrokerSceneRestoreExecutor(broker).restore(ActiveScene(tunedScene(), transaction))

        assertFalse(result.succeeded)
        assertTrue(store.loadJournal() is DebugJournalLoad.Loaded)
        assertTrue(DebugRecoveryRunner(store).recoverPending() is DebugRecoveryResult.Recovered)
        assertEquals(original, store.loadValues())
    }

    @Test
    fun `corrupt journal fails closed and blocks new writes`() = runBlocking {
        val store = InMemoryDebugTuningStateStore().also { it.corruptJournal() }
        val broker = DebugTuningExecutionBroker(store)

        val result = broker.execute(CapabilityCommand("memory.swappiness.set", mapOf("value" to "80")))

        assertEquals("SIM_JOURNAL_CORRUPT", (result as ExecutionResult.Failed).code)
        assertEquals(original, store.loadValues())
        assertTrue(DebugRecoveryRunner(store).recoverPending() is DebugRecoveryResult.Failed)
    }

    @Test
    fun `unknown capability in persisted journal fails closed`() {
        val journal = DebugTuningJournal(
            "unknown-capability",
            original,
            listOf(DebugWriteRecord("device.reboot", DebugWritePhase.APPLIED))
        )
        val store = InMemoryDebugTuningStateStore(original, DebugJournalLoad.Loaded(journal))

        val result = DebugRecoveryRunner(store).recoverPending() as DebugRecoveryResult.Failed

        assertEquals("SIM_JOURNAL_CORRUPT", result.code)
        assertTrue(store.loadJournal() is DebugJournalLoad.Loaded)
        assertEquals(original, store.loadValues())
    }

    @Test
    fun `recreated broker blocks new writes until pending journal is recovered`() = runBlocking {
        val store = InMemoryDebugTuningStateStore()
        val firstBroker = DebugTuningExecutionBroker(store)
        firstBroker.execute(CapabilityCommand("cpu.governor.set", mapOf("value" to "performance")))
        val recreatedBroker = DebugTuningExecutionBroker(store)

        val blocked = recreatedBroker.execute(
            CapabilityCommand("memory.swappiness.set", mapOf("value" to "80"))
        ) as ExecutionResult.Failed

        assertEquals("SIM_RECOVERY_REQUIRED", blocked.code)
        assertEquals(60, store.loadValues().swappiness)
        assertTrue(DebugRecoveryRunner(store).recoverPending() is DebugRecoveryResult.Recovered)
        assertTrue(recreatedBroker.execute(CapabilityCommand("memory.swappiness.set", mapOf("value" to "80"))) is ExecutionResult.Applied)
    }

    @Test
    fun `standalone restore without journal is rejected without mutation`() = runBlocking {
        val store = InMemoryDebugTuningStateStore()
        val broker = DebugTuningExecutionBroker(store)

        val result = broker.execute(
            CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "80", "expected" to "60"))
        ) as ExecutionResult.Failed

        assertEquals("SIM_RESTORE_WITHOUT_JOURNAL", result.code)
        assertEquals(original, store.loadValues())
        assertTrue(store.loadJournal() is DebugJournalLoad.None)
    }

    private fun tunedScene() = SceneProfile(
        id = "gaming",
        name = "Gaming",
        packageNames = setOf("com.example.game"),
        cpu = CpuIntent(governor = "performance", minFrequencyKHz = 600_000L, maxFrequencyKHz = 1_800_000L),
        memory = MemoryIntent(swappiness = 80)
    )

    private fun commandsForTunedState() = listOf(
        CapabilityCommand("cpu.governor.set", mapOf("value" to "performance")),
        CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to "600000")),
        CapabilityCommand("cpu.max_frequency.set", mapOf("khz" to "1800000")),
        CapabilityCommand("memory.swappiness.set", mapOf("value" to "80"))
    )
}
