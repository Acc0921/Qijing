package com.qijing.feature.telemetry

import kotlin.math.max
import kotlin.math.min

/** Converts FrameMetrics durations into stable, display-aware metrics. */
object FpsMath {
    fun frameTimeMs(durationNanos: Long): Double = max(0L, durationNanos) / 1_000_000.0

    fun fps(durationNanos: Long, refreshRateHz: Float): Double {
        if (durationNanos <= 0L) return 0.0
        val displayCap = refreshRateHz.takeIf { it > 0f }?.toDouble() ?: 60.0
        return min(displayCap, 1_000_000_000.0 / durationNanos)
    }

    fun isJank(durationNanos: Long, refreshRateHz: Float, multiplier: Double = 1.5): Boolean {
        if (durationNanos <= 0L) return false
        val budgetNanos = 1_000_000_000.0 / (refreshRateHz.takeIf { it > 0f } ?: 60f)
        return durationNanos > budgetNanos * multiplier
    }
}

data class FpsWindowSample(
    val frameCount: Int,
    val fps: Double,
    val averageFrameTimeMs: Double,
    val jankCount: Int,
    val droppedReportCount: Int,
    val elapsedNanos: Long,
    val frameTimesMs: List<Double>
)

/** Thread-safe window accumulator; rendered FPS uses monotonic elapsed time, not render cost. */
class FpsWindowAccumulator(
    private val refreshRateHz: Float,
    private val clockNanos: () -> Long = System::nanoTime
) {
    private var frameCount = 0
    private var totalDurationNanos = 0L
    private var jankCount = 0
    private var droppedReports = 0
    private var windowStartedNanos = clockNanos()
    private val frameDurationsNanos = mutableListOf<Long>()

    @Synchronized
    fun add(durationNanos: Long, droppedReportCount: Int = 0) {
        if (durationNanos <= 0L) return
        frameCount++
        totalDurationNanos += durationNanos
        frameDurationsNanos += durationNanos
        if (FpsMath.isJank(durationNanos, refreshRateHz)) jankCount++
        droppedReports += droppedReportCount.coerceAtLeast(0)
    }

    @Synchronized
    fun flush(windowEndedNanos: Long = clockNanos()): FpsWindowSample? {
        val elapsedNanos = (windowEndedNanos - windowStartedNanos).coerceAtLeast(1L)
        windowStartedNanos = windowEndedNanos
        if (frameCount == 0) return null
        val result = FpsWindowSample(
            frameCount = frameCount,
            fps = min(
                refreshRateHz.takeIf { it > 0f }?.toDouble() ?: 60.0,
                frameCount * 1_000_000_000.0 / elapsedNanos
            ),
            averageFrameTimeMs = FpsMath.frameTimeMs(totalDurationNanos / frameCount),
            jankCount = jankCount,
            droppedReportCount = droppedReports,
            elapsedNanos = elapsedNanos,
            frameTimesMs = frameDurationsNanos.map(FpsMath::frameTimeMs)
        )
        frameCount = 0
        totalDurationNanos = 0L
        jankCount = 0
        droppedReports = 0
        frameDurationsNanos.clear()
        return result
    }
}
