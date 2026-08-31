package com.scenepilot.feature.telemetry

import com.scenepilot.core.model.TelemetrySample
import java.util.Locale

object FpsCsvExporter {
    fun export(samples: List<TelemetrySample>): String = buildString {
        appendLine("session_id,timestamp_ms,fps,frame_time_ms,jank_count")
        samples.forEach { sample ->
            append(csv(sample.sessionId)); append(',')
            append(sample.timestampMs); append(',')
            append(String.format(Locale.US, "%.3f", sample.fps)); append(',')
            append(String.format(Locale.US, "%.3f", sample.frameTimeMs)); append(',')
            appendLine(sample.jankCount)
        }
    }

    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value
}
