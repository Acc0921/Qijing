package com.qijing.feature.scene

import com.qijing.core.data.NewDataStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile

data class SceneDraft(
    val id: String,
    val name: String,
    val packages: Set<String> = emptySet(),
    val governor: String = "",
    val minFrequencyKHz: String = "",
    val maxFrequencyKHz: String = "",
    val zramEnabled: Boolean? = null,
    val zramSizeMiB: String = "",
    val swappiness: String = ""
) {
    companion object {
        fun fromProfile(profile: SceneProfile): SceneDraft = SceneDraft(
            id = profile.id,
            name = profile.name,
            packages = profile.packageNames,
            governor = profile.cpu.governor.orEmpty(),
            minFrequencyKHz = profile.cpu.minFrequencyKHz?.toString().orEmpty(),
            maxFrequencyKHz = profile.cpu.maxFrequencyKHz?.toString().orEmpty(),
            zramEnabled = profile.memory.zramEnabled,
            zramSizeMiB = profile.memory.zramSizeBytes?.div(1024 * 1024L)?.toString().orEmpty(),
            swappiness = profile.memory.swappiness?.toString().orEmpty()
        )
    }

    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("场景名称不能为空")
        val min = minFrequencyKHz.toLongOrNull(); val max = maxFrequencyKHz.toLongOrNull()
        if (minFrequencyKHz.isNotBlank() && min == null) add("CPU 最小频率格式错误")
        if (maxFrequencyKHz.isNotBlank() && max == null) add("CPU 最大频率格式错误")
        if (min != null && max != null && min > max) add("CPU 最小频率不能高于最大频率")
        val swappinessValue = swappiness.toIntOrNull()
        if (swappiness.isNotBlank() && (swappinessValue == null || swappinessValue !in 0..200)) add("swappiness 必须在 0..200")
        if (zramSizeMiB.isNotBlank() && (zramSizeMiB.toLongOrNull() ?: 0) <= 0) add("ZRAM 容量必须大于 0")
    }

    fun toProfile(): SceneProfile {
        val errors = validate(); require(errors.isEmpty()) { errors.joinToString("；") }
        return SceneProfile(id = id, name = name, packageNames = packages,
            cpu = CpuIntent(governor.ifBlank { null }, minFrequencyKHz.toLongOrNull(), maxFrequencyKHz.toLongOrNull()),
            memory = MemoryIntent(zramEnabled, zramSizeMiB.toLongOrNull()?.times(1024 * 1024), swappiness = swappiness.toIntOrNull()), enabled = true)
    }
}

class SceneDraftStore(private val store: NewDataStore) {
    fun save(draft: SceneDraft): List<String> { val errors = draft.validate(); if (errors.isEmpty()) store.saveScene(draft.toProfile()); return errors }
    fun load(): List<SceneProfile> = store.scenes()
    fun setEnabled(id: String, enabled: Boolean) {
        store.scenes().firstOrNull { it.id == id }?.let { store.saveScene(it.copy(enabled = enabled)) }
    }
}
