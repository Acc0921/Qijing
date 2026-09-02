package com.qijing.core.execution

import com.qijing.core.model.ExecutionBackend
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
        broker.execute(CapabilityCommand("cpu.max_frequency.set.restore", mapOf("value" to "2400000", "expected" to "2800000")))
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

    @Test fun `process transport drains bounded output and returns it`() = runBlocking {
        val process = FakeProcess(running = false, stdout = "  applied\n")
        var launchedCommand = ""
        val transport = ProcessSuTransport(
            timeoutMs = 1_000L,
            maxOutputCharacters = 128,
            processLauncher = RootProcessLauncher { command ->
                launchedCommand = command
                process
            },
            processTreeTerminator = RootProcessTreeTerminator { false }
        )

        assertEquals("applied", transport.execute("fixed-command"))
        assertEquals("fixed-command", launchedCommand)
    }

    @Test fun `timeout returns only after the process tree is confirmed stopped`() = runBlocking {
        val process = FakeProcess(running = true)
        var terminationCalls = 0
        val transport = ProcessSuTransport(
            timeoutMs = 5L,
            maxOutputCharacters = 128,
            processLauncher = RootProcessLauncher { process },
            processTreeTerminator = RootProcessTreeTerminator {
                terminationCalls += 1
                process.finish(137)
                true
            }
        )

        val failure = rootTransportFailure { transport.execute("fixed-command") }

        assertEquals("ROOT_TIMEOUT", failure.code)
        assertEquals(1, terminationCalls)
        assertFalse(process.running)
    }

    @Test fun `timeout reports unconfirmed termination separately`() = runBlocking {
        val process = FakeProcess(running = true)
        val transport = ProcessSuTransport(
            timeoutMs = 5L,
            maxOutputCharacters = 128,
            processLauncher = RootProcessLauncher { process },
            processTreeTerminator = RootProcessTreeTerminator { false }
        )

        val failure = rootTransportFailure { transport.execute("fixed-command") }

        assertEquals("ROOT_TIMEOUT_TERMINATION_UNCONFIRMED", failure.code)
        assertTrue(process.running)
    }

    @Test fun `output hard limit terminates a still running command`() = runBlocking {
        val process = FakeProcess(running = true, stdout = "x".repeat(4_096))
        val transport = ProcessSuTransport(
            timeoutMs = 1_000L,
            maxOutputCharacters = 256,
            processLauncher = RootProcessLauncher { process },
            processTreeTerminator = RootProcessTreeTerminator {
                process.finish(137)
                true
            }
        )

        val failure = rootTransportFailure { transport.execute("fixed-command") }

        assertEquals("ROOT_OUTPUT_TOO_LARGE", failure.code)
        assertFalse(process.running)
    }

    @Test fun `linux terminator requires privileged tree verification`() {
        val root = FakeProcess(running = true, pidValue = 4242L)
        var killerArguments: List<String> = emptyList()
        val terminator = LinuxRootProcessTreeTerminator(
            suExecutable = File("/system/bin/su"),
            killerLauncher = { arguments ->
                killerArguments = arguments
                FakeProcess(running = false, exitCode = 0)
            }
        )

        assertTrue(terminator.terminate(root))
        assertFalse(root.running)
        assertEquals(File("/system/bin/su").absolutePath, killerArguments.first())
        assertTrue(killerArguments.last().contains("root='4242'"))
        assertTrue(killerArguments.last().contains("kill -KILL"))
    }

    @Test fun `linux terminator rejects failed privileged verification`() {
        val root = FakeProcess(running = true, pidValue = 4242L)
        val terminator = LinuxRootProcessTreeTerminator(
            suExecutable = File("/system/bin/su"),
            killerLauncher = { FakeProcess(running = false, exitCode = 42) }
        )

        assertFalse(terminator.terminate(root))
        assertFalse(root.running)
    }

    private suspend fun rootTransportFailure(block: suspend () -> Unit): RootTransportException =
        try {
            block()
            throw AssertionError("Expected RootTransportException")
        } catch (error: RootTransportException) {
            error
        }

    private class RecordingRootTransport : RootTransport {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): String {
            commands += command
            return "ok"
        }
    }

    private class FakeProcess(
        @Volatile var running: Boolean,
        private var exitCode: Int = 0,
        stdout: String = "",
        private val pidValue: Long = 4242L
    ) : Process() {
        @Suppress("unused")
        private val pid: Long = pidValue
        private val standardInput = ByteArrayOutputStream()
        private val standardOutput = ByteArrayInputStream(stdout.toByteArray())
        private val standardError = ByteArrayInputStream(ByteArray(0))

        fun finish(code: Int) {
            exitCode = code
            running = false
        }

        override fun getOutputStream() = standardInput
        override fun getInputStream() = standardOutput
        override fun getErrorStream() = standardError

        override fun waitFor(): Int {
            while (running) Thread.sleep(1L)
            return exitCode
        }

        override fun exitValue(): Int {
            if (running) throw IllegalThreadStateException("still running")
            return exitCode
        }

        override fun destroy() {
            finish(143)
        }

        override fun destroyForcibly(): Process {
            finish(137)
            return this
        }

        override fun isAlive(): Boolean = running
    }
}
