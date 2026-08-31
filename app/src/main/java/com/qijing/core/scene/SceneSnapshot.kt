package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand

fun interface CapabilityValueReader { suspend fun read(capability: String): String? }

data class SceneSnapshot(val values: Map<String, String>)

/** Captures only declared capabilities and attaches explicit restore commands. */
class SceneSnapshotManager(private val reader: CapabilityValueReader) {
    suspend fun capture(commands: List<CapabilityCommand>): SceneSnapshot {
        val values = linkedMapOf<String, String>()
        commands.forEach { command -> reader.read(command.capability)?.let { values[command.capability] = it } }
        return SceneSnapshot(values)
    }

    fun attachRestore(commands: List<CapabilityCommand>, snapshot: SceneSnapshot): List<CapabilityCommand> = commands.map { command ->
        snapshot.values[command.capability]?.let { previous ->
            command.copy(rollback = CapabilityCommand("${command.capability}.restore", mapOf("value" to previous)))
        } ?: command
    }
}
