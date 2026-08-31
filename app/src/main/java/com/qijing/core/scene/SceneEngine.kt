package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandPlan
import com.qijing.core.execution.CommandValidator
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.TransactionResult
import com.qijing.core.logging.TaskLog
import com.qijing.core.logging.TaskLogStore
import com.qijing.core.model.SceneProfile
import java.util.UUID

class SceneEngine(private val broker: ExecutionBroker, private val logs: TaskLogStore, private val snapshots: SceneSnapshotManager? = null) {
    suspend fun apply(scene: SceneProfile): TransactionResult {
        val rawCommands = buildCommands(scene)
        val taskId = UUID.randomUUID().toString()
        val rawPlan = CommandPlan(taskId, rawCommands)
        (broker as? CommandValidator)?.let { validator ->
            rawCommands.firstNotNullOfOrNull(validator::validate)?.let { failure ->
                logs.append(TaskLog(taskId, "preflight", failure.toString(), false, System.currentTimeMillis()))
                return TransactionResult(rawPlan, emptyList(), failure)
            }
        }
        val commands = snapshots?.let { manager -> manager.attachRestore(rawCommands, manager.capture(rawCommands)) } ?: rawCommands
        if (snapshots != null && broker is CommandValidator) {
            commands.firstOrNull { it.rollback == null }?.let { missing ->
                val failure = ExecutionResult.Failed("SNAPSHOT_INCOMPLETE", "无法读取 ${missing.capability} 的原值，未执行任何写入")
                logs.append(TaskLog(taskId, "snapshot", failure.toString(), false, System.currentTimeMillis()))
                return TransactionResult(CommandPlan(taskId, commands), emptyList(), failure)
            }
            commands.mapNotNull { it.rollback }.firstNotNullOfOrNull(broker::validate)?.let { failure ->
                logs.append(TaskLog(taskId, "snapshot-validation", failure.toString(), false, System.currentTimeMillis()))
                return TransactionResult(CommandPlan(taskId, commands), emptyList(), failure)
            }
        }
        val plan = CommandPlan(taskId, commands)
        val appliedCommands = mutableListOf<Pair<CapabilityCommand, ExecutionResult.Applied>>()
        for (command in plan.commands) {
            val result = broker.execute(command)
            logs.append(TaskLog(plan.id, command.capability, result.toString(), result is ExecutionResult.Applied, System.currentTimeMillis()))
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

    private fun buildCommands(scene: SceneProfile): List<CapabilityCommand> = buildList {
        scene.cpu.governor?.let { add(CapabilityCommand("cpu.governor.set", mapOf("value" to it))) }
        scene.cpu.minFrequencyKHz?.let { add(CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to it.toString()))) }
        scene.cpu.maxFrequencyKHz?.let { add(CapabilityCommand("cpu.max_frequency.set", mapOf("khz" to it.toString()))) }
        scene.memory.zramEnabled?.let { add(CapabilityCommand("memory.zram.enabled", mapOf("value" to it.toString()))) }
        scene.memory.zramSizeBytes?.let { add(CapabilityCommand("memory.zram.size", mapOf("bytes" to it.toString()))) }
        scene.memory.swappiness?.let { add(CapabilityCommand("memory.swappiness.set", mapOf("value" to it.toString()))) }
    }
}
