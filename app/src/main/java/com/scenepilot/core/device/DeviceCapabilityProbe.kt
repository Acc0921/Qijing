package com.scenepilot.core.device

import android.os.Build
import com.scenepilot.core.model.DeviceSnapshot
import com.scenepilot.core.model.ExecutionBackend

interface DeviceCapabilityProbe { fun snapshot(): DeviceSnapshot }

class AndroidDeviceCapabilityProbe : DeviceCapabilityProbe {
    override fun snapshot() = DeviceSnapshot(
        model = Build.MODEL.orEmpty(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        soc = Build.HARDWARE,
        availableBackends = setOf(ExecutionBackend.DRY_RUN),
        capabilities = emptySet()
    )
}
