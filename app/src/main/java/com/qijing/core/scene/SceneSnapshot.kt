package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand

fun interface CapabilityValueReader { suspend fun read(capability: String): String? }
fun interface CommandValueReader { suspend fun read(command: CapabilityCommand): String? }

internal fun CapabilityCommand.snapshotIdentity(): String {
    val baseCapability = capability.removeSuffix(".restore")
    val identityArguments = when (baseCapability) {
        "scheduler.profile.limiter.cluster.set" -> arguments.filterKeys { it in setOf("profile", "policy") }
        "scheduler.profile.app_frequencies.set" -> arguments.filterKeys { it == "package" }
        "scheduler.profile.gesture_boost.configure" -> arguments.filterKeys { it == "contract_id" }
        else -> arguments.filterKeys { it !in SNAPSHOT_TARGET_ARGUMENTS }
    }
    return buildString {
        append(baseCapability)
        identityArguments.toSortedMap().forEach { (key, value) -> append('|').append(key).append('=').append(value) }
    }
}

private val SNAPSHOT_TARGET_ARGUMENTS = setOf("value", "expected", "khz", "cpus", "nice", "policy")

data class SceneSnapshot(
    val values: Map<String, String>,
    val commandValues: Map<String, String> = emptyMap(),
    val allowCapabilityFallback: Boolean = true
) {
    fun valueFor(command: CapabilityCommand): String? = commandValues[command.snapshotIdentity()]
        ?: values[command.capability].takeIf { allowCapabilityFallback }
}

/** Captures only declared capabilities and attaches explicit restore commands. */
class SceneSnapshotManager private constructor(
    private val reader: CommandValueReader,
    private val allowCapabilityFallback: Boolean
) {
    constructor(reader: CommandValueReader) : this(reader, false)
    constructor(reader: CapabilityValueReader) : this(CommandValueReader { command -> reader.read(command.capability) }, true)

    suspend fun capture(commands: List<CapabilityCommand>): SceneSnapshot {
        val values = linkedMapOf<String, String>()
        val commandValues = linkedMapOf<String, String>()
        commands.forEach { command ->
            reader.read(command)?.let { value ->
                if (!values.containsKey(command.capability)) values[command.capability] = value
                commandValues[command.snapshotIdentity()] = value
            }
        }
        return SceneSnapshot(values, commandValues, allowCapabilityFallback)
    }

    fun attachRestore(commands: List<CapabilityCommand>, snapshot: SceneSnapshot): List<CapabilityCommand> = commands.map { command ->
        snapshot.valueFor(command)?.let { previous ->
            command.copy(rollback = CapabilityCommand("${command.capability}.restore", command.restoreArguments(previous)))
        } ?: command
    }

    private fun CapabilityCommand.restoreArguments(previous: String): Map<String, String> = when (capability) {
        "scheduler.node.write" -> mapOf(
            "path" to arguments.getValue("path"),
            "expected" to arguments.getValue("value"),
            "value" to previous
        )
        "display.refresh_rate.set" -> mapOf(
            "value" to previous,
            "expected" to arguments.getValue("value").toDoubleOrNull()?.let { hz ->
                if (hz == 0.0) "absent|absent" else {
                    val normalized = if (hz % 1.0 == 0.0) hz.toInt().toString() else hz.toString()
                    "$normalized|$normalized"
                }
            }.orEmpty()
        )
        "scheduler.profile.limiter.cluster.set" -> arguments.profileLimiterRestore(previous)
        "scheduler.profile.limiter.clear" -> mapOf("scope" to "cpu_ddr", "expected" to "inactive")
        "scheduler.profile.gesture_boost.configure" -> arguments + mapOf(
            "expected" to "owned|${arguments.getValue("contract_id")}"
        )
        "scheduler.profile.app_frequencies.set" -> arguments.profileAppFrequencyRestore(previous)
        "scheduler.thread.cpuset.set" -> arguments.threadRestore(previous, arguments.getValue("value").substringBefore('@'))
        "scheduler.thread.affinity.set",
        "scheduler.thread.nice.set",
        "scheduler.thread.policy.set" -> arguments.threadRestore(previous, arguments.getValue("value"))
        else -> mapOf(
            "value" to previous,
            "expected" to (arguments["value"] ?: arguments["khz"]).orEmpty()
        )
    }

    private fun Map<String, String>.threadRestore(previous: String, applied: String): Map<String, String> =
        filterKeys { it in THREAD_IDENTITY_KEYS } + mapOf("expected" to applied, "value" to previous)

    private fun Map<String, String>.profileLimiterRestore(previous: String): Map<String, String> {
        val values = previous.removePrefix("inactive|").split('|', limit = 3)
        if (values.size != 3) return emptyMap()
        return this + mapOf(
            "min_khz" to values[0],
            "max_khz" to values[1],
            "core_ctl" to values[2],
            "expected_min_khz" to getValue("min_khz"),
            "expected_max_khz" to getValue("max_khz"),
            "expected_core_ctl" to getValue("core_ctl")
        )
    }

    private fun Map<String, String>.profileAppFrequencyRestore(previous: String): Map<String, String> {
        val values = previous.split('|', limit = 4)
        if (values.size != 4) return emptyMap()
        return mapOf(
            "package" to getValue("package"),
            "efficiency_policy" to values[0],
            "efficiency_khz" to values[1],
            "performance_policy" to values[2],
            "performance_khz" to values[3],
            "expected_efficiency_khz" to getValue("efficiency_khz"),
            "expected_performance_khz" to getValue("performance_khz")
        )
    }

    private companion object {
        val THREAD_IDENTITY_KEYS = setOf("package", "pid", "process_start", "tid", "start_ticks")
    }
}
