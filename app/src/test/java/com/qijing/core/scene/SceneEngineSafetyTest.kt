package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandValidator
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.DryRunExecutionBroker
import com.qijing.core.execution.RootExecutionBroker
import com.qijing.core.logging.InMemoryTaskLogStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.SceneProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneEngineSafetyTest {
    private val scene = SceneProfile("safe", "Safe", setOf("com.demo"), cpu = CpuIntent(governor = "performance"))

    @Test fun `preflight rejection prevents every write`() = runBlocking {
        val broker = RecordingValidatingBroker(ExecutionResult.Unsupported("cpu.governor.set", "not writable"))
        val result = SceneEngine(broker, InMemoryTaskLogStore()).apply(scene)
        assertTrue(result.failure is ExecutionResult.Unsupported)
        assertEquals(0, broker.calls)
    }

    @Test fun `missing snapshot prevents privileged write`() = runBlocking {
        val broker = RecordingValidatingBroker(null)
        val snapshots = SceneSnapshotManager(object : CapabilityValueReader {
            override suspend fun read(capability: String): String? = null
        })
        val result = SceneEngine(broker, InMemoryTaskLogStore(), snapshots).apply(scene)
        assertEquals("SNAPSHOT_INCOMPLETE", (result.failure as ExecutionResult.Failed).code)
        assertEquals(0, broker.calls)
    }

    @Test fun `failed command attempts its own restore for partial write safety`() = runBlocking {
        val calls = mutableListOf<String>()
        val broker = object : ExecutionBroker, CommandValidator {
            override fun validate(command: CapabilityCommand): ExecutionResult? = null
            override suspend fun execute(command: CapabilityCommand): ExecutionResult {
                calls += command.capability
                return if (command.capability.endsWith(".restore")) ExecutionResult.Applied(ExecutionBackend.ROOT)
                else ExecutionResult.Failed("READBACK_MISMATCH", "write was not fully applied")
            }
        }
        val snapshots = SceneSnapshotManager(object : CapabilityValueReader {
            override suspend fun read(capability: String): String = "schedutil"
        })
        val result = SceneEngine(broker, InMemoryTaskLogStore(), snapshots).apply(scene)
        assertEquals(listOf("cpu.governor.set", "cpu.governor.set.restore"), calls)
        assertTrue(result.rolledBack)
    }

    @Test fun `prepare performs validation and snapshot without executing writes`() = runBlocking {
        val broker = RecordingValidatingBroker(null)
        val snapshots = SceneSnapshotManager(CapabilityValueReader { "schedutil" })
        val preparation = SceneEngine(broker, InMemoryTaskLogStore(), snapshots).prepare(scene)

        assertTrue(preparation.ready)
        assertNull(preparation.failure)
        assertEquals(0, broker.calls)
        assertEquals("schedutil", preparation.snapshot?.values?.get("cpu.governor.set"))
        assertEquals("schedutil", preparation.plan.commands.single().rollback?.arguments?.get("value"))
    }

    @Test fun `prepare blocks incomplete snapshot without writing or preview logging`() = runBlocking {
        val broker = RecordingValidatingBroker(null)
        val logs = InMemoryTaskLogStore()
        val snapshots = SceneSnapshotManager(CapabilityValueReader { null })

        val preparation = SceneEngine(broker, logs, snapshots).prepare(scene, recordFailureLog = false)

        assertFalse(preparation.ready)
        assertEquals("SNAPSHOT_INCOMPLETE", (preparation.failure as ExecutionResult.Failed).code)
        assertEquals(0, broker.calls)
        assertTrue(logs.recent().isEmpty())
    }

    @Test fun `apply prepares again and refuses a snapshot that became unavailable`() = runBlocking {
        val broker = RecordingValidatingBroker(null)
        var reads = 0
        val snapshots = SceneSnapshotManager(CapabilityValueReader {
            reads += 1
            if (reads == 1) "schedutil" else null
        })
        val engine = SceneEngine(broker, InMemoryTaskLogStore(), snapshots)

        val preview = engine.prepare(scene, recordFailureLog = false)
        val result = engine.apply(scene)

        assertTrue(preview.ready)
        assertEquals("SNAPSHOT_INCOMPLETE", (result.failure as ExecutionResult.Failed).code)
        assertEquals(2, reads)
        assertEquals(0, broker.calls)
    }

    @Test fun `dry run log says preview and never claims applied`() = runBlocking {
        val logs = InMemoryTaskLogStore()
        val result = SceneEngine(DryRunExecutionBroker(), logs).apply(scene)

        assertNull(result.failure)
        val log = logs.recent().single()
        assertTrue(log.stage.startsWith("preview:"))
        assertTrue(log.message.contains("未修改系统"))
        assertFalse(log.message.contains("Applied"))
    }

    @Test fun `empty scene is rejected instead of succeeding without work`() = runBlocking {
        val result = SceneEngine(DryRunExecutionBroker(), InMemoryTaskLogStore()).apply(
            SceneProfile("empty", "Empty", setOf("com.demo"))
        )

        assertEquals("SCENE_NO_WRITABLE_INTENT", (result.failure as ExecutionResult.Failed).code)
        assertTrue(result.applied.isEmpty())
    }

    @Test fun `real broker without snapshot reader is rejected before transport`() = runBlocking {
        var transportCalls = 0
        val broker = RootExecutionBroker {
            transportCalls += 1
            ""
        }

        val result = SceneEngine(broker, InMemoryTaskLogStore()).apply(scene)

        assertEquals("SNAPSHOT_UNAVAILABLE", (result.failure as ExecutionResult.Failed).code)
        assertEquals(0, transportCalls)
    }

    private class RecordingValidatingBroker(private val validation: ExecutionResult?) : ExecutionBroker, CommandValidator {
        var calls = 0
        override fun validate(command: CapabilityCommand): ExecutionResult? = validation
        override suspend fun execute(command: CapabilityCommand): ExecutionResult {
            calls++
            return ExecutionResult.Applied(ExecutionBackend.ROOT)
        }
    }
}
