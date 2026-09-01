package com.qijing.core.logging

enum class TaskLogState { PREVIEWED, COMPLETED, RESTORED, WARNING, FAILED }

data class TaskLogPresentation(
    val title: String,
    val detail: String,
    val status: String,
    val state: TaskLogState
)

/** Converts audit identifiers into stable user-facing task language without changing stored evidence. */
fun TaskLog.presentation(): TaskLogPresentation {
    val preview = stage.startsWith("preview:")
    val rollback = stage.startsWith("rollback:")
    val capability = stage.substringAfter(':', stage)
    val capabilityTitle = capability.displayCapability()
    return when {
        stage == "recovery-required" -> TaskLogPresentation(
            "恢复尚未完成", message.ifBlank { "需要查看恢复记录后再进行真实调节。" }, "需处理", TaskLogState.FAILED
        )
        stage == "recovered-after-restart" -> TaskLogPresentation(
            "重启后已恢复", message.ifBlank { "已读回并确认恢复结果。" }, "已恢复", TaskLogState.RESTORED
        )
        stage == "journal-cleared-without-write" -> TaskLogPresentation(
            "恢复计划已安全关闭", message.ifBlank { "没有发生系统写入。" }, "已关闭", TaskLogState.RESTORED
        )
        stage == "service-not-started" -> TaskLogPresentation(
            "自动化未启动", message.ifBlank { "启动条件尚未满足。" }, "未启动", TaskLogState.WARNING
        )
        preview -> TaskLogPresentation(
            "$capabilityTitle · 预演", "预演完成，系统参数未修改。", "已预演", TaskLogState.PREVIEWED
        )
        rollback && success -> TaskLogPresentation(
            "$capabilityTitle · 恢复", "已读回并确认恢复结果。", "已恢复", TaskLogState.RESTORED
        )
        rollback -> TaskLogPresentation(
            "$capabilityTitle · 恢复", "恢复未能完整验证，请查看详细日志。", "恢复异常", TaskLogState.FAILED
        )
        success -> TaskLogPresentation(
            capabilityTitle, "写入与读回验证已完成。", "已验证", TaskLogState.COMPLETED
        )
        else -> TaskLogPresentation(
            capabilityTitle, userFailureDetail(message), "失败", TaskLogState.FAILED
        )
    }
}

private fun String.displayCapability(): String {
    val policy = Regex("cpu\\.policy\\.(\\d+)").find(this)?.groupValues?.getOrNull(1)
    val scope = policy?.let { "CPU 策略域 $it" } ?: when {
        startsWith("cpu.") -> "CPU"
        startsWith("memory.") || contains("swappiness") -> "内存"
        startsWith("scheduler.uperf_gt") -> "UperfGT 调度"
        startsWith("scheduler.uperf") -> "Uperf 调度"
        startsWith("scheduler.fas_rs") -> "fas-rs 调度"
        else -> "调节任务"
    }
    val action = when {
        contains("governor") -> "Governor"
        contains("min_frequency") -> "最低频率"
        contains("max_frequency") -> "最高频率"
        contains("swappiness") -> "Swappiness"
        contains("mode") -> "模式"
        else -> "状态"
    }
    return "$scope · $action"
}

private fun userFailureDetail(raw: String): String = when {
    raw.contains("snapshot", ignoreCase = true) -> "原值快照不完整，未进入真实执行。"
    raw.contains("backend", ignoreCase = true) -> "执行方式不可用或结果不一致，任务已停止。"
    raw.contains("unsupported", ignoreCase = true) -> "当前设备不支持该目标，未进行写入。"
    raw.contains("rollback", ignoreCase = true) -> "执行未完成，请检查恢复结果。"
    else -> "任务未完成，系统已保留审计记录。"
}
