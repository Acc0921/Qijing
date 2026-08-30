package com.scenepilot.feature.telemetry

import com.scenepilot.core.data.InMemoryNewDataStore
import com.scenepilot.core.model.TelemetrySample
import org.junit.Assert.assertEquals
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
    }
}
