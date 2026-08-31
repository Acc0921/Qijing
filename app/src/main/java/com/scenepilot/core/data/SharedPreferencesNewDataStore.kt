package com.scenepilot.core.data

import android.content.Context
import com.scenepilot.core.model.AppEntry
import com.scenepilot.core.model.CpuIntent
import com.scenepilot.core.model.DeviceSnapshot
import com.scenepilot.core.model.ExecutionBackend
import com.scenepilot.core.model.MemoryIntent
import com.scenepilot.core.model.SceneProfile
import com.scenepilot.core.model.TelemetrySample
import org.json.JSONArray
import org.json.JSONObject

/** Small first-version persistence adapter. The schema is owned by NewDataStore. */
class SharedPreferencesNewDataStore(context: Context) : NewDataStore {
    private val prefs = context.getSharedPreferences("scenepilot_data_v1", Context.MODE_PRIVATE)
    init {
        if (!prefs.contains(SCHEMA_KEY)) prefs.edit().putInt(SCHEMA_KEY, SCHEMA_VERSION).apply()
    }

    @Synchronized override fun saveDevice(snapshot: DeviceSnapshot) { if (!supported()) return; prefs.edit().putString("device", JSONObject().apply {
        put("model", snapshot.model); put("manufacturer", snapshot.manufacturer); put("android", snapshot.androidVersion); put("soc", snapshot.soc)
        put("backends", JSONArray(snapshot.availableBackends.map { it.name })); put("capabilities", JSONArray(snapshot.capabilities.toList()))
    }.toString()).apply() }
    @Synchronized override fun device(): DeviceSnapshot? = if (!supported()) null else runCatching { prefs.getString("device", null)?.let { raw -> JSONObject(raw).let { o ->
        DeviceSnapshot(o.optString("model"), o.optString("manufacturer"), o.optString("android"), o.stringOrNull("soc"),
            o.optJSONArray("backends").strings().mapNotNull { runCatching { ExecutionBackend.valueOf(it) }.getOrNull() }.toSet(), o.optJSONArray("capabilities").strings().toSet())
    } } }.getOrNull()

    @Synchronized override fun saveApps(apps: List<AppEntry>) { if (!supported()) return; prefs.edit().putString("apps", JSONArray(apps.map { JSONObject().apply { put("package", it.packageName); put("label", it.label); put("version", it.versionName); put("system", it.isSystem) } }).toString()).apply() }
    @Synchronized override fun apps(): List<AppEntry> = if (!supported()) emptyList() else runCatching { prefs.getString("apps", null)?.let { JSONArray(it).objects().map { o -> AppEntry(o.optString("package"), o.optString("label"), o.optString("version"), o.optBoolean("system")) } } ?: emptyList() }.getOrDefault(emptyList())

    @Synchronized override fun saveScene(scene: SceneProfile) { if (!supported()) return; val all = scenes().associateBy { it.id }.toMutableMap(); all[scene.id] = scene; prefs.edit().putString("scenes", JSONArray(all.values.map(::sceneJson)).toString()).apply() }
    @Synchronized override fun scenes(): List<SceneProfile> = if (!supported()) emptyList() else runCatching { prefs.getString("scenes", null)?.let { JSONArray(it).objects().map(::sceneFromJson) } ?: emptyList() }.getOrDefault(emptyList())

    @Synchronized override fun appendTelemetry(sample: TelemetrySample) {
        if (!supported()) return
        val all = telemetry(sample.sessionId).takeLast(MAX_TELEMETRY_SAMPLES - 1).toMutableList().apply { add(sample) }
        val updatedSessions = telemetrySessionIds().toMutableList().apply { remove(sample.sessionId); add(sample.sessionId) }
        val sessions = updatedSessions.takeLast(MAX_TELEMETRY_SESSIONS)
        val editor = prefs.edit()
            .putString("telemetry:${sample.sessionId}", JSONArray(all.map { JSONObject().apply { put("t", it.timestampMs); put("fps", it.fps); put("frame", it.frameTimeMs); put("jank", it.jankCount) } }).toString())
            .putString(TELEMETRY_SESSIONS_KEY, JSONArray(sessions).toString())
        updatedSessions.filterNot(sessions::contains).forEach { editor.remove("telemetry:$it") }
        editor.apply()
    }
    @Synchronized override fun telemetry(sessionId: String): List<TelemetrySample> = if (!supported()) emptyList() else runCatching { prefs.getString("telemetry:$sessionId", null)?.let { JSONArray(it).objects().map { o -> TelemetrySample(sessionId, o.optLong("t"), o.optDouble("fps"), o.optDouble("frame"), o.optInt("jank")) } } ?: emptyList() }.getOrDefault(emptyList())
    @Synchronized override fun telemetrySessionIds(): List<String> = if (!supported()) emptyList() else runCatching {
        prefs.getString(TELEMETRY_SESSIONS_KEY, null)?.let { JSONArray(it).strings() } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun sceneJson(s: SceneProfile) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("packages", JSONArray(s.packageNames.toList())); put("priority", s.priority); put("enabled", s.enabled)
        put("cpu", JSONObject().apply { put("governor", s.cpu.governor); put("min", s.cpu.minFrequencyKHz); put("max", s.cpu.maxFrequencyKHz); put("cores", s.cpu.onlineCores?.let { JSONArray(it.toList()) }) })
        put("memory", JSONObject().apply { put("enabled", s.memory.zramEnabled); put("size", s.memory.zramSizeBytes); put("algorithm", s.memory.compressionAlgorithm); put("swappiness", s.memory.swappiness) })
    }
    private fun sceneFromJson(o: JSONObject) = SceneProfile(o.optString("id"), o.optString("name"), o.optJSONArray("packages").strings().toSet(),
        o.optJSONObject("cpu")?.let { c -> CpuIntent(c.stringOrNull("governor"), c.longOrNull("min"), c.longOrNull("max"), c.optJSONArray("cores")?.ints()?.toSet()) } ?: CpuIntent(),
        o.optJSONObject("memory")?.let { m -> MemoryIntent(m.boolOrNull("enabled"), m.longOrNull("size"), m.stringOrNull("algorithm"), m.intOrNull("swappiness")) } ?: MemoryIntent(), o.optInt("priority"), o.optBoolean("enabled", true))

    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.boolOrNull(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
    private fun JSONArray.ints(): List<Int> = (0 until length()).map { optInt(it) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun supported(): Boolean = prefs.getInt(SCHEMA_KEY, SCHEMA_VERSION) == SCHEMA_VERSION

    private companion object {
        const val SCHEMA_KEY = "schema_version"
        const val SCHEMA_VERSION = 1
        const val MAX_TELEMETRY_SAMPLES = 2_000
        const val MAX_TELEMETRY_SESSIONS = 50
        const val TELEMETRY_SESSIONS_KEY = "telemetry_sessions"
    }
}
