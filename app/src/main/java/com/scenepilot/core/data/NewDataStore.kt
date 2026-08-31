package com.scenepilot.core.data

import com.scenepilot.core.model.AppEntry
import com.scenepilot.core.model.DeviceSnapshot
import com.scenepilot.core.model.SceneProfile
import com.scenepilot.core.model.TelemetrySample

/** New schema contract. Deliberately has no legacy migration API. */
interface NewDataStore {
    fun saveDevice(snapshot: DeviceSnapshot)
    fun device(): DeviceSnapshot?
    fun saveApps(apps: List<AppEntry>)
    fun apps(): List<AppEntry>
    fun saveScene(scene: SceneProfile)
    fun scenes(): List<SceneProfile>
    fun appendTelemetry(sample: TelemetrySample)
    fun telemetry(sessionId: String): List<TelemetrySample>
    fun telemetrySessionIds(): List<String>
}

class InMemoryNewDataStore : NewDataStore {
    private var deviceSnapshot: DeviceSnapshot? = null
    private var appEntries: List<AppEntry> = emptyList()
    private val sceneEntries = linkedMapOf<String, SceneProfile>()
    private val samples = mutableListOf<TelemetrySample>()
    override fun saveDevice(snapshot: DeviceSnapshot) { deviceSnapshot = snapshot }
    override fun device() = deviceSnapshot
    override fun saveApps(apps: List<AppEntry>) { appEntries = apps }
    override fun apps() = appEntries
    override fun saveScene(scene: SceneProfile) { sceneEntries[scene.id] = scene }
    override fun scenes() = sceneEntries.values.toList()
    override fun appendTelemetry(sample: TelemetrySample) { samples += sample }
    override fun telemetry(sessionId: String) = samples.filter { it.sessionId == sessionId }
    override fun telemetrySessionIds(): List<String> = samples.map { it.sessionId }.distinct()
}
