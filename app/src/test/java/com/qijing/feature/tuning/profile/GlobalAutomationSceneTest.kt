package com.qijing.feature.tuning.profile

import com.qijing.core.scene.SceneSelector
import com.qijing.core.scene.SceneTriggerEvent
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import com.qijing.core.model.SceneProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAutomationSceneTest {
    @Test fun `configuration engine creates package specific lowest priority fallback`() {
        val configuration = GlobalTuningConfiguration(
            selected = TuningProfileReference.BuiltIn(SchedulerMode.PERFORMANCE),
            provider = SchedulerProviderId.QIJING_PROFILE,
            revision = 7L
        )

        val result = GlobalAutomationSceneFactory().resolve(configuration, "com.demo.game", null)

        assertTrue(result is GlobalAutomationSceneResolution.Ready)
        val scene = (result as GlobalAutomationSceneResolution.Ready).scene
        assertEquals("global-default:com.demo.game", scene.id)
        assertEquals(Int.MIN_VALUE, scene.priority)
        assertEquals(SchedulerMode.PERFORMANCE, scene.schedulerMode)
        assertEquals(SchedulerProviderId.QIJING_PROFILE, scene.schedulerProvider)
    }

    @Test fun `explicit matching scene wins over global fallback`() {
        val fallback = (GlobalAutomationSceneFactory().resolve(
            GlobalTuningConfiguration(
                selected = TuningProfileReference.BuiltIn(SchedulerMode.BALANCED),
                provider = SchedulerProviderId.QIJING_PROFILE
            ),
            "com.demo.game",
            null
        ) as GlobalAutomationSceneResolution.Ready).scene
        val explicit = SceneProfile("explicit", "游戏", setOf("com.demo.game"), priority = 0)

        val selected = SceneSelector().select(
            listOf(fallback, explicit),
            SceneTriggerEvent.ForegroundApp("com.demo.game")
        )

        assertEquals("explicit", selected.scene?.id)
    }

    @Test fun `invalid foreground identity is blocked`() {
        val result = GlobalAutomationSceneFactory().resolve(
            GlobalTuningConfiguration(provider = SchedulerProviderId.QIJING_PROFILE),
            "bad package",
            null
        )

        assertTrue(result is GlobalAutomationSceneResolution.Blocked)
    }
}
