package com.scenepilot.core.device

import android.os.Build
import com.scenepilot.core.model.DeviceSnapshot
import com.scenepilot.core.model.ExecutionBackend
import java.io.File

interface DeviceCapabilityProbe { fun snapshot(): DeviceSnapshot }

class AndroidDeviceCapabilityProbe(private val backendDetector: BackendDetector = LocalBackendDetector()) : DeviceCapabilityProbe {
    override fun snapshot() = DeviceSnapshot(
        model = Build.MODEL.orEmpty(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        soc = Build.HARDWARE,
        availableBackends = backendDetector.detect().filter { it.available }.map { it.backend }.toSet(),
        capabilities = buildSet {
            if (File("/sys/devices/system/cpu").exists()) add("cpu.read")
            if (File("/proc/meminfo").canRead()) add("memory.read")
            if (File("/sys/block/zram0").exists()) add("zram.read")
            if (File("/sys/class/kgsl/kgsl-3d0").exists() || File("/sys/class/devfreq").exists()) add("gpu.read")
        }
    )
}
