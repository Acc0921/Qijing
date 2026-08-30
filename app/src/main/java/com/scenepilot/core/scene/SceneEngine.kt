package com.scenepilot.core.scene

import com.scenepilot.core.execution.CapabilityCommand
import com.scenepilot.core.execution.ExecutionBroker
import com.scenepilot.core.execution.ExecutionResult
import com.scenepilot.core.logging.TaskLog
import com.scenepilot.core.logging.TaskLogStore
import com.scenepilot.core.model.SceneProfile
import java.util.UUID

class SceneEngine(private val broker: ExecutionBroker, private val logs: TaskLogStore) {
    suspend fun apply(scene: SceneProfile): List<ExecutionResult> {
        val taskId = UUID.randomUUID().toString()
        val commands = buildCommands(scene)
        return commands.map { command ->
            val result = broker.execute(command)
            logs.append(TaskLog(taskId, command.capability, result.toString(), result is ExecutionResult.Applied, System.currentTimeMillis()))
            result
        }
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
