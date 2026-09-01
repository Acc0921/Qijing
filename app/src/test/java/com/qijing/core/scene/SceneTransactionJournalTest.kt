package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandValidator
import com.qijing.core.execution.ExecutionBackendProvider
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.RequiresRollbackSnapshot
import com.qijing.core.logging.InMemoryTaskLogStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTransactionJournalTest {
    private val scene = SceneProfile(
        id = "game",
        name = "Game",
        packageNames = setOf("com.demo"),
        cpu = CpuIntent(governor = "performance"),
        memory = MemoryIntent(swappiness = 80)
    )

    @Test fun `successful real apply persists recovery plan and normal restore clears it`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore()
        val events = InMemorySceneTaskEventStore()
        val broker = RecordingRealBroker()
        val snapshots = SceneSnapshotManager(CapabilityValueReader { capability ->
            if (capability == "memory.swappiness.set") "60" else "schedutil"
        })
        val engine = SceneEngine(broker, InMemoryTaskLogStore(), snapshots, store, events)

        val transaction = engine.apply(scene)

        assertNull(transaction.failure)
        val journal = (store.load() as SceneJournalLoad.Loaded).journal
        assertEquals(listOf(SceneJournalPhase.APPLIED, SceneJournalPhase.APPLIED), journal.records.map { it.phase })
        val restore = BrokerSceneRestoreExecutor(broker, store, events).restore(ActiveScene(scene, transaction))
        assertTrue(restore.succeeded)
        assertEquals(SceneJournalLoad.None, store.load())
        assertEquals(
            listOf("cpu.governor.set", "memory.swappiness.set", "memory.swappiness.set.restore", "cpu.governor.set.restore"),
            broker.calls
        )
        val phases = events.recent().map { it.phase }
        assertTrue(SceneTaskPhase.MATCHED in phases)
        assertTrue(SceneTaskPhase.SNAPSHOT in phases)
        assertTrue(SceneTaskPhase.VERIFIED in phases)
        assertTrue(SceneTaskPhase.ACTIVE in phases)
        assertEquals(SceneTaskPhase.RESTORED, phases.last())
    }

    @Test fun `journal persistence failure blocks first write`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore().apply { failWrites = true }
        val broker = RecordingRealBroker()
        val engine = SceneEngine(
            broker,
            InMemoryTaskLogStore(),
            SceneSnapshotManager(CapabilityValueReader { "schedutil" }),
            store
        )

        val result = engine.apply(scene.copy(memory = MemoryIntent()))

        assertEquals("JOURNAL_NOT_READY", (result.failure as ExecutionResult.Failed).code)
        assertTrue(broker.calls.isEmpty())
    }

    @Test fun `restart recovery restores only possibly written records in reverse order`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore()
        val journal = SceneTransactionJournal(
            transactionId = "tx",
            sceneId = "scene",
            sceneName = "Scene",
            packageName = "com.demo",
            backend = ExecutionBackend.ROOT,
            records = listOf(
                SceneJournalRecord("cpu.governor.set", CapabilityCommand("cpu.governor.set.restore", mapOf("value" to "schedutil")), SceneJournalPhase.APPLIED),
                SceneJournalRecord("memory.swappiness.set", CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "60")), SceneJournalPhase.WRITE_STARTED),
                SceneJournalRecord("cpu.min_frequency.set", CapabilityCommand("cpu.min_frequency.set.restore", mapOf("value" to "300000")), SceneJournalPhase.PENDING)
            ),
            createdAtMs = 1L
        )
        assertTrue(store.save(journal))
        val broker = RecordingRealBroker()

        val result = SceneJournalRecovery(store, broker).recoverPending()

        assertTrue(result.succeeded)
        assertEquals(2, result.restoredCommands)
        assertEquals(listOf("memory.swappiness.set.restore", "cpu.governor.set.restore"), broker.calls)
        assertEquals(SceneJournalLoad.None, store.load())
    }

    @Test fun `failed restart recovery keeps journal for another attempt`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore()
        val journal = SceneTransactionJournal(
            "tx", "scene", "Scene", "com.demo", ExecutionBackend.ROOT,
            listOf(SceneJournalRecord("cpu.governor.set", CapabilityCommand("cpu.governor.set.restore", mapOf("value" to "schedutil")), SceneJournalPhase.APPLIED)),
            1L
        )
        assertTrue(store.save(journal))
        val broker = RecordingRealBroker(failCapability = "cpu.governor.set.restore")

        val result = SceneJournalRecovery(store, broker).recoverPending()

        assertFalse(result.succeeded)
        assertTrue(store.load() is SceneJournalLoad.Loaded)
    }

    @Test fun `recovery refuses broker without a declared backend`() = runBlocking {
        val store = journalStoreWithOneAppliedRecord()
        val calls = mutableListOf<String>()
        val broker = object : ExecutionBroker {
            override suspend fun execute(command: CapabilityCommand): ExecutionResult {
                calls += command.capability
                return ExecutionResult.Applied(ExecutionBackend.ROOT)
            }
        }

        val result = SceneJournalRecovery(store, broker).recoverPending()

        assertEquals("JOURNAL_BACKEND_UNVERIFIED", (result.failure as ExecutionResult.Failed).code)
        assertTrue(calls.isEmpty())
        assertTrue(store.load() is SceneJournalLoad.Loaded)
    }

    @Test fun `recovery refuses broker for a different backend before executing`() = runBlocking {
        val store = journalStoreWithOneAppliedRecord()
        val broker = RecordingRealBroker(backend = ExecutionBackend.SHIZUKU)

        val result = SceneJournalRecovery(store, broker).recoverPending()

        assertEquals("JOURNAL_BACKEND_MISMATCH", (result.failure as ExecutionResult.Failed).code)
        assertTrue(broker.calls.isEmpty())
        assertTrue(store.load() is SceneJournalLoad.Loaded)
    }

    @Test fun `recovery keeps journal when applied result reports a different backend`() = runBlocking {
        val store = journalStoreWithOneAppliedRecord()
        val broker = RecordingRealBroker(resultBackend = ExecutionBackend.SHIZUKU)

        val result = SceneJournalRecovery(store, broker).recoverPending()

        assertEquals("JOURNAL_RESULT_BACKEND_MISMATCH", (result.failure as ExecutionResult.Failed).code)
        val journal = (store.load() as SceneJournalLoad.Loaded).journal
        assertEquals(SceneJournalPhase.APPLIED, journal.records.single().phase)
    }

    @Test fun `stale session cannot overwrite progress or clear a newer revision`() {
        val store = journalStoreWithOneAppliedRecord()
        val first = SceneJournalSession.resume(store, "tx")!!
        val stale = SceneJournalSession.resume(store, "tx")!!

        assertTrue(first.markRestored(0))
        assertFalse(stale.markWriteStarted(0))
        assertFalse(stale.clear())

        val current = (store.load() as SceneJournalLoad.Loaded).journal
        assertEquals(1L, current.revision)
        assertEquals(SceneJournalPhase.RESTORED, current.records.single().phase)
        assertTrue(first.clear())
    }

    @Test fun `create does not replace an existing transaction`() {
        val store = journalStoreWithOneAppliedRecord()
        val replacement = (store.load() as SceneJournalLoad.Loaded).journal.copy(
            transactionId = "new-tx",
            revision = 0L
        )

        assertFalse(store.save(replacement))
        assertEquals("tx", ((store.load() as SceneJournalLoad.Loaded).journal.transactionId))
    }

    @Test fun `failed command with incomplete rollback keeps durable recovery record`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore()
        val broker = RecordingRealBroker(failCapabilities = setOf("cpu.governor.set", "cpu.governor.set.restore"))
        val engine = SceneEngine(
            broker,
            InMemoryTaskLogStore(),
            SceneSnapshotManager(CapabilityValueReader { "schedutil" }),
            store
        )

        val result = engine.apply(scene.copy(memory = MemoryIntent()))

        assertFalse(result.rolledBack)
        val journal = (store.load() as SceneJournalLoad.Loaded).journal
        assertEquals(SceneJournalPhase.WRITE_STARTED, journal.records.single().phase)
    }

    @Test fun `completed rollback with journal clear failure remains recovery required`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore().apply { failClears = true }
        val events = InMemorySceneTaskEventStore()
        val broker = RecordingRealBroker(failCapability = "cpu.governor.set")
        val engine = SceneEngine(
            broker,
            InMemoryTaskLogStore(),
            SceneSnapshotManager(CapabilityValueReader { "schedutil" }),
            store,
            events
        )

        val result = engine.apply(scene.copy(memory = MemoryIntent()))

        assertFalse(result.rolledBack)
        val journal = (store.load() as SceneJournalLoad.Loaded).journal
        assertEquals(SceneJournalPhase.RESTORED, journal.records.single().phase)
        assertEquals(SceneTaskPhase.RECOVERY_REQUIRED, events.recent().last().phase)
    }

    @Test fun `normal restore refuses a different backend and keeps journal`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore()
        val root = RecordingRealBroker()
        val engine = SceneEngine(
            root,
            InMemoryTaskLogStore(),
            SceneSnapshotManager(CapabilityValueReader { "schedutil" }),
            store
        )
        val oneCommandScene = scene.copy(memory = MemoryIntent())
        val transaction = engine.apply(oneCommandScene)
        val wrongBackend = RecordingRealBroker(backend = ExecutionBackend.SHIZUKU)

        val restore = BrokerSceneRestoreExecutor(wrongBackend, store).restore(ActiveScene(oneCommandScene, transaction))

        assertFalse(restore.succeeded)
        assertTrue(wrongBackend.calls.isEmpty())
        assertTrue(store.load() is SceneJournalLoad.Loaded)
    }

    @Test fun `apply result from a different backend cannot close recovery journal`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore()
        val broker = RecordingRealBroker(resultBackend = ExecutionBackend.SHIZUKU)
        val engine = SceneEngine(
            broker,
            InMemoryTaskLogStore(),
            SceneSnapshotManager(CapabilityValueReader { "schedutil" }),
            store
        )

        val result = engine.apply(scene.copy(memory = MemoryIntent()))

        assertEquals("EXECUTION_BACKEND_MISMATCH", (result.failure as ExecutionResult.Failed).code)
        assertFalse(result.rolledBack)
        val journal = (store.load() as SceneJournalLoad.Loaded).journal
        assertEquals(SceneJournalPhase.WRITE_STARTED, journal.records.single().phase)
    }

    private fun journalStoreWithOneAppliedRecord(): InMemorySceneTransactionJournalStore =
        InMemorySceneTransactionJournalStore().also { store ->
            assertTrue(
                store.save(
                    SceneTransactionJournal(
                        "tx",
                        "scene",
                        "Scene",
                        "com.demo",
                        ExecutionBackend.ROOT,
                        listOf(
                            SceneJournalRecord(
                                "cpu.governor.set",
                                CapabilityCommand("cpu.governor.set.restore", mapOf("value" to "schedutil")),
                                SceneJournalPhase.APPLIED
                            )
                        ),
                        1L
                    )
                )
            )
        }

    private class RecordingRealBroker(
        failCapability: String? = null,
        private val failCapabilities: Set<String> = failCapability?.let(::setOf) ?: emptySet(),
        private val backend: ExecutionBackend = ExecutionBackend.ROOT,
        private val resultBackend: ExecutionBackend = backend
    ) : ExecutionBroker, CommandValidator, ExecutionBackendProvider, RequiresRollbackSnapshot {
        override val executionBackend: ExecutionBackend = backend
        val calls = mutableListOf<String>()
        override fun validate(command: CapabilityCommand): ExecutionResult? = null
        override suspend fun execute(command: CapabilityCommand): ExecutionResult {
            calls += command.capability
            return if (command.capability in failCapabilities) ExecutionResult.Failed("INJECTED", "failure")
            else ExecutionResult.Applied(resultBackend)
        }
    }
}
