package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandValidator
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.logging.InMemoryTaskLogStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.SceneProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            override fun read(capability: String): String? = null
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
            override fun read(capability: String): String = "schedutil"
        })
        val result = SceneEngine(broker, InMemoryTaskLogStore(), snapshots).apply(scene)
        assertEquals(listOf("cpu.governor.set", "cpu.governor.set.restore"), calls)
        assertTrue(result.rolledBack)
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
