package com.qijing.core.model

import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId

enum class ExecutionBackend { ROOT, ADB, SHIZUKU, DAEMON, DRY_RUN }

data class DeviceSnapshot(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val soc: String?,
    val availableBackends: Set<ExecutionBackend>,
    val capabilities: Set<String>
)

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val isSystem: Boolean,
    /** A launcher entry is a strong, but not absolute, signal that UsageStats can observe it. */
    val isLaunchable: Boolean = true
)

data class CpuIntent(
    val governor: String? = null,
    val minFrequencyKHz: Long? = null,
    val maxFrequencyKHz: Long? = null,
    val onlineCores: Set<Int>? = null,
    val policies: List<CpuPolicyIntent> = emptyList()
)

/** A typed CPUFreq policy target; [policyId] is the numeric suffix of policyN. */
data class CpuPolicyIntent(
    val policyId: Int,
    val governor: String? = null,
    val minFrequencyKHz: Long? = null,
    val maxFrequencyKHz: Long? = null
) {
    init {
        require(policyId in 0..255) { "CPU policy ID must be in 0..255" }
        require(minFrequencyKHz == null || maxFrequencyKHz == null || minFrequencyKHz <= maxFrequencyKHz) {
            "CPU policy minimum frequency cannot exceed maximum frequency"
        }
    }
}

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
    val enabled: Boolean = true,
    val schedulerProvider: SchedulerProviderId = SchedulerProviderId.SYSTEM,
    val schedulerMode: SchedulerMode? = null,
    val followsGlobalProfile: Boolean = false
)

data class TelemetrySample(
    val sessionId: String,
    val timestampMs: Long,
    val fps: Double,
    val frameTimeMs: Double,
    val jankCount: Int,
    /** Per-frame values for a real session percentile; empty for legacy window-only samples. */
    val frameTimesMs: List<Double> = emptyList()
)
