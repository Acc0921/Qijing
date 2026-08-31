package com.qijing.core.execution

import com.qijing.core.model.ExecutionBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootExecutionBrokerTest {
    @Test fun `maps governor frequency and swappiness through fixed commands`() = runBlocking {
        val transport = RecordingRootTransport()
        val broker = RootExecutionBroker(transport)

        assertEquals(
            ExecutionBackend.ROOT,
            (broker.execute(CapabilityCommand("cpu.governor.set", mapOf("value" to "schedutil"))) as ExecutionResult.Applied).backend
        )
        broker.execute(CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to "300000")))
        broker.execute(CapabilityCommand("cpu.max_frequency.set.restore", mapOf("value" to "2400000")))
        broker.execute(CapabilityCommand("memory.swappiness.set", mapOf("value" to "80")))

        assertTrue(transport.commands[0].contains("scaling_governor"))
        assertTrue(transport.commands[0].contains("'schedutil'"))
        assertTrue(transport.commands[1].contains("scaling_min_freq"))
        assertTrue(transport.commands[1].contains("'300000'"))
        assertTrue(transport.commands[2].contains("scaling_max_freq"))
        assertTrue(transport.commands[3].contains("/proc/sys/vm/swappiness"))
        assertTrue(transport.commands[3].contains("= '80'"))
    }

    @Test fun `rejects shell injection and unexpected argument keys before transport`() = runBlocking {
        val transport = RecordingRootTransport()
        val broker = RootExecutionBroker(transport)

        val injection = broker.execute(
            CapabilityCommand("cpu.governor.set", mapOf("value" to "schedutil; reboot"))
        )
        val extraArgument = broker.execute(
            CapabilityCommand("memory.swappiness.set", mapOf("value" to "60", "shell" to "reboot"))
        )

        assertEquals("ROOT_INVALID_ARGUMENT", (injection as ExecutionResult.Failed).code)
        assertEquals("ROOT_INVALID_ARGUMENT", (extraArgument as ExecutionResult.Failed).code)
        assertTrue(transport.commands.isEmpty())
    }

    @Test fun `rejects out of range numeric values`() = runBlocking {
        val transport = RecordingRootTransport()
        val broker = RootExecutionBroker(transport)

        val frequency = broker.execute(CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to "99999")))
        val swappiness = broker.execute(CapabilityCommand("memory.swappiness.set", mapOf("value" to "201")))

        assertTrue(frequency is ExecutionResult.Failed)
        assertTrue(swappiness is ExecutionResult.Failed)
        assertTrue(transport.commands.isEmpty())
    }

    @Test fun `keeps zram rebuild and unknown capabilities unsupported`() = runBlocking {
        val transport = RecordingRootTransport()
        val broker = RootExecutionBroker(transport)

        val zram = broker.execute(CapabilityCommand("memory.zram.size", mapOf("bytes" to "268435456")))
        val unknown = broker.execute(CapabilityCommand("device.reboot"))

        assertTrue(zram is ExecutionResult.Unsupported)
        assertTrue(unknown is ExecutionResult.Unsupported)
        assertTrue(transport.commands.isEmpty())
    }

    @Test fun `returns transport error code and preserves rollback`() = runBlocking {
        val rollback = CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "60"))
        val broker = RootExecutionBroker(RootTransport {
            throw RootTransportException("ROOT_TIMEOUT", "Root command timed out")
        })

        val result = broker.execute(
            CapabilityCommand("memory.swappiness.set", mapOf("value" to "80"), rollback)
        ) as ExecutionResult.Failed

        assertEquals("ROOT_TIMEOUT", result.code)
        assertEquals(rollback, result.rollback)
        assertFalse(result.message.isBlank())
    }

    private class RecordingRootTransport : RootTransport {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): String {
            commands += command
            return "ok"
        }
    }
}
