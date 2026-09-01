package com.qijing.core.scene

import com.qijing.core.model.ExecutionBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneServiceStatePolicyTest {
    @Test fun `backend remains locked until service is stopped`() {
        assertFalse(SceneServiceStatePolicy.canSwitchBackend(SceneServiceSnapshot(SceneServicePhase.RUNNING, ExecutionBackend.ROOT)))
        assertFalse(SceneServiceStatePolicy.canSwitchBackend(SceneServiceSnapshot(SceneServicePhase.STOPPING, ExecutionBackend.ROOT)))
        assertFalse(SceneServiceStatePolicy.canSwitchBackend(SceneServiceSnapshot(SceneServicePhase.RECOVERY_REQUIRED, ExecutionBackend.ROOT)))
        assertTrue(SceneServiceStatePolicy.canSwitchBackend(SceneServiceSnapshot(SceneServicePhase.STOPPED)))
    }

    @Test fun `interrupted real service requires recovery but preview can stop safely`() {
        val real = SceneServiceStatePolicy.interruptedState(SceneServiceSnapshot(SceneServicePhase.RUNNING, ExecutionBackend.ROOT))
        val preview = SceneServiceStatePolicy.interruptedState(SceneServiceSnapshot(SceneServicePhase.RUNNING, ExecutionBackend.DRY_RUN))

        assertEquals(SceneServicePhase.RECOVERY_REQUIRED, real.phase)
        assertEquals(SceneServicePhase.STOPPED, preview.phase)
    }

    @Test fun `stale heartbeat fails closed for real backend and stops preview`() {
        val real = SceneServiceSnapshot(SceneServicePhase.RUNNING, ExecutionBackend.ROOT, updatedAtMs = 1_000L)
        val preview = SceneServiceSnapshot(SceneServicePhase.RUNNING, ExecutionBackend.DRY_RUN, updatedAtMs = 1_000L)

        assertEquals(SceneServicePhase.RECOVERY_REQUIRED, SceneServiceStatePolicy.staleState(real, 100_000L).phase)
        assertEquals(SceneServicePhase.STOPPED, SceneServiceStatePolicy.staleState(preview, 100_000L).phase)
        assertEquals(SceneServicePhase.RUNNING, SceneServiceStatePolicy.staleState(real, 50_000L).phase)
    }
}
