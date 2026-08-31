package com.qijing.core.execution

/** Explicit allowlist for the first read-only privileged slice. */
class CommandPolicy(private val allowedReadCapabilities: Set<String> = setOf("device.props.read", "cpu.status.read", "memory.status.read", "zram.status.read")) {
    fun check(command: CapabilityCommand): Result<Unit> = if (command.capability in allowedReadCapabilities) {
        Result.success(Unit)
    } else {
        Result.failure(IllegalArgumentException("命令未被第一版只读策略允许: ${command.capability}"))
    }
}
