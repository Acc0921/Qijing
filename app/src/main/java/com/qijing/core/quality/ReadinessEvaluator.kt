package com.qijing.core.quality

data class ReadinessCheck(val id: String, val title: String, val ready: Boolean, val detail: String)
data class ReadinessReport(val checks: List<ReadinessCheck>) {
    val ready: Boolean get() = checks.all { it.ready }
}

class ReadinessEvaluator {
    fun evaluate(usageAccess: Boolean, backendAvailable: Boolean, readableCapabilities: Set<String>, hasPersistentStore: Boolean): ReadinessReport = ReadinessReport(listOf(
        ReadinessCheck("usage", "前台应用识别", usageAccess, if (usageAccess) "已授权" else "需要 Usage Stats 权限，场景自动化暂不可用"),
        ReadinessCheck("backend", "执行后端", backendAvailable, if (backendAvailable) "至少有一个后端可用" else "当前只能浏览，无法执行任务"),
        ReadinessCheck("capabilities", "设备能力", readableCapabilities.isNotEmpty(), if (readableCapabilities.isNotEmpty()) "已发现 ${readableCapabilities.size} 项只读能力" else "未发现可读取能力"),
        ReadinessCheck("storage", "数据保存", hasPersistentStore, if (hasPersistentStore) "配置和日志可保存" else "当前数据只在内存中")
    ))
}
