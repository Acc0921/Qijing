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
                    val values = command.scalarValues(restore)
                        ?: return Result.Invalid("$capability has invalid value/expected arguments")
                    val value = values.first
                    if (!GOVERNOR.matches(value)) return Result.Invalid("Invalid CPU governor")
                    val expected = values.second
                    if (expected != null && !GOVERNOR.matches(expected)) return Result.Invalid("Invalid expected CPU governor")
                    Result.Command(if (restore) {
                        conditionalSinglePolicyWrite(policyId, "scaling_governor", value, expected!!)
                    } else singlePolicyWrite(policyId, "scaling_governor", value))
                }
                "min_frequency" -> mapPolicyFrequency(command, restore, policyId, "scaling_min_freq")
                "max_frequency" -> mapPolicyFrequency(command, restore, policyId, "scaling_max_freq")
                else -> Result.Unsupported("CPU policy capability is not supported")
            }
        }
        return when (capability) {
            "scheduler.node.write" -> mapNodeWrite(command, restore)
            "scheduler.profile.limiter.cluster.set" -> mapProfileLimiterCluster(command, restore)
            "scheduler.profile.limiter.clear" -> mapProfileLimiterClear(command)
            "scheduler.profile.gesture_boost.configure" -> mapGestureBoost(command, restore)
            "scheduler.profile.app_frequencies.set" -> mapProfileAppFrequencies(command, restore)
            "scheduler.thread.cpuset.set",
            "scheduler.thread.affinity.set",
            "scheduler.thread.nice.set",
            "scheduler.thread.policy.set" -> mapThreadMutation(command, capability, restore)
            "display.refresh_rate.set" -> mapRefreshRate(command, restore)
            "scheduler.uperf.mode.set" -> mapUperfMode(command, restore)
            "scheduler.uperf_gt.mode.set" -> mapUperfGtMode(command, restore)
            "scheduler.fas_rs.mode.set" -> mapFasRsMode(command)
            "scheduler.config_bridge.mode.set" -> mapConfigBridgeMode(command)
            "cpu.governor.set" -> {
                val values = command.scalarValues(restore)
                    ?: return Result.Invalid("cpu.governor.set has invalid value/expected arguments")
                val value = values.first
                if (!GOVERNOR.matches(value)) return Result.Invalid("Invalid CPU governor")
                val expected = values.second
                if (expected != null && !GOVERNOR.matches(expected)) return Result.Invalid("Invalid expected CPU governor")
                Result.Command(if (restore) {
                    conditionalPolicyWrite("scaling_governor", value, expected!!)
                } else policyWrite("scaling_governor", value))
            }
            "cpu.min_frequency.set" -> mapFrequency(command, restore, "scaling_min_freq")
            "cpu.max_frequency.set" -> mapFrequency(command, restore, "scaling_max_freq")
            "memory.swappiness.set" -> {
                val values = command.scalarValues(restore)
                    ?: return Result.Invalid("memory.swappiness.set has invalid value/expected arguments")
                val value = values.first.toIntOrNull()
                    ?: return Result.Invalid("memory.swappiness.set requires integer values")
                if (value !in 0..200) return Result.Invalid("swappiness must be in 0..200")
                val expected = values.second?.toIntOrNull()
                if (values.second != null && expected !in 0..200) return Result.Invalid("expected swappiness must be in 0..200")
                Result.Command(
                    if (restore) conditionalFileWrite("/proc/sys/vm/swappiness", value.toString(), expected.toString())
                    else "printf '%s\\n' '$value' > /proc/sys/vm/swappiness && " +
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
        val values = command.frequencyValues(restore)
            ?: return Result.Invalid("${command.capability} has invalid frequency arguments")
        val value = values.first.toLongOrNull()
            ?: return Result.Invalid("${command.capability} requires integer frequency values")
        if (value !in MIN_CPU_FREQUENCY_KHZ..MAX_CPU_FREQUENCY_KHZ) {
            return Result.Invalid("CPU frequency must be in $MIN_CPU_FREQUENCY_KHZ..$MAX_CPU_FREQUENCY_KHZ kHz")
        }
        val expected = values.second?.toLongOrNull()
        if (values.second != null && expected !in MIN_CPU_FREQUENCY_KHZ..MAX_CPU_FREQUENCY_KHZ) {
            return Result.Invalid("Expected CPU frequency is out of range")
        }
        return Result.Command(if (restore) {
            conditionalPolicyWrite(node, value.toString(), expected.toString())
        } else policyWrite(node, value.toString()))
    }

    private fun mapPolicyFrequency(
        command: CapabilityCommand,
        restore: Boolean,
        policyId: Int,
        node: String
    ): Result {
        val values = command.frequencyValues(restore)
            ?: return Result.Invalid("${command.capability} has invalid frequency arguments")
        val value = values.first.toLongOrNull()
            ?: return Result.Invalid("${command.capability} requires integer frequency values")
        if (value !in MIN_CPU_FREQUENCY_KHZ..MAX_CPU_FREQUENCY_KHZ) {
            return Result.Invalid("CPU frequency must be in $MIN_CPU_FREQUENCY_KHZ..$MAX_CPU_FREQUENCY_KHZ kHz")
        }
        val expected = values.second?.toLongOrNull()
        if (values.second != null && expected !in MIN_CPU_FREQUENCY_KHZ..MAX_CPU_FREQUENCY_KHZ) {
            return Result.Invalid("Expected CPU frequency is out of range")
        }
        return Result.Command(if (restore) {
            conditionalSinglePolicyWrite(policyId, node, value.toString(), expected.toString())
        } else singlePolicyWrite(policyId, node, value.toString()))
    }

    private fun mapUperfMode(command: CapabilityCommand, restore: Boolean): Result {
        val values = command.scalarValues(restore)
            ?: return Result.Invalid("${command.capability} has invalid value/expected arguments")
        val value = values.first
        if (value !in if (restore) UPERF_RESTORE_MODES else SCHEDULER_MODES) {
            return Result.Invalid("Unsupported Uperf mode")
        }
        val expected = values.second
        if (expected != null && expected !in SCHEDULER_MODES) return Result.Invalid("Unsupported expected Uperf mode")
        val identity = "[ -f /data/adb/modules/uperf/module.prop ] && " +
            "grep -qx 'id=uperf' /data/adb/modules/uperf/module.prop && [ -f /data/powercfg.sh ]"
        val current = "tr -d '[:space:]' < /sdcard/Android/yc/uperf/cur_powermode.txt"
        return Result.Command(
            if (restore) "$identity && current=\"\$($current)\" && " +
                "if [ \"\$current\" = '$value' ]; then exit 0; elif [ \"\$current\" = '$expected' ]; then " +
                "sh /data/powercfg.sh '$value' && [ \"\$($current)\" = '$value' ]; else exit 5; fi"
            else "$identity && sh /data/powercfg.sh '$value' && [ \"\$($current)\" = '$value' ]"
        )
    }

    private fun mapUperfGtMode(command: CapabilityCommand, restore: Boolean): Result {
        val mapped = mapUperfMode(command.copy(capability = "scheduler.uperf.mode.set"), restore)
        if (mapped !is Result.Command) return mapped
        val identity = "grep -qx 'name=Uperf Game Turbo' /data/adb/modules/uperf/module.prop"
        return Result.Command("$identity && ${mapped.shell}")
    }

    private fun mapFasRsMode(command: CapabilityCommand): Result {
        val restore = command.capability.endsWith(RESTORE_SUFFIX)
        val values = command.scalarValues(restore)
            ?: return Result.Invalid("${command.capability} has invalid value/expected arguments")
        val value = values.first
        if (value !in SCHEDULER_MODES) return Result.Invalid("Unsupported fas-rs mode")
        val expected = values.second
        if (expected != null && expected !in SCHEDULER_MODES) return Result.Invalid("Unsupported expected fas-rs mode")
        val node = "/dev/fas_rs/mode"
        return Result.Command(
            "[ -f /data/adb/modules/fas-rs/module.prop ] && " +
                "grep -qx 'id=fas-rs' /data/adb/modules/fas-rs/module.prop && [ -e '$node' ] && " +
                if (restore) conditionalFileWrite(node, value, expected!!)
                else "printf '%s\\n' '$value' > '$node' && [ \"\$(tr -d '[:space:]' < '$node')\" = '$value' ]"
        )
    }

    private fun mapConfigBridgeMode(command: CapabilityCommand): Result {
        val restore = command.capability.endsWith(RESTORE_SUFFIX)
        val values = command.scalarValues(restore)
            ?: return Result.Invalid("${command.capability} has invalid value/expected arguments")
        val value = values.first
        if (value !in SCHEDULER_MODES) return Result.Invalid("Unsupported configuration bridge mode")
        val expected = values.second
        if (expected != null && expected !in SCHEDULER_MODES) return Result.Invalid("Unsupported expected configuration bridge mode")
        val base = "/data/adb/modules/Scene_Config_replace"
        val bridge = "$base/qijing"
        val identity = "[ -f '$base/module.prop' ] && " +
            "grep -qx 'id=Scene_Config_replace' '$base/module.prop' && " +
            "grep -qx 'qijing-scheduler-bridge-v1' '$bridge/contract' && [ -x '$bridge/apply-mode' ]"
        return Result.Command(
            if (restore) "$identity && current=\"\$(tr -d '[:space:]' < '$bridge/current_mode')\" && " +
                "if [ \"\$current\" = '$value' ]; then exit 0; elif [ \"\$current\" = '$expected' ]; then " +
                "'$bridge/apply-mode' '$value' && [ \"\$(tr -d '[:space:]' < '$bridge/current_mode')\" = '$value' ]; else exit 5; fi"
            else "$identity && '$bridge/apply-mode' '$value' && " +
                "[ \"\$(tr -d '[:space:]' < '$bridge/current_mode')\" = '$value' ]"
        )
    }

    private fun mapProfileLimiterCluster(command: CapabilityCommand, restore: Boolean): Result {
        val cluster = ProfileLimiterCommandPolicy.parse(command, restore)
            ?: return Result.Invalid("Profile limiter cluster arguments are invalid")
        if (cluster.ddrBoost) return Result.Unsupported("Imported DDR boost has no verified device contract")
        if (ManagedLimiterRuntime.isManaged(cluster)) {
            return Result.Command(
                if (restore) ManagedLimiterRuntime.restore(cluster) else ManagedLimiterRuntime.configure(cluster)
            )
        }
        val base = "/sys/devices/system/cpu/cpufreq/policy${cluster.policy}"
        val minFile = "$base/scaling_min_freq"
        val maxFile = "$base/scaling_max_freq"
        val coreFile = "/sys/devices/system/cpu/cpu${cluster.policy}/core_ctl/enable"
        val corePreflight = if (cluster.coreCtl == "absent") "" else " && [ -e '$coreFile' ] && [ ! -d '$coreFile' ]"
        val coreWrite = if (cluster.coreCtl == "absent") "" else
            " && printf '%s\\n' '${cluster.coreCtl}' > '$coreFile' && " +
                "[ \"\$(tr -d '[:space:]' < '$coreFile')\" = '${cluster.coreCtl}' ]"
        val writeRange = profileLimiterRangeWrite(minFile, maxFile, cluster.minKHz, cluster.maxKHz)
        if (!restore) {
            return Result.Command(
                "[ -e '$minFile' ] && [ ! -d '$minFile' ] && [ -e '$maxFile' ] && [ ! -d '$maxFile' ]" +
                    corePreflight + " && $writeRange$coreWrite"
            )
        }
        val expectedMin = cluster.expectedMinKHz ?: return Result.Invalid("Limiter restore lacks expected minimum")
        val expectedMax = cluster.expectedMaxKHz ?: return Result.Invalid("Limiter restore lacks expected maximum")
        val expectedCore = cluster.expectedCoreCtl ?: return Result.Invalid("Limiter restore lacks expected core_ctl")
        if ((cluster.coreCtl == "absent") != (expectedCore == "absent")) {
            return Result.Invalid("Limiter restore core_ctl shape differs from the applied command")
        }
        val current = "cur_min=\"\$(tr -d '[:space:]' < '$minFile')\" && cur_max=\"\$(tr -d '[:space:]' < '$maxFile')\""
        val currentCore = if (cluster.coreCtl == "absent") "cur_core=absent" else
            "cur_core=\"\$(tr -d '[:space:]' < '$coreFile')\""
        val targetMatches = "[ \"\$cur_min\" = '${cluster.minKHz}' ] && [ \"\$cur_max\" = '${cluster.maxKHz}' ] && [ \"\$cur_core\" = '${cluster.coreCtl}' ]"
        val appliedMatches = "[ \"\$cur_min\" = '$expectedMin' ] && [ \"\$cur_max\" = '$expectedMax' ] && [ \"\$cur_core\" = '$expectedCore' ]"
        return Result.Command(
            "[ -e '$minFile' ] && [ ! -d '$minFile' ] && [ -e '$maxFile' ] && [ ! -d '$maxFile' ]" +
                corePreflight + " && $current && $currentCore && " +
                "if $targetMatches; then exit 0; elif $appliedMatches; then $writeRange$coreWrite; else exit 5; fi"
        )
    }

    private fun profileLimiterRangeWrite(minFile: String, maxFile: String, min: Long, max: Long): String =
        "cur_min=\"\$(tr -d '[:space:]' < '$minFile')\" && cur_max=\"\$(tr -d '[:space:]' < '$maxFile')\" && " +
            "if [ '$min' -gt \"\$cur_max\" ]; then printf '%s\\n' '$max' > '$maxFile' && printf '%s\\n' '$min' > '$minFile'; " +
            "elif [ '$max' -lt \"\$cur_min\" ]; then printf '%s\\n' '$min' > '$minFile' && printf '%s\\n' '$max' > '$maxFile'; " +
            "else printf '%s\\n' '$max' > '$maxFile' && printf '%s\\n' '$min' > '$minFile'; fi && " +
            "[ \"\$(tr -d '[:space:]' < '$minFile')\" = '$min' ] && [ \"\$(tr -d '[:space:]' < '$maxFile')\" = '$max' ]"

    private fun mapProfileLimiterClear(command: CapabilityCommand): Result {
        val expected = if (command.capability.endsWith(RESTORE_SUFFIX)) setOf("scope", "expected") else setOf("scope")
        if (command.arguments.keys != expected || command.arguments["scope"] != "cpu_ddr" ||
            ("expected" in expected && command.arguments["expected"] != "inactive")
        ) {
            return Result.Invalid("Profile limiter clear requires scope=cpu_ddr")
        }
        return Result.Command(ManagedLimiterRuntime.ensureAbsent())
    }

    private fun mapGestureBoost(command: CapabilityCommand, restore: Boolean): Result {
        val contract = ManagedGestureCommandPolicy.parse(command, restore)
            ?: return Result.Invalid("Gesture boost contract is invalid or uses an unverified exit operation")
        return Result.Command(
            if (restore) ManagedGestureRuntime.restore(contract) else ManagedGestureRuntime.configure(contract)
        )
    }

    private fun mapProfileAppFrequencies(command: CapabilityCommand, restore: Boolean): Result {
        val frequencies = ProfileAppFrequencyCommandPolicy.parse(command, restore)
        if (frequencies == null) {
            return Result.Invalid("Profile app-frequency arguments are invalid")
        }
        val discover = appFrequencyPolicyDiscovery()
        if (!restore) {
            val efficiencyFile = "\$base/policy\$efficiency_policy/scaling_max_freq"
            val performanceFile = "\$base/policy\$performance_policy/scaling_max_freq"
            val efficiencyTable = "\$base/policy\$efficiency_policy/scaling_available_frequencies"
            val performanceTable = "\$base/policy\$performance_policy/scaling_available_frequencies"
            return Result.Command(
                "$discover && " +
                    "[ -r \"$efficiencyTable\" ] && [ -w \"$efficiencyFile\" ] && " +
                    "[ -r \"$performanceTable\" ] && [ -w \"$performanceFile\" ] && " +
                    "grep -qw '${frequencies.efficiencyKHz}' \"$efficiencyTable\" && " +
                    "grep -qw '${frequencies.performanceKHz}' \"$performanceTable\" && " +
                    "printf '%s\\n' '${frequencies.efficiencyKHz}' > \"$efficiencyFile\" && " +
                    "[ \"\$(tr -d '[:space:]' < \"$efficiencyFile\")\" = '${frequencies.efficiencyKHz}' ] && " +
                    "printf '%s\\n' '${frequencies.performanceKHz}' > \"$performanceFile\" && " +
                    "[ \"\$(tr -d '[:space:]' < \"$performanceFile\")\" = '${frequencies.performanceKHz}' ] && " +
                    "printf 'policy%s=%s|policy%s=%s' \"\$efficiency_policy\" '${frequencies.efficiencyKHz}' " +
                    "\"\$performance_policy\" '${frequencies.performanceKHz}'"
            )
        }
        val efficiencyPolicy = frequencies.efficiencyPolicy ?: return Result.Invalid("Restore lacks efficiency policy")
        val performancePolicy = frequencies.performancePolicy ?: return Result.Invalid("Restore lacks performance policy")
        val expectedEfficiency = frequencies.expectedEfficiencyKHz
            ?: return Result.Invalid("Restore lacks applied efficiency frequency")
        val expectedPerformance = frequencies.expectedPerformanceKHz
            ?: return Result.Invalid("Restore lacks applied performance frequency")
        val efficiencyFile = "\$base/policy\$efficiency_policy/scaling_max_freq"
        val performanceFile = "\$base/policy\$performance_policy/scaling_max_freq"
        val restoreFunction = "restore_cap() { file=\"\$1\"; original=\"\$2\"; expected=\"\$3\"; " +
            "current=\"\$(tr -d '[:space:]' < \"\$file\")\" || return 6; " +
            "if [ \"\$current\" = \"\$original\" ]; then return 0; " +
            "elif [ \"\$current\" = \"\$expected\" ]; then printf '%s\\n' \"\$original\" > \"\$file\" && " +
            "[ \"\$(tr -d '[:space:]' < \"\$file\")\" = \"\$original\" ]; else return 5; fi; };"
        return Result.Command(
            "$discover && [ \"\$efficiency_policy\" = '$efficiencyPolicy' ] && " +
                "[ \"\$performance_policy\" = '$performancePolicy' ] && " +
                "[ -r \"$efficiencyFile\" ] && [ -w \"$efficiencyFile\" ] && " +
                "[ -r \"$performanceFile\" ] && [ -w \"$performanceFile\" ] && " +
                "$restoreFunction " +
                "restore_cap \"$performanceFile\" '${frequencies.performanceKHz}' '$expectedPerformance' && " +
                "restore_cap \"$efficiencyFile\" '${frequencies.efficiencyKHz}' '$expectedEfficiency'"
        )
    }

    private fun appFrequencyPolicyDiscovery(): String =
        "base=/sys/devices/system/cpu/cpufreq; policies=\"\$(for path in \"\$base\"/policy[0-9]*; do " +
            "[ -d \"\$path\" ] || continue; id=\"\${path##*policy}\"; " +
            "case \"\$id\" in ''|*[!0-9]*) continue;; esac; [ \"\$id\" -le 255 ] || continue; " +
            "printf '%s\\n' \"\$id\"; done | sort -n -u)\" && " +
            "efficiency_policy=\"\$(printf '%s\\n' \"\$policies\" | head -n 1)\" && " +
            "performance_policy=\"\$(printf '%s\\n' \"\$policies\" | tail -n 1)\" && " +
            "[ -n \"\$efficiency_policy\" ] && [ -n \"\$performance_policy\" ] && " +
            "[ \"\$efficiency_policy\" != \"\$performance_policy\" ]"

    private fun mapNodeWrite(command: CapabilityCommand, restore: Boolean): Result {
        val expectedKeys = if (restore) setOf("path", "expected", "value") else setOf("path", "value")
        if (command.arguments.keys != expectedKeys) return Result.Invalid("scheduler.node.write arguments are invalid")
        val path = command.arguments["path"] ?: return Result.Invalid("Missing node path")
        val value = command.arguments["value"] ?: return Result.Invalid("Missing node value")
        if (!PrivilegedNodePolicy.validPath(path)) return Result.Invalid("Node path is outside the structured scheduler policy")
        if (!PrivilegedNodePolicy.validValue(value)) return Result.Invalid("Node value is not a bounded plain value")
        if (restore) {
            val expected = command.arguments["expected"] ?: return Result.Invalid("Missing applied node value")
            if (!PrivilegedNodePolicy.validValue(expected)) return Result.Invalid("Applied node value is invalid")
            return Result.Command(
                "[ -e '$path' ] && [ ! -d '$path' ] && current=\"\$(tr -d '\\r\\n' < '$path')\" && " +
                    "if [ \"\$current\" = '$value' ]; then exit 0; " +
                    "elif [ \"\$current\" = '$expected' ]; then printf '%s\\n' '$value' > '$path' && " +
                    "[ \"\$(tr -d '\\r\\n' < '$path')\" = '$value' ]; else exit 5; fi"
            )
        }
        return Result.Command(
            "[ -e '$path' ] && [ ! -d '$path' ] && " +
                "printf '%s\\n' '$value' > '$path' && " +
                "[ \"\$(tr -d '\\r\\n' < '$path')\" = '$value' ]"
        )
    }

    private fun mapThreadMutation(command: CapabilityCommand, capability: String, restore: Boolean): Result {
        val identity = ThreadCommandPolicy.parse(command)
            ?: return Result.Invalid("Thread mutation identity or arguments are invalid")
        val prefix = ThreadCommandPolicy.identityShell(identity)
        return when (capability) {
            "scheduler.thread.cpuset.set" -> mapThreadCpuSet(identity, prefix, restore)
            "scheduler.thread.affinity.set" -> {
                if (!CPU_MASK.matches(identity.expected) || !CPU_MASK.matches(identity.value)) {
                    return Result.Invalid("Thread affinity mask is invalid")
                }
                conditionalThreadMutation(
                    prefix, identity, restore,
                    "taskset -p '${identity.tid}' | sed -n 's/.*: //p' | tr 'A-F' 'a-f'",
                    "taskset -p '${identity.value.lowercase()}' '${identity.tid}' >/dev/null",
                    "[ \"\$(taskset -p '${identity.tid}' | sed -n 's/.*: //p' | tr 'A-F' 'a-f')\" = '${identity.value.lowercase()}' ]"
                )
            }
            "scheduler.thread.nice.set" -> {
                val expected = identity.expected.toIntOrNull()?.takeIf { it in -20..19 }
                    ?: return Result.Invalid("Expected thread nice value is invalid")
                val value = identity.value.toIntOrNull()?.takeIf { it in -20..19 }
                    ?: return Result.Invalid("Target thread nice value is invalid")
                val stat = "sed 's/^.*) //' '/proc/${identity.pid}/task/${identity.tid}/stat' | awk '{print \$17}'"
                conditionalThreadMutation(
                    prefix, identity.copy(expected = expected.toString(), value = value.toString()), restore,
                    stat,
                    "renice -n '$value' -p '${identity.tid}' >/dev/null",
                    "[ \"\$($stat)\" = '$value' ]"
                )
            }
            "scheduler.thread.policy.set" -> mapThreadPolicy(identity, prefix, restore)
            else -> Result.Unsupported("Thread mutation is not supported")
        }
    }

    private fun mapThreadCpuSet(identity: ThreadCommandIdentity, prefix: String, restore: Boolean): Result {
        if (!CPUSET_GROUP.matches(identity.expected) || ".." in identity.expected) {
            return Result.Invalid("Expected cpuset group is invalid")
        }
        val targetParts = identity.value.split('@', limit = 2)
        val targetGroup = targetParts[0]
        if (!CPUSET_GROUP.matches(targetGroup) || ".." in targetGroup) return Result.Invalid("Target cpuset group is invalid")
        val prepare = if (targetParts.size == 2) {
            val cpus = targetParts[1]
            if (!CPU_LIST.matches(cpus)) return Result.Invalid("Target cpuset CPU list is invalid")
            if (!targetGroup.startsWith("/qijing_")) return Result.Invalid("New cpuset groups must be owned by Qijing")
            val target = "/dev/cpuset$targetGroup"
            "mkdir -p '$target' && [ -d '$target' ] && " +
                "cat /dev/cpuset/mems > '$target/mems' && printf '%s\\n' '$cpus' > '$target/cpus' && "
        } else {
            if (targetGroup.startsWith("/qijing_")) return Result.Invalid("Managed cpuset restore lacks CPU list")
            "[ -d '/dev/cpuset$targetGroup' ] && "
        }
        val cleanup = if (identity.expected.startsWith("/qijing_")) {
            " && rmdir '/dev/cpuset${identity.expected}' 2>/dev/null && [ ! -d '/dev/cpuset${identity.expected}' ]"
        } else ""
        if (restore && identity.expected.startsWith("/qijing_")) {
            val current = "cat '/proc/${identity.pid}/task/${identity.tid}/cpuset'"
            val removeOwned = "rmdir '/dev/cpuset${identity.expected}' 2>/dev/null && [ ! -d '/dev/cpuset${identity.expected}' ]"
            return Result.Command(
                "if { $prefix; }; then current=\"\$($current)\" || exit 6; " +
                    "if [ \"\$current\" = '$targetGroup' ]; then $removeOwned; " +
                    "elif [ \"\$current\" = '${identity.expected}' ]; then $prepare" +
                    "printf '%s\\n' '${identity.tid}' > '/dev/cpuset$targetGroup/tasks' && " +
                    "[ \"\$($current)\" = '$targetGroup' ] && $removeOwned; else exit 5; fi; " +
                    "else $removeOwned; fi"
            )
        }
        return conditionalThreadMutation(
            prefix, identity.copy(value = targetGroup), restore,
            "cat '/proc/${identity.pid}/task/${identity.tid}/cpuset'",
            prepare + "printf '%s\\n' '${identity.tid}' > '/dev/cpuset$targetGroup/tasks'",
            "[ \"\$(cat '/proc/${identity.pid}/task/${identity.tid}/cpuset')\" = '$targetGroup' ]$cleanup"
        )
    }

    private fun mapThreadPolicy(identity: ThreadCommandIdentity, prefix: String, restore: Boolean): Result {
        val expected = parseThreadPolicy(identity.expected) ?: return Result.Invalid("Expected scheduling policy is invalid")
        val target = parseThreadPolicy(identity.value) ?: return Result.Invalid("Target scheduling policy is invalid")
        val flag = when (target.first) {
            "OTHER" -> "-o"
            "FIFO" -> "-f"
            "ROUND_ROBIN" -> "-r"
            "BATCH" -> "-b"
            "IDLE" -> "-i"
            else -> return Result.Unsupported("SCHED_DEADLINE restore is not supported")
        }
        val current = threadPolicyRead(identity)
        return conditionalThreadMutation(
            prefix,
            identity.copy(expected = "${expected.first}:${expected.second}", value = "${target.first}:${target.second}"),
            restore,
            current,
            "chrt '$flag' -p '${target.second}' '${identity.tid}' >/dev/null",
            "[ \"\$($current)\" = '${target.first}:${target.second}' ]"
        )
    }

    private fun conditionalThreadMutation(
        identityShell: String,
        identity: ThreadCommandIdentity,
        restore: Boolean,
        currentShell: String,
        mutationShell: String,
        verifyShell: String
    ): Result.Command {
        val body = if (restore) {
            "{ $identityShell; } || exit 0; current=\"\$($currentShell)\" || exit 6; " +
                "if [ \"\$current\" = '${identity.value}' ]; then exit 0; " +
                "elif [ \"\$current\" = '${identity.expected}' ]; then $mutationShell && $verifyShell; " +
                "else exit 5; fi"
        } else {
            "$identityShell && current=\"\$($currentShell)\" && [ \"\$current\" = '${identity.expected}' ] && " +
                "$mutationShell && $verifyShell"
        }
        return Result.Command(body)
    }

    private fun threadPolicyRead(identity: ThreadCommandIdentity): String =
        "p=\"\$(sed 's/^.*) //' '/proc/${identity.pid}/task/${identity.tid}/stat' | awk '{print \$39}')\"; " +
            "r=\"\$(sed 's/^.*) //' '/proc/${identity.pid}/task/${identity.tid}/stat' | awk '{print \$38}')\"; " +
            "case \"\$p\" in 0) n=OTHER;; 1) n=FIFO;; 2) n=ROUND_ROBIN;; 3) n=BATCH;; 5) n=IDLE;; 6) n=DEADLINE;; *) exit 4;; esac; printf '%s:%s' \"\$n\" \"\$r\""

    private fun parseThreadPolicy(raw: String): Pair<String, Int>? {
        val parts = raw.split(':', limit = 2)
        if (parts.size != 2 || parts[0] !in THREAD_POLICIES) return null
        val priority = parts[1].toIntOrNull() ?: return null
        val valid = if (parts[0] in setOf("FIFO", "ROUND_ROBIN")) priority in 1..99 else priority == 0
        return if (valid) parts[0] to priority else null
    }

    private fun mapRefreshRate(command: CapabilityCommand, restore: Boolean): Result {
        val values = command.scalarValues(restore)
            ?: return Result.Invalid("display.refresh_rate.set has invalid value/expected arguments")
        val raw = values.first
        if (restore) {
            val parts = raw.split('|', limit = 2)
            val expected = values.second!!.split('|', limit = 2)
            if (parts.size != 2 || expected.size != 2 || (parts + expected).any {
                    it != "absent" && it.toDoubleOrNull()?.let { hz -> hz in 0.0..240.0 } != true
                }) {
                return Result.Invalid("Invalid refresh-rate snapshot")
            }
            val peak = settingsConditionalRestore("peak_refresh_rate", parts[0], expected[0])
            val minimum = settingsConditionalRestore("min_refresh_rate", parts[1], expected[1])
            return Result.Command("$peak && $minimum")
        }
        val hz = raw.toDoubleOrNull() ?: return Result.Invalid("Refresh rate must be numeric")
        if (hz !in 0.0..240.0) return Result.Invalid("Refresh rate must be in 0..240 Hz")
        val normalized = if (hz % 1.0 == 0.0) hz.toInt().toString() else hz.toString()
        return if (hz == 0.0) {
            Result.Command(
                "settings delete system peak_refresh_rate >/dev/null && settings delete system min_refresh_rate >/dev/null && " +
                    "[ -z \"\$(settings get system peak_refresh_rate | sed '/^null$/d')\" ] && " +
                    "[ -z \"\$(settings get system min_refresh_rate | sed '/^null$/d')\" ]"
            )
        } else {
            Result.Command(
                "settings put system peak_refresh_rate '$normalized' && settings put system min_refresh_rate '$normalized' && " +
                    "[ \"\$(settings get system peak_refresh_rate | tr -d '[:space:]')\" = '$normalized' ] && " +
                    "[ \"\$(settings get system min_refresh_rate | tr -d '[:space:]')\" = '$normalized' ]"
            )
        }
    }

    private fun settingsRestore(key: String, value: String): String = if (value == "absent") {
        "settings delete system '$key' >/dev/null && [ -z \"\$(settings get system '$key' | sed '/^null$/d')\" ]"
    } else {
        "settings put system '$key' '$value' && [ \"\$(settings get system '$key' | tr -d '[:space:]')\" = '$value' ]"
    }

    private fun settingsConditionalRestore(key: String, original: String, expected: String): String {
        val read = "current=\"\$(settings get system '$key' | tr -d '[:space:]')\"; [ \"\$current\" = 'null' ] && current=absent"
        return "$read; if [ \"\$current\" = '$original' ]; then true; " +
            "elif [ \"\$current\" = '$expected' ]; then ${settingsRestore(key, original)}; else exit 5; fi"
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

    private fun conditionalFileWrite(file: String, original: String, expected: String): String =
        "[ -e '$file' ] && current=\"\$(tr -d '[:space:]' < '$file')\" && " +
            "if [ \"\$current\" = '$original' ]; then exit 0; " +
            "elif [ \"\$current\" = '$expected' ]; then printf '%s\\n' '$original' > '$file' && " +
            "[ \"\$(tr -d '[:space:]' < '$file')\" = '$original' ]; else exit 5; fi"

    private fun conditionalSinglePolicyWrite(policyId: Int, node: String, original: String, expected: String): String =
        conditionalFileWrite("/sys/devices/system/cpu/cpufreq/policy$policyId/$node", original, expected)

    private fun conditionalPolicyWrite(node: String, original: String, expected: String): String =
        "files=''; for file in /sys/devices/system/cpu/cpufreq/policy*/$node; do " +
            "[ -e \"\$file\" ] || exit 1; current=\"\$(tr -d '[:space:]' < \"\$file\")\" || exit 1; " +
            "[ \"\$current\" = '$original' ] || [ \"\$current\" = '$expected' ] || exit 5; files=\"\$files \$file\"; done; " +
            "[ -n \"\$files\" ] || exit 1; for file in \$files; do current=\"\$(tr -d '[:space:]' < \"\$file\")\"; " +
            "[ \"\$current\" = '$original' ] || printf '%s\\n' '$original' > \"\$file\" || exit 1; " +
            "[ \"\$(tr -d '[:space:]' < \"\$file\")\" = '$original' ] || exit 2; done"

    private fun CapabilityCommand.scalarValues(restore: Boolean): Pair<String, String?>? = if (restore) {
        if (arguments.keys != setOf("value", "expected")) null
        else arguments["value"]?.takeIf(String::isNotBlank)?.let { value ->
            arguments["expected"]?.takeIf(String::isNotBlank)?.let { expected -> value to expected }
        }
    } else singleValue("value")?.let { it to null }

    private fun CapabilityCommand.frequencyValues(restore: Boolean): Pair<String, String?>? = if (restore) {
        if (arguments.keys != setOf("value", "expected")) null
        else arguments["value"]?.takeIf(String::isNotBlank)?.let { value ->
            arguments["expected"]?.takeIf(String::isNotBlank)?.let { expected -> value to expected }
        }
    } else singleValue("khz")?.let { it to null }

    private fun CapabilityCommand.singleValue(name: String): String? =
        arguments.takeIf { it.keys == setOf(name) }?.get(name)?.takeIf(String::isNotBlank)

    private const val RESTORE_SUFFIX = ".restore"
    private const val MIN_CPU_FREQUENCY_KHZ = 100_000L
    private const val MAX_CPU_FREQUENCY_KHZ = 10_000_000L
    private val GOVERNOR = Regex("[A-Za-z0-9_-]{1,32}")
    private val POLICY_CAPABILITY = Regex("cpu\\.policy\\.([0-9]{1,3})\\.(governor|min_frequency|max_frequency)\\.set")
    private val CPU_MASK = Regex("[0-9a-fA-F]{1,64}")
    private val CPU_LIST = Regex("[0-9,-]{1,128}")
    private val CPUSET_GROUP = Regex("/[A-Za-z0-9_./-]{0,127}")
    private val THREAD_POLICIES = setOf("OTHER", "FIFO", "ROUND_ROBIN", "BATCH", "IDLE", "DEADLINE")
    private val SCHEDULER_MODES = setOf("powersave", "balance", "performance", "fast")
    private val UPERF_RESTORE_MODES = SCHEDULER_MODES + "auto"
    private val ZRAM_CAPABILITIES = setOf("memory.zram.enabled", "memory.zram.size", "memory.zram.algorithm.set")
}

internal data class ProfileLimiterClusterCommand(
    val profile: String,
    val policy: Int,
    val minKHz: Long,
    val maxKHz: Long,
    val margins: String,
    val excludes: String,
    val prefer: String,
    val coreCtl: String,
    val ddrBoost: Boolean,
    val expectedMinKHz: Long? = null,
    val expectedMaxKHz: Long? = null,
    val expectedCoreCtl: String? = null
)

internal object ProfileLimiterCommandPolicy {
    private val FORWARD_KEYS = setOf(
        "profile", "policy", "min_khz", "max_khz", "margins", "excludes", "prefer", "core_ctl", "ddr_boost"
    )
    private val RESTORE_KEYS = FORWARD_KEYS + setOf("expected_min_khz", "expected_max_khz", "expected_core_ctl")
    private val PROFILE = Regex("[A-Za-z0-9._-]{1,48}")
    private val MARGINS = Regex("[0-9]{1,4}(?: [0-9]{6,8}:[0-9]{1,4}){0,16}")
    private val EXCLUDES = Regex("[0-9]{1,3}(?:,[0-9]{1,3}){0,31}")

    fun parse(command: CapabilityCommand, restore: Boolean = command.capability.endsWith(".restore")): ProfileLimiterClusterCommand? {
        if (command.arguments.keys != if (restore) RESTORE_KEYS else FORWARD_KEYS) return null
        val profile = command.arguments["profile"]?.takeIf(PROFILE::matches) ?: return null
        val policy = command.arguments["policy"]?.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        val min = command.arguments["min_khz"]?.frequency() ?: return null
        val max = command.arguments["max_khz"]?.frequency()?.takeIf { it >= min } ?: return null
        val margins = command.arguments["margins"]?.takeIf { it == "absent" || validMargins(it) } ?: return null
        val excludes = command.arguments["excludes"]?.takeIf { it == "absent" || validExcludes(it) } ?: return null
        val prefer = command.arguments["prefer"]?.takeIf {
            it == "absent" || it.toIntOrNull()?.let { value -> value in 0..255 } == true
        } ?: return null
        val coreCtl = command.arguments["core_ctl"]?.takeIf { it in setOf("absent", "0", "1") } ?: return null
        val ddrBoost = when (command.arguments["ddr_boost"]) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        val expectedMin = if (restore) command.arguments["expected_min_khz"]?.frequency() ?: return null else null
        val expectedMax = if (restore) command.arguments["expected_max_khz"]?.frequency() ?: return null else null
        val expectedCore = if (restore) command.arguments["expected_core_ctl"]
            ?.takeIf { it in setOf("absent", "0", "1") } ?: return null else null
        if (restore && expectedMin!! > expectedMax!!) return null
        return ProfileLimiterClusterCommand(
            profile, policy, min, max, margins, excludes, prefer, coreCtl, ddrBoost,
            expectedMin, expectedMax, expectedCore
        )
    }

    private fun validMargins(raw: String): Boolean {
        if (!MARGINS.matches(raw)) return false
        val parts = raw.split(' ')
        if (parts.first().toIntOrNull() !in 0..999) return false
        var previous = 0L
        return parts.drop(1).all { token ->
            val pair = token.split(':', limit = 2)
            val frequency = pair.getOrNull(0)?.frequency() ?: return@all false
            pair.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..999 } ?: return@all false
            (frequency > previous).also { previous = frequency }
        }
    }

    private fun validExcludes(raw: String): Boolean {
        if (!EXCLUDES.matches(raw)) return false
        val values = raw.split(',').mapNotNull(String::toIntOrNull)
        return values.isNotEmpty() && values.all { it in 0..255 } && values.distinct().size == values.size
    }

    private fun String.frequency(): Long? = toLongOrNull()?.takeIf { it in 100_000L..10_000_000L }
}

internal data class ProfileAppFrequencyCommand(
    val packageName: String,
    val performanceKHz: Long,
    val efficiencyKHz: Long,
    val performancePolicy: Int? = null,
    val efficiencyPolicy: Int? = null,
    val expectedPerformanceKHz: Long? = null,
    val expectedEfficiencyKHz: Long? = null
)

internal object ProfileAppFrequencyCommandPolicy {
    private val FORWARD_KEYS = setOf("package", "performance_khz", "efficiency_khz")
    private val RESTORE_KEYS = FORWARD_KEYS + setOf(
        "performance_policy", "efficiency_policy", "expected_performance_khz", "expected_efficiency_khz"
    )
    private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun parse(
        command: CapabilityCommand,
        restore: Boolean = command.capability.endsWith(".restore")
    ): ProfileAppFrequencyCommand? {
        if (command.arguments.keys != if (restore) RESTORE_KEYS else FORWARD_KEYS) return null
        val packageName = command.arguments["package"]?.takeIf(PACKAGE::matches) ?: return null
        val performance = command.arguments["performance_khz"]?.toLongOrNull()
            ?.takeIf { it in 100_000L..10_000_000L } ?: return null
        val efficiency = command.arguments["efficiency_khz"]?.toLongOrNull()
            ?.takeIf { it in 100_000L..10_000_000L } ?: return null
        val performancePolicy = if (restore) command.arguments["performance_policy"]?.policy() ?: return null else null
        val efficiencyPolicy = if (restore) command.arguments["efficiency_policy"]?.policy() ?: return null else null
        if (restore && efficiencyPolicy!! >= performancePolicy!!) return null
        val expectedPerformance = if (restore) command.arguments["expected_performance_khz"]?.frequency() ?: return null else null
        val expectedEfficiency = if (restore) command.arguments["expected_efficiency_khz"]?.frequency() ?: return null else null
        return ProfileAppFrequencyCommand(
            packageName, performance, efficiency, performancePolicy, efficiencyPolicy,
            expectedPerformance, expectedEfficiency
        )
    }

    private fun String.policy(): Int? = toIntOrNull()?.takeIf { it in 0..255 }
    private fun String.frequency(): Long? = toLongOrNull()?.takeIf { it in 100_000L..10_000_000L }
}

/** Shared lexical policy for typed scheduler node reads and writes. */
object PrivilegedNodePolicy {
    fun validPath(path: String): Boolean {
        if (path.length !in 2..256 || !PATH.matches(path) || "//" in path) return false
        if (path.split('/').any { it == "." || it == ".." }) return false
        if (path.substringAfterLast('/') in DENIED_BASENAMES) return false
        return ALLOWED_PATHS.any { it.matches(path) }
    }

    fun validValue(value: String): Boolean = value.length in 1..512 && VALUE.matches(value)

    private val PATH = Regex("/[A-Za-z0-9_./:-]+")
    private val VALUE = Regex("[A-Za-z0-9_.,:+/@% =-]+")
    private val ALLOWED_PATHS = listOf(
        Regex("/sys/devices/system/cpu/cpu[0-9]{1,3}/online"),
        Regex("/sys/devices/system/cpu/cpu[0-9]{1,3}/core_ctl/(enable|min_cpus|max_cpus|busy_up_thres|busy_down_thres|offline_delay_ms)"),
        Regex("/sys/devices/system/cpu/cpufreq/boost"),
        Regex("/sys/devices/system/cpu/cpufreq/policy[0-9]{1,3}/scaling_(min|max)_freq"),
        Regex("/sys/devices/system/cpu/cpufreq/policy[0-9]{1,3}/walt/(up_rate_limit_us|down_rate_limit_us)"),
        Regex("/sys/devices/system/cpu/cpu[0-9]{1,3}/cpufreq/walt/(target_loads|hispeed_freq)"),
        Regex("/sys/class/kgsl/kgsl-3d0/devfreq/(min_freq|max_freq|mod_percent)"),
        Regex("/sys/module/migt/parameters/(glk_disable|glk_freq_limit_walt)"),
        Regex("/sys/module/perfmgr/parameters/perfmgr_enable"),
        Regex("/proc/sys/walt/(sched_boost|sched_group_upmigrate|sched_group_downmigrate)"),
        Regex("/proc/sys/walt/input_boost/(sched_boost_on_input|input_boost_freq)"),
        Regex("/proc/game_opt/disable_cpufreq_limit"),
        Regex("/dev/cpuset/(background|system-background|foreground|top-app)(/[A-Za-z0-9_-]{1,32})?/cpus")
    )
    private val DENIED_BASENAMES = setOf("bind", "unbind", "remove", "delete", "uevent", "trigger")
}
