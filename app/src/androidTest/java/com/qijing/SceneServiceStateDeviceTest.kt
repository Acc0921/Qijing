package com.qijing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.BackendSelectionResult
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scene.SceneServicePhase
import com.qijing.core.scene.SceneServiceStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneServiceStateDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun resetState() {
        context.getSharedPreferences("qijing_scene_service_v1", 0).edit().clear().commit()
    }

    @After fun leaveStopped() {
        SceneServiceStateStore(context).markStopped("测试清理")
    }

    @Test fun persistentServiceStateBlocksBackendSwitchUntilStopped() {
        val state = SceneServiceStateStore(context)
        val preference = BackendPreference(context)
        val original = preference.selected()
        val requested = if (original == ExecutionBackend.ROOT) ExecutionBackend.DRY_RUN else ExecutionBackend.ROOT

        state.markRunning(original)
        assertEquals(SceneServicePhase.RUNNING, SceneServiceStateStore(context).current().phase)
        assertEquals(BackendSelectionResult.BLOCKED_SERVICE_ACTIVE, preference.select(requested))
        assertEquals(original, preference.selected())

        state.markStopped()
        assertEquals(BackendSelectionResult.SELECTED, preference.select(requested))
        preference.select(original)
    }
}
