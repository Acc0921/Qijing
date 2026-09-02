package com.qijing.core.execution

import com.qijing.core.model.ExecutionBackend
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal transport boundary for an injectable `su -c` implementation. */
fun interface RootTransport {
    suspend fun execute(command: String): String
}

class RootTransportException(val code: String, message: String) : IllegalStateException(message)

internal fun interface RootProcessLauncher {
    fun start(command: String): Process
}

internal fun interface RootProcessTreeTerminator {
    /** Returns true only when the root process and every discovered descendant stopped. */
    fun terminate(process: Process): Boolean
}

/**
 * Runs only commands produced by [RootExecutionBroker]. Callers never pass shell text through this
 * transport directly.
 */
class ProcessSuTransport internal constructor(
    private val timeoutMs: Long,
    private val maxOutputCharacters: Int,
    private val processLauncher: RootProcessLauncher,
    private val processTreeTerminator: RootProcessTreeTerminator
) : RootTransport {
    constructor(
        suExecutable: File = File("/system/bin/su"),
        timeoutMs: Long = 15_000L
    ) : this(
        timeoutMs = timeoutMs,
        maxOutputCharacters = DEFAULT_MAX_OUTPUT_CHARACTERS,
        processLauncher = RootProcessLauncher { command ->
            ProcessBuilder(suExecutable.absolutePath, "-c", command)
                .redirectErrorStream(true)
                .start()
        },
        processTreeTerminator = LinuxRootProcessTreeTerminator(suExecutable)
    )

    override suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Root command cannot be blank" }
        val process = try {
            processLauncher.start(command)
        } catch (error: Throwable) {
            throw RootTransportException("ROOT_UNAVAILABLE", error.message ?: "Unable to start su")
        }

        val output = StringBuilder()
        val overflow = AtomicBoolean(false)
        val readFailure = AtomicReference<Throwable?>(null)
        val drain = Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(8 * 1024)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val remaining = maxOutputCharacters - output.length
                        if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                        if (count > remaining) overflow.set(true)
                    }
                }
            } catch (error: Throwable) {
                readFailure.set(error)
            }
        }, "qijing-root-output").apply { isDaemon = true; start() }

        val startedAt = System.nanoTime()
        try {
            while (isAlive(process)) {
                if (overflow.get()) {
                    throwAfterTermination(
                        process = process,
                        drain = drain,
                        code = "ROOT_OUTPUT_TOO_LARGE",
                        unconfirmedCode = "ROOT_OUTPUT_LIMIT_TERMINATION_UNCONFIRMED",
                        message = "Root output exceeded $maxOutputCharacters characters"
                    )
                }
                readFailure.get()?.let { failure ->
                    throwAfterTermination(
                        process = process,
                        drain = drain,
                        code = "ROOT_OUTPUT_FAILED",
                        unconfirmedCode = "ROOT_OUTPUT_READ_TERMINATION_UNCONFIRMED",
                        message = failure.message ?: "Unable to read Root output"
                    )
                }
                val elapsedNanos = System.nanoTime() - startedAt
                if (elapsedNanos >= timeoutMs.coerceAtLeast(1L) * NANOS_PER_MILLISECOND) {
                    throwAfterTermination(
                        process = process,
                        drain = drain,
                        code = "ROOT_TIMEOUT",
                        unconfirmedCode = "ROOT_TIMEOUT_TERMINATION_UNCONFIRMED",
                        message = "Root command timed out"
                    )
                }
                Thread.sleep(10L)
            }
        } catch (error: RootTransportException) {
            throw error
        } catch (error: Throwable) {
            if (isAlive(process)) {
                val terminated = processTreeTerminator.terminate(process)
                runCatching { process.inputStream.close() }
                drain.join(OUTPUT_DRAIN_JOIN_MS)
                if (!terminated) {
                    throw RootTransportException(
                        "ROOT_INTERRUPTED_TERMINATION_UNCONFIRMED",
                        "Root execution was interrupted and its process tree could not be confirmed stopped"
                    )
                }
            }
            throw error
        }

        drain.join(OUTPUT_DRAIN_JOIN_MS)
        if (drain.isAlive) throw RootTransportException("ROOT_OUTPUT_TIMEOUT", "Root output stream did not close")
        readFailure.get()?.let { throw RootTransportException("ROOT_OUTPUT_FAILED", it.message ?: "Unable to read Root output") }
        if (overflow.get()) throw RootTransportException("ROOT_OUTPUT_TOO_LARGE", "Root output exceeded $maxOutputCharacters characters")
        val outputText = output.toString().trim()
        if (process.exitValue() != 0) {
            throw RootTransportException(
                "ROOT_COMMAND_FAILED",
                "su exited with ${process.exitValue()}${outputText.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            )
        }
        outputText
    }

    private fun throwAfterTermination(
        process: Process,
        drain: Thread,
        code: String,
        unconfirmedCode: String,
        message: String
    ): Nothing {
        val terminated = processTreeTerminator.terminate(process)
        runCatching { process.inputStream.close() }
        drain.join(OUTPUT_DRAIN_JOIN_MS)
        if (!terminated) {
            throw RootTransportException(
                unconfirmedCode,
                "$message; the Root process tree could not be confirmed stopped"
            )
        }
        throw RootTransportException(code, message)
    }

    private companion object {
        const val DEFAULT_MAX_OUTPUT_CHARACTERS = 4 * 1024 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val OUTPUT_DRAIN_JOIN_MS = 500L
    }
}

/** Linux/Android implementation kept separate so termination can be deterministically tested. */
internal class LinuxRootProcessTreeTerminator(
    private val suExecutable: File,
    private val killerTimeoutMs: Long = 2_000L,
    private val killerLauncher: (List<String>) -> Process = { arguments ->
        ProcessBuilder(arguments).redirectErrorStream(true).start()
    }
) : RootProcessTreeTerminator {
    override fun terminate(process: Process): Boolean {
        val rootPid = processPid(process)
        val privilegedResult = rootPid?.let(::terminatePrivilegedTree) ?: false

        runCatching { process.destroy() }
        waitUntilExited(process, LOCAL_TERM_GRACE_MS)
        if (isAlive(process)) {
            runCatching { Process::class.java.getMethod("destroyForcibly").invoke(process) }
            waitUntilExited(process, LOCAL_KILL_GRACE_MS)
        }
        return privilegedResult && !isAlive(process)
    }

    private fun terminatePrivilegedTree(rootPid: Long): Boolean {
        val killer = runCatching {
            killerLauncher(
                listOf(
                    suExecutable.absolutePath,
                    "-c",
                    terminationShell(rootPid)
                )
            )
        }.getOrNull() ?: return false

        runCatching { killer.outputStream.close() }
        val exited = waitUntilExited(killer, killerTimeoutMs)
        if (!exited) {
            runCatching { killer.destroy() }
            runCatching { Process::class.java.getMethod("destroyForcibly").invoke(killer) }
            waitUntilExited(killer, LOCAL_KILL_GRACE_MS)
            return false
        }
        runCatching { killer.inputStream.close() }
        runCatching { killer.errorStream.close() }
        return runCatching { killer.exitValue() == 0 }.getOrDefault(false)
    }

    private fun terminationShell(rootPid: Long): String =
        "root='$rootPid'; " +
            "collect() { p=\"\$1\"; [ -d /proc/\"\$p\" ] || return; " +
            "kill -STOP \"\$p\" 2>/dev/null || true; " +
            "for c in \$(cat /proc/\"\$p\"/task/\"\$p\"/children 2>/dev/null); do collect \"\$c\"; done; " +
            "printf '%s\\n' \"\$p\"; }; " +
            "pids=\$(collect \"\$root\"); [ -z \"\$pids\" ] && exit 0; " +
            "kill -TERM \$pids 2>/dev/null || true; sleep 0.05; " +
            "kill -KILL \$pids 2>/dev/null || true; sleep 0.05; " +
            "for p in \$pids; do [ ! -d /proc/\"\$p\" ] && continue; " +
            "state=\$(cut -d ' ' -f 3 /proc/\"\$p\"/stat 2>/dev/null); " +
            "[ \"\$state\" = 'Z' ] || exit 42; done; exit 0"

    private fun processPid(process: Process): Long? {
        val viaMethod = runCatching {
            (Process::class.java.getMethod("pid").invoke(process) as Number).toLong()
        }.getOrNull()
        if (viaMethod != null) return viaMethod.takeIf(::validPid)

        var type: Class<*>? = process.javaClass
        while (type != null) {
            val value = runCatching {
                val field: Field = type.getDeclaredField("pid")
                field.isAccessible = true
                (field.get(process) as Number).toLong()
            }.getOrNull()
            if (value != null) return value.takeIf(::validPid)
            type = type.superclass
        }
        return null
    }

    private fun validPid(pid: Long): Boolean = pid in 2..MAX_LINUX_PID

    private companion object {
        const val MAX_LINUX_PID = 4_194_304L
        const val LOCAL_TERM_GRACE_MS = 150L
        const val LOCAL_KILL_GRACE_MS = 350L
    }
}

private fun isAlive(process: Process): Boolean = try {
    process.exitValue()
    false
} catch (_: IllegalThreadStateException) {
    true
}

private fun waitUntilExited(process: Process, timeoutMs: Long): Boolean {
    val startedAt = System.nanoTime()
    val timeoutNanos = timeoutMs.coerceAtLeast(0L) * 1_000_000L
    while (isAlive(process) && System.nanoTime() - startedAt < timeoutNanos) {
        Thread.sleep(5L)
    }
    return !isAlive(process)
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
