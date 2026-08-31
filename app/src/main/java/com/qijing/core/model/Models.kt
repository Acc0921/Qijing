package com.qijing.core.model

enum class ExecutionBackend { ROOT, ADB, SHIZUKU, DAEMON, DRY_RUN }

data class DeviceSnapshot(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val soc: String?,
    val availableBackends: Set<ExecutionBackend>,
    val capabilities: Set<String>
)

data class AppEntry(val packageName: String, val label: String, val versionName: String, val isSystem: Boolean)

data class CpuIntent(
    val governor: String? = null,
    val minFrequencyKHz: Long? = null,
    val maxFrequencyKHz: Long? = null,
    val onlineCores: Set<Int>? = null
)

data class MemoryIntent(
    val zramEnabled: Boolean? = null,
    val zramSizeBytes: Long? = null,
    val compressionAlgorithm: String? = null,
    val swappiness: Int? = null
)

data class SceneProfile(
    val id: String,
    val name: String,
    val packageNames: Set<String>,
    val cpu: CpuIntent = CpuIntent(),
    val memory: MemoryIntent = MemoryIntent(),
    val priority: Int = 0,
    val enabled: Boolean = true
)

data class TelemetrySample(
    val sessionId: String,
    val timestampMs: Long,
    val fps: Double,
    val frameTimeMs: Double,
    val jankCount: Int
)
