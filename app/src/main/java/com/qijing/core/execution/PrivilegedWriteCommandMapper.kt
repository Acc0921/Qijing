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
        POLICY_CAPABILITY.matchEntire(capability)?.let { match ->
            val policyId = match.groupValues[1].toIntOrNull()
                ?.takeIf { it in 0..255 }
                ?: return Result.Invalid("Invalid CPU policy ID")
            return when (match.groupValues[2]) {
                "governor" -> {
                    val value = command.singleValue("value")
                        ?: return Result.Invalid("$capability requires only a value argument")
                    if (!GOVERNOR.matches(value)) return Result.Invalid("Invalid CPU governor")
                    Result.Command(singlePolicyWrite(policyId, "scaling_governor", value))
                }
                "min_frequency" -> mapPolicyFrequency(command, restore, policyId, "scaling_min_freq")
                "max_frequency" -> mapPolicyFrequency(command, restore, policyId, "scaling_max_freq")
                else -> Result.Unsupported("CPU policy capability is not supported")
            }
        }
        return when (capability) {
            "scheduler.uperf.mode.set" -> mapUperfMode(command, restore)
            "scheduler.uperf_gt.mode.set" -> mapUperfGtMode(command, restore)
            "scheduler.fas_rs.mode.set" -> mapFasRsMode(command)
            "scheduler.config_bridge.mode.set" -> mapConfigBridgeMode(command)
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

    private fun mapPolicyFrequency(
        command: CapabilityCommand,
        restore: Boolean,
        policyId: Int,
        node: String
    ): Result {
        val argument = if (restore) "value" else "khz"
        val value = command.singleValue(argument)?.toLongOrNull()
            ?: return Result.Invalid("${command.capability} requires one integer $argument argument")
        if (value !in MIN_CPU_FREQUENCY_KHZ..MAX_CPU_FREQUENCY_KHZ) {
            return Result.Invalid("CPU frequency must be in $MIN_CPU_FREQUENCY_KHZ..$MAX_CPU_FREQUENCY_KHZ kHz")
        }
        return Result.Command(singlePolicyWrite(policyId, node, value.toString()))
    }

    private fun mapUperfMode(command: CapabilityCommand, restore: Boolean): Result {
        val value = command.singleValue("value")
            ?: return Result.Invalid("${command.capability} requires only a value argument")
        if (value !in if (restore) UPERF_RESTORE_MODES else SCHEDULER_MODES) {
            return Result.Invalid("Unsupported Uperf mode")
        }
        val identity = "[ -f /data/adb/modules/uperf/module.prop ] && " +
            "grep -qx 'id=uperf' /data/adb/modules/uperf/module.prop && [ -f /data/powercfg.sh ]"
        return Result.Command(
            "$identity && sh /data/powercfg.sh '$value' && " +
                "[ \"\$(tr -d '[:space:]' < /sdcard/Android/yc/uperf/cur_powermode.txt)\" = '$value' ]"
        )
    }

    private fun mapUperfGtMode(command: CapabilityCommand, restore: Boolean): Result {
        val mapped = mapUperfMode(command.copy(capability = "scheduler.uperf.mode.set"), restore)
        if (mapped !is Result.Command) return mapped
        val identity = "grep -qx 'name=Uperf Game Turbo' /data/adb/modules/uperf/module.prop"
        return Result.Command("$identity && ${mapped.shell}")
    }

    private fun mapFasRsMode(command: CapabilityCommand): Result {
        val value = command.singleValue("value")
            ?: return Result.Invalid("${command.capability} requires only a value argument")
        if (value !in SCHEDULER_MODES) return Result.Invalid("Unsupported fas-rs mode")
        val node = "/dev/fas_rs/mode"
        return Result.Command(
            "[ -f /data/adb/modules/fas-rs/module.prop ] && " +
                "grep -qx 'id=fas-rs' /data/adb/modules/fas-rs/module.prop && [ -e '$node' ] && " +
                "printf '%s\\n' '$value' > '$node' && " +
                "[ \"\$(tr -d '[:space:]' < '$node')\" = '$value' ]"
        )
    }

    private fun mapConfigBridgeMode(command: CapabilityCommand): Result {
        val value = command.singleValue("value")
            ?: return Result.Invalid("${command.capability} requires only a value argument")
        if (value !in SCHEDULER_MODES) return Result.Invalid("Unsupported configuration bridge mode")
        val base = "/data/adb/modules/Scene_Config_replace"
        val bridge = "$base/qijing"
        val identity = "[ -f '$base/module.prop' ] && " +
            "grep -qx 'id=Scene_Config_replace' '$base/module.prop' && " +
            "grep -qx 'qijing-scheduler-bridge-v1' '$bridge/contract' && [ -x '$bridge/apply-mode' ]"
        return Result.Command(
            "$identity && '$bridge/apply-mode' '$value' && " +
                "[ \"\$(tr -d '[:space:]' < '$bridge/current_mode')\" = '$value' ]"
        )
    }

    private fun policyWrite(node: String, value: String): String =
        "for file in /sys/devices/system/cpu/cpufreq/policy*/$node; do " +
            "[ -e \"\$file\" ] || exit 1; printf '%s\\n' '$value' > \"\$file\" || exit 1; " +
            "[ \"\$(tr -d '[:space:]' < \"\$file\")\" = '$value' ] || exit 2; done"

    private fun singlePolicyWrite(policyId: Int, node: String, value: String): String {
        val file = "/sys/devices/system/cpu/cpufreq/policy$policyId/$node"
        return "[ -e '$file' ] && printf '%s\\n' '$value' > '$file' && " +
            "[ \"\$(tr -d '[:space:]' < '$file')\" = '$value' ]"
    }

    private fun CapabilityCommand.singleValue(name: String): String? =
        arguments.takeIf { it.keys == setOf(name) }?.get(name)?.takeIf(String::isNotBlank)

    private const val RESTORE_SUFFIX = ".restore"
    private const val MIN_CPU_FREQUENCY_KHZ = 100_000L
    private const val MAX_CPU_FREQUENCY_KHZ = 10_000_000L
    private val GOVERNOR = Regex("[A-Za-z0-9_-]{1,32}")
    private val POLICY_CAPABILITY = Regex("cpu\\.policy\\.([0-9]{1,3})\\.(governor|min_frequency|max_frequency)\\.set")
    private val SCHEDULER_MODES = setOf("powersave", "balance", "performance", "fast")
    private val UPERF_RESTORE_MODES = SCHEDULER_MODES + "auto"
    private val ZRAM_CAPABILITIES = setOf("memory.zram.enabled", "memory.zram.size", "memory.zram.algorithm.set")
}
