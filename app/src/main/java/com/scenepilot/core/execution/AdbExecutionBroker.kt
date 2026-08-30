package com.scenepilot.core.execution

import com.scenepilot.core.model.ExecutionBackend

interface AdbTransport {
    suspend fun shell(command: String): String
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
