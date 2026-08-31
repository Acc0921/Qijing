package com.qijing.debug.tuning

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandValidator
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.PrivilegedWriteCommandMapper
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scene.CapabilityValueReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class DebugTuningValues(
    val governor: String = "schedutil",
    val minFrequencyKHz: Long = 300_000L,
    val maxFrequencyKHz: Long = 2_400_000L,
    val swappiness: Int = 60
)

enum class DebugWritePhase { WRITE_STARTED, APPLIED, RESTORED }

data class DebugWriteRecord(val capability: String, val phase: DebugWritePhase)

data class DebugTuningJournal(
    val transactionId: String,
    val original: DebugTuningValues,
    val records: List<DebugWriteRecord> = emptyList()
)

internal fun DebugTuningJournal.validationError(): String? = when {
    transactionId.isBlank() -> "transaction id is blank"
    !DEBUG_GOVERNOR_PATTERN.matches(original.governor) -> "invalid original governor"
    original.minFrequencyKHz !in DEBUG_MIN_FREQUENCY_KHZ..DEBUG_MAX_FREQUENCY_KHZ -> "invalid original minimum frequency"
    original.maxFrequencyKHz !in DEBUG_MIN_FREQUENCY_KHZ..DEBUG_MAX_FREQUENCY_KHZ -> "invalid original maximum frequency"
    original.minFrequencyKHz > original.maxFrequencyKHz -> "original minimum exceeds maximum"
    original.swappiness !in 0..200 -> "invalid original swappiness"
    records.size > DEBUG_MAX_JOURNAL_RECORDS -> "too many journal records"
    records.any { it.capability.removeSuffix(".restore") !in DEBUG_SUPPORTED_CAPABILITIES } -> "unknown journal capability"
    else -> null
}

sealed interface DebugJournalLoad {
    data object None : DebugJournalLoad
    data class Loaded(val journal: DebugTuningJournal) : DebugJournalLoad
    data class Corrupt(val reason: String) : DebugJournalLoad
}

/** One commit must persist the values and journal together. */
interface DebugTuningStateStore {
    fun <T> exclusive(block: () -> T): T
    fun loadValues(): DebugTuningValues
    fun loadJournal(): DebugJournalLoad
    fun commit(values: DebugTuningValues, journal: DebugTuningJournal?): Boolean
}

class InMemoryDebugTuningStateStore(
    values: DebugTuningValues = DebugTuningValues(),
    journal: DebugJournalLoad = DebugJournalLoad.None
) : DebugTuningStateStore {
    private val lock = Any()
    private var currentValues = values
    private var currentJournal = journal

    override fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }
    override fun loadValues(): DebugTuningValues = exclusive { currentValues }
    override fun loadJournal(): DebugJournalLoad = exclusive { currentJournal }

    override fun commit(values: DebugTuningValues, journal: DebugTuningJournal?): Boolean = exclusive {
        currentValues = values
        currentJournal = journal?.let(DebugJournalLoad::Loaded) ?: DebugJournalLoad.None
        true
    }

    fun corruptJournal(reason: String = "invalid debug journal") {
        exclusive { currentJournal = DebugJournalLoad.Corrupt(reason) }
    }
}

enum class DebugFailurePhase {
    BEFORE_WRITE,
    AFTER_WRITE_BEFORE_READBACK,
    READBACK_MISMATCH,
    DURING_ROLLBACK
}

data class DebugFailureRule(
    val capability: String,
    val phase: DebugFailurePhase,
    val ordinal: Int? = null,
    val oneShot: Boolean = true
)

class DebugFailureInjector(private val rules: MutableList<DebugFailureRule> = mutableListOf()) {
    private var writeOrdinal = 0

    fun add(rule: DebugFailureRule) { rules += rule }

    fun nextOrdinal(): Int = ++writeOrdinal

    fun shouldFail(capability: String, phase: DebugFailurePhase, ordinal: Int? = null): Boolean {
        val index = rules.indexOfFirst { rule ->
            rule.capability == capability && rule.phase == phase && (rule.ordinal == null || rule.ordinal == ordinal)
        }
        if (index < 0) return false
        if (rules[index].oneShot) rules.removeAt(index)
        return true
    }
}

data class DebugTuningEvent(val action: String, val capability: String)

class DebugTuningEventRecorder {
    private val mutableEvents = mutableListOf<DebugTuningEvent>()
    val events: List<DebugTuningEvent> get() = synchronized(mutableEvents) { mutableEvents.toList() }

    fun record(action: String, capability: String) {
        synchronized(mutableEvents) { mutableEvents += DebugTuningEvent(action, capability) }
    }
}

/**
 * Debug-only execution broker. It never invokes a process, shell, Root, Shizuku, or sysfs node.
 */
class DebugTuningExecutionBroker(
    private val store: DebugTuningStateStore,
    private val failures: DebugFailureInjector = DebugFailureInjector(),
    private val events: DebugTuningEventRecorder = DebugTuningEventRecorder()
) : ExecutionBroker, CommandValidator, CapabilityValueReader {
    private val mutex = Mutex()
    private var recoveryRequired = store.exclusive { store.loadJournal() is DebugJournalLoad.Loaded }

    override fun validate(command: CapabilityCommand): ExecutionResult? = store.exclusive {
        when (val loaded = store.loadJournal()) {
            is DebugJournalLoad.Corrupt -> return@exclusive ExecutionResult.Failed("SIM_JOURNAL_CORRUPT", loaded.reason)
            is DebugJournalLoad.Loaded -> {
                loaded.journal.validationError()?.let { reason ->
                    return@exclusive ExecutionResult.Failed("SIM_JOURNAL_CORRUPT", reason)
                }
                if (recoveryRequired) {
                    return@exclusive ExecutionResult.Failed("SIM_RECOVERY_REQUIRED", "检测到未结束的模拟事务，必须先恢复")
                }
            }
            DebugJournalLoad.None -> recoveryRequired = false
        }
        PrivilegedWriteCommandMapper.validationResult(command, "SIM")?.let { return@exclusive it }
        projectedValues(store.loadValues(), command).exceptionOrNull()?.let { error ->
            ExecutionResult.Failed("SIM_INVALID_STATE", error.message ?: "模拟调节状态无效", command.rollback)
        }
    }

    override suspend fun read(capability: String): String? = store.exclusive { valueFor(store.loadValues(), capability) }

    fun values(): DebugTuningValues = store.exclusive { store.loadValues() }

    override suspend fun execute(command: CapabilityCommand): ExecutionResult = mutex.withLock {
        store.exclusive executeLocked@ {
        validate(command)?.let { return@executeLocked it }
        val ordinal = failures.nextOrdinal()
        val restoring = command.capability.endsWith(RESTORE_SUFFIX)

        if (failures.shouldFail(command.capability, DebugFailurePhase.BEFORE_WRITE, ordinal)) {
            return@executeLocked injectedFailure(command, DebugFailurePhase.BEFORE_WRITE)
        }
        if (restoring && failures.shouldFail(command.capability, DebugFailurePhase.DURING_ROLLBACK, ordinal)) {
            return@executeLocked injectedFailure(command, DebugFailurePhase.DURING_ROLLBACK)
        }

        val before = store.loadValues()
        val existing = (store.loadJournal() as? DebugJournalLoad.Loaded)?.journal
        if (restoring && existing == null) {
            return@executeLocked ExecutionResult.Failed("SIM_RESTORE_WITHOUT_JOURNAL", "没有待恢复的模拟事务")
        }
        val journal = existing ?: if (restoring) null else DebugTuningJournal(UUID.randomUUID().toString(), before)
        if (existing == null && journal != null) events.record("snapshot", "all")

        val started = journal?.copy(records = journal.records + DebugWriteRecord(command.capability, DebugWritePhase.WRITE_STARTED))
        if (!store.commit(before, started)) {
            return@executeLocked ExecutionResult.Failed("SIM_STORE_WRITE_FAILED", "无法保存模拟写入前记录", command.rollback)
        }
        events.record("write-started", command.capability)

        val after = projectedValues(before, command).getOrElse { error ->
            return@executeLocked ExecutionResult.Failed("SIM_INVALID_STATE", error.message ?: "模拟调节状态无效", command.rollback)
        }
        val appliedJournal = started?.withLastPhase(DebugWritePhase.APPLIED)
        if (!store.commit(after, appliedJournal)) {
            return@executeLocked ExecutionResult.Failed("SIM_STORE_WRITE_FAILED", "无法保存模拟写入结果", command.rollback)
        }
        events.record("write", command.capability)

        if (failures.shouldFail(command.capability, DebugFailurePhase.AFTER_WRITE_BEFORE_READBACK, ordinal)) {
            return@executeLocked injectedFailure(command, DebugFailurePhase.AFTER_WRITE_BEFORE_READBACK)
        }
        if (failures.shouldFail(command.capability, DebugFailurePhase.READBACK_MISMATCH, ordinal)) {
            events.record("readback-mismatch", command.capability)
            return@executeLocked injectedFailure(command, DebugFailurePhase.READBACK_MISMATCH)
        }

        val expected = command.arguments.values.single()
        val actual = valueFor(after, command.capability)
        if (actual != expected) {
            return@executeLocked ExecutionResult.Failed("SIM_READBACK_MISMATCH", "模拟写入读回不一致", command.rollback)
        }
        events.record("readback", command.capability)

        val finalJournal = if (appliedJournal != null && after == appliedJournal.original) null else appliedJournal
        if (finalJournal == null && appliedJournal != null && !store.commit(after, null)) {
            return@executeLocked ExecutionResult.Failed("SIM_STORE_WRITE_FAILED", "无法清除已恢复的模拟记录", command.rollback)
        }
        ExecutionResult.Applied(ExecutionBackend.DAEMON, "simulated:${command.capability}=$actual")
        }
    }

    private fun injectedFailure(command: CapabilityCommand, phase: DebugFailurePhase): ExecutionResult.Failed =
        ExecutionResult.Failed("SIM_INJECTED_${phase.name}", "模拟故障：${phase.name}", command.rollback)

    private fun projectedValues(values: DebugTuningValues, command: CapabilityCommand): Result<DebugTuningValues> = runCatching {
        val base = command.capability.removeSuffix(RESTORE_SUFFIX)
        val raw = command.arguments.values.single()
        val projected = when (base) {
            GOVERNOR -> values.copy(governor = raw)
            MIN_FREQUENCY -> values.copy(minFrequencyKHz = raw.toLong())
            MAX_FREQUENCY -> values.copy(maxFrequencyKHz = raw.toLong())
            SWAPPINESS -> values.copy(swappiness = raw.toInt())
            else -> error("不支持的模拟能力：$base")
        }
        require(projected.minFrequencyKHz <= projected.maxFrequencyKHz) { "最低频率不能高于最高频率" }
        projected
    }

    private fun valueFor(values: DebugTuningValues, capability: String): String? =
        when (capability.removeSuffix(RESTORE_SUFFIX)) {
            GOVERNOR -> values.governor
            MIN_FREQUENCY -> values.minFrequencyKHz.toString()
            MAX_FREQUENCY -> values.maxFrequencyKHz.toString()
            SWAPPINESS -> values.swappiness.toString()
            else -> null
        }

    private fun DebugTuningJournal.withLastPhase(phase: DebugWritePhase): DebugTuningJournal =
        copy(records = records.dropLast(1) + records.last().copy(phase = phase))

    private companion object {
        const val RESTORE_SUFFIX = ".restore"
        const val GOVERNOR = "cpu.governor.set"
        const val MIN_FREQUENCY = "cpu.min_frequency.set"
        const val MAX_FREQUENCY = "cpu.max_frequency.set"
        const val SWAPPINESS = "memory.swappiness.set"
    }
}

sealed interface DebugRecoveryResult {
    data object NothingToRecover : DebugRecoveryResult
    data class Recovered(val transactionId: String, val restoredCapabilities: List<String>) : DebugRecoveryResult
    data class Failed(val code: String, val message: String, val restoredCapabilities: List<String>) : DebugRecoveryResult
}

class DebugRecoveryRunner(
    private val store: DebugTuningStateStore,
    private val failures: DebugFailureInjector = DebugFailureInjector(),
    private val events: DebugTuningEventRecorder = DebugTuningEventRecorder()
) {
    fun recoverPending(): DebugRecoveryResult = store.exclusive { recoverPendingLocked() }

    private fun recoverPendingLocked(): DebugRecoveryResult {
        val journal = when (val loaded = store.loadJournal()) {
            DebugJournalLoad.None -> return DebugRecoveryResult.NothingToRecover
            is DebugJournalLoad.Corrupt -> return DebugRecoveryResult.Failed("SIM_JOURNAL_CORRUPT", loaded.reason, emptyList())
            is DebugJournalLoad.Loaded -> loaded.journal
        }
        journal.validationError()?.let { reason ->
            return DebugRecoveryResult.Failed("SIM_JOURNAL_CORRUPT", reason, emptyList())
        }
        var values = store.loadValues()
        var working = journal
        val restored = mutableListOf<String>()

        for (index in working.records.indices.reversed()) {
            val record = working.records[index]
            if (record.phase == DebugWritePhase.RESTORED) continue
            if (failures.shouldFail(record.capability, DebugFailurePhase.DURING_ROLLBACK)) {
                return DebugRecoveryResult.Failed("SIM_RECOVERY_INCOMPLETE", "模拟恢复在 ${record.capability} 失败", restored)
            }
            values = restoreCapability(values, working.original, record.capability)
            val records = working.records.toMutableList().also { it[index] = record.copy(phase = DebugWritePhase.RESTORED) }
            working = working.copy(records = records)
            if (!store.commit(values, working)) {
                return DebugRecoveryResult.Failed("SIM_STORE_WRITE_FAILED", "无法保存模拟恢复进度", restored)
            }
            restored += record.capability
            events.record("recovery", record.capability)
        }

        if (values != working.original) {
            return DebugRecoveryResult.Failed("SIM_RECOVERY_INCOMPLETE", "恢复后状态与原始快照不一致", restored)
        }
        if (!store.commit(values, null)) {
            return DebugRecoveryResult.Failed("SIM_STORE_WRITE_FAILED", "无法清除模拟恢复记录", restored)
        }
        return DebugRecoveryResult.Recovered(working.transactionId, restored)
    }

    private fun restoreCapability(current: DebugTuningValues, original: DebugTuningValues, capability: String): DebugTuningValues =
        when (capability.removeSuffix(".restore")) {
            "cpu.governor.set" -> current.copy(governor = original.governor)
            "cpu.min_frequency.set" -> current.copy(minFrequencyKHz = original.minFrequencyKHz)
            "cpu.max_frequency.set" -> current.copy(maxFrequencyKHz = original.maxFrequencyKHz)
            "memory.swappiness.set" -> current.copy(swappiness = original.swappiness)
            else -> current
        }

}

private const val DEBUG_MIN_FREQUENCY_KHZ = 100_000L
private const val DEBUG_MAX_FREQUENCY_KHZ = 10_000_000L
private const val DEBUG_MAX_JOURNAL_RECORDS = 256
private val DEBUG_GOVERNOR_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")
private val DEBUG_SUPPORTED_CAPABILITIES = setOf(
    "cpu.governor.set",
    "cpu.min_frequency.set",
    "cpu.max_frequency.set",
    "memory.swappiness.set"
)
