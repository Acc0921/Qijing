package com.qijing.core.scene

import android.content.Context
import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandPlan
import com.qijing.core.execution.ExecutionBackendProvider
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.PrivilegedWriteCommandMapper
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.SceneProfile
import org.json.JSONArray
import org.json.JSONObject

enum class SceneJournalPhase { PENDING, WRITE_STARTED, APPLIED, RESTORED }

data class SceneJournalRecord(
    val capability: String,
    val rollback: CapabilityCommand,
    val phase: SceneJournalPhase = SceneJournalPhase.PENDING
)

data class SceneTransactionJournal(
    val transactionId: String,
    val sceneId: String,
    val sceneName: String,
    val packageName: String?,
    val backend: ExecutionBackend,
    val records: List<SceneJournalRecord>,
    val createdAtMs: Long,
    val revision: Long = 0L
) {
    fun validationError(): String? = when {
        transactionId.isBlank() || sceneId.isBlank() -> "事务或场景标识为空"
        revision < 0L -> "journal revision 无效"
        backend !in setOf(ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU) -> "journal 只允许真实执行后端"
        records.isEmpty() || records.size > MAX_RECORDS -> "journal 命令数量无效"
        records.any { it.capability.isBlank() || it.rollback.capability != "${it.capability}.restore" } -> "恢复命令与能力不匹配"
        records.any { PrivilegedWriteCommandMapper.map(it.rollback) !is PrivilegedWriteCommandMapper.Result.Command } -> "恢复命令不在安全白名单"
        else -> null
    }

    private companion object { const val MAX_RECORDS = 8 }
}

sealed interface SceneJournalLoad {
    data object None : SceneJournalLoad
    data class Loaded(val journal: SceneTransactionJournal) : SceneJournalLoad
    data class Corrupt(val reason: String) : SceneJournalLoad
}

interface SceneTransactionJournalStore {
    fun load(): SceneJournalLoad
    /** Creates a transaction only when no journal currently exists. */
    fun save(journal: SceneTransactionJournal): Boolean
    /** Replaces exactly one known revision; stale sessions must fail without changing storage. */
    fun compareAndSet(expectedTransactionId: String, expectedRevision: Long, journal: SceneTransactionJournal): Boolean
    fun clear(expectedTransactionId: String, expectedRevision: Long): Boolean
}

class InMemorySceneTransactionJournalStore : SceneTransactionJournalStore {
    var current: SceneJournalLoad = SceneJournalLoad.None
    var failWrites: Boolean = false
    var failClears: Boolean = false

    override fun load(): SceneJournalLoad = current
    override fun save(journal: SceneTransactionJournal): Boolean {
        if (failWrites) return false
        journal.validationError()?.let { return false }
        if (current !is SceneJournalLoad.None) return false
        current = SceneJournalLoad.Loaded(journal)
        return true
    }
    override fun compareAndSet(
        expectedTransactionId: String,
        expectedRevision: Long,
        journal: SceneTransactionJournal
    ): Boolean {
        if (failWrites || journal.validationError() != null) return false
        val loaded = current as? SceneJournalLoad.Loaded ?: return false
        if (loaded.journal.transactionId != expectedTransactionId || loaded.journal.revision != expectedRevision) return false
        if (journal.transactionId != expectedTransactionId || journal.revision != expectedRevision + 1L) return false
        current = SceneJournalLoad.Loaded(journal)
        return true
    }
    override fun clear(expectedTransactionId: String, expectedRevision: Long): Boolean {
        if (failWrites || failClears) return false
        val loaded = current as? SceneJournalLoad.Loaded ?: return false
        if (loaded.journal.transactionId != expectedTransactionId || loaded.journal.revision != expectedRevision) return false
        current = SceneJournalLoad.None
        return true
    }
}

/** Process-persistent journal. Every mutation uses commit so no write starts before its recovery state is durable. */
class SharedPreferencesSceneTransactionJournalStore(context: Context) : SceneTransactionJournalStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): SceneJournalLoad = synchronized(PROCESS_LOCK) {
        val raw = prefs.getString(KEY_JOURNAL, null) ?: return@synchronized SceneJournalLoad.None
        runCatching { decode(raw) }
            .fold(onSuccess = SceneJournalLoad::Loaded, onFailure = { SceneJournalLoad.Corrupt(it.message ?: "journal 无法解析") })
    }

    override fun save(journal: SceneTransactionJournal): Boolean = synchronized(PROCESS_LOCK) {
        journal.validationError()?.let { return@synchronized false }
        if (load() !is SceneJournalLoad.None) return@synchronized false
        prefs.edit().putString(KEY_JOURNAL, encode(journal)).commit()
    }

    override fun compareAndSet(
        expectedTransactionId: String,
        expectedRevision: Long,
        journal: SceneTransactionJournal
    ): Boolean = synchronized(PROCESS_LOCK) {
        journal.validationError()?.let { return@synchronized false }
        val loaded = load() as? SceneJournalLoad.Loaded ?: return@synchronized false
        if (loaded.journal.transactionId != expectedTransactionId || loaded.journal.revision != expectedRevision) {
            return@synchronized false
        }
        if (journal.transactionId != expectedTransactionId || journal.revision != expectedRevision + 1L) {
            return@synchronized false
        }
        prefs.edit().putString(KEY_JOURNAL, encode(journal)).commit()
    }

    override fun clear(expectedTransactionId: String, expectedRevision: Long): Boolean = synchronized(PROCESS_LOCK) {
        val loaded = load() as? SceneJournalLoad.Loaded ?: return@synchronized false
        if (loaded.journal.transactionId != expectedTransactionId || loaded.journal.revision != expectedRevision) {
            return@synchronized false
        }
        prefs.edit().remove(KEY_JOURNAL).commit()
    }

    private fun encode(journal: SceneTransactionJournal): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("transaction", journal.transactionId)
        put("scene", journal.sceneId)
        put("name", journal.sceneName)
        put("package", journal.packageName)
        put("backend", journal.backend.name)
        put("created", journal.createdAtMs)
        put("revision", journal.revision)
        put("records", JSONArray().apply {
            journal.records.forEach { record ->
                put(JSONObject().apply {
                    put("capability", record.capability)
                    put("phase", record.phase.name)
                    put("rollbackCapability", record.rollback.capability)
                    put("rollbackArguments", JSONObject(record.rollback.arguments))
                })
            }
        })
    }.toString()

    private fun decode(raw: String): SceneTransactionJournal {
        val root = JSONObject(raw)
        require(root.optInt("schema") == SCHEMA) { "不支持的 journal schema" }
        val recordsJson = root.getJSONArray("records")
        val records = (0 until recordsJson.length()).map { index ->
            val item = recordsJson.getJSONObject(index)
            val argumentsJson = item.getJSONObject("rollbackArguments")
            val arguments = argumentsJson.keys().asSequence().associateWith(argumentsJson::getString)
            SceneJournalRecord(
                capability = item.getString("capability"),
                rollback = CapabilityCommand(item.getString("rollbackCapability"), arguments),
                phase = SceneJournalPhase.valueOf(item.getString("phase"))
            )
        }
        return SceneTransactionJournal(
            transactionId = root.getString("transaction"),
            sceneId = root.getString("scene"),
            sceneName = root.optString("name"),
            packageName = root.optString("package").takeIf(String::isNotBlank),
            backend = ExecutionBackend.valueOf(root.getString("backend")),
            records = records,
            createdAtMs = root.getLong("created"),
            revision = root.getLong("revision")
        ).also { require(it.validationError() == null) { it.validationError().orEmpty() } }
    }

    private companion object {
        const val PREFS = "qijing_scene_transaction_v1"
        const val KEY_JOURNAL = "active_journal"
        const val SCHEMA = 1
        val PROCESS_LOCK = Any()
    }
}

class SceneJournalSession private constructor(
    private val store: SceneTransactionJournalStore,
    private var journal: SceneTransactionJournal
) {
    val transactionId: String get() = journal.transactionId

    fun markWriteStarted(index: Int): Boolean = update(index, SceneJournalPhase.WRITE_STARTED)
    fun markApplied(index: Int): Boolean = update(index, SceneJournalPhase.APPLIED)
    fun markRestored(index: Int): Boolean = update(index, SceneJournalPhase.RESTORED)
    fun clear(): Boolean = store.clear(journal.transactionId, journal.revision)

    private fun update(index: Int, phase: SceneJournalPhase): Boolean {
        if (index !in journal.records.indices) return false
        val expectedRevision = journal.revision
        val updated = journal.copy(
            records = journal.records.mapIndexed { recordIndex, record ->
                if (recordIndex == index) record.copy(phase = phase) else record
            },
            revision = expectedRevision + 1L
        )
        if (!store.compareAndSet(journal.transactionId, expectedRevision, updated)) return false
        journal = updated
        return true
    }

    companion object {
        fun open(
            store: SceneTransactionJournalStore,
            scene: SceneProfile,
            plan: CommandPlan,
            backend: ExecutionBackend
        ): SceneJournalSession? {
            if (store.load() !is SceneJournalLoad.None) return null
            val records = plan.commands.map { command ->
                val rollback = command.rollback ?: return null
                SceneJournalRecord(command.capability, rollback)
            }
            val journal = SceneTransactionJournal(plan.id, scene.id, scene.name, scene.packageNames.firstOrNull(), backend, records, System.currentTimeMillis())
            if (!store.save(journal)) return null
            return SceneJournalSession(store, journal)
        }

        fun resume(
            store: SceneTransactionJournalStore,
            transactionId: String,
            expectedRevision: Long? = null
        ): SceneJournalSession? {
            val loaded = store.load() as? SceneJournalLoad.Loaded ?: return null
            if (loaded.journal.transactionId != transactionId) return null
            if (expectedRevision != null && loaded.journal.revision != expectedRevision) return null
            return SceneJournalSession(store, loaded.journal)
        }
    }
}

data class SceneJournalRecoveryResult(
    val transactionId: String?,
    val restoredCommands: Int,
    val failure: ExecutionResult? = null
) { val succeeded: Boolean get() = failure == null }

class SceneJournalRecovery(
    private val store: SceneTransactionJournalStore,
    private val broker: ExecutionBroker
) {
    suspend fun recoverPending(): SceneJournalRecoveryResult {
        val journal = when (val loaded = store.load()) {
            SceneJournalLoad.None -> return SceneJournalRecoveryResult(null, 0)
            is SceneJournalLoad.Corrupt -> return SceneJournalRecoveryResult(null, 0, ExecutionResult.Failed("JOURNAL_CORRUPT", loaded.reason))
            is SceneJournalLoad.Loaded -> loaded.journal
        }
        val provider = broker as? ExecutionBackendProvider
            ?: return SceneJournalRecoveryResult(
                journal.transactionId,
                0,
                ExecutionResult.Failed("JOURNAL_BACKEND_UNVERIFIED", "恢复 broker 未声明执行后端")
            )
        if (provider.executionBackend != journal.backend) {
            return SceneJournalRecoveryResult(
                journal.transactionId,
                0,
                ExecutionResult.Failed(
                    "JOURNAL_BACKEND_MISMATCH",
                    "journal 后端为 ${journal.backend}，恢复 broker 为 ${provider.executionBackend}"
                )
            )
        }
        val session = SceneJournalSession.resume(store, journal.transactionId, journal.revision)
            ?: return SceneJournalRecoveryResult(journal.transactionId, 0, ExecutionResult.Failed("JOURNAL_OPEN_FAILED", "无法打开恢复 journal"))
        var restored = 0
        journal.records.withIndex().toList().asReversed().forEach { (index, record) ->
            if (record.phase !in setOf(SceneJournalPhase.WRITE_STARTED, SceneJournalPhase.APPLIED)) return@forEach
            val result = broker.execute(record.rollback)
            if (result !is ExecutionResult.Applied) return SceneJournalRecoveryResult(journal.transactionId, restored, result)
            if (result.backend != journal.backend) {
                return SceneJournalRecoveryResult(
                    journal.transactionId,
                    restored,
                    ExecutionResult.Failed(
                        "JOURNAL_RESULT_BACKEND_MISMATCH",
                        "恢复结果后端为 ${result.backend}，预期 ${journal.backend}"
                    )
                )
            }
            if (!session.markRestored(index)) {
                return SceneJournalRecoveryResult(journal.transactionId, restored, ExecutionResult.Failed("JOURNAL_PROGRESS_FAILED", "恢复已执行但无法保存进度"))
            }
            restored += 1
        }
        if (!session.clear()) return SceneJournalRecoveryResult(journal.transactionId, restored, ExecutionResult.Failed("JOURNAL_CLEAR_FAILED", "恢复完成但无法清除 journal"))
        return SceneJournalRecoveryResult(journal.transactionId, restored)
    }
}
