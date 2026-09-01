package com.qijing.feature.telemetry

import com.qijing.core.data.NewDataStore
import com.qijing.core.model.TelemetrySample
import java.util.UUID

class FpsMonitor(private val store: NewDataStore) {
    @Volatile private var sessionId: String? = null
    fun currentSessionId(): String? = sessionId
    fun start(): String { return UUID.randomUUID().toString().also { sessionId = it } }
    fun record(fps: Double, frameTimeMs: Double, jankCount: Int, frameTimesMs: List<Double> = emptyList()) {
        val session = sessionId ?: error("FPS session 未开始")
        require(fps >= 0 && frameTimeMs >= 0 && jankCount >= 0)
        require(frameTimesMs.all { it >= 0.0 && it.isFinite() })
        store.appendTelemetry(TelemetrySample(session, System.currentTimeMillis(), fps, frameTimeMs, jankCount, frameTimesMs))
    }
    fun stop() { sessionId = null }
}
