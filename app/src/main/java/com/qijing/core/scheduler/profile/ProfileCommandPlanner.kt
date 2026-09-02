package com.qijing.core.scheduler.profile

import com.qijing.core.execution.CapabilityCommand

data class ProfileDeviceBinding(
    /** Numeric policy suffixes in cluster order, for example [0, 6]. */
    val policyIds: List<Int>,
    val availableFrequenciesKHz: Map<Int, List<Long>> = emptyMap(),
    val governorDirectory: String = "walt"
) {
    init {
        require(policyIds.isNotEmpty() && policyIds.size <= 16)
        require(policyIds.distinct().size == policyIds.size && policyIds.all { it in 0..255 })
        require(GOVERNOR.matches(governorDirectory))
    }

    private companion object { val GOVERNOR = Regex("[A-Za-z0-9_-]{1,32}") }
}

sealed interface ProfileCommandPlan {
    data class Planned(val commands: List<CapabilityCommand>, val notices: List<String>) : ProfileCommandPlan
    data class Rejected(val code: String, val reason: String) : ProfileCommandPlan
}

/** Converts compiled profile intent into structured commands; it never emits shell. */
class ProfileCommandPlanner {
    fun plan(
        program: CompiledProfileProgram,
        operations: List<ProfileOperation>,
        binding: ProfileDeviceBinding
    ): ProfileCommandPlan = try {
        val commands = mutableListOf<CapabilityCommand>()
        val notices = linkedSetOf<String>()
        operations.forEach { operation ->
            when (operation) {
                is ProfileOperation.Capture -> Unit // @values consumes capture-only presets.
                is ProfileOperation.Write -> commands += node(operation.target.absolutePath, operation.value)
                is ProfileOperation.Values -> operation.targets.zip(operation.values).forEach { (target, value) ->
                    commands += node(target.absolutePath, value)
                }
                is ProfileOperation.CpuSet -> {
                    commands += node("/dev/cpuset/background/cpus", operation.background)
                    commands += node("/dev/cpuset/system-background/cpus", operation.systemBackground)
                    commands += node("/dev/cpuset/foreground/cpus", operation.foreground)
                    commands += node("/dev/cpuset/top-app/cpus", operation.topApp)
                }
                is ProfileOperation.CpuFrequenciesMin -> commands += policyValues(binding, operation.policies, "scaling_min_freq")
                is ProfileOperation.CpuFrequenciesMax -> commands += policyValues(binding, operation.policies, "scaling_max_freq")
                is ProfileOperation.CpuFrequency -> {
                    val policyId = parsePolicy(operation.policy)
                    commands += policyFrequency(
                        policyId,
                        operation.bound == CpuFrequencyBound.MINIMUM,
                        resolveFrequency(operation.value, binding, policyId, zeroMeansMinimum = true)
                    )
                }
                is ProfileOperation.TargetLoads -> commands += perPolicyGovernorValues(binding, operation.policies, "target_loads")
                is ProfileOperation.HispeedFrequencies -> commands += perPolicyGovernorValues(binding, operation.policies, "hispeed_freq")
                is ProfileOperation.Governor -> binding.policyIds.forEach { policy ->
                    commands += CapabilityCommand(
                        "cpu.policy.$policy.governor.set",
                        mapOf("value" to operation.name)
                    )
                }
                is ProfileOperation.Limiter -> {
                    commands.removeAll {
                        it.capability == "scheduler.profile.limiter.cluster.set" ||
                            it.capability == "scheduler.profile.limiter.clear"
                    }
                    commands += limiterCommands(program.features, operation.profileName, binding)
                    notices += if (operation.profileName.equals("NONE", ignoreCase = true)) {
                        "该路由显式撤销配置限频器"
                    } else {
                        "该路由应用完整的 ${operation.profileName} CPU/DDR 限频声明"
                    }
                }
                is ProfileOperation.FrameRate -> commands += CapabilityCommand(
                    capability = "display.refresh_rate.set",
                    arguments = mapOf("value" to operation.value.text)
                )
                is ProfileOperation.PlatformReset -> notices += "平台复位由栖境事务快照替代：进入前记录原值，离场时逐项恢复"
            }
        }
        if (commands.size > MAX_COMMANDS) ProfileCommandPlan.Rejected("PROFILE_TOO_MANY_COMMANDS", "路由展开超过 $MAX_COMMANDS 项")
        else ProfileCommandPlan.Planned(commands, notices.toList())
    } catch (error: RejectedBinding) {
        ProfileCommandPlan.Rejected(error.code, error.message ?: "配置无法绑定到设备")
    }

    private fun policyValues(binding: ProfileDeviceBinding, values: List<ProfileValue>, node: String): List<CapabilityCommand> {
        if (values.size != binding.policyIds.size) reject("PROFILE_POLICY_COUNT_MISMATCH", "配置策略数量与设备 CPU 簇不一致")
        return binding.policyIds.zip(values).map { (policy, value) ->
            policyFrequency(
                policy,
                minimum = node == "scaling_min_freq",
                khz = resolveFrequency(value, binding, policy, zeroMeansMinimum = true)
            )
        }
    }

    private fun perPolicyGovernorValues(
        binding: ProfileDeviceBinding,
        values: List<ProfileValue>,
        node: String
    ): List<CapabilityCommand> {
        if (values.size != binding.policyIds.size) reject("PROFILE_POLICY_COUNT_MISMATCH", "配置策略数量与设备 CPU 簇不一致")
        return binding.policyIds.zip(values).map { (policy, value) ->
            val resolved = when {
                node != "hispeed_freq" || value.text == "0" -> value
                value.notation != ValueNotation.PLAIN ->
                    ProfileValue(resolveFrequency(value, binding, policy, zeroMeansMinimum = false).toString())
                value.text.toLongOrNull()?.let { it in FREQUENCY_RANGE } == true -> value
                else -> reject("PROFILE_FREQUENCY_INVALID", "policy$policy 的 WALT hispeed_freq ${value.text} 无效")
            }
            node("/sys/devices/system/cpu/cpu$policy/cpufreq/${binding.governorDirectory}/$node", resolved)
        }
    }

    private fun limiterCommands(
        features: Map<String, ProfileFeatureValue>,
        name: String,
        binding: ProfileDeviceBinding
    ): List<CapabilityCommand> {
        if (name.equals("NONE", ignoreCase = true)) return listOf(
            CapabilityCommand("scheduler.profile.limiter.clear", mapOf("scope" to "cpu_ddr"))
        )
        val limiterFeature = features.objectEntry("limiter")
            ?: reject("PROFILE_LIMITER_FEATURE_MISSING", "配置缺少 limiter 功能声明")
        limiterFeature.requireOnlyKeys(
            setOf("ddr_boost", "limiters"),
            "PROFILE_LIMITER_FEATURE_UNSUPPORTED",
            "limiter 功能"
        )
        val ddrBoost = limiterFeature.boolean("ddr_boost")
            ?: reject("PROFILE_LIMITER_DDR_BOOST_INVALID", "limiter 缺少布尔 ddr_boost 声明")
        val limiter = limiterFeature.objectEntry("limiters")?.objectEntry(name)
            ?: reject("PROFILE_LIMITER_MISSING", "配置引用的限频器 $name 不存在")
        limiter.requireOnlyKeys(
            setOf("core_ctl", "cpus"),
            "PROFILE_LIMITER_FIELD_UNSUPPORTED",
            "限频器 $name"
        )
        val cpus = limiter.arrayEntry("cpus") ?: reject("PROFILE_LIMITER_INVALID", "限频器 $name 缺少 CPU 策略")
        if (cpus.size != binding.policyIds.size) reject("PROFILE_LIMITER_POLICY_MISMATCH", "限频器 $name 与设备 CPU 簇数量不一致")
        val coreCtl = limiter.optionalIntegerArray("core_ctl", cpus.size, 0..1, "PROFILE_LIMITER_CORE_CTL_INVALID")
        return cpus.zip(binding.policyIds).mapIndexed { index, (raw, policy) ->
            val cpu = raw as? ProfileFeatureValue.ObjectValue
                ?: reject("PROFILE_LIMITER_INVALID", "限频器 $name 的 CPU 策略无效")
            cpu.requireOnlyKeys(
                setOf("min", "max", "margins", "excludes", "prefer"),
                "PROFILE_LIMITER_CLUSTER_FIELD_UNSUPPORTED",
                "限频器 $name 的 policy$policy"
            )
            val min = cpu.requiredFrequency("min", name, binding, policy)
            val max = cpu.requiredFrequency("max", name, binding, policy)
            if (min > max) reject("PROFILE_LIMITER_RANGE_INVALID", "限频器 $name 的最小频率高于最大频率")
            val margins = cpu.requiredMargins(name)
            val excludes = cpu.optionalIntegerArray("excludes", null, 0..255, "PROFILE_LIMITER_EXCLUDES_INVALID")
                ?.joinToString(",") ?: ABSENT
            val prefer = cpu.optionalInteger("prefer", 0..255, "PROFILE_LIMITER_PREFER_INVALID")
                ?.toString() ?: ABSENT
            CapabilityCommand(
                capability = "scheduler.profile.limiter.cluster.set",
                arguments = mapOf(
                    "profile" to name,
                    "policy" to policy.toString(),
                    "min_khz" to min.toString(),
                    "max_khz" to max.toString(),
                    "margins" to margins,
                    "excludes" to excludes,
                    "prefer" to prefer,
                    "core_ctl" to (coreCtl?.get(index)?.toString() ?: ABSENT),
                    "ddr_boost" to ddrBoost.toString()
                )
            )
        }
    }

    private fun ProfileFeatureValue.ObjectValue.requiredFrequency(
        key: String,
        limiter: String,
        binding: ProfileDeviceBinding,
        policy: Int
    ): Long {
        val raw = scalar(key)?.toLongOrNull()?.takeIf { it in FREQUENCY_RANGE }
            ?: reject("PROFILE_LIMITER_FREQUENCY_INVALID", "限频器 $limiter 的 $key 频率无效")
        return resolveFrequency(ProfileValue(raw.toString(), ValueNotation.HASH_PREFIXED), binding, policy, true)
    }

    private fun ProfileFeatureValue.ObjectValue.requiredMargins(limiter: String): String {
        val raw = scalar("margins")
            ?: reject("PROFILE_LIMITER_MARGINS_INVALID", "限频器 $limiter 缺少 margins")
        if (raw.length !in 1..256) reject("PROFILE_LIMITER_MARGINS_INVALID", "限频器 $limiter 的 margins 过长")
        val tokens = raw.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) reject("PROFILE_LIMITER_MARGINS_INVALID", "限频器 $limiter 的 margins 为空")
        val initial = tokens.first().toIntOrNull()?.takeIf { it in MANAGED_MARGIN_RANGE }
            ?: reject("PROFILE_LIMITER_MARGINS_INVALID", "限频器 $limiter 的初始 margin 无效")
        var previousFrequency = 0L
        val normalized = mutableListOf(initial.toString())
        tokens.drop(1).forEach { token ->
            val parts = token.split(':', limit = 2)
            val frequency = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it in FREQUENCY_RANGE }
                ?: reject("PROFILE_LIMITER_MARGINS_INVALID", "限频器 $limiter 的 margin 频率无效")
            val load = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in MANAGED_MARGIN_RANGE }
                ?: reject("PROFILE_LIMITER_MARGINS_INVALID", "限频器 $limiter 的 margin 数值无效")
            if (frequency <= previousFrequency) {
                reject("PROFILE_LIMITER_MARGINS_INVALID", "限频器 $limiter 的 margin 频率必须递增")
            }
            previousFrequency = frequency
            normalized += "$frequency:$load"
        }
        return normalized.joinToString(" ")
    }

    private fun node(path: String, value: ProfileValue): CapabilityCommand {
        // Legacy # means an explicit literal. ^ requested adaptive frequency selection; the kernel
        // performs the final table clamp and read-back verification decides whether it was accepted.
        return plainNode(path, value.text)
    }

    private fun plainNode(path: String, value: String) = CapabilityCommand(
        capability = "scheduler.node.write",
        arguments = mapOf("path" to path, "value" to value)
    )

    private fun policyFrequency(policyId: Int, minimum: Boolean, khz: Long) = CapabilityCommand(
        capability = "cpu.policy.$policyId.${if (minimum) "min_frequency" else "max_frequency"}.set",
        arguments = mapOf("khz" to khz.toString())
    )

    private fun resolveFrequency(
        value: ProfileValue,
        binding: ProfileDeviceBinding,
        policyId: Int,
        zeroMeansMinimum: Boolean
    ): Long {
        val requested = value.text.toLongOrNull()?.takeIf { it >= 0 }
            ?: reject("PROFILE_FREQUENCY_INVALID", "CPU 频率 ${value.text} 无效")
        val table = binding.availableFrequenciesKHz[policyId].orEmpty().filter { it > 0 }.distinct().sorted()
        if (table.isEmpty()) {
            if (requested == 0L) reject("PROFILE_FREQUENCY_TABLE_MISSING", "policy$policyId 缺少频率表，无法解释 0")
            return requested
        }
        if (requested == 0L && zeroMeansMinimum) return table.first()
        if (requested == 0L) return 0L
        return when (value.notation) {
            ValueNotation.PLAIN -> requested.takeIf { it in table }
                ?: reject("PROFILE_FREQUENCY_UNSUPPORTED", "policy$policyId 不支持频率 $requested")
            ValueNotation.HASH_PREFIXED -> table.lastOrNull { it <= requested } ?: table.first()
            ValueNotation.CARET_PREFIXED -> table.firstOrNull { it >= requested } ?: table.last()
        }
    }

    private fun parsePolicy(raw: String): Int = raw.removePrefix("policy").toIntOrNull()?.takeIf { it in 0..255 }
        ?: reject("PROFILE_POLICY_INVALID", "CPU 策略标识 $raw 无效")

    private fun Map<String, ProfileFeatureValue>.objectEntry(key: String) = get(key) as? ProfileFeatureValue.ObjectValue
    private fun ProfileFeatureValue.ObjectValue.objectEntry(key: String) = entries[key] as? ProfileFeatureValue.ObjectValue
    private fun ProfileFeatureValue.ObjectValue.arrayEntry(key: String) = (entries[key] as? ProfileFeatureValue.ArrayValue)?.entries
    private fun ProfileFeatureValue.ObjectValue.boolean(key: String) = (entries[key] as? ProfileFeatureValue.BooleanValue)?.value
    private fun ProfileFeatureValue.ObjectValue.scalar(key: String): String? = when (val value = entries[key]) {
        is ProfileFeatureValue.NumberValue -> value.value
        is ProfileFeatureValue.StringValue -> value.value
        else -> null
    }

    private fun ProfileFeatureValue.ObjectValue.optionalInteger(
        key: String,
        range: IntRange,
        code: String
    ): Int? {
        val raw = entries[key] ?: return null
        return (raw as? ProfileFeatureValue.NumberValue)?.value?.toIntOrNull()?.takeIf { it in range }
            ?: reject(code, "limiter 的 $key 声明无效")
    }

    private fun ProfileFeatureValue.ObjectValue.optionalIntegerArray(
        key: String,
        requiredSize: Int?,
        range: IntRange,
        code: String
    ): List<Int>? {
        val raw = entries[key] ?: return null
        val values = (raw as? ProfileFeatureValue.ArrayValue)?.entries?.map { entry ->
            (entry as? ProfileFeatureValue.NumberValue)?.value?.toIntOrNull()?.takeIf { it in range }
                ?: reject(code, "limiter 的 $key 声明无效")
        } ?: reject(code, "limiter 的 $key 必须是整数数组")
        if (requiredSize != null && values.size != requiredSize) reject(code, "limiter 的 $key 数量与 CPU 簇不一致")
        if (key == "excludes" && values.distinct().size != values.size) reject(code, "limiter 的 excludes 包含重复项")
        return values
    }

    private fun ProfileFeatureValue.ObjectValue.requireOnlyKeys(
        supported: Set<String>,
        code: String,
        label: String
    ) {
        val unknown = entries.keys - supported
        if (unknown.isNotEmpty()) reject(code, "$label 包含未定义语义字段：${unknown.sorted().joinToString()}")
    }

    private fun reject(code: String, reason: String): Nothing = throw RejectedBinding(code, reason)
    private class RejectedBinding(val code: String, message: String) : IllegalArgumentException(message)
    private companion object {
        const val MAX_COMMANDS = 2048
        const val ABSENT = "absent"
        val FREQUENCY_RANGE = 100_000L..10_000_000L
        val LOAD_RANGE = 0..1_000
        val MANAGED_MARGIN_RANGE = 0..999
    }
}
