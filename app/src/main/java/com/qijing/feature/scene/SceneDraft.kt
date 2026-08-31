package com.qijing.feature.scene

import com.qijing.core.data.NewDataStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.scene.ScenePreparation
import com.qijing.core.model.ExecutionBackend

data class SceneDraft(
    val id: String,
    val name: String,
    val packages: Set<String> = emptySet(),
    val governor: String = "",
    val minFrequencyKHz: String = "",
    val maxFrequencyKHz: String = "",
    val onlineCores: Set<Int>? = null,
    val zramEnabled: Boolean? = null,
    val zramSizeMiB: String = "",
    val compressionAlgorithm: String = "",
    val swappiness: String = "",
    val priority: Int = 50,
    val enabled: Boolean = false
) {
    companion object {
        fun fromProfile(profile: SceneProfile): SceneDraft = SceneDraft(
            id = profile.id,
            name = profile.name,
            packages = profile.packageNames,
            governor = profile.cpu.governor.orEmpty(),
            minFrequencyKHz = profile.cpu.minFrequencyKHz?.toString().orEmpty(),
            maxFrequencyKHz = profile.cpu.maxFrequencyKHz?.toString().orEmpty(),
            onlineCores = profile.cpu.onlineCores,
            zramEnabled = profile.memory.zramEnabled,
            zramSizeMiB = profile.memory.zramSizeBytes?.div(1024 * 1024L)?.toString().orEmpty(),
            compressionAlgorithm = profile.memory.compressionAlgorithm.orEmpty(),
            swappiness = profile.memory.swappiness?.toString().orEmpty(),
            priority = profile.priority.coerceIn(0, 100),
            enabled = profile.enabled
        )
    }

    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("场景名称不能为空")
        if (packages.isEmpty()) add("必须绑定至少一个应用")
        val min = minFrequencyKHz.toLongOrNull(); val max = maxFrequencyKHz.toLongOrNull()
        if (minFrequencyKHz.isNotBlank() && min == null) add("CPU 最小频率格式错误")
        if (maxFrequencyKHz.isNotBlank() && max == null) add("CPU 最大频率格式错误")
        if (min != null && max != null && min > max) add("CPU 最小频率不能高于最大频率")
        val swappinessValue = swappiness.toIntOrNull()
        if (swappiness.isNotBlank() && (swappinessValue == null || swappinessValue !in 0..200)) add("swappiness 必须在 0..200")
        if (zramSizeMiB.isNotBlank() && (zramSizeMiB.toLongOrNull() ?: 0) <= 0) add("ZRAM 容量必须大于 0")
        if (priority !in 0..100) add("优先级必须在 0..100")
    }

    fun toProfile(): SceneProfile {
        val errors = validate(); require(errors.isEmpty()) { errors.joinToString("；") }
        return SceneProfile(id = id, name = name, packageNames = packages,
            cpu = CpuIntent(governor.ifBlank { null }, minFrequencyKHz.toLongOrNull(), maxFrequencyKHz.toLongOrNull(), onlineCores),
            memory = MemoryIntent(zramEnabled, zramSizeMiB.toLongOrNull()?.times(1024 * 1024), compressionAlgorithm.ifBlank { null }, swappiness.toIntOrNull()),
            priority = priority,
            enabled = enabled)
    }
}

class SceneDraftStore(private val store: NewDataStore) {
    /** Editing and ordinary saves can never enable a scene. */
    fun save(draft: SceneDraft): List<String> {
        val errors = draft.validate()
        if (errors.isEmpty()) store.saveScene(draft.copy(enabled = false).toProfile())
        return errors
    }

    fun load(): List<SceneProfile> = store.scenes()

    fun disable(id: String) {
        store.scenes().firstOrNull { it.id == id }?.let { store.saveScene(it.copy(enabled = false)) }
    }

    /** The prepared scene must be byte-for-byte equivalent to the draft being approved. */
    fun enableApproved(draft: SceneDraft, preparation: ScenePreparation?, backend: ExecutionBackend): List<String> {
        val errors = draft.validate().toMutableList()
        val candidate = if (errors.isEmpty()) draft.copy(enabled = false).toProfile() else null
        if (preparation == null || !preparation.ready) errors += "必须先完成安全预演"
        if (preparation?.plan?.commands?.isEmpty() != false) errors += "场景没有可执行的调节目标"
        if (candidate != null && preparation?.scene != candidate) errors += "场景内容已变化，请重新预演"
        if (preparation?.backend != backend) errors += "执行方式已变化，请重新预演"
        if (errors.isEmpty()) store.saveScene(candidate!!.copy(enabled = true))
        return errors.distinct()
    }
}
