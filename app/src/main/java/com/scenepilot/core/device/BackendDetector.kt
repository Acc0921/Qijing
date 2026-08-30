package com.scenepilot.core.device

import com.scenepilot.core.model.ExecutionBackend
import java.io.File

data class BackendAvailability(val backend: ExecutionBackend, val available: Boolean, val reason: String? = null)

/** Read-only detector. It never starts su or writes device state. */
interface BackendDetector { fun detect(): List<BackendAvailability> }

class LocalBackendDetector : BackendDetector {
    override fun detect(): List<BackendAvailability> = listOf(
        BackendAvailability(ExecutionBackend.ROOT, File("/system/bin/su").canExecute() || File("/system/xbin/su").canExecute(), "未发现可执行 su"),
        BackendAvailability(ExecutionBackend.ADB, false, "ADB 由外部连接提供"),
        BackendAvailability(ExecutionBackend.SHIZUKU, false, "未接入 Shizuku SDK"),
        BackendAvailability(ExecutionBackend.DAEMON, false, "daemon 尚未安装"),
        BackendAvailability(ExecutionBackend.DRY_RUN, true)
    )
}
