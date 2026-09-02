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
                SceneJournalRecord(
                    "cpu.governor.set",
                    CapabilityCommand("cpu.governor.set.restore", mapOf("value" to "schedutil", "expected" to "performance")),
                    SceneJournalPhase.APPLIED,
                    target = CapabilityCommand("cpu.governor.set", mapOf("value" to "performance")),
                    originalValue = "schedutil",
                    appliedValue = "performance"
                ),
                SceneJournalRecord(
                    "memory.swappiness.set",
                    CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "60", "expected" to "80")),
                    SceneJournalPhase.WRITE_STARTED,
                    target = CapabilityCommand("memory.swappiness.set", mapOf("value" to "80")),
                    originalValue = "60",
                    appliedValue = "80"
                ),
                SceneJournalRecord(
                    "cpu.min_frequency.set",
                    CapabilityCommand("cpu.min_frequency.set.restore", mapOf("value" to "300000", "expected" to "500000")),
                    SceneJournalPhase.PENDING,
                    target = CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to "500000")),
                    originalValue = "300000",
                    appliedValue = "500000"
                )
            ),
            createdAtMs = 1L,
            schemaVersion = 2,
            bootId = DIFFERENT_BOOT_ID
        )
        assertTrue(store.save(journal))
        val broker = RecordingRealBroker()

        val result = SceneJournalRecovery(
            store,
            broker,
            CommandValueReader { command -> if (command.capability == "cpu.governor.set") "performance" else "80" }
        ) { CURRENT_BOOT_ID }.recoverPending()

        assertTrue(result.succeeded)
        assertEquals(2, result.restoredCommands)
        assertEquals(listOf("memory.swappiness.set.restore", "cpu.governor.set.restore"), broker.calls)
        assertEquals(SceneJournalLoad.None, store.load())
    }

    @Test fun `write started with unchanged original value closes without issuing rollback`() = runBlocking {
        val store = schemaTwoWriteStartedStore()
        val broker = RecordingRealBroker()

        val result = SceneJournalRecovery(store, broker, CommandValueReader { "60" }) { CURRENT_BOOT_ID }.recoverPending()

        assertTrue(result.succeeded)
        assertEquals(1, result.restoredCommands)
        assertTrue(broker.calls.isEmpty())
        assertEquals(SceneJournalLoad.None, store.load())
    }

    @Test fun `write started at applied value performs verified rollback`() = runBlocking {
        val store = schemaTwoWriteStartedStore()
        val broker = RecordingRealBroker()

        val result = SceneJournalRecovery(store, broker, CommandValueReader { "80" }) { CURRENT_BOOT_ID }.recoverPending()

        assertTrue(result.succeeded)
        assertEquals(listOf("memory.swappiness.set.restore"), broker.calls)
        assertEquals(SceneJournalLoad.None, store.load())
    }

    @Test fun `write started at a third value is locked without overwrite`() = runBlocking {
        val store = schemaTwoWriteStartedStore()
        val broker = RecordingRealBroker()

        val result = SceneJournalRecovery(store, broker, CommandValueReader { "100" }) { CURRENT_BOOT_ID }.recoverPending()

        assertEquals("JOURNAL_CURRENT_VALUE_CONFLICT", (result.failure as ExecutionResult.Failed).code)
        assertTrue(broker.calls.isEmpty())
        assertTrue(store.load() is SceneJournalLoad.Loaded)
    }

    @Test fun `same boot write started never races an unconfirmed Root process`() = runBlocking {
        val store = schemaTwoWriteStartedStore()
        val journal = (store.load() as SceneJournalLoad.Loaded).journal
        store.current = SceneJournalLoad.Loaded(journal.copy(bootId = CURRENT_BOOT_ID))
        val broker = RecordingRealBroker()
        var reads = 0

        val result = SceneJournalRecovery(store, broker, CommandValueReader { reads += 1; "80" }) {
            CURRENT_BOOT_ID
        }.recoverPending()

        assertEquals("JOURNAL_WRITE_PROCESS_UNVERIFIED", (result.failure as ExecutionResult.Failed).code)
        assertEquals(0, reads)
        assertTrue(broker.calls.isEmpty())
    }

    @Test fun `applied record at a third value is locked without overwrite`() = runBlocking {
        val store = journalStoreWithOneAppliedRecord()
        val broker = RecordingRealBroker()

        val result = SceneJournalRecovery(store, broker, CommandValueReader { "powersave" }).recoverPending()

        assertEquals("JOURNAL_CURRENT_VALUE_CONFLICT", (result.failure as ExecutionResult.Failed).code)
        assertTrue(broker.calls.isEmpty())
    }

    @Test fun `schema two rejects state values that do not match typed commands`() {
        val store = journalStoreWithOneAppliedRecord()
        val journal = (store.load() as SceneJournalLoad.Loaded).journal
        val tampered = journal.copy(
            transactionId = "tampered",
            records = journal.records.map { it.copy(appliedValue = "powersave") },
            revision = 0
        )
        val empty = InMemorySceneTransactionJournalStore()

        assertFalse(empty.save(tampered))
    }

    @Test fun `legacy write started record is conservatively locked`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore()
        assertTrue(
            store.save(
                SceneTransactionJournal(
                    "legacy", "scene", "Scene", "com.demo", ExecutionBackend.ROOT,
                    listOf(
                        SceneJournalRecord(
                            "memory.swappiness.set",
                            CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "60")),
                            SceneJournalPhase.WRITE_STARTED
                        )
                    ),
                    1L,
                    schemaVersion = 1
                )
            )
        )
        val broker = RecordingRealBroker()

        val result = SceneJournalRecovery(store, broker, CommandValueReader { "80" }).recoverPending()

        assertEquals("JOURNAL_WRITE_STATE_UNVERIFIED", (result.failure as ExecutionResult.Failed).code)
        assertTrue(broker.calls.isEmpty())
        assertTrue(store.load() is SceneJournalLoad.Loaded)
    }

    @Test fun `failed restart recovery keeps journal for another attempt`() = runBlocking {
        val store = InMemorySceneTransactionJournalStore()
        val journal = SceneTransactionJournal(
            "tx", "scene", "Scene", "com.demo", ExecutionBackend.ROOT,
            listOf(SceneJournalRecord(
                "cpu.governor.set",
                CapabilityCommand("cpu.governor.set.restore", mapOf("value" to "schedutil", "expected" to "performance")),
                SceneJournalPhase.APPLIED,
                target = CapabilityCommand("cpu.governor.set", mapOf("value" to "performance")),
                originalValue = "schedutil",
                appliedValue = "performance"
            )),
            1L,
            schemaVersion = 2
        )
        assertTrue(store.save(journal))
        val broker = RecordingRealBroker(failCapability = "cpu.governor.set.restore")

        val result = SceneJournalRecovery(store, broker, CommandValueReader { "performance" }).recoverPending()

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

        val result = SceneJournalRecovery(store, broker, CommandValueReader { "performance" }).recoverPending()

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

        val result = SceneJournalRecovery(store, broker, CommandValueReader { "performance" }).recoverPending()

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
                                CapabilityCommand("cpu.governor.set.restore", mapOf("value" to "schedutil", "expected" to "performance")),
                                SceneJournalPhase.APPLIED,
                                target = CapabilityCommand("cpu.governor.set", mapOf("value" to "performance")),
                                originalValue = "schedutil",
                                appliedValue = "performance"
                            )
                        ),
                        1L,
                        schemaVersion = 2
                    )
                )
            )
        }

    private fun schemaTwoWriteStartedStore(): InMemorySceneTransactionJournalStore =
        InMemorySceneTransactionJournalStore().also { store ->
            assertTrue(
                store.save(
                    SceneTransactionJournal(
                        "tx-v2",
                        "scene",
                        "Scene",
                        "com.demo",
                        ExecutionBackend.ROOT,
                        listOf(
                            SceneJournalRecord(
                                capability = "memory.swappiness.set",
                                rollback = CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "60", "expected" to "80")),
                                phase = SceneJournalPhase.WRITE_STARTED,
                                target = CapabilityCommand("memory.swappiness.set", mapOf("value" to "80")),
                                originalValue = "60",
                                appliedValue = "80"
                            )
                        ),
                        1L,
                        schemaVersion = 2,
                        bootId = DIFFERENT_BOOT_ID
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

    private companion object {
        const val DIFFERENT_BOOT_ID = "00000000-0000-0000-0000-000000000000"
        const val CURRENT_BOOT_ID = "11111111-1111-1111-1111-111111111111"
    }
}
