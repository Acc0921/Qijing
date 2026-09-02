package com.qijing.core.scene

import android.content.Context
import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandPlan
import com.qijing.core.execution.ExecutionBackendProvider
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.PrivilegedWriteCommandMapper
import com.qijing.core.execution.ManagedLimiterRuntime
import com.qijing.core.execution.ProfileLimiterCommandPolicy
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.SceneProfile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class SceneJournalPhase { PENDING, WRITE_STARTED, APPLIED, RESTORED }

internal object LinuxBootIdentity {
    private val FORMAT = Regex("[A-Fa-f0-9-]{16,64}")

    fun current(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf(FORMAT::matches)
    }.getOrNull()
}

data class SceneJournalRecord(
    val capability: String,
    val rollback: CapabilityCommand,
    val phase: SceneJournalPhase = SceneJournalPhase.PENDING,
    val target: CapabilityCommand? = null,
    val originalValue: String? = null,
    val appliedValue: String? = null
)

data class SceneTransactionJournal(
    val transactionId: String,
    val sceneId: String,
    val sceneName: String,
    val packageName: String?,
    val backend: ExecutionBackend,
    val records: List<SceneJournalRecord>,
    val createdAtMs: Long,
    val revision: Long = 0L,
    /** Schema 1 records predate target/original/applied state and are recovered conservatively. */
    val schemaVersion: Int = 1,
    /** Linux boot identity at creation; same-boot WRITE_STARTED recovery cannot prove a timed-out writer stopped. */
    val bootId: String? = null
) {
    fun validationError(): String? = when {
        transactionId.isBlank() || sceneId.isBlank() -> "事务或场景标识为空"
        revision < 0L -> "journal revision 无效"
        schemaVersion !in 1..CURRENT_SCHEMA -> "journal schema 无效"
        backend !in setOf(ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU) -> "journal 只允许真实执行后端"
        records.isEmpty() || records.size > MAX_RECORDS -> "journal 命令数量无效"
        records.any { it.capability.isBlank() || it.rollback.capability != "${it.capability}.restore" } -> "恢复命令与能力不匹配"
        schemaVersion >= 2 && records.any {
            PrivilegedWriteCommandMapper.map(it.rollback) !is PrivilegedWriteCommandMapper.Result.Command
        } -> "恢复命令不在安全白名单"
        schemaVersion == 1 && records.any { record ->
            record.rollback.arguments.size > 64 || record.rollback.arguments.any { (key, value) ->
                key.length !in 1..64 || value.length !in 1..4_096 || '\u0000' in key || '\u0000' in value
            }
        } -> "旧版 journal 参数越界"
        schemaVersion >= 2 && records.any { record ->
            record.target == null || record.target.capability != record.capability || record.target.rollback != null ||
                record.originalValue.isNullOrEmpty() || record.appliedValue.isNullOrEmpty()
        } -> "journal 缺少写入三态恢复数据"
        schemaVersion >= 2 && records.any { record ->
            PrivilegedWriteCommandMapper.map(record.target!!) !is PrivilegedWriteCommandMapper.Result.Command
        } -> "journal 目标命令不在安全白名单"
        schemaVersion >= 2 && records.any { record ->
            record.originalValue!!.length > MAX_STATE_VALUE_LENGTH || record.appliedValue!!.length > MAX_STATE_VALUE_LENGTH
        } -> "journal 状态值超过限制"
        records.any { record ->
            listOfNotNull(record.target, record.rollback).any { command ->
                command.arguments.size > MAX_ARGUMENTS || command.arguments.entries.sumOf { it.key.length + it.value.length } > MAX_ARGUMENT_BYTES ||
                    command.arguments.any { (key, value) -> '\u0000' in key || '\u0000' in value }
            }
        } -> "journal 命令参数超过限制"
        schemaVersion >= 2 && records.any { record ->
            record.target!!.journalStateValues(record.rollback) != (record.originalValue to record.appliedValue)
        } -> "journal 状态值与类型化命令不一致"
        bootId != null && !BOOT_ID.matches(bootId) -> "journal 启动身份无效"
        else -> null
    }

    private companion object {
        const val CURRENT_SCHEMA = 2
        const val MAX_RECORDS = 2048
        const val MAX_STATE_VALUE_LENGTH = 4_096
        const val MAX_ARGUMENTS = 64
        const val MAX_ARGUMENT_BYTES = 32 * 1024
        val BOOT_ID = Regex("[A-Fa-f0-9-]{16,64}")
    }
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
        put("schema", journal.schemaVersion)
        put("transaction", journal.transactionId)
        put("scene", journal.sceneId)
        put("name", journal.sceneName)
        put("package", journal.packageName)
        put("backend", journal.backend.name)
        put("created", journal.createdAtMs)
        put("revision", journal.revision)
        journal.bootId?.let { put("bootId", it) }
        put("records", JSONArray().apply {
            journal.records.forEach { record ->
                put(JSONObject().apply {
                    put("capability", record.capability)
                    put("phase", record.phase.name)
                    put("rollbackCapability", record.rollback.capability)
                    put("rollbackArguments", JSONObject(record.rollback.arguments))
                    if (journal.schemaVersion >= 2) {
                        put("targetCapability", record.target!!.capability)
                        put("targetArguments", JSONObject(record.target.arguments))
                        put("originalValue", record.originalValue)
                        put("appliedValue", record.appliedValue)
                    }
                })
            }
        })
    }.toString()

    private fun decode(raw: String): SceneTransactionJournal {
        val root = JSONObject(raw)
        val schema = root.optInt("schema")
        require(schema in 1..CURRENT_SCHEMA) { "不支持的 journal schema" }
        val recordsJson = root.getJSONArray("records")
        val records = (0 until recordsJson.length()).map { index ->
            val item = recordsJson.getJSONObject(index)
            val rollbackArguments = item.getJSONObject("rollbackArguments").stringMap()
            SceneJournalRecord(
                capability = item.getString("capability"),
                rollback = CapabilityCommand(item.getString("rollbackCapability"), rollbackArguments),
                phase = SceneJournalPhase.valueOf(item.getString("phase")),
                target = if (schema >= 2) CapabilityCommand(
                    item.getString("targetCapability"),
                    item.getJSONObject("targetArguments").stringMap()
                ) else null,
                originalValue = item.optString("originalValue").takeIf { schema >= 2 },
                appliedValue = item.optString("appliedValue").takeIf { schema >= 2 }
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
            revision = root.getLong("revision"),
            schemaVersion = schema,
            bootId = root.optString("bootId").takeIf(String::isNotBlank)
        ).also { require(it.validationError() == null) { it.validationError().orEmpty() } }
    }

    private fun JSONObject.stringMap(): Map<String, String> =
        keys().asSequence().associateWith(::getString)

    private companion object {
        const val PREFS = "qijing_scene_transaction_v1"
        const val KEY_JOURNAL = "active_journal"
        const val CURRENT_SCHEMA = 2
        val PROCESS_LOCK = Any()
    }
}

class SceneJournalSession private constructor(
    private val store: SceneTransactionJournalStore,
    private var journal: SceneTransactionJournal
) {
    val transactionId: String get() = journal.transactionId

    fun markWriteStarted(index: Int): Boolean = update(index, SceneJournalPhase.PENDING, SceneJournalPhase.WRITE_STARTED)
    fun markApplied(index: Int): Boolean = update(index, SceneJournalPhase.WRITE_STARTED, SceneJournalPhase.APPLIED)
    fun markRestored(index: Int): Boolean {
        val current = journal.records.getOrNull(index)?.phase ?: return false
        if (current == SceneJournalPhase.RESTORED) return true
        if (current !in setOf(SceneJournalPhase.WRITE_STARTED, SceneJournalPhase.APPLIED)) return false
        return update(index, current, SceneJournalPhase.RESTORED)
    }
    fun clear(): Boolean = store.clear(journal.transactionId, journal.revision)

    private fun update(index: Int, expectedPhase: SceneJournalPhase, phase: SceneJournalPhase): Boolean {
        if (index !in journal.records.indices) return false
        if (journal.records[index].phase != expectedPhase) return false
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
                val values = command.journalStateValues(rollback) ?: return null
                SceneJournalRecord(
                    capability = command.capability,
                    rollback = rollback.copy(rollback = null),
                    target = command.copy(rollback = null),
                    originalValue = values.first,
                    appliedValue = values.second
                )
            }
            val journal = SceneTransactionJournal(
                plan.id,
                scene.id,
                scene.name,
                scene.packageNames.firstOrNull(),
                backend,
                records,
                System.currentTimeMillis(),
                schemaVersion = 2,
                bootId = LinuxBootIdentity.current()
            )
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
    private val broker: ExecutionBroker,
    private val currentValueReader: CommandValueReader? = null,
    private val bootIdentity: () -> String? = LinuxBootIdentity::current
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
            if (journal.schemaVersion < 2 || record.target == null || record.originalValue == null || record.appliedValue == null) {
                return SceneJournalRecoveryResult(
                    journal.transactionId,
                    restored,
                    ExecutionResult.Failed("JOURNAL_WRITE_STATE_UNVERIFIED", "旧版 journal 缺少写入三态数据，已锁定自动恢复")
                )
            }
            if (record.phase == SceneJournalPhase.WRITE_STARTED) {
                val currentBoot = bootIdentity()
                if (journal.bootId == null || currentBoot == null || journal.bootId == currentBoot) {
                    return SceneJournalRecoveryResult(
                        journal.transactionId,
                        restored,
                        ExecutionResult.Failed(
                            "JOURNAL_WRITE_PROCESS_UNVERIFIED",
                            "写入在同一次开机中中断，无法证明旧 Root 进程已停止；重启设备后才能安全恢复"
                        )
                    )
                }
            }
            val reader = currentValueReader ?: return SceneJournalRecoveryResult(
                journal.transactionId,
                restored,
                ExecutionResult.Failed("JOURNAL_CURRENT_READ_UNAVAILABLE", "缺少当前值读取通道，已锁定自动恢复")
            )
            val current = reader.read(record.target) ?: return SceneJournalRecoveryResult(
                journal.transactionId,
                restored,
                ExecutionResult.Failed("JOURNAL_CURRENT_READ_FAILED", "无法读取 ${record.capability} 当前值，已锁定自动恢复")
            )
            when (current) {
                record.originalValue -> {
                    if (!session.markRestored(index)) {
                        return SceneJournalRecoveryResult(
                            journal.transactionId,
                            restored,
                            ExecutionResult.Failed("JOURNAL_PROGRESS_FAILED", "当前已是原值但无法保存恢复进度")
                        )
                    }
                    restored += 1
                    return@forEach
                }
                record.appliedValue -> Unit
                else -> return SceneJournalRecoveryResult(
                    journal.transactionId,
                    restored,
                    ExecutionResult.Failed(
                        "JOURNAL_CURRENT_VALUE_CONFLICT",
                        "${record.capability} 当前值既不是原值也不是栖境目标值，已阻止覆盖"
                    )
                )
            }
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

private fun CapabilityCommand.journalStateValues(rollback: CapabilityCommand): Pair<String, String>? {
    val original = when (capability) {
        "scheduler.profile.limiter.cluster.set" -> {
            val cluster = ProfileLimiterCommandPolicy.parse(this, restore = false) ?: return null
            val state = listOf(
                rollback.arguments["min_khz"], rollback.arguments["max_khz"], rollback.arguments["core_ctl"]
            ).takeIf { values -> values.none { it.isNullOrEmpty() } }?.joinToString("|") { it!! }
            state?.let { if (ManagedLimiterRuntime.isManaged(cluster)) "inactive|$it" else it }
        }
        "scheduler.profile.limiter.clear" -> "inactive"
        "scheduler.profile.gesture_boost.configure" -> "inactive"
        "scheduler.profile.app_frequencies.set" -> listOf(
            rollback.arguments["efficiency_policy"],
            rollback.arguments["efficiency_khz"],
            rollback.arguments["performance_policy"],
            rollback.arguments["performance_khz"]
        ).takeIf { values -> values.none { it.isNullOrEmpty() } }?.joinToString("|") { it!! }
        else -> rollback.arguments["value"]
    } ?: return null
    val applied = when (capability) {
        "scheduler.profile.limiter.cluster.set" -> {
            val cluster = ProfileLimiterCommandPolicy.parse(this, restore = false) ?: return null
            if (ManagedLimiterRuntime.isManaged(cluster)) "owned|${ManagedLimiterRuntime.contractId(cluster, restore = false)}"
            else listOf(arguments["min_khz"], arguments["max_khz"], arguments["core_ctl"])
                .takeIf { values -> values.none { it.isNullOrEmpty() } }?.joinToString("|") { it!! }
        }
        "scheduler.profile.limiter.clear" -> "inactive"
        "scheduler.profile.gesture_boost.configure" -> arguments["contract_id"]?.let { "owned|$it" }
        "scheduler.profile.app_frequencies.set" -> listOf(
            rollback.arguments["efficiency_policy"],
            arguments["efficiency_khz"],
            rollback.arguments["performance_policy"],
            arguments["performance_khz"]
        ).takeIf { values -> values.none { it.isNullOrEmpty() } }?.joinToString("|") { it!! }
        "scheduler.thread.cpuset.set" -> arguments["value"]?.substringBefore('@')
        "display.refresh_rate.set" -> arguments["value"]?.toDoubleOrNull()?.let { hz ->
            if (hz == 0.0) "absent|absent" else {
                val normalized = if (hz % 1.0 == 0.0) hz.toInt().toString() else hz.toString()
                "$normalized|$normalized"
            }
        }
        else -> arguments["value"] ?: arguments["khz"]
    } ?: return null
    return original to applied
}
