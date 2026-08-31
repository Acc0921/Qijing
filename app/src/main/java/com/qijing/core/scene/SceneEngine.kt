package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandPlan
import com.qijing.core.execution.CommandValidator
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.ExecutionBackendProvider
import com.qijing.core.execution.RequiresRollbackSnapshot
import com.qijing.core.execution.TransactionResult
import com.qijing.core.logging.TaskLog
import com.qijing.core.logging.TaskLogStore
import com.qijing.core.model.SceneProfile
import java.util.UUID

class SceneEngine(private val broker: ExecutionBroker, private val logs: TaskLogStore, private val snapshots: SceneSnapshotManager? = null) {
    /** Builds and validates a plan without performing any writes. */
    suspend fun prepare(scene: SceneProfile, recordFailureLog: Boolean = true): ScenePreparation {
        val rawCommands = buildCommands(scene)
        val backend = (broker as? ExecutionBackendProvider)?.executionBackend
        val taskId = UUID.randomUUID().toString()
        val rawPlan = CommandPlan(taskId, rawCommands)
        if (rawCommands.isEmpty()) {
            val failure = ExecutionResult.Failed("SCENE_NO_WRITABLE_INTENT", "场景没有可执行的调节目标，未执行任何写入")
            recordPreparationFailure(taskId, "preflight", failure, recordFailureLog)
            return ScenePreparation(scene, backend, rawPlan, snapshot = null, failure)
        }
        (broker as? CommandValidator)?.let { validator ->
            rawCommands.firstNotNullOfOrNull(validator::validate)?.let { failure ->
                recordPreparationFailure(taskId, "preflight", failure, recordFailureLog)
                return ScenePreparation(scene, backend, rawPlan, snapshot = null, failure)
            }
        }
        if (broker is RequiresRollbackSnapshot && snapshots == null) {
            val failure = ExecutionResult.Failed("SNAPSHOT_UNAVAILABLE", "真实执行后端未提供原值读取能力，未执行任何写入")
            recordPreparationFailure(taskId, "snapshot", failure, recordFailureLog)
            return ScenePreparation(scene, backend, rawPlan, snapshot = null, failure)
        }
        val snapshot = snapshots?.capture(rawCommands)
        val commands = if (snapshot == null) rawCommands else snapshots.attachRestore(rawCommands, snapshot)
        val plan = CommandPlan(taskId, commands)
        if (snapshots != null && broker is CommandValidator) {
            commands.firstOrNull { it.rollback == null }?.let { missing ->
                val failure = ExecutionResult.Failed("SNAPSHOT_INCOMPLETE", "无法读取 ${missing.capability} 的原值，未执行任何写入")
                recordPreparationFailure(taskId, "snapshot", failure, recordFailureLog)
                return ScenePreparation(scene, backend, plan, snapshot, failure)
            }
            commands.mapNotNull { it.rollback }.firstNotNullOfOrNull(broker::validate)?.let { failure ->
                recordPreparationFailure(taskId, "snapshot-validation", failure, recordFailureLog)
                return ScenePreparation(scene, backend, plan, snapshot, failure)
            }
        }
        return ScenePreparation(scene, backend, plan, snapshot)
    }

    suspend fun apply(scene: SceneProfile): TransactionResult {
        // Always prepare again at execution time so a UI preview can never supply a stale snapshot.
        val preparation = prepare(scene, recordFailureLog = true)
        preparation.failure?.let { return TransactionResult(preparation.plan, emptyList(), it) }
        val plan = preparation.plan
        val appliedCommands = mutableListOf<Pair<CapabilityCommand, ExecutionResult.Applied>>()
        for (command in plan.commands) {
            val result = broker.execute(command)
            val previewed = result is ExecutionResult.Applied && result.backend == com.qijing.core.model.ExecutionBackend.DRY_RUN
            logs.append(
                TaskLog(
                    plan.id,
                    if (previewed) "preview:${command.capability}" else command.capability,
                    if (previewed) "预演完成，未修改系统" else result.toString(),
                    result is ExecutionResult.Applied,
                    System.currentTimeMillis()
                )
            )
            if (result is ExecutionResult.Applied) {
                appliedCommands += command to result
                continue
            }
            var rolledBack = true
            var rollbackAttempted = false
            val rollbackCommands = buildList {
                command.rollback?.let(::add)
                appliedCommands.asReversed().mapNotNullTo(this) { (appliedCommand, _) -> appliedCommand.rollback }
            }
            if (command.rollback == null || rollbackCommands.size < appliedCommands.size + 1) rolledBack = false
            rollbackCommands.forEach { rollback ->
                rollbackAttempted = true
                val rollbackResult = broker.execute(rollback)
                val ok = rollbackResult is ExecutionResult.Applied
                rolledBack = rolledBack && ok
                logs.append(TaskLog(plan.id, "rollback:${rollback.capability}", rollbackResult.toString(), ok, System.currentTimeMillis()))
            }
            return TransactionResult(plan, appliedCommands.map { it.second }, result, rolledBack && (rollbackAttempted || appliedCommands.isEmpty()))
        }
        return TransactionResult(plan, appliedCommands.map { it.second })
    }

    private fun recordPreparationFailure(
        taskId: String,
        stage: String,
        failure: ExecutionResult,
        enabled: Boolean
    ) {
        if (enabled) logs.append(TaskLog(taskId, stage, failure.toString(), false, System.currentTimeMillis()))
    }

    private fun buildCommands(scene: SceneProfile): List<CapabilityCommand> = buildList {
        scene.cpu.governor?.let { add(CapabilityCommand("cpu.governor.set", mapOf("value" to it))) }
        scene.cpu.minFrequencyKHz?.let { add(CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to it.toString()))) }
        scene.cpu.maxFrequencyKHz?.let { add(CapabilityCommand("cpu.max_frequency.set", mapOf("khz" to it.toString()))) }
        scene.cpu.onlineCores?.let { add(CapabilityCommand("cpu.online_cores.set", mapOf("value" to it.sorted().joinToString(",")))) }
        scene.memory.zramEnabled?.let { add(CapabilityCommand("memory.zram.enabled", mapOf("value" to it.toString()))) }
        scene.memory.zramSizeBytes?.let { add(CapabilityCommand("memory.zram.size", mapOf("bytes" to it.toString()))) }
        scene.memory.compressionAlgorithm?.let { add(CapabilityCommand("memory.zram.algorithm.set", mapOf("value" to it))) }
        scene.memory.swappiness?.let { add(CapabilityCommand("memory.swappiness.set", mapOf("value" to it.toString()))) }
    }
}
