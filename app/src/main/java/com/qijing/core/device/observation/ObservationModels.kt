package com.qijing.core.device.observation

enum class MetricStatus {
    AVAILABLE,
    SAMPLING,
    UNSUPPORTED,
    PERMISSION_DENIED,
    INACTIVE,
    INVALID,
    STALE
}

enum class MetricSource { PROCFS, SYSFS, ANDROID_API, DERIVED }

/** A value is never silently replaced with zero when the device did not provide it. */
data class ObservedMetric<T>(
    val status: MetricStatus,
    val value: T? = null,
    val source: MetricSource? = null,
    val sampledAtMs: Long,
    val detail: String? = null,
    val estimated: Boolean = false
) {
    init {
        require(status != MetricStatus.AVAILABLE || value != null) {
            "An available metric must contain a value"
        }
    }

    companion object {
        fun <T> available(
            value: T,
            source: MetricSource,
            sampledAtMs: Long,
            detail: String? = null,
            estimated: Boolean = false
        ) = ObservedMetric(MetricStatus.AVAILABLE, value, source, sampledAtMs, detail, estimated)

        fun <T> unavailable(
            status: MetricStatus,
            sampledAtMs: Long,
            source: MetricSource? = null,
            detail: String? = null
        ): ObservedMetric<T> {
            require(status != MetricStatus.AVAILABLE)
            return ObservedMetric(status, null, source, sampledAtMs, detail)
        }
    }
}

data class CpuPolicyObservation(
    val id: String,
    val relatedCores: Set<Int>,
    val currentFrequencyKHz: ObservedMetric<Long>,
    val hardwareMinFrequencyKHz: ObservedMetric<Long>,
    val hardwareMaxFrequencyKHz: ObservedMetric<Long>,
    val scalingMinFrequencyKHz: ObservedMetric<Long>,
    val scalingMaxFrequencyKHz: ObservedMetric<Long>,
    val governor: ObservedMetric<String>,
    val availableGovernors: ObservedMetric<Set<String>>
)

data class CpuCoreObservation(
    val id: Int,
    val online: ObservedMetric<Boolean>,
    val policyId: String?,
    val currentFrequencyKHz: ObservedMetric<Long>,
    val loadPercent: ObservedMetric<Double>
)

data class CpuObservation(
    val policyCount: ObservedMetric<Int>,
    val policies: List<CpuPolicyObservation>,
    val cores: List<CpuCoreObservation>,
    val sampledAtMs: Long
)

data class ZramObservation(
    val device: String,
    val active: ObservedMetric<Boolean>,
    val diskSizeBytes: ObservedMetric<Long>,
    val originalDataBytes: ObservedMetric<Long>,
    val compressedDataBytes: ObservedMetric<Long>,
    val memoryUsedBytes: ObservedMetric<Long>,
    val algorithms: ObservedMetric<Set<String>>,
    val currentAlgorithm: ObservedMetric<String>
)

data class MemoryObservation(
    val totalBytes: ObservedMetric<Long>,
    val availableBytes: ObservedMetric<Long>,
    val cachedBytes: ObservedMetric<Long>,
    val swapTotalBytes: ObservedMetric<Long>,
    val swapFreeBytes: ObservedMetric<Long>,
    val swapUsedBytes: ObservedMetric<Long>,
    val swappiness: ObservedMetric<Int>,
    val zramDeviceCount: ObservedMetric<Int>,
    val zramDevices: List<ZramObservation>,
    val sampledAtMs: Long
)

data class GpuDeviceObservation(
    val id: String,
    val adapter: String,
    val currentFrequencyHz: ObservedMetric<Long>,
    val minFrequencyHz: ObservedMetric<Long>,
    val maxFrequencyHz: ObservedMetric<Long>,
    val availableFrequenciesHz: ObservedMetric<List<Long>>,
    val loadPercent: ObservedMetric<Double>,
    val governor: ObservedMetric<String>
)

data class GpuObservation(
    val status: MetricStatus,
    val devices: List<GpuDeviceObservation>,
    val sampledAtMs: Long,
    val detail: String? = null
)

enum class BatteryFlow { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

data class BatteryObservation(
    val capacityPercent: ObservedMetric<Int>,
    val currentMicroAmps: ObservedMetric<Long>,
    val voltageMilliVolts: ObservedMetric<Long>,
    val temperatureCelsius: ObservedMetric<Double>,
    val powerMilliWatts: ObservedMetric<Double>,
    val flow: ObservedMetric<BatteryFlow>,
    val sampledAtMs: Long
)
