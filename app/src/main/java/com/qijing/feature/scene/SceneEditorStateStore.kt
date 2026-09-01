package com.qijing.feature.scene

import android.content.Context
import com.qijing.core.model.AppEntry
import com.qijing.core.model.CpuPolicyIntent
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import org.json.JSONArray
import org.json.JSONObject

/** Durable editable input only. Capability probes and rehearsal results are never stored here. */
data class SceneEditorState(
    val app: AppEntry,
    val draft: SceneDraft,
    val selectedIntent: String,
    val editorOpen: Boolean
)

interface SceneEditorStateStore {
    fun load(): SceneEditorState?
    fun save(state: SceneEditorState)
    fun clear()
}

class InMemorySceneEditorStateStore : SceneEditorStateStore {
    private var state: SceneEditorState? = null
    override fun load(): SceneEditorState? = state
    override fun save(state: SceneEditorState) { this.state = state }
    override fun clear() { state = null }
}

/** New-schema process durable store for one unfinished scene. Corrupt input fails closed. */
class SharedPreferencesSceneEditorStateStore(context: Context) : SceneEditorStateStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): SceneEditorState? {
        val raw = prefs.getString(KEY_STATE, null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    override fun save(state: SceneEditorState) {
        prefs.edit().putString(KEY_STATE, encode(state).toString()).apply()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun encode(state: SceneEditorState) = JSONObject().apply {
        put("schema", SCHEMA_VERSION)
        put("intent", state.selectedIntent)
        put("open", state.editorOpen)
        put("app", JSONObject().apply {
            put("package", state.app.packageName)
            put("label", state.app.label)
            put("version", state.app.versionName)
            put("system", state.app.isSystem)
            put("launchable", state.app.isLaunchable)
        })
        put("draft", JSONObject().apply {
            val draft = state.draft
            put("id", draft.id)
            put("name", draft.name)
            put("packages", JSONArray(draft.packages.toList()))
            put("governor", draft.governor)
            put("min", draft.minFrequencyKHz)
            put("max", draft.maxFrequencyKHz)
            put("policies", JSONArray(draft.policyIntents.map { policy -> JSONObject().apply {
                put("id", policy.policyId)
                put("governor", policy.governor)
                put("min", policy.minFrequencyKHz)
                put("max", policy.maxFrequencyKHz)
            } }))
            put("online", draft.onlineCores?.let { JSONArray(it.toList()) })
            put("zramEnabled", draft.zramEnabled)
            put("zramSize", draft.zramSizeMiB)
            put("compression", draft.compressionAlgorithm)
            put("swappiness", draft.swappiness)
            put("priority", draft.priority)
            put("provider", draft.schedulerProvider.name)
            put("mode", draft.schedulerMode?.name)
            put("global", draft.followsGlobalProfile)
        })
    }

    private fun decode(value: JSONObject): SceneEditorState {
        require(value.optInt("schema") == SCHEMA_VERSION)
        val appJson = value.getJSONObject("app")
        val draftJson = value.getJSONObject("draft")
        val app = AppEntry(
            packageName = appJson.getString("package"),
            label = appJson.optString("label", appJson.getString("package")),
            versionName = appJson.optString("version"),
            isSystem = appJson.optBoolean("system"),
            isLaunchable = appJson.optBoolean("launchable", true)
        )
        val provider = runCatching {
            SchedulerProviderId.valueOf(draftJson.optString("provider", SchedulerProviderId.SYSTEM.name))
        }.getOrDefault(SchedulerProviderId.SYSTEM)
        val mode = draftJson.stringOrNull("mode")?.let { runCatching { SchedulerMode.valueOf(it) }.getOrNull() }
        val online = draftJson.optJSONArray("online")?.ints()?.toSet()
        val draft = SceneDraft(
            id = draftJson.getString("id"),
            name = draftJson.optString("name"),
            packages = draftJson.getJSONArray("packages").strings().toSet(),
            governor = draftJson.optString("governor"),
            minFrequencyKHz = draftJson.optString("min"),
            maxFrequencyKHz = draftJson.optString("max"),
            policyIntents = draftJson.optJSONArray("policies")?.objects()?.map { policy ->
                CpuPolicyIntent(
                    policyId = policy.getInt("id"),
                    governor = policy.stringOrNull("governor"),
                    minFrequencyKHz = policy.longOrNull("min"),
                    maxFrequencyKHz = policy.longOrNull("max")
                )
            }.orEmpty(),
            onlineCores = online,
            zramEnabled = draftJson.boolOrNull("zramEnabled"),
            zramSizeMiB = draftJson.optString("zramSize"),
            compressionAlgorithm = draftJson.optString("compression"),
            swappiness = draftJson.optString("swappiness"),
            priority = draftJson.optInt("priority", 50),
            enabled = false,
            schedulerProvider = provider,
            schedulerMode = mode,
            followsGlobalProfile = draftJson.optBoolean("global")
        )
        require(draft.id.isNotBlank() && draft.packages.contains(app.packageName))
        return SceneEditorState(app, draft, value.optString("intent", "global"), value.optBoolean("open"))
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.boolOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::optString)
    private fun JSONArray.ints(): List<Int> = (0 until length()).map(::optInt)
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private companion object {
        const val PREFS_NAME = "qijing_scene_editor_v1"
        const val KEY_STATE = "unfinished_scene"
        const val SCHEMA_VERSION = 1
    }
}
