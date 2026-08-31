package com.qijing.feature.telemetry

import com.qijing.core.data.NewDataStore
import com.qijing.core.model.TelemetrySample
import kotlin.math.ceil

data class FpsSessionSummary(
    val sessionId: String,
    val sampleCount: Int,
    val averageFps: Double,
    val minFps: Double,
    val maxFps: Double,
    val p95FrameTimeMs: Double,
    val totalJank: Int
)

class FpsSessionAnalyzer(private val store: NewDataStore) {
    fun summarize(sessionId: String): FpsSessionSummary? {
        val samples = store.telemetry(sessionId)
        if (samples.isEmpty()) return null
        val frames = samples.map(TelemetrySample::frameTimeMs).sorted()
        val p95Index = (ceil(frames.size * 0.95).toInt() - 1).coerceIn(0, frames.lastIndex)
        return FpsSessionSummary(sessionId, samples.size, samples.map { it.fps }.average(), samples.minOf { it.fps }, samples.maxOf { it.fps }, frames[p95Index], samples.sumOf { it.jankCount })
    }
}
