package com.qijing.core.execution

import com.qijing.core.model.ExecutionBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuExecutionBrokerTest {
    @Test fun `validated governor maps to fixed policy command`() = runBlocking {
        var shell = ""
        val broker = ShizukuExecutionBroker { shell = it; "ok" }
        val result = broker.execute(CapabilityCommand("cpu.governor.set", mapOf("value" to "schedutil")))
        assertTrue(result is ExecutionResult.Applied && result.backend == ExecutionBackend.SHIZUKU)
        assertTrue(shell.contains("policy*/scaling_governor"))
        assertTrue(shell.contains("'schedutil'"))
    }

    @Test fun `injection and extra arguments never reach transport`() = runBlocking {
        var calls = 0
        val broker = ShizukuExecutionBroker { calls++; "" }
        val result = broker.execute(CapabilityCommand("cpu.governor.set", mapOf("value" to "x; reboot", "extra" to "1")))
        assertTrue(result is ExecutionResult.Failed)
        assertEquals(0, calls)
    }

    @Test fun `unsupported zram is rejected before binding`() = runBlocking {
        var calls = 0
        val broker = ShizukuExecutionBroker { calls++; "" }
        val result = broker.execute(CapabilityCommand("memory.zram.size", mapOf("bytes" to "1024")))
        assertTrue(result is ExecutionResult.Unsupported)
        assertEquals(0, calls)
    }

    @Test fun `transport errors keep stable code and rollback`() = runBlocking {
        val rollback = CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "60"))
        val broker = ShizukuExecutionBroker { throw ShizukuTransportException("SHIZUKU_BINDER_FAILED", "binder died") }
        val result = broker.execute(CapabilityCommand("memory.swappiness.set", mapOf("value" to "80"), rollback))
        assertTrue(result is ExecutionResult.Failed)
        result as ExecutionResult.Failed
        assertEquals("SHIZUKU_BINDER_FAILED", result.code)
        assertEquals(rollback, result.rollback)
    }
}
