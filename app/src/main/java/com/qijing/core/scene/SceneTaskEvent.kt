package com.qijing.core.scene

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.qijing.core.model.ExecutionBackend
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

enum class SceneTaskPhase {
    MATCHED,
    PREFLIGHT,
    SNAPSHOT,
    APPLYING,
    VERIFIED,
    PREVIEWED,
    ACTIVE,
    RESTORING,
    RESTORED,
    FAILED,
    RECOVERY_REQUIRED
}

data class SceneTaskEvent(
    val sequence: Long = 0L,
    val taskId: String,
    val sceneId: String,
    val sceneName: String,
    val packageName: String?,
    val backend: ExecutionBackend?,
    val phase: SceneTaskPhase,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

interface SceneTaskEventStore {
    fun append(event: SceneTaskEvent)
    fun recent(limit: Int = 100): List<SceneTaskEvent>
}

class InMemorySceneTaskEventStore : SceneTaskEventStore {
    private val events = ArrayDeque<SceneTaskEvent>()
    private var sequence = 0L
    override fun append(event: SceneTaskEvent) {
        sequence += 1
        events.addLast(event.copy(sequence = sequence))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }
    override fun recent(limit: Int): List<SceneTaskEvent> = events.toList().takeLast(limit.coerceAtLeast(0))
}

/** Bounded observable event ledger used by Overview and Scenes. It is separate from free-form audit logs. */
class SharedPreferencesSceneTaskEventStore(context: Context) : SceneTaskEventStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun append(event: SceneTaskEvent) = synchronized(PROCESS_LOCK) {
        val next = prefs.getLong(KEY_SEQUENCE, 0L) + 1L
        val events = decode(prefs.getString(KEY_EVENTS, null)).takeLast(MAX_EVENTS - 1) + event.copy(sequence = next)
        prefs.edit()
            .putLong(KEY_SEQUENCE, next)
            .putString(KEY_EVENTS, encode(events))
            .commit()
        Unit
    }

    override fun recent(limit: Int): List<SceneTaskEvent> = synchronized(PROCESS_LOCK) {
        decode(prefs.getString(KEY_EVENTS, null)).takeLast(limit.coerceAtLeast(0))
    }

    fun observe(limit: Int = 100, observer: (List<SceneTaskEvent>) -> Unit): Closeable {
        val main = Handler(Looper.getMainLooper())
        fun publish() {
            val value = recent(limit)
            if (Looper.myLooper() == Looper.getMainLooper()) observer(value) else main.post { observer(value) }
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_EVENTS) publish()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        publish()
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun encode(events: List<SceneTaskEvent>): String = JSONArray().apply {
        events.forEach { event -> put(JSONObject().apply {
            put("sequence", event.sequence)
            put("task", event.taskId)
            put("scene", event.sceneId)
            put("name", event.sceneName)
            put("package", event.packageName)
            put("backend", event.backend?.name)
            put("phase", event.phase.name)
            put("detail", event.detail)
            put("time", event.timestampMs)
        }) }
    }.toString()

    private fun decode(raw: String?): List<SceneTaskEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SceneTaskEvent(
                    sequence = item.getLong("sequence"),
                    taskId = item.getString("task"),
                    sceneId = item.getString("scene"),
                    sceneName = item.optString("name"),
                    packageName = item.optString("package").takeIf(String::isNotBlank),
                    backend = item.optString("backend").takeIf(String::isNotBlank)?.let { ExecutionBackend.valueOf(it) },
                    phase = SceneTaskPhase.valueOf(item.getString("phase")),
                    detail = item.optString("detail"),
                    timestampMs = item.getLong("time")
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS = "qijing_scene_task_events_v1"
        const val KEY_EVENTS = "events"
        const val KEY_SEQUENCE = "sequence"
        val PROCESS_LOCK = Any()
    }
}

private const val MAX_EVENTS = 500
