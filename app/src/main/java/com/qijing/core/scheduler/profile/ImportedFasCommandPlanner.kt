package com.qijing.core.scheduler.profile

import com.qijing.core.execution.CapabilityCommand

/** Converts the bounded `fas` import metadata into an explicit execution contract. */
class ImportedFasCommandPlanner {
    fun plan(
        metadata: Map<String, ProfileFeatureValue>,
        packageName: String,
        binding: ProfileDeviceBinding
    ): ProfileCommandPlan {
        if (metadata.isEmpty()) return ProfileCommandPlan.Planned(emptyList(), emptyList())
        if (metadata.keys != setOf(FREQUENCIES)) {
            return rejected("PROFILE_FAS_METADATA_UNSUPPORTED", "fas 包含尚未定义执行语义的字段")
        }
        if (!PACKAGE.matches(packageName)) {
            return rejected("PROFILE_FAS_PACKAGE_INVALID", "fas 需要一个有效的目标应用包名")
        }
        if (binding.policyIds.size != 2) {
            return rejected("PROFILE_FAS_TOPOLOGY_UNSUPPORTED", "fas.freq 当前只定义了两簇设备的大核/小核顺序")
        }
        val values = (metadata[FREQUENCIES] as? ProfileFeatureValue.ArrayValue)?.entries
            ?: return rejected("PROFILE_FAS_FREQUENCIES_INVALID", "fas.freq 必须是频率数组")
        if (values.size != 2) {
            return rejected("PROFILE_FAS_FREQUENCIES_INVALID", "fas.freq 必须依次包含大核与小核频率")
        }
        val performance = values[0].frequencyOrNull()
            ?: return rejected("PROFILE_FAS_FREQUENCIES_INVALID", "fas 大核频率无效")
        val efficiency = values[1].frequencyOrNull()
            ?: return rejected("PROFILE_FAS_FREQUENCIES_INVALID", "fas 小核频率无效")
        val efficiencyPolicy = binding.policyIds.first()
        val performancePolicy = binding.policyIds.last()
        if (!binding.supports(performancePolicy, performance)) {
            return rejected("PROFILE_FAS_FREQUENCY_UNSUPPORTED", "大核 policy$performancePolicy 不支持 fas 频率 $performance")
        }
        if (!binding.supports(efficiencyPolicy, efficiency)) {
            return rejected("PROFILE_FAS_FREQUENCY_UNSUPPORTED", "小核 policy$efficiencyPolicy 不支持 fas 频率 $efficiency")
        }
        return ProfileCommandPlan.Planned(
            commands = listOf(
                CapabilityCommand(
                    capability = "scheduler.profile.app_frequencies.set",
                    arguments = mapOf(
                        "package" to packageName,
                        "performance_khz" to performance.toString(),
                        "efficiency_khz" to efficiency.toString()
                    )
                )
            ),
            notices = listOf("已装入该应用的 FAS 大核/小核频率契约")
        )
    }

    private fun ProfileDeviceBinding.supports(policy: Int, frequency: Long): Boolean {
        val table = availableFrequenciesKHz[policy].orEmpty()
        return table.isNotEmpty() && frequency in table
    }

    private fun ProfileFeatureValue.frequencyOrNull(): Long? = when (this) {
        is ProfileFeatureValue.NumberValue -> value.toLongOrNull()
        is ProfileFeatureValue.StringValue -> value.toLongOrNull()
        else -> null
    }?.takeIf { it in FREQUENCY_RANGE }

    private fun rejected(code: String, reason: String) = ProfileCommandPlan.Rejected(code, reason)

    private companion object {
        const val FREQUENCIES = "freq"
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val FREQUENCY_RANGE = 100_000L..10_000_000L
    }
}
