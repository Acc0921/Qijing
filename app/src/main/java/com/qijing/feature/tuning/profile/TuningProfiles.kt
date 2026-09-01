package com.qijing.feature.tuning.profile

import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId

sealed interface TuningProfileReference {
    val stableId: String

    data class BuiltIn(val mode: SchedulerMode) : TuningProfileReference {
        override val stableId: String = "builtin:${mode.stableId}"
    }

    data class Custom(val profileId: String) : TuningProfileReference {
        init { require(PROFILE_ID.matches(profileId)) { "Invalid custom profile ID" } }
        override val stableId: String = "custom:$profileId"
    }

    companion object {
        fun parse(stableId: String): TuningProfileReference? = when {
            stableId.startsWith("builtin:") -> SchedulerMode.fromStableId(stableId.removePrefix("builtin:"))?.let(::BuiltIn)
            stableId.startsWith("custom:") -> runCatching { Custom(stableId.removePrefix("custom:")) }.getOrNull()
            else -> null
        }
    }
}

data class CustomTuningProfile(
    val id: String,
    val name: String,
    val governor: String? = null,
    val minFrequencyKHz: Long? = null,
    val maxFrequencyKHz: Long? = null,
    val swappiness: Int? = null
) {
    fun validationError(): String? = when {
        !PROFILE_ID.matches(id) -> "自定义方案 ID 无效"
        name.isBlank() || name.length > 48 -> "自定义方案名称无效"
        governor != null && !GOVERNOR.matches(governor) -> "CPU governor 无效"
        minFrequencyKHz != null && minFrequencyKHz !in FREQUENCY_RANGE -> "CPU 最小频率无效"
        maxFrequencyKHz != null && maxFrequencyKHz !in FREQUENCY_RANGE -> "CPU 最大频率无效"
        minFrequencyKHz != null && maxFrequencyKHz != null && minFrequencyKHz > maxFrequencyKHz -> "CPU 最小频率高于最大频率"
        swappiness != null && swappiness !in 0..200 -> "swappiness 无效"
        governor == null && minFrequencyKHz == null && maxFrequencyKHz == null && swappiness == null -> "自定义方案没有调节目标"
        else -> null
    }

    val reference: TuningProfileReference.Custom get() = TuningProfileReference.Custom(id)
}

data class GlobalTuningConfiguration(
    val selected: TuningProfileReference = TuningProfileReference.BuiltIn(SchedulerMode.BALANCED),
    val provider: SchedulerProviderId = SchedulerProviderId.SYSTEM,
    val customProfiles: List<CustomTuningProfile> = emptyList(),
    val revision: Long = 0L,
    val updatedAtMs: Long = 0L
) {
    fun validationError(): String? = when {
        revision < 0L -> "全局方案 revision 无效"
        customProfiles.size > MAX_CUSTOM_PROFILES -> "自定义方案数量过多"
        customProfiles.map { it.id }.distinct().size != customProfiles.size -> "自定义方案 ID 重复"
        customProfiles.firstNotNullOfOrNull(CustomTuningProfile::validationError) != null ->
            customProfiles.firstNotNullOf(CustomTuningProfile::validationError)
        selected is TuningProfileReference.Custom && customProfiles.none { it.id == selected.profileId } -> "选中的自定义方案不存在"
        selected is TuningProfileReference.Custom && provider != SchedulerProviderId.SYSTEM -> "第三方调度器不接受系统自定义参数"
        else -> null
    }

    private companion object { const val MAX_CUSTOM_PROFILES = 32 }
}

private val PROFILE_ID = Regex("[A-Za-z0-9._-]{1,48}")
private val GOVERNOR = Regex("[A-Za-z0-9_-]{1,32}")
private val FREQUENCY_RANGE = 100_000L..10_000_000L
