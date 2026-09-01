package com.qijing.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TaskLogPresentationTest {
    @Test fun `preview policy is presented without internal identifier`() {
        val result = TaskLog(
            "task",
            "preview:cpu.policy.4.governor.set",
            "预演完成，未修改系统",
            true,
            1
        ).presentation()

        assertEquals("CPU 策略域 4 · Governor · 预演", result.title)
        assertEquals("已预演", result.status)
        assertEquals(TaskLogState.PREVIEWED, result.state)
        assertFalse(result.title.contains("cpu.policy"))
    }

    @Test fun `recovery required uses actionable language`() {
        val result = TaskLog("task", "recovery-required", "恢复命令失败", false, 1).presentation()

        assertEquals("恢复尚未完成", result.title)
        assertEquals("需处理", result.status)
        assertEquals(TaskLogState.FAILED, result.state)
    }
}
