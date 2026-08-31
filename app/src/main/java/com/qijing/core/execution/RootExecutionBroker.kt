package com.qijing.core.execution

import com.qijing.core.model.ExecutionBackend
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal transport boundary for an injectable `su -c` implementation. */
fun interface RootTransport {
    suspend fun execute(command: String): String
}

class RootTransportException(val code: String, message: String) : IllegalStateException(message)

/**
 * Runs only commands produced by [RootExecutionBroker]. Callers never pass shell text through this
 * transport directly.
 */
class ProcessSuTransport(
    private val suExecutable: File = File("/system/bin/su"),
    private val timeoutMs: Long = 5_000L
) : RootTransport {
    override suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Root command cannot be blank" }
        val process = try {
            ProcessBuilder(suExecutable.absolutePath, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (error: Throwable) {
            throw RootTransportException("ROOT_UNAVAILABLE", error.message ?: "Unable to start su")
        }

        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        while (true) {
            try {
                process.exitValue()
                break
            } catch (_: IllegalThreadStateException) {
                if (System.nanoTime() >= deadline) {
                    process.destroy()
                    throw RootTransportException("ROOT_TIMEOUT", "Root command timed out")
                }
                Thread.sleep(10L)
            }
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() != 0) {
            throw RootTransportException(
                "ROOT_COMMAND_FAILED",
                "su exited with ${process.exitValue()}${output.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            )
        }
        output
    }
}

/**
 * Root write broker with a deliberately small, typed allowlist.
 *
 * Every shell fragment is fixed here. Values are parsed and bounded before they can be inserted;
 * unknown arguments, unknown capabilities, and ZRAM rebuild requests never reach the transport.
 */
class RootExecutionBroker(private val transport: RootTransport) : ExecutionBroker, CommandValidator, RequiresRollbackSnapshot, ExecutionBackendProvider {
    override val executionBackend: ExecutionBackend = ExecutionBackend.ROOT
    override fun validate(command: CapabilityCommand): ExecutionResult? =
        PrivilegedWriteCommandMapper.validationResult(command, "ROOT")

    override suspend fun execute(command: CapabilityCommand): ExecutionResult {
        validate(command)?.let { return it }
        val shell = when (val mapped = PrivilegedWriteCommandMapper.map(command)) {
            is PrivilegedWriteCommandMapper.Result.Command -> mapped.shell
            else -> error("Validated command did not produce a shell mapping")
        }

        return try {
            ExecutionResult.Applied(ExecutionBackend.ROOT, transport.execute(shell))
        } catch (error: RootTransportException) {
            ExecutionResult.Failed(error.code, error.message ?: "Root execution failed", command.rollback)
        } catch (error: Throwable) {
            ExecutionResult.Failed(
                "ROOT_EXECUTION_FAILED",
                error.message ?: "Root execution failed",
                command.rollback
            )
        }
    }

}
