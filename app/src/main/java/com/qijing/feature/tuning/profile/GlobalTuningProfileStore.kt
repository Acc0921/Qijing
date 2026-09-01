package com.qijing.feature.tuning.profile

import android.content.Context
import com.qijing.core.scheduler.SchedulerProviderId
import org.json.JSONArray
import org.json.JSONObject

sealed interface GlobalTuningLoad {
    data object None : GlobalTuningLoad
    data class Loaded(val configuration: GlobalTuningConfiguration) : GlobalTuningLoad
    data class Corrupt(val reason: String) : GlobalTuningLoad
}

interface GlobalTuningProfileStore {
    fun load(): GlobalTuningLoad
    fun create(configuration: GlobalTuningConfiguration): Boolean
    fun compareAndSet(expectedRevision: Long, configuration: GlobalTuningConfiguration): Boolean
}

class InMemoryGlobalTuningProfileStore : GlobalTuningProfileStore {
    private var current: GlobalTuningLoad = GlobalTuningLoad.None

    override fun load(): GlobalTuningLoad = current

    override fun create(configuration: GlobalTuningConfiguration): Boolean {
        if (current !is GlobalTuningLoad.None || configuration.revision != 0L || configuration.validationError() != null) return false
        current = GlobalTuningLoad.Loaded(configuration)
        return true
    }

    override fun compareAndSet(expectedRevision: Long, configuration: GlobalTuningConfiguration): Boolean {
        val loaded = current as? GlobalTuningLoad.Loaded ?: return false
        if (loaded.configuration.revision != expectedRevision || configuration.revision != expectedRevision + 1L) return false
        if (configuration.validationError() != null) return false
        current = GlobalTuningLoad.Loaded(configuration)
        return true
    }
}

class SharedPreferencesGlobalTuningProfileStore(context: Context) : GlobalTuningProfileStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): GlobalTuningLoad = synchronized(PROCESS_LOCK) { loadLocked() }

    override fun create(configuration: GlobalTuningConfiguration): Boolean = synchronized(PROCESS_LOCK) {
        if (loadLocked() !is GlobalTuningLoad.None || configuration.revision != 0L || configuration.validationError() != null) {
            return@synchronized false
        }
        prefs.edit().putString(KEY, encode(configuration)).commit()
    }

    override fun compareAndSet(expectedRevision: Long, configuration: GlobalTuningConfiguration): Boolean = synchronized(PROCESS_LOCK) {
        val loaded = loadLocked() as? GlobalTuningLoad.Loaded ?: return@synchronized false
        if (loaded.configuration.revision != expectedRevision || configuration.revision != expectedRevision + 1L) return@synchronized false
        if (configuration.validationError() != null) return@synchronized false
        prefs.edit().putString(KEY, encode(configuration)).commit()
    }

    private fun loadLocked(): GlobalTuningLoad {
        val raw = prefs.getString(KEY, null) ?: return GlobalTuningLoad.None
        return runCatching { decode(raw) }
            .fold(onSuccess = GlobalTuningLoad::Loaded, onFailure = { GlobalTuningLoad.Corrupt(it.message ?: "全局方案无法解析") })
    }

    private fun encode(configuration: GlobalTuningConfiguration): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("selected", configuration.selected.stableId)
        put("provider", configuration.provider.name)
        put("revision", configuration.revision)
        put("updated", configuration.updatedAtMs)
        put("custom", JSONArray().apply {
            configuration.customProfiles.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("governor", profile.governor)
                    put("min", profile.minFrequencyKHz)
                    put("max", profile.maxFrequencyKHz)
                    put("swappiness", profile.swappiness)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): GlobalTuningConfiguration {
        val root = JSONObject(raw)
        require(root.getInt("schema") == SCHEMA) { "不支持的全局方案 schema" }
        val customJson = root.getJSONArray("custom")
        val custom = (0 until customJson.length()).map { index ->
            val item = customJson.getJSONObject(index)
            CustomTuningProfile(
                id = item.getString("id"),
                name = item.getString("name"),
                governor = item.stringOrNull("governor"),
                minFrequencyKHz = item.longOrNull("min"),
                maxFrequencyKHz = item.longOrNull("max"),
                swappiness = item.intOrNull("swappiness")
            )
        }
        return GlobalTuningConfiguration(
            selected = TuningProfileReference.parse(root.getString("selected")) ?: error("全局方案引用无效"),
            provider = root.optString("provider", SchedulerProviderId.SYSTEM.name)
                .let { SchedulerProviderId.valueOf(it) },
            customProfiles = custom,
            revision = root.getLong("revision"),
            updatedAtMs = root.getLong("updated")
        ).also { require(it.validationError() == null) { it.validationError().orEmpty() } }
    }

    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private companion object {
        const val PREFS = "qijing_global_tuning_v1"
        const val KEY = "configuration"
        const val SCHEMA = 1
        val PROCESS_LOCK = Any()
    }
}
