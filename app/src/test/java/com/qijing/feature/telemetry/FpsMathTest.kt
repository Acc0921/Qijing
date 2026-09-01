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
        var now = 1_000_000_000L
        val accumulator = FpsWindowAccumulator(60f) { now }
        accumulator.add(16_000_000)
        accumulator.add(32_000_000, droppedReportCount = 2)
        now += 1_000_000_000L
        val sample = accumulator.flush()!!
        assertEquals(2, sample.frameCount)
        assertEquals(2.0, sample.fps, 0.001)
        assertEquals(24.0, sample.averageFrameTimeMs, 0.001)
        assertEquals(1, sample.jankCount)
        assertEquals(2, sample.droppedReportCount)
        assertEquals(1_000_000_000L, sample.elapsedNanos)
        assertEquals(listOf(16.0, 32.0), sample.frameTimesMs)
        assertEquals(null, accumulator.flush())
    }

    @Test fun `window fps uses elapsed time rather than sum of render durations`() {
        var now = 0L
        val accumulator = FpsWindowAccumulator(120f) { now }
        repeat(60) { accumulator.add(1_000_000L) }
        now = 1_000_000_000L

        assertEquals(60.0, accumulator.flush()!!.fps, 0.001)
    }
}
