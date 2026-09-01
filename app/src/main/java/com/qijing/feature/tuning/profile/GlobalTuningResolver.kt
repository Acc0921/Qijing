package com.qijing.feature.tuning.profile

import com.qijing.core.device.observation.CpuObservation
import com.qijing.core.device.observation.MetricStatus
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.CpuPolicyIntent
import com.qijing.core.model.MemoryIntent
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId

data class ResolvedGlobalTuning(
    val provider: SchedulerProviderId,
    val mode: SchedulerMode?,
    val cpu: CpuIntent,
    val memory: MemoryIntent,
    val label: String,
    val warnings: List<String> = emptyList()
)

sealed interface GlobalTuningResolution {
    data class Ready(val target: ResolvedGlobalTuning) : GlobalTuningResolution
    data class Blocked(val reason: String) : GlobalTuningResolution
}

/** Maps product intent to capabilities that the current device actually declared. */
class GlobalTuningResolver {
    fun resolve(
        configuration: GlobalTuningConfiguration,
        cpu: CpuObservation
    ): GlobalTuningResolution {
        configuration.validationError()?.let { return GlobalTuningResolution.Blocked(it) }
        val selected = configuration.selected
        if (configuration.provider != SchedulerProviderId.SYSTEM) {
            val mode = (selected as? TuningProfileReference.BuiltIn)?.mode
                ?: return GlobalTuningResolution.Blocked("第三方调度只能使用四档标准模式")
            return GlobalTuningResolution.Ready(
                ResolvedGlobalTuning(configuration.provider, mode, CpuIntent(), MemoryIntent(), mode.displayName())
            )
        }

        return when (selected) {
            is TuningProfileReference.BuiltIn -> resolveBuiltIn(selected.mode, cpu)
            is TuningProfileReference.Custom -> {
                val profile = configuration.customProfiles.firstOrNull { it.id == selected.profileId }
                    ?: return GlobalTuningResolution.Blocked("自定义方案不存在")
                resolveCustom(profile, cpu)
            }
        }
    }

    private fun resolveBuiltIn(mode: SchedulerMode, cpu: CpuObservation): GlobalTuningResolution {
        if (cpu.policies.isEmpty()) return GlobalTuningResolution.Blocked("设备未公开 CPUFreq policy，无法生成安全目标")
        val warnings = mutableListOf<String>()
        val policies = cpu.policies.map { policy ->
            val available = policy.availableGovernors.value.orEmpty()
            val governor = chooseGovernor(mode, available)
                ?: return GlobalTuningResolution.Blocked("${policy.id} 没有与${mode.displayName()}兼容的 Governor")
            val max = if (mode == SchedulerMode.EXTREME) {
                policy.hardwareMaxFrequencyKHz.value
                    ?: return GlobalTuningResolution.Blocked("${policy.id} 缺少硬件最高频率，不能生成极速模式恢复计划")
            } else null
            if (mode == SchedulerMode.EXTREME) warnings += "极速模式会把各策略域最高限制恢复到硬件声明值，发热与耗电可能明显增加"
            CpuPolicyIntent(
                policyId = policy.id.removePrefix("policy").toIntOrNull()
                    ?: return GlobalTuningResolution.Blocked("CPU policy 标识无效：${policy.id}"),
                governor = governor,
                maxFrequencyKHz = max
            )
        }
        return GlobalTuningResolution.Ready(
            ResolvedGlobalTuning(
                SchedulerProviderId.SYSTEM,
                mode,
                CpuIntent(policies = policies),
                MemoryIntent(),
                mode.displayName(),
                warnings.distinct()
            )
        )
    }

    private fun resolveCustom(profile: CustomTuningProfile, cpu: CpuObservation): GlobalTuningResolution {
        profile.validationError()?.let { return GlobalTuningResolution.Blocked(it) }
        if (cpu.policies.isEmpty()) return GlobalTuningResolution.Blocked("设备未公开 CPUFreq policy")
        val policies = cpu.policies.map { policy ->
            val id = policy.id.removePrefix("policy").toIntOrNull()
                ?: return GlobalTuningResolution.Blocked("CPU policy 标识无效：${policy.id}")
            profile.governor?.let { governor ->
                val candidates = policy.availableGovernors
                if (candidates.status != MetricStatus.AVAILABLE || governor !in candidates.value.orEmpty()) {
                    return GlobalTuningResolution.Blocked("${policy.id} 不支持 Governor $governor")
                }
            }
            val hardwareMin = policy.hardwareMinFrequencyKHz.value
            val hardwareMax = policy.hardwareMaxFrequencyKHz.value
            if (profile.minFrequencyKHz != null && (hardwareMin == null || hardwareMax == null || profile.minFrequencyKHz !in hardwareMin..hardwareMax)) {
                return GlobalTuningResolution.Blocked("${policy.id} 不支持自定义最低频率 ${profile.minFrequencyKHz}")
            }
            if (profile.maxFrequencyKHz != null && (hardwareMin == null || hardwareMax == null || profile.maxFrequencyKHz !in hardwareMin..hardwareMax)) {
                return GlobalTuningResolution.Blocked("${policy.id} 不支持自定义最高频率 ${profile.maxFrequencyKHz}")
            }
            CpuPolicyIntent(id, profile.governor, profile.minFrequencyKHz, profile.maxFrequencyKHz)
        }
        return GlobalTuningResolution.Ready(
            ResolvedGlobalTuning(
                SchedulerProviderId.SYSTEM,
                null,
                CpuIntent(policies = policies),
                MemoryIntent(swappiness = profile.swappiness),
                profile.name
            )
        )
    }

    private fun chooseGovernor(mode: SchedulerMode, available: Set<String>): String? {
        val preferences = when (mode) {
            SchedulerMode.POWER_SAVE -> listOf("powersave", "conservative", "schedutil")
            SchedulerMode.BALANCED -> listOf("schedutil", "interactive", "ondemand")
            SchedulerMode.PERFORMANCE -> listOf("performance", "schedutil", "interactive")
            SchedulerMode.EXTREME -> listOf("performance")
        }
        return preferences.firstOrNull(available::contains)
    }
}

fun SchedulerMode.displayName(): String = when (this) {
    SchedulerMode.POWER_SAVE -> "省电"
    SchedulerMode.BALANCED -> "均衡"
    SchedulerMode.PERFORMANCE -> "性能"
    SchedulerMode.EXTREME -> "极速"
}
