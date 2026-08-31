package com.qijing.feature.telemetry

import com.qijing.core.model.TelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsCsvExporterTest {
    @Test fun `export uses stable columns and locale independent decimals`() {
        val csv = FpsCsvExporter.export(listOf(TelemetrySample("s1", 123L, 59.1256, 16.7894, 2)))
        val lines = csv.trim().lines()
        assertEquals("session_id,timestamp_ms,fps,frame_time_ms,jank_count", lines[0])
        assertEquals("s1,123,59.126,16.789,2", lines[1])
    }

    @Test fun `export escapes session identifiers`() {
        val csv = FpsCsvExporter.export(listOf(TelemetrySample("a,\"b", 1L, 60.0, 16.0, 0)))
        assertTrue(csv.contains("\"a,\"\"b\""))
    }
}
