package com.qijing.feature.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsMathTest {
    @Test fun `duration converts to display capped fps`() {
        assertEquals(16.0, FpsMath.frameTimeMs(16_000_000), 0.001)
        assertEquals(60.0, FpsMath.fps(8_000_000, 60f), 0.001)
        assertEquals(30.0, FpsMath.fps(33_333_333, 60f), 0.01)
    }

    @Test fun `jank uses one and a half frame budget`() {
        assertFalse(FpsMath.isJank(16_000_000, 60f))
        assertTrue(FpsMath.isJank(26_000_000, 60f))
    }

    @Test fun `window flush aggregates and resets`() {
        val accumulator = FpsWindowAccumulator(60f)
        accumulator.add(16_000_000)
        accumulator.add(32_000_000, droppedReportCount = 2)
        val sample = accumulator.flush()!!
        assertEquals(2, sample.frameCount)
        assertEquals(41.666, sample.fps, 0.01)
        assertEquals(24.0, sample.averageFrameTimeMs, 0.001)
        assertEquals(1, sample.jankCount)
        assertEquals(2, sample.droppedReportCount)
        assertEquals(null, accumulator.flush())
    }
}
