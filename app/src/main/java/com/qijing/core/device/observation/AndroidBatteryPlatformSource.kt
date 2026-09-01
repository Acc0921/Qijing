package com.qijing.core.device.observation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Public Android battery APIs; no BATTERY_STATS or runtime permission is required. */
class AndroidBatteryPlatformSource(context: Context) : BatteryPlatformSource {
    private val appContext = context.applicationContext

    override fun read(): BatteryPlatformSnapshot {
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val sticky = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        return BatteryPlatformSnapshot(
            capacityPercent = manager?.let { intProperty(it, BatteryManager.BATTERY_PROPERTY_CAPACITY, 0..100) }
                ?: PlatformBatteryValue.Unsupported,
            currentMicroAmps = manager?.let { longProperty(it, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
                ?: PlatformBatteryValue.Unsupported,
            voltageMilliVolts = sticky?.intExtra(BatteryManager.EXTRA_VOLTAGE)?.let { value ->
                if (value > 0) PlatformBatteryValue.Available(value.toLong())
                else PlatformBatteryValue.Invalid("Android 电池电压无效")
            } ?: PlatformBatteryValue.Unsupported,
            temperatureCelsius = sticky?.intExtra(BatteryManager.EXTRA_TEMPERATURE)?.let { value ->
                if (value in -1000..1500) PlatformBatteryValue.Available(value / 10.0)
                else PlatformBatteryValue.Invalid("Android 电池温度无效")
            } ?: PlatformBatteryValue.Unsupported,
            flow = sticky?.intExtra(BatteryManager.EXTRA_STATUS)?.let { status ->
                PlatformBatteryValue.Available(
                    when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> BatteryFlow.CHARGING
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryFlow.DISCHARGING
                        BatteryManager.BATTERY_STATUS_FULL -> BatteryFlow.FULL
                        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryFlow.NOT_CHARGING
                        else -> BatteryFlow.UNKNOWN
                    }
                )
            } ?: PlatformBatteryValue.Unsupported
        )
    }

    private fun Intent.intExtra(name: String): Int? {
        val value = getIntExtra(name, Int.MIN_VALUE)
        return value.takeUnless { it == Int.MIN_VALUE }
    }

    private fun intProperty(manager: BatteryManager, property: Int, accepted: IntRange): PlatformBatteryValue<Int> {
        val value = runCatching { manager.getIntProperty(property) }
            .getOrElse { return PlatformBatteryValue.Invalid(it.message ?: "Android 电池属性读取失败") }
        return when {
            value == Int.MIN_VALUE -> PlatformBatteryValue.Unsupported
            value !in accepted -> PlatformBatteryValue.Invalid("Android 电池属性超出有效范围")
            else -> PlatformBatteryValue.Available(value)
        }
    }

    private fun longProperty(manager: BatteryManager, property: Int): PlatformBatteryValue<Long> {
        val value = runCatching { manager.getLongProperty(property) }
            .getOrElse { return PlatformBatteryValue.Invalid(it.message ?: "Android 电池属性读取失败") }
        return if (value == Long.MIN_VALUE) PlatformBatteryValue.Unsupported
        else PlatformBatteryValue.Available(value)
    }
}
