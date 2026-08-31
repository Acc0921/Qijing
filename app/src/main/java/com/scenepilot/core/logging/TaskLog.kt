package com.scenepilot.core.logging

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TaskLog(val taskId: String, val stage: String, val message: String, val success: Boolean, val timestampMs: Long)

interface TaskLogStore { fun append(log: TaskLog); fun recent(limit: Int = 100): List<TaskLog> }

class InMemoryTaskLogStore : TaskLogStore {
    private val logs = ArrayDeque<TaskLog>()
    override fun append(log: TaskLog) { logs.addLast(log); while (logs.size > 500) logs.removeFirst() }
    override fun recent(limit: Int) = logs.toList().takeLast(limit)
}

/** Persistent bounded log store for task audit. New schema, independent of UI. */
class SharedPreferencesTaskLogStore(context: Context) : TaskLogStore {
    private val prefs = context.getSharedPreferences("scenepilot_task_logs_v1", Context.MODE_PRIVATE)
    @Synchronized override fun append(log: TaskLog) {
        val entries = recent(499).toMutableList().apply { add(log) }
        val json = JSONArray(entries.map { JSONObject().apply {
            put("task", it.taskId); put("stage", it.stage); put("message", it.message); put("success", it.success); put("time", it.timestampMs)
        } })
        prefs.edit().putString("entries", json.toString()).apply()
    }
    @Synchronized override fun recent(limit: Int): List<TaskLog> = prefs.getString("entries", null)?.let { raw ->
        runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { index -> json.getJSONObject(index).let { o -> TaskLog(o.optString("task"), o.optString("stage"), o.optString("message"), o.optBoolean("success"), o.optLong("time")) } }
        }.getOrDefault(emptyList())
    }?.takeLast(limit.coerceAtLeast(0)) ?: emptyList()
}
