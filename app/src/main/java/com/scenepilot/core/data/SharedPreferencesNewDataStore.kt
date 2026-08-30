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

    override fun saveDevice(snapshot: DeviceSnapshot) { prefs.edit().putString("device", JSONObject().apply {
        put("model", snapshot.model); put("manufacturer", snapshot.manufacturer); put("android", snapshot.androidVersion); put("soc", snapshot.soc)
        put("backends", JSONArray(snapshot.availableBackends.map { it.name })); put("capabilities", JSONArray(snapshot.capabilities.toList()))
    }.toString()).apply() }
    override fun device(): DeviceSnapshot? = prefs.getString("device", null)?.let { raw -> JSONObject(raw).let { o ->
        DeviceSnapshot(o.optString("model"), o.optString("manufacturer"), o.optString("android"), o.stringOrNull("soc"),
            o.optJSONArray("backends").strings().mapNotNull { runCatching { ExecutionBackend.valueOf(it) }.getOrNull() }.toSet(), o.optJSONArray("capabilities").strings().toSet())
    } }

    override fun saveApps(apps: List<AppEntry>) { prefs.edit().putString("apps", JSONArray(apps.map { JSONObject().apply { put("package", it.packageName); put("label", it.label); put("version", it.versionName); put("system", it.isSystem) } }).toString()).apply() }
    override fun apps(): List<AppEntry> = prefs.getString("apps", null)?.let { JSONArray(it).objects().map { o -> AppEntry(o.optString("package"), o.optString("label"), o.optString("version"), o.optBoolean("system")) } } ?: emptyList()

    override fun saveScene(scene: SceneProfile) { val all = scenes().associateBy { it.id }.toMutableMap(); all[scene.id] = scene; prefs.edit().putString("scenes", JSONArray(all.values.map(::sceneJson)).toString()).apply() }
    override fun scenes(): List<SceneProfile> = prefs.getString("scenes", null)?.let { JSONArray(it).objects().map(::sceneFromJson) } ?: emptyList()

    override fun appendTelemetry(sample: TelemetrySample) { val all = telemetry(sample.sessionId).toMutableList(); all += sample; prefs.edit().putString("telemetry:${sample.sessionId}", JSONArray(all.map { JSONObject().apply { put("t", it.timestampMs); put("fps", it.fps); put("frame", it.frameTimeMs); put("jank", it.jankCount) } }).toString()).apply() }
    override fun telemetry(sessionId: String): List<TelemetrySample> = prefs.getString("telemetry:$sessionId", null)?.let { JSONArray(it).objects().map { o -> TelemetrySample(sessionId, o.optLong("t"), o.optDouble("fps"), o.optDouble("frame"), o.optInt("jank")) } } ?: emptyList()

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
}
