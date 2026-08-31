package com.qijing.core.execution

import com.qijing.core.model.ExecutionBackend
import java.io.File

interface AdbTransport {
    suspend fun shell(command: String): String
}

/** Optional desktop/companion transport. It only executes the broker's fixed read commands. */
class ProcessAdbTransport(
    private val adbExecutable: File,
    private val serial: String? = null,
    private val timeoutMs: Long = 5_000L
) : AdbTransport {
    override suspend fun shell(command: String): String {
        require(command.isNotBlank()) { "ADB command cannot be blank" }
        val args = buildList {
            add(adbExecutable.absolutePath)
            serial?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-s", it)) }
            addAll(listOf("shell", command))
        }
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        while (true) {
            try {
                process.exitValue()
                break
            } catch (_: IllegalThreadStateException) {
                if (System.nanoTime() >= deadline) {
                    process.destroy()
                    throw IllegalStateException("ADB command timed out")
                }
                Thread.sleep(10L)
            }
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) { "ADB exited with ${process.exitValue()}: $output" }
        return output.trim()
    }
}

/** Maps a small fixed set of capabilities to read-only shell commands. */
class ReadOnlyAdbExecutionBroker(private val transport: AdbTransport, private val policy: CommandPolicy = CommandPolicy()) : ExecutionBroker {
    override suspend fun execute(command: CapabilityCommand): ExecutionResult {
        policy.check(command).onFailure { return ExecutionResult.Unsupported(command.capability, it.message ?: "命令被拒绝") }
        val shell = when (command.capability) {
            "device.props.read" -> "getprop"
            "cpu.status.read" -> "cat /sys/devices/system/cpu/possible; cat /sys/devices/system/cpu/online"
            "memory.status.read" -> "cat /proc/meminfo"
            "zram.status.read" -> "cat /sys/block/zram0/disksize; cat /sys/block/zram0/comp_algorithm"
            else -> return ExecutionResult.Unsupported(command.capability, "没有只读命令映射")
        }
        return try {
            ExecutionResult.Applied(ExecutionBackend.ADB, transport.shell(shell))
        } catch (error: Throwable) {
            ExecutionResult.Failed("ADB_EXECUTION_FAILED", error.message ?: "ADB 执行失败")
        }
    }
}
