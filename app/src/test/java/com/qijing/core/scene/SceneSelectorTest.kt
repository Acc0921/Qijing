package com.qijing.core.scene

import com.qijing.core.model.SceneProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneSelectorTest {
    @Test fun `highest priority matching scene wins`() {
        val selector = SceneSelector()
        val low = SceneProfile("low", "低优先级", setOf("com.demo.game"), priority = 1)
        val high = SceneProfile("high", "高优先级", setOf("com.demo.game"), priority = 10)
        assertEquals("high", selector.select(listOf(low, high), SceneTriggerEvent.ForegroundApp("com.demo.game")).scene?.id)
    }

    @Test fun `disabled or unrelated scene is ignored`() {
        val scene = SceneProfile("off", "关闭", setOf("com.demo.game"), enabled = false)
        assertEquals(null, SceneSelector().select(listOf(scene), SceneTriggerEvent.ForegroundApp("com.demo.game")).scene)
    }
}
