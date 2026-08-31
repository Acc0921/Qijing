package com.qijing.core.execution

/** Fixed privileged write templates shared by Root and Shizuku shell-UID transports. */
object PrivilegedWriteCommandMapper {
    sealed interface Result {
        data class Command(val shell: String) : Result
        data class Invalid(val reason: String) : Result
        data class Unsupported(val reason: String) : Result
    }

    fun map(command: CapabilityCommand): Result {
        val restore = command.capability.endsWith(RESTORE_SUFFIX)
        val capability = command.capability.removeSuffix(RESTORE_SUFFIX)
        if (capability in ZRAM_CAPABILITIES) {
            return Result.Unsupported("ZRAM rebuild is not enabled until device-specific rollback is verified")
        }
        return when (capability) {
            "cpu.governor.set" -> {
                val value = command.singleValue("value")
                    ?: return Result.Invalid("cpu.governor.set requires only a value argument")
                if (!GOVERNOR.matches(value)) return Result.Invalid("Invalid CPU governor")
                Result.Command(policyWrite("scaling_governor", value))
            }
            "cpu.min_frequency.set" -> mapFrequency(command, restore, "scaling_min_freq")
            "cpu.max_frequency.set" -> mapFrequency(command, restore, "scaling_max_freq")
            "memory.swappiness.set" -> {
                val value = command.singleValue("value")?.toIntOrNull()
                    ?: return Result.Invalid("memory.swappiness.set requires one integer value")
                if (value !in 0..200) return Result.Invalid("swappiness must be in 0..200")
                Result.Command(
                    "printf '%s\\n' '$value' > /proc/sys/vm/swappiness && " +
                        "[ \"\$(tr -d '[:space:]' < /proc/sys/vm/swappiness)\" = '$value' ]"
                )
            }
            else -> Result.Unsupported("Capability is not in the privileged write allowlist")
        }
    }

    fun validationResult(command: CapabilityCommand, codePrefix: String): ExecutionResult? = when (val mapped = map(command)) {
        is Result.Command -> null
        is Result.Invalid -> ExecutionResult.Failed("${codePrefix}_INVALID_ARGUMENT", mapped.reason, command.rollback)
        is Result.Unsupported -> ExecutionResult.Unsupported(command.capability, mapped.reason)
    }

    private fun mapFrequency(command: CapabilityCommand, restore: Boolean, node: String): Result {
        val argument = if (restore) "value" else "khz"
        val value = command.singleValue(argument)?.toLongOrNull()
            ?: return Result.Invalid("${command.capability} requires one integer $argument argument")
        if (value !in MIN_CPU_FREQUENCY_KHZ..MAX_CPU_FREQUENCY_KHZ) {
            return Result.Invalid("CPU frequency must be in $MIN_CPU_FREQUENCY_KHZ..$MAX_CPU_FREQUENCY_KHZ kHz")
        }
        return Result.Command(policyWrite(node, value.toString()))
    }

    private fun policyWrite(node: String, value: String): String =
        "for file in /sys/devices/system/cpu/cpufreq/policy*/$node; do " +
            "[ -e \"\$file\" ] || exit 1; printf '%s\\n' '$value' > \"\$file\" || exit 1; " +
            "[ \"\$(tr -d '[:space:]' < \"\$file\")\" = '$value' ] || exit 2; done"

    private fun CapabilityCommand.singleValue(name: String): String? =
        arguments.takeIf { it.keys == setOf(name) }?.get(name)?.takeIf(String::isNotBlank)

    private const val RESTORE_SUFFIX = ".restore"
    private const val MIN_CPU_FREQUENCY_KHZ = 100_000L
    private const val MAX_CPU_FREQUENCY_KHZ = 10_000_000L
    private val GOVERNOR = Regex("[A-Za-z0-9_-]{1,32}")
    private val ZRAM_CAPABILITIES = setOf("memory.zram.enabled", "memory.zram.size", "memory.zram.algorithm.set")
}
