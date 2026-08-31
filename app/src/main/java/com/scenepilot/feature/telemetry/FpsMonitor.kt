package com.scenepilot.feature.telemetry

import com.scenepilot.core.data.NewDataStore
import com.scenepilot.core.model.TelemetrySample
import java.util.UUID

class FpsMonitor(private val store: NewDataStore) {
    @Volatile private var sessionId: String? = null
    fun currentSessionId(): String? = sessionId
    fun start(): String { return UUID.randomUUID().toString().also { sessionId = it } }
    fun record(fps: Double, frameTimeMs: Double, jankCount: Int) {
        val session = sessionId ?: error("FPS session 未开始")
        require(fps >= 0 && frameTimeMs >= 0 && jankCount >= 0)
        store.appendTelemetry(TelemetrySample(session, System.currentTimeMillis(), fps, frameTimeMs, jankCount))
    }
    fun stop() { sessionId = null }
}
