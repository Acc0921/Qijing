package com.scenepilot.core.scene

import com.scenepilot.core.execution.CapabilityCommand
import com.scenepilot.core.execution.CommandPlan
import com.scenepilot.core.execution.ExecutionBroker
import com.scenepilot.core.execution.ExecutionResult
import com.scenepilot.core.execution.TransactionResult
import com.scenepilot.core.logging.TaskLog
import com.scenepilot.core.logging.TaskLogStore
import com.scenepilot.core.model.SceneProfile
import java.util.UUID

class SceneEngine(private val broker: ExecutionBroker, private val logs: TaskLogStore) {
    suspend fun apply(scene: SceneProfile): TransactionResult {
        val plan = CommandPlan(UUID.randomUUID().toString(), buildCommands(scene))
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
            appliedCommands.asReversed().forEach { (appliedCommand, _) ->
                val rollback = appliedCommand.rollback ?: run { rolledBack = false; return@forEach }
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
