package com.qijing.core.device.observation

import kotlin.math.abs

class BatteryObservationReader(
    private val platformSource: BatteryPlatformSource? = null,
    private val fileSystem: ObservationFileSystem = LocalObservationFileSystem(),
    private val powerSupplyRoot: String = "/sys/class/power_supply",
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun read(): BatteryObservation {
        val sampledAt = clock()
        val platform = platformSource?.let { source ->
            runCatching { source.read() }.getOrElse { error ->
                val detail = error.message ?: "Android 电池 API 读取失败"
                BatteryPlatformSnapshot(
                    capacityPercent = PlatformBatteryValue.Invalid(detail),
                    currentMicroAmps = PlatformBatteryValue.Invalid(detail),
                    voltageMilliVolts = PlatformBatteryValue.Invalid(detail),
                    temperatureCelsius = PlatformBatteryValue.Invalid(detail),
                    flow = PlatformBatteryValue.Invalid(detail)
                )
            }
        } ?: BatteryPlatformSnapshot()
        val supply = discoverBatterySupply()

        val capacity = preferPlatform(
            platform.capacityPercent,
            fromSupply(supply, sampledAt) { readIntMetric("$it/capacity", sampledAt, 0..100) },
            sampledAt
        )
        val current = preferPlatform(
            platform.currentMicroAmps,
            fromSupply(supply, sampledAt) { readLongMetric("$it/current_now", sampledAt) },
            sampledAt
        )
        val voltage = preferPlatform(
            platform.voltageMilliVolts,
            fromSupply(supply, sampledAt) { path ->
                readPositiveLongMetric("$path/voltage_now", sampledAt, "电池电压").mapAvailable { microVolts -> microVolts / 1000L }
            },
            sampledAt
        )
        val temperature = preferPlatform(
            platform.temperatureCelsius,
            fromSupply(supply, sampledAt) { path ->
                val raw = readLongMetric("$path/temp", sampledAt)
                if (raw.status == MetricStatus.AVAILABLE && raw.value !in -1000L..1500L) {
                    ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "电池温度超出有效范围")
                } else raw.mapAvailable { tenths -> tenths / 10.0 }
            },
            sampledAt
        )
        val flow = preferPlatform(
            platform.flow,
            fromSupply(supply, sampledAt) { readFlowMetric("$it/status", sampledAt) },
            sampledAt
        )
        val directPower = fromSupply(supply, sampledAt) { path ->
            readLongMetric("$path/power_now", sampledAt).mapAvailable { microWatts -> abs(microWatts.toDouble()) / 1000.0 }
        }
        val power = when {
            directPower?.status == MetricStatus.AVAILABLE -> directPower
            current.status == MetricStatus.AVAILABLE && voltage.status == MetricStatus.AVAILABLE -> {
                val milliWatts = abs(current.value!!.toDouble() * voltage.value!!.toDouble()) / 1_000_000.0
                ObservedMetric.available(
                    milliWatts,
                    MetricSource.DERIVED,
                    sampledAt,
                    "由瞬时电流与电压估算，不代表 CPU 单独功耗",
                    estimated = true
                )
            }
            else -> {
                val status = listOfNotNull(directPower?.status, current.status, voltage.status)
                    .firstOrNull { it == MetricStatus.PERMISSION_DENIED || it == MetricStatus.INVALID }
                    ?: MetricStatus.UNSUPPORTED
                ObservedMetric.unavailable(status, sampledAt, detail = "设备未提供可用的功率，且电流/电压不足以估算")
            }
        }
        return BatteryObservation(capacity, current, voltage, temperature, power, flow, sampledAt)
    }

    private fun discoverBatterySupply(): BatterySupply {
        return when (val directory = fileSystem.list(powerSupplyRoot)) {
            is DirectoryReadResult.Success -> {
                var denied = false
                var invalid: String? = null
                directory.names.forEach { name ->
                    when (val type = fileSystem.readText("$powerSupplyRoot/$name/type")) {
                        is FileReadResult.Success -> if (type.text.trim().equals("Battery", ignoreCase = true)) {
                            return BatterySupply.Found("$powerSupplyRoot/$name")
                        }
                        FileReadResult.PermissionDenied -> denied = true
                        is FileReadResult.Failed -> invalid = invalid ?: type.detail
                        FileReadResult.Missing -> Unit
                    }
                }
                when {
                    denied -> BatterySupply.Unavailable(MetricStatus.PERMISSION_DENIED, "当前身份无权识别电池电源节点")
                    invalid != null -> BatterySupply.Unavailable(MetricStatus.INVALID, invalid!!)
                    else -> BatterySupply.Unavailable(MetricStatus.UNSUPPORTED, "未发现 type=Battery 的电源设备")
                }
            }
            DirectoryReadResult.Missing -> BatterySupply.Unavailable(MetricStatus.UNSUPPORTED, "系统未提供 power_supply")
            DirectoryReadResult.PermissionDenied -> BatterySupply.Unavailable(MetricStatus.PERMISSION_DENIED, "当前身份无权枚举电池 sysfs")
            is DirectoryReadResult.Failed -> BatterySupply.Unavailable(MetricStatus.INVALID, directory.detail)
        }
    }

    private fun <T> fromSupply(
        supply: BatterySupply,
        sampledAt: Long,
        reader: (String) -> ObservedMetric<T>
    ): ObservedMetric<T> = when (supply) {
        is BatterySupply.Found -> reader(supply.path)
        is BatterySupply.Unavailable -> ObservedMetric.unavailable(supply.status, sampledAt, MetricSource.SYSFS, supply.detail)
    }

    private fun <T> preferPlatform(
        platform: PlatformBatteryValue<T>,
        sysfs: ObservedMetric<T>?,
        sampledAt: Long
    ): ObservedMetric<T> = when (platform) {
        is PlatformBatteryValue.Available -> ObservedMetric.available(platform.value, MetricSource.ANDROID_API, sampledAt)
        is PlatformBatteryValue.Invalid -> sysfs?.takeIf { it.status == MetricStatus.AVAILABLE }
            ?: ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.ANDROID_API, platform.detail)
        PlatformBatteryValue.Unsupported -> sysfs
            ?: ObservedMetric.unavailable(MetricStatus.UNSUPPORTED, sampledAt, detail = "Android API 与 sysfs 均未提供")
    }

    private fun readLongMetric(path: String, sampledAt: Long): ObservedMetric<Long> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> result.text.trim().toLongOrNull()?.let {
                ObservedMetric.available(it, MetricSource.SYSFS, sampledAt)
            } ?: ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "数值格式无效")
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, result.detailWhenUnavailable())
        }

    private fun readPositiveLongMetric(path: String, sampledAt: Long, label: String): ObservedMetric<Long> {
        val raw = readLongMetric(path, sampledAt)
        return if (raw.status == MetricStatus.AVAILABLE && raw.value!! <= 0L) {
            ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "$label 必须大于 0")
        } else raw
    }

    private fun readIntMetric(path: String, sampledAt: Long, accepted: IntRange): ObservedMetric<Int> {
        val raw = readLongMetric(path, sampledAt)
        if (raw.status != MetricStatus.AVAILABLE) {
            return ObservedMetric.unavailable(raw.status, sampledAt, raw.source, raw.detail)
        }
        val value = raw.value!!
        return if (value in accepted.first.toLong()..accepted.last.toLong()) {
            ObservedMetric.available(value.toInt(), MetricSource.SYSFS, sampledAt)
        } else ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "数值超出有效范围")
    }

    private fun readFlowMetric(path: String, sampledAt: Long): ObservedMetric<BatteryFlow> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> ObservedMetric.available(
                when (result.text.trim().lowercase()) {
                    "charging" -> BatteryFlow.CHARGING
                    "discharging" -> BatteryFlow.DISCHARGING
                    "full" -> BatteryFlow.FULL
                    "not charging" -> BatteryFlow.NOT_CHARGING
                    else -> BatteryFlow.UNKNOWN
                },
                MetricSource.SYSFS,
                sampledAt
            )
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, result.detailWhenUnavailable())
        }

    private fun <T, R> ObservedMetric<T>.mapAvailable(transform: (T) -> R): ObservedMetric<R> =
        if (status == MetricStatus.AVAILABLE) {
            ObservedMetric.available(transform(value!!), source ?: MetricSource.SYSFS, sampledAtMs, detail, estimated)
        } else {
            ObservedMetric.unavailable(status, sampledAtMs, source, detail)
        }

    private sealed interface BatterySupply {
        data class Found(val path: String) : BatterySupply
        data class Unavailable(val status: MetricStatus, val detail: String) : BatterySupply
    }
}
