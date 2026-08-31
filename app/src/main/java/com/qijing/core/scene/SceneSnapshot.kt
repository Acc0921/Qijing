package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand

interface CapabilityValueReader { fun read(capability: String): String? }

data class SceneSnapshot(val values: Map<String, String>)

/** Captures only declared capabilities and attaches explicit restore commands. */
class SceneSnapshotManager(private val reader: CapabilityValueReader) {
    fun capture(commands: List<CapabilityCommand>): SceneSnapshot = SceneSnapshot(commands.mapNotNull { command ->
        reader.read(command.capability)?.let { command.capability to it }
    }.toMap())

    fun attachRestore(commands: List<CapabilityCommand>, snapshot: SceneSnapshot): List<CapabilityCommand> = commands.map { command ->
        snapshot.values[command.capability]?.let { previous ->
            command.copy(rollback = CapabilityCommand("${command.capability}.restore", mapOf("value" to previous)))
        } ?: command
    }
}
