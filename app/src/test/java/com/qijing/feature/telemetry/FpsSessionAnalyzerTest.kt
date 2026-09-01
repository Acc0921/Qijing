package com.qijing.feature.telemetry

import com.qijing.core.data.InMemoryNewDataStore
import com.qijing.core.model.TelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsSessionAnalyzerTest {
    @Test fun `summary calculates average range p95 and jank`() {
        val store = InMemoryNewDataStore()
        listOf(
            TelemetrySample("s1", 1, 60.0, 16.0, 0),
            TelemetrySample("s1", 2, 30.0, 33.0, 2),
            TelemetrySample("s1", 3, 45.0, 22.0, 1)
        ).forEach(store::appendTelemetry)
        val summary = FpsSessionAnalyzer(store).summarize("s1")!!
        assertEquals(45.0, summary.averageFps, 0.001)
        assertEquals(30.0, summary.minFps, 0.001)
        assertEquals(60.0, summary.maxFps, 0.001)
        assertEquals(33.0, summary.p95FrameTimeMs, 0.001)
        assertEquals(3, summary.totalJank)
        assertFalse(summary.hasPerFramePercentile)
    }

    @Test fun `summary p95 uses individual frame distribution when present`() {
        val store = InMemoryNewDataStore()
        store.appendTelemetry(TelemetrySample("s1", 1, 60.0, 16.0, 0, listOf(10.0, 12.0, 40.0)))
        store.appendTelemetry(TelemetrySample("s1", 2, 60.0, 16.0, 1, listOf(11.0, 13.0, 50.0)))

        val summary = FpsSessionAnalyzer(store).summarize("s1")!!

        assertEquals(50.0, summary.p95FrameTimeMs, 0.001)
        assertTrue(summary.hasPerFramePercentile)
    }

    @Test fun `session index keeps first seen order without duplicates`() {
        val store = InMemoryNewDataStore()
        store.appendTelemetry(TelemetrySample("s1", 1, 60.0, 16.0, 0))
        store.appendTelemetry(TelemetrySample("s2", 2, 30.0, 33.0, 1))
        store.appendTelemetry(TelemetrySample("s1", 3, 55.0, 18.0, 0))
        assertEquals(listOf("s1", "s2"), store.telemetrySessionIds())
    }
}
