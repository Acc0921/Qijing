package com.qijing.core.device.observation

sealed interface PlatformBatteryValue<out T> {
    data class Available<T>(val value: T) : PlatformBatteryValue<T>
    data object Unsupported : PlatformBatteryValue<Nothing>
    data class Invalid(val detail: String) : PlatformBatteryValue<Nothing>
}

data class BatteryPlatformSnapshot(
    val capacityPercent: PlatformBatteryValue<Int> = PlatformBatteryValue.Unsupported,
    val currentMicroAmps: PlatformBatteryValue<Long> = PlatformBatteryValue.Unsupported,
    val voltageMilliVolts: PlatformBatteryValue<Long> = PlatformBatteryValue.Unsupported,
    val temperatureCelsius: PlatformBatteryValue<Double> = PlatformBatteryValue.Unsupported,
    val flow: PlatformBatteryValue<BatteryFlow> = PlatformBatteryValue.Unsupported
)

fun interface BatteryPlatformSource {
    fun read(): BatteryPlatformSnapshot
}
