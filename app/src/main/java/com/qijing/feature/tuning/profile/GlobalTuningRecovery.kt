package com.qijing.feature.tuning.profile

import android.content.Context
import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.PrivilegedWriteCommandMapper
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.CpuPolicyIntent
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import com.qijing.core.scene.SceneJournalLoad
import com.qijing.core.scene.SceneJournalPhase
import com.qijing.core.scene.SceneTransactionJournalStore
import org.json.JSONArray
import org.json.JSONObject

data class GlobalTuningRecoveryPlan(
    val backend: ExecutionBackend,
    val commands: List<CapabilityCommand>,
    val createdAtMs: Long,
    val label: String,
    /** UI intent that corresponded to the captured system values; absent on legacy schema 1 plans. */
    val previousConfiguration: GlobalTuningConfiguration? = null
) {
    fun validationError(): String? = when {
        backend !in setOf(ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU) -> "恢复计划后端无效"
        commands.isEmpty() || commands.size > MAX_RECOVERY_COMMANDS -> "恢复命令数量无效"
        commands.any { !it.capability.endsWith(".restore") } -> "恢复命令类型无效"
        commands.any { command ->
            command.toForwardCommandOrNull()?.let(PrivilegedWriteCommandMapper::map) !is PrivilegedWriteCommandMapper.Result.Command
        } -> "恢复目标不在白名单"
        commands.any { it.capability in setOf("scheduler.uperf.mode.set.restore", "scheduler.uperf_gt.mode.set.restore") && SchedulerMode.fromStableId(it.arguments["value"].orEmpty()) == null } -> "Uperf 当前模式不能安全转换为可撤销计划"
        commands.any { it.capability == "scheduler.config_bridge.mode.set.restore" && SchedulerMode.fromStableId(it.arguments["value"].orEmpty()) == null } -> "配置调度桥接当前模式不能安全转换为可撤销计划"
        previousConfiguration?.validationError() != null -> "恢复计划中的原全局配置无效"
        else -> null
    }

    fun toSceneProfile(): SceneProfile {
        validationError()?.let { error(it) }
        val policies = linkedMapOf<Int, CpuPolicyIntent>()
        var governor: String? = null
        var min: Long? = null
        var max: Long? = null
        var swappiness: Int? = null
        var schedulerProvider = SchedulerProviderId.SYSTEM
        var schedulerMode: SchedulerMode? = null
        commands.forEach { command ->
            val capability = command.capability.removeSuffix(".restore")
            val value = command.arguments["value"] ?: return@forEach
            val policy = POLICY.matchEntire(capability)
            if (policy != null) {
                val id = policy.groupValues[1].toInt()
                val previous = policies[id] ?: CpuPolicyIntent(id)
                policies[id] = when (policy.groupValues[2]) {
                    "governor" -> previous.copy(governor = value)
                    "min_frequency" -> previous.copy(minFrequencyKHz = value.toLong())
                    "max_frequency" -> previous.copy(maxFrequencyKHz = value.toLong())
                    else -> previous
                }
            } else when (capability) {
                "cpu.governor.set" -> governor = value
                "cpu.min_frequency.set" -> min = value.toLong()
                "cpu.max_frequency.set" -> max = value.toLong()
                "memory.swappiness.set" -> swappiness = value.toInt()
                "scheduler.uperf.mode.set" -> {
                    schedulerProvider = SchedulerProviderId.UPERF
                    schedulerMode = SchedulerMode.fromStableId(value)
                }
                "scheduler.uperf_gt.mode.set" -> {
                    schedulerProvider = SchedulerProviderId.UPERF_GT
                    schedulerMode = SchedulerMode.fromStableId(value)
                }
                "scheduler.fas_rs.mode.set" -> {
                    schedulerProvider = SchedulerProviderId.FAS_RS
                    schedulerMode = SchedulerMode.fromStableId(value)
                }
                "scheduler.config_bridge.mode.set" -> {
                    schedulerProvider = SchedulerProviderId.CONFIG_BRIDGE
                    schedulerMode = SchedulerMode.fromStableId(value)
                }
            }
        }
        return SceneProfile(
            id = "global-restore-${System.currentTimeMillis()}",
            name = "恢复上次调节",
            packageNames = emptySet(),
            cpu = CpuIntent(governor, min, max, policies = policies.values.toList()),
            memory = MemoryIntent(swappiness = swappiness),
            schedulerProvider = schedulerProvider,
            schedulerMode = schedulerMode
        )
    }

    /** Replays stored target values through the normal forward-command validation and snapshot path. */
    fun toForwardCommands(): List<CapabilityCommand> {
        validationError()?.let { error(it) }
        return commands.map { it.toForwardCommandOrNull() ?: error("恢复命令无法转换") }
    }

    private fun CapabilityCommand.toForwardCommandOrNull(): CapabilityCommand? {
        if (!capability.endsWith(".restore")) return null
        val forwardCapability = capability.removeSuffix(".restore")
        val forwardArguments = when {
            forwardCapability.endsWith(".min_frequency.set") || forwardCapability.endsWith(".max_frequency.set") ->
                mapOf("khz" to (arguments["value"] ?: return null))
            forwardCapability == "scheduler.node.write" -> mapOf(
                "path" to (arguments["path"] ?: return null),
                "value" to (arguments["value"] ?: return null)
            )
            forwardCapability == "scheduler.profile.limiter.cluster.set" ->
                arguments - setOf("expected_min_khz", "expected_max_khz", "expected_core_ctl")
            forwardCapability == "scheduler.profile.app_frequencies.set" -> mapOf(
                "package" to (arguments["package"] ?: return null),
                "performance_khz" to (arguments["performance_khz"] ?: return null),
                "efficiency_khz" to (arguments["efficiency_khz"] ?: return null)
            )
            else -> mapOf("value" to (arguments["value"] ?: return null))
        }
        return CapabilityCommand(forwardCapability, forwardArguments)
    }

    private companion object {
        val POLICY = Regex("cpu\\.policy\\.([0-9]{1,3})\\.(governor|min_frequency|max_frequency)\\.set")
        const val MAX_RECOVERY_COMMANDS = 2_048
    }
}

sealed interface GlobalTuningRecoveryLoad {
    data object None : GlobalTuningRecoveryLoad
    data class Loaded(val plan: GlobalTuningRecoveryPlan) : GlobalTuningRecoveryLoad
    data class Corrupt(val reason: String) : GlobalTuningRecoveryLoad
}

interface GlobalTuningRecoveryStore {
    fun load(): GlobalTuningRecoveryLoad
    fun save(plan: GlobalTuningRecoveryPlan): Boolean
}

class SharedPreferencesGlobalTuningRecoveryStore(context: Context) : GlobalTuningRecoveryStore {
    private val prefs = context.applicationContext.getSharedPreferences("qijing_global_recovery_v1", Context.MODE_PRIVATE)

    override fun load(): GlobalTuningRecoveryLoad {
        val raw = prefs.getString("plan", null) ?: return GlobalTuningRecoveryLoad.None
        return runCatching { GlobalTuningRecoveryCodec.decode(JSONObject(raw)) }
            .fold({ GlobalTuningRecoveryLoad.Loaded(it) }, { GlobalTuningRecoveryLoad.Corrupt(it.message ?: "恢复计划损坏") })
    }

    override fun save(plan: GlobalTuningRecoveryPlan): Boolean {
        if (plan.validationError() != null) return false
        val json = GlobalTuningRecoveryCodec.encode(plan)
        return prefs.edit().putString("plan", json.toString()).commit()
    }
}

internal object GlobalTuningRecoveryCodec {
    private const val SCHEMA = 2

    fun encode(plan: GlobalTuningRecoveryPlan): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("backend", plan.backend.name)
        put("created", plan.createdAtMs)
        put("label", plan.label)
        put("previousConfiguration", plan.previousConfiguration?.let(GlobalTuningConfigurationCodec::encode))
        put("commands", JSONArray(plan.commands.map { command -> JSONObject().apply {
            put("capability", command.capability)
            put("arguments", JSONObject(command.arguments))
        } }))
    }

    fun decode(root: JSONObject): GlobalTuningRecoveryPlan {
        val schema = root.getInt("schema")
        require(schema in 1..SCHEMA)
        return GlobalTuningRecoveryPlan(
            ExecutionBackend.valueOf(root.getString("backend")),
            root.getJSONArray("commands").let { array ->
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val arguments = item.getJSONObject("arguments")
                    CapabilityCommand(
                        item.getString("capability"),
                        arguments.keys().asSequence().associateWith { arguments.getString(it) }
                    )
                }
            },
            root.getLong("created"),
            root.getString("label"),
            previousConfiguration = if (schema >= 2 && root.has("previousConfiguration") && !root.isNull("previousConfiguration")) {
                GlobalTuningConfigurationCodec.decode(root.getJSONObject("previousConfiguration"))
            } else null
        ).also { require(it.validationError() == null) { it.validationError().orEmpty() } }
    }
}

/** Commits a verified active transaction as an undo plan, then releases the scene journal lock. */
fun commitVerifiedGlobalTransaction(
    journalStore: SceneTransactionJournalStore,
    recoveryStore: GlobalTuningRecoveryStore,
    label: String,
    previousConfiguration: GlobalTuningConfiguration
): Boolean {
    val journal = (journalStore.load() as? SceneJournalLoad.Loaded)?.journal ?: return false
    if (journal.records.any { it.phase != SceneJournalPhase.APPLIED }) return false
    val plan = GlobalTuningRecoveryPlan(
        journal.backend,
        journal.records.asReversed().map { it.rollback },
        System.currentTimeMillis(),
        label,
        previousConfiguration
    )
    if (!recoveryStore.save(plan)) return false
    return journalStore.clear(journal.transactionId, journal.revision)
}
