package com.scenepilot.feature.overview

import com.scenepilot.core.data.NewDataStore
import com.scenepilot.core.device.DeviceCapabilityProbe
import com.scenepilot.core.model.DeviceSnapshot

data class OverviewState(
    val loading: Boolean = false,
    val device: DeviceSnapshot? = null,
    val appCount: Int = 0,
    val sceneCount: Int = 0,
    val lastError: String? = null
)

/** Presenter keeps M1 independent from Compose and Android navigation. */
class OverviewPresenter(private val probe: DeviceCapabilityProbe, private val store: NewDataStore) {
    fun load(): OverviewState = runCatching {
        val snapshot = probe.snapshot()
        store.saveDevice(snapshot)
        OverviewState(device = snapshot, appCount = store.apps().size, sceneCount = store.scenes().size)
    }.getOrElse { OverviewState(lastError = it.message ?: "设备信息读取失败") }
}
