package com.qijing.core.device

import com.qijing.core.model.ExecutionBackend
import com.qijing.core.execution.ShizukuRuntime
import java.io.File

data class BackendAvailability(val backend: ExecutionBackend, val available: Boolean, val reason: String? = null)

/** Read-only detector. It never starts su or writes device state. */
interface BackendDetector { fun detect(): List<BackendAvailability> }

class LocalBackendDetector : BackendDetector {
    override fun detect(): List<BackendAvailability> {
        val root = File("/system/bin/su").canExecute() || File("/system/xbin/su").canExecute()
        val shizuku = ShizukuRuntime.status()
        return listOf(
        BackendAvailability(ExecutionBackend.ROOT, root, if (root) null else "未发现可执行 su"),
        BackendAvailability(ExecutionBackend.ADB, false, "ADB 由外部连接提供"),
        BackendAvailability(ExecutionBackend.SHIZUKU, shizuku.ready, shizuku.detail),
        BackendAvailability(ExecutionBackend.DAEMON, false, "daemon 尚未安装"),
        BackendAvailability(ExecutionBackend.DRY_RUN, true)
    )
    }
}
