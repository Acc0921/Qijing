package com.qijing.core.data

import android.content.Context
import com.qijing.core.model.AppEntry
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.CpuPolicyIntent
import com.qijing.core.model.DeviceSnapshot
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.model.TelemetrySample
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Small first-version persistence adapter. The schema is owned by NewDataStore. */
class SharedPreferencesNewDataStore(context: Context) : NewDataStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("qijing_data_v1", Context.MODE_PRIVATE)
    private val telemetryDirectory = File(appContext.filesDir, "telemetry-v1")
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

    @Synchronized override fun saveApps(apps: List<AppEntry>) { if (!supported()) return; prefs.edit().putString("apps", JSONArray(apps.map { JSONObject().apply { put("package", it.packageName); put("label", it.label); put("version", it.versionName); put("system", it.isSystem); put("launchable", it.isLaunchable) } }).toString()).apply() }
    @Synchronized override fun apps(): List<AppEntry> = if (!supported()) emptyList() else runCatching { prefs.getString("apps", null)?.let { JSONArray(it).objects().map { o -> AppEntry(o.optString("package"), o.optString("label"), o.optString("version"), o.optBoolean("system"), o.optBoolean("launchable", true)) } } ?: emptyList() }.getOrDefault(emptyList())

    @Synchronized override fun saveScene(scene: SceneProfile) { if (!supported()) return; val all = scenes().associateBy { it.id }.toMutableMap(); all[scene.id] = scene; prefs.edit().putString("scenes", JSONArray(all.values.map(::sceneJson)).toString()).apply() }
    @Synchronized override fun scenes(): List<SceneProfile> = if (!supported()) emptyList() else runCatching { prefs.getString("scenes", null)?.let { JSONArray(it).objects().map(::sceneFromJson) } ?: emptyList() }.getOrDefault(emptyList())

    @Synchronized override fun appendTelemetry(sample: TelemetrySample) {
        if (!supported()) return
        val file = telemetryFile(sample.sessionId)
        migrateLegacyTelemetry(sample.sessionId, file)
        var count = prefs.getInt(telemetryCountKey(sample.sessionId), 0)
        if (count >= TELEMETRY_COMPACTION_THRESHOLD) {
            compactTelemetry(file)
            count = file.useLines { lines -> lines.count() }
        }
        val appended = runCatching {
            telemetryDirectory.mkdirs()
            FileOutputStream(file, true).use { output ->
                output.write((telemetryJson(sample).toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        }.isSuccess
        if (!appended) return
        val updatedSessions = telemetrySessionIds().toMutableList().apply { remove(sample.sessionId); add(sample.sessionId) }
        val sessions = updatedSessions.takeLast(MAX_TELEMETRY_SESSIONS)
        val editor = prefs.edit()
            .putInt(telemetryCountKey(sample.sessionId), count + 1)
            .putString(TELEMETRY_SESSIONS_KEY, JSONArray(sessions).toString())
        updatedSessions.filterNot(sessions::contains).forEach { expired ->
            editor.remove("telemetry:$expired")
            editor.remove(telemetryCountKey(expired))
            telemetryFile(expired).delete()
        }
        editor.apply()
    }
    @Synchronized override fun telemetry(sessionId: String): List<TelemetrySample> {
        if (!supported()) return emptyList()
        val file = telemetryFile(sessionId)
        if (file.isFile) return runCatching {
            file.useLines { lines -> lines.mapNotNull { line -> runCatching { telemetryFromJson(sessionId, JSONObject(line)) }.getOrNull() }.toList() }
        }.getOrDefault(emptyList())
        return legacyTelemetry(sessionId)
    }
    @Synchronized override fun telemetrySessionIds(): List<String> = if (!supported()) emptyList() else runCatching {
        prefs.getString(TELEMETRY_SESSIONS_KEY, null)?.let { JSONArray(it).strings() } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun sceneJson(s: SceneProfile) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("packages", JSONArray(s.packageNames.toList())); put("priority", s.priority); put("enabled", s.enabled)
        put("cpu", JSONObject().apply {
            put("governor", s.cpu.governor); put("min", s.cpu.minFrequencyKHz); put("max", s.cpu.maxFrequencyKHz)
            put("cores", s.cpu.onlineCores?.let { JSONArray(it.toList()) })
            put("policies", JSONArray(s.cpu.policies.map { p -> JSONObject().apply {
                put("id", p.policyId); put("governor", p.governor); put("min", p.minFrequencyKHz); put("max", p.maxFrequencyKHz)
            } }))
        })
        put("memory", JSONObject().apply { put("enabled", s.memory.zramEnabled); put("size", s.memory.zramSizeBytes); put("algorithm", s.memory.compressionAlgorithm); put("swappiness", s.memory.swappiness) })
        put("schedulerProvider", s.schedulerProvider.name)
        put("schedulerMode", s.schedulerMode?.stableId)
        put("followsGlobalProfile", s.followsGlobalProfile)
    }
    private fun sceneFromJson(o: JSONObject) = SceneProfile(o.optString("id"), o.optString("name"), o.optJSONArray("packages").strings().toSet(),
        o.optJSONObject("cpu")?.let { c -> CpuIntent(
            c.stringOrNull("governor"), c.longOrNull("min"), c.longOrNull("max"), c.optJSONArray("cores")?.ints()?.toSet(),
            c.optJSONArray("policies")?.objects()?.map { p -> CpuPolicyIntent(p.optInt("id"), p.stringOrNull("governor"), p.longOrNull("min"), p.longOrNull("max")) }.orEmpty()
        ) } ?: CpuIntent(),
        o.optJSONObject("memory")?.let { m -> MemoryIntent(m.boolOrNull("enabled"), m.longOrNull("size"), m.stringOrNull("algorithm"), m.intOrNull("swappiness")) } ?: MemoryIntent(),
        o.optInt("priority"), o.optBoolean("enabled", true),
        runCatching { SchedulerProviderId.valueOf(o.optString("schedulerProvider", SchedulerProviderId.SYSTEM.name)) }.getOrDefault(SchedulerProviderId.SYSTEM),
        o.stringOrNull("schedulerMode")?.let(SchedulerMode::fromStableId),
        o.optBoolean("followsGlobalProfile", false))

    private fun telemetryJson(sample: TelemetrySample) = JSONObject().apply {
        put("t", sample.timestampMs)
        put("fps", sample.fps)
        put("frame", sample.frameTimeMs)
        put("jank", sample.jankCount)
        if (sample.frameTimesMs.isNotEmpty()) put("frames", JSONArray(sample.frameTimesMs))
    }

    private fun telemetryFromJson(sessionId: String, value: JSONObject) = TelemetrySample(
        sessionId = sessionId,
        timestampMs = value.optLong("t"),
        fps = value.optDouble("fps"),
        frameTimeMs = value.optDouble("frame"),
        jankCount = value.optInt("jank"),
        frameTimesMs = value.optJSONArray("frames")?.doubles().orEmpty()
    )

    private fun legacyTelemetry(sessionId: String): List<TelemetrySample> = runCatching {
        prefs.getString("telemetry:$sessionId", null)
            ?.let { JSONArray(it).objects().map { value -> telemetryFromJson(sessionId, value) } }
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun migrateLegacyTelemetry(sessionId: String, target: File) {
        if (target.exists()) return
        val legacy = legacyTelemetry(sessionId)
        if (legacy.isEmpty()) return
        val migrated = runCatching {
            telemetryDirectory.mkdirs()
            target.bufferedWriter(Charsets.UTF_8).use { writer ->
                legacy.forEach { sample ->
                    writer.append(telemetryJson(sample).toString())
                    writer.newLine()
                }
            }
        }.isSuccess
        if (migrated) prefs.edit()
            .remove("telemetry:$sessionId")
            .putInt(telemetryCountKey(sessionId), legacy.size)
            .apply()
    }

    /** Amortized compaction keeps the latest samples without rewriting history every second. */
    private fun compactTelemetry(file: File) {
        if (!file.isFile) return
        val retained = runCatching { file.readLines(Charsets.UTF_8).takeLast(MAX_TELEMETRY_SAMPLES - 1) }.getOrNull() ?: return
        runCatching {
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                retained.forEach { line -> writer.appendLine(line) }
            }
        }
    }

    private fun telemetryFile(sessionId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return File(telemetryDirectory, "$name.jsonl")
    }

    private fun telemetryCountKey(sessionId: String) = "telemetry_count:${telemetryFile(sessionId).nameWithoutExtension}"

    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.boolOrNull(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
    private fun JSONArray.ints(): List<Int> = (0 until length()).map { optInt(it) }
    private fun JSONArray.doubles(): List<Double> = (0 until length()).map { optDouble(it) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun supported(): Boolean = prefs.getInt(SCHEMA_KEY, SCHEMA_VERSION) == SCHEMA_VERSION

    private companion object {
        const val SCHEMA_KEY = "schema_version"
        const val SCHEMA_VERSION = 1
        const val MAX_TELEMETRY_SAMPLES = 2_000
        const val TELEMETRY_COMPACTION_THRESHOLD = MAX_TELEMETRY_SAMPLES * 2
        const val MAX_TELEMETRY_SESSIONS = 50
        const val TELEMETRY_SESSIONS_KEY = "telemetry_sessions"
    }
}
