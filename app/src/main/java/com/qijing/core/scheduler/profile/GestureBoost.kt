package com.qijing.core.scheduler.profile

import com.qijing.core.execution.CapabilityCommand
import java.security.MessageDigest

data class GestureBoostContract(
    val configureCommand: CapabilityCommand,
    val enterCommands: List<CapabilityCommand>,
    val exitCommands: List<CapabilityCommand>,
    /** An empty exit list still restores the complete DOWN snapshot on UP. */
    val restoreEnterOnUp: Boolean = true
)

sealed interface GestureBoostContractPlan {
    data object AbsentOrDisabled : GestureBoostContractPlan
    data object NoOp : GestureBoostContractPlan
    data class Configured(val contract: GestureBoostContract) : GestureBoostContractPlan
    data class Rejected(val code: String, val reason: String) : GestureBoostContractPlan
}

/**
 * Decodes only [alias, scalar] rows from features.gesture_boost. Shell, macros, direct paths and
 * unknown fields are rejected. The resulting operations still pass through ProfileCommandPlanner.
 */
class GestureBoostContractPlanner(
    private val commandPlanner: ProfileCommandPlanner = ProfileCommandPlanner()
) {
    fun plan(program: CompiledProfileProgram, binding: ProfileDeviceBinding): GestureBoostContractPlan {
        return try {
        val feature = program.features[FEATURE_KEY] ?: return GestureBoostContractPlan.AbsentOrDisabled
        val root = feature as? ProfileFeatureValue.ObjectValue
            ?: reject("GESTURE_BOOST_FEATURE_INVALID", "gesture_boost 必须是对象")
        val unknown = root.entries.keys - SUPPORTED_KEYS
        if (unknown.isNotEmpty()) {
            reject("GESTURE_BOOST_FIELD_UNSUPPORTED", "gesture_boost 包含未定义字段：${unknown.sorted().joinToString()}")
        }
        val enabled = (root.entries["enable"] as? ProfileFeatureValue.BooleanValue)?.value
            ?: reject("GESTURE_BOOST_ENABLE_INVALID", "gesture_boost.enable 必须是布尔值")
        val enter = decodeOperations(program, root, "enter", binding)
        val exit = decodeOperations(program, root, "exit", binding)
        if (!enabled) return GestureBoostContractPlan.AbsentOrDisabled
        if (enter.isEmpty() && exit.isEmpty()) return GestureBoostContractPlan.NoOp

        val enterPlan = commandPlanner.plan(program, enter, binding).requirePlanned("enter")
        val exitPlan = commandPlanner.plan(program, exit, binding).requirePlanned("exit")
        if (exitPlan.commands.isNotEmpty()) {
            reject("GESTURE_BOOST_EXIT_UNVERIFIED", "当前仅启用真实配置包已验证的 DOWN 写入、UP 恢复原值语义")
        }
        val all = enterPlan.commands + exitPlan.commands
        if (all.any { it.capability != "scheduler.node.write" || it.rollback != null }) {
            reject("GESTURE_BOOST_COMMAND_UNSUPPORTED", "手势调节只能包含由配置节点规划器生成的节点写入")
        }
        if (all.map { it.arguments.getValue("path") }.distinct().size != all.size) {
            reject("GESTURE_BOOST_TARGET_DUPLICATED", "gesture_boost 同一节点只能出现一次")
        }
        val contractId = stableId(enterPlan.commands, exitPlan.commands)
        GestureBoostContractPlan.Configured(
            GestureBoostContract(
                configureCommand = CapabilityCommand(
                    capability = CONFIGURE_CAPABILITY,
                    arguments = buildMap {
                        putAll(mapOf(
                        "contract_id" to contractId,
                        "event_protocol" to EVENT_PROTOCOL,
                        "enter_count" to enterPlan.commands.size.toString(),
                        "exit_count" to exitPlan.commands.size.toString(),
                        "restore_enter_on_up" to "true",
                        "root_only" to "true"
                        ))
                        enterPlan.commands.forEachIndexed { index, command ->
                            put("enter_${index}_path", command.arguments.getValue("path"))
                            put("enter_${index}_value", command.arguments.getValue("value"))
                        }
                        exitPlan.commands.forEachIndexed { index, command ->
                            put("exit_${index}_path", command.arguments.getValue("path"))
                            put("exit_${index}_value", command.arguments.getValue("value"))
                        }
                    }
                ),
                enterCommands = enterPlan.commands,
                exitCommands = exitPlan.commands
            )
        )
    } catch (rejected: RejectedGestureBoost) {
        GestureBoostContractPlan.Rejected(rejected.code, rejected.message ?: "手势调节配置无效")
    }
    }

    private fun decodeOperations(
        program: CompiledProfileProgram,
        root: ProfileFeatureValue.ObjectValue,
        key: String,
        binding: ProfileDeviceBinding
    ): List<ProfileOperation> {
        val array = root.entries[key] as? ProfileFeatureValue.ArrayValue
            ?: reject("GESTURE_BOOST_${key.uppercase()}_INVALID", "gesture_boost.$key 必须是数组")
        if (array.entries.size > MAX_OPERATIONS) {
            reject("GESTURE_BOOST_TOO_MANY_OPERATIONS", "gesture_boost.$key 超过 $MAX_OPERATIONS 项")
        }
        return array.entries.mapIndexed { index, entry ->
            val row = (entry as? ProfileFeatureValue.ArrayValue)?.entries
                ?: reject("GESTURE_BOOST_ROW_INVALID", "gesture_boost.$key[$index] 必须是二元数组")
            if (row.size != 2) reject("GESTURE_BOOST_ROW_INVALID", "gesture_boost.$key[$index] 必须包含目标和值")
            val aliasReference = (row[0] as? ProfileFeatureValue.StringValue)?.value
                ?: reject("GESTURE_BOOST_TARGET_INVALID", "gesture_boost.$key[$index] 目标必须是别名")
            if (!aliasReference.startsWith('$') || !IDENTIFIER.matches(aliasReference.drop(1))) {
                reject("GESTURE_BOOST_TARGET_INVALID", "gesture_boost 目标只允许使用已编译的配置别名")
            }
            val alias = aliasReference.drop(1)
            val target = program.aliases[alias] ?: builtInAlias(alias, binding)
                ?: reject("GESTURE_BOOST_ALIAS_UNKNOWN", "gesture_boost 引用了未知别名 $aliasReference")
            ProfileOperation.Write(
                target = target,
                value = decodeValue(row[1], key, index),
                origin = OperationOrigin("features.gesture_boost.$key", index = index)
            )
        }
    }

    private fun builtInAlias(alias: String, binding: ProfileDeviceBinding): KernelNode? {
        val policy = CPU_MAX_ALIAS.matchEntire(alias)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (policy !in binding.policyIds) return null
        return KernelNode("/sys/devices/system/cpu/cpufreq/policy$policy/scaling_max_freq")
    }

    private fun decodeValue(value: ProfileFeatureValue, section: String, index: Int): ProfileValue {
        val raw = when (value) {
            is ProfileFeatureValue.StringValue -> value.value
            is ProfileFeatureValue.NumberValue -> value.value
            is ProfileFeatureValue.BooleanValue -> value.value.toString()
            else -> reject("GESTURE_BOOST_VALUE_INVALID", "gesture_boost.$section[$index] 值必须是标量")
        }
        if (raw.isEmpty() || raw.length > MAX_VALUE_LENGTH || '\u0000' in raw || raw.any { it == '\r' || it == '\n' }) {
            reject("GESTURE_BOOST_VALUE_INVALID", "gesture_boost.$section[$index] 值超出边界")
        }
        val notation = when {
            raw.startsWith('#') -> ValueNotation.HASH_PREFIXED
            raw.startsWith('^') -> ValueNotation.CARET_PREFIXED
            else -> ValueNotation.PLAIN
        }
        val normalized = if (notation == ValueNotation.PLAIN) raw else raw.drop(1)
        if (normalized.isEmpty()) reject("GESTURE_BOOST_VALUE_INVALID", "gesture_boost 值前缀后为空")
        return ProfileValue(normalized, notation)
    }

    private fun ProfileCommandPlan.requirePlanned(section: String): ProfileCommandPlan.Planned = when (this) {
        is ProfileCommandPlan.Planned -> this
        is ProfileCommandPlan.Rejected -> reject(code, "gesture_boost.$section 无法规划：$reason")
    }

    private fun stableId(enter: List<CapabilityCommand>, exit: List<CapabilityCommand>): String {
        val canonical = buildString {
            append("qijing-gesture-v1|").append(EVENT_PROTOCOL).append("|enter|")
            enter.forEachIndexed { index, command ->
                append(index).append('|').append(command.arguments.getValue("path"))
                    .append('=').append(command.arguments.getValue("value")).append('|')
            }
            append("exit|")
            exit.forEachIndexed { index, command ->
                append(index).append('|').append(command.arguments.getValue("path"))
                    .append('=').append(command.arguments.getValue("value")).append('|')
            }
            append("restore=true")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun reject(code: String, reason: String): Nothing = throw RejectedGestureBoost(code, reason)

    private class RejectedGestureBoost(val code: String, message: String) : IllegalArgumentException(message)

    companion object {
        const val CONFIGURE_CAPABILITY = "scheduler.profile.gesture_boost.configure"
        const val EVENT_PROTOCOL = "getevent:EV_KEY:BTN_TOUCH:DOWN_UP:v1"
        private const val FEATURE_KEY = "gesture_boost"
        private const val MAX_OPERATIONS = 16
        private const val MAX_VALUE_LENGTH = 512
        private val SUPPORTED_KEYS = setOf("enable", "enter", "exit")
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val CPU_MAX_ALIAS = Regex("cpu_max_([0-9]{1,3})")
    }
}

enum class GestureBoostBlockCode {
    ROOT_REQUIRED,
    INPUT_ENUMERATION_FAILED,
    TOUCH_INPUT_UNAVAILABLE,
    JOURNAL_LEASE_UNAVAILABLE
}

data class GestureInputDevice(
    val eventNode: String,
    val supportsBtnTouch: Boolean
)

sealed interface GestureBoostRuntimeReadiness {
    data class Ready(val touchDevices: Set<String>) : GestureBoostRuntimeReadiness
    data class Blocked(val code: GestureBoostBlockCode, val reason: String) : GestureBoostRuntimeReadiness
}

object GestureBoostRuntimeGate {
    fun evaluate(
        rootSelected: Boolean,
        inputEnumerationSucceeded: Boolean,
        devices: List<GestureInputDevice>,
        journalLeaseAvailable: Boolean
    ): GestureBoostRuntimeReadiness {
        if (!rootSelected) return blocked(GestureBoostBlockCode.ROOT_REQUIRED, "手势调节仅支持明确选择的 Root 后端")
        if (!inputEnumerationSucceeded) {
            return blocked(GestureBoostBlockCode.INPUT_ENUMERATION_FAILED, "无法枚举输入设备，已阻止手势监听")
        }
        val touchDevices = devices.filter { it.supportsBtnTouch && EVENT_NODE.matches(it.eventNode) }
            .mapTo(linkedSetOf()) { it.eventNode }
        if (touchDevices.isEmpty()) {
            return blocked(GestureBoostBlockCode.TOUCH_INPUT_UNAVAILABLE, "未发现可验证的 BTN_TOUCH 输入设备")
        }
        if (!journalLeaseAvailable) {
            return blocked(GestureBoostBlockCode.JOURNAL_LEASE_UNAVAILABLE, "当前场景事务占用 journal，无法启动手势瞬时事务")
        }
        return GestureBoostRuntimeReadiness.Ready(touchDevices)
    }

    private fun blocked(code: GestureBoostBlockCode, reason: String) = GestureBoostRuntimeReadiness.Blocked(code, reason)
    private val EVENT_NODE = Regex("/dev/input/event(?:0|[1-9][0-9]{0,2})")
}

enum class GestureTouchAction { DOWN, UP }

data class GestureTouchEvent(val device: String, val action: GestureTouchAction)

/** Accepts only symbolic BTN_TOUCH DOWN/UP records produced by a fixed getevent -l reader. */
class GestureTouchEventParser(private val allowedDevices: Set<String>) {
    fun parse(line: String): GestureTouchEvent? {
        if (line.length !in 1..MAX_LINE_LENGTH || '\u0000' in line) return null
        val match = EVENT.find(line) ?: return null
        val device = match.groupValues[1]
        if (device !in allowedDevices) return null
        val action = when (match.groupValues[2]) {
            "DOWN" -> GestureTouchAction.DOWN
            "UP" -> GestureTouchAction.UP
            else -> return null
        }
        return GestureTouchEvent(device, action)
    }

    private companion object {
        const val MAX_LINE_LENGTH = 512
        val EVENT = Regex("(?:^|\\s)(/dev/input/event(?:0|[1-9][0-9]{0,2})):\\s+EV_KEY\\s+BTN_TOUCH\\s+(DOWN|UP)\\s*$")
    }
}

enum class GestureBoostPhase {
    STOPPED,
    WATCHING,
    ENTERING,
    BOOSTED,
    RESTORING,
    RECOVERY_REQUIRED
}

data class GestureBoostMachineState(
    val phase: GestureBoostPhase = GestureBoostPhase.STOPPED,
    val pressedDevice: String? = null,
    val enterTransactionId: String? = null,
    val releaseRequested: Boolean = false,
    val stopAfterRestore: Boolean = false
)

sealed interface GestureBoostMachineEvent {
    data object Start : GestureBoostMachineEvent
    data class Touch(val event: GestureTouchEvent) : GestureBoostMachineEvent
    data class EnterApplied(val transactionId: String) : GestureBoostMachineEvent
    data object EnterFailedWithoutWrite : GestureBoostMachineEvent
    data object EnterFailedRestored : GestureBoostMachineEvent
    data object EnterFailedRecoveryRequired : GestureBoostMachineEvent
    data object RestoreVerified : GestureBoostMachineEvent
    data object RestoreFailed : GestureBoostMachineEvent
    data object Stop : GestureBoostMachineEvent
}

sealed interface GestureBoostMachineEffect {
    data object None : GestureBoostMachineEffect
    data object BeginEnterTransaction : GestureBoostMachineEffect
    data class RestoreEnterTransaction(val transactionId: String) : GestureBoostMachineEffect
    data object Stopped : GestureBoostMachineEffect
    data object RecoveryRequired : GestureBoostMachineEffect
}

data class GestureBoostTransition(
    val state: GestureBoostMachineState,
    val effect: GestureBoostMachineEffect
)

/** Pure state machine. It never writes nodes and never starts getevent itself. */
object GestureBoostStateMachine {
    fun reduce(state: GestureBoostMachineState, event: GestureBoostMachineEvent): GestureBoostTransition = when (event) {
        GestureBoostMachineEvent.Start -> if (state.phase == GestureBoostPhase.STOPPED) {
            transition(GestureBoostPhase.WATCHING)
        } else unchanged(state)
        is GestureBoostMachineEvent.Touch -> onTouch(state, event.event)
        is GestureBoostMachineEvent.EnterApplied -> if (state.phase == GestureBoostPhase.ENTERING && event.transactionId.isNotBlank()) {
            if (state.releaseRequested) {
                GestureBoostTransition(
                    state.copy(phase = GestureBoostPhase.RESTORING, enterTransactionId = event.transactionId),
                    GestureBoostMachineEffect.RestoreEnterTransaction(event.transactionId)
                )
            } else {
                GestureBoostTransition(
                    state.copy(phase = GestureBoostPhase.BOOSTED, enterTransactionId = event.transactionId),
                    GestureBoostMachineEffect.None
                )
            }
        } else unchanged(state)
        GestureBoostMachineEvent.EnterFailedWithoutWrite,
        GestureBoostMachineEvent.EnterFailedRestored -> if (state.phase == GestureBoostPhase.ENTERING) {
            transition(GestureBoostPhase.WATCHING)
        } else unchanged(state)
        GestureBoostMachineEvent.EnterFailedRecoveryRequired -> if (state.phase == GestureBoostPhase.ENTERING) {
            recoveryRequired(state)
        } else unchanged(state)
        GestureBoostMachineEvent.RestoreVerified -> if (state.phase == GestureBoostPhase.RESTORING) {
            if (state.stopAfterRestore) transition(GestureBoostPhase.STOPPED, GestureBoostMachineEffect.Stopped)
            else transition(GestureBoostPhase.WATCHING)
        } else unchanged(state)
        GestureBoostMachineEvent.RestoreFailed -> if (state.phase == GestureBoostPhase.RESTORING) {
            recoveryRequired(state)
        } else unchanged(state)
        GestureBoostMachineEvent.Stop -> onStop(state)
    }

    private fun onTouch(state: GestureBoostMachineState, event: GestureTouchEvent): GestureBoostTransition = when {
        state.phase == GestureBoostPhase.WATCHING && event.action == GestureTouchAction.DOWN -> GestureBoostTransition(
            state.copy(phase = GestureBoostPhase.ENTERING, pressedDevice = event.device),
            GestureBoostMachineEffect.BeginEnterTransaction
        )
        state.phase == GestureBoostPhase.BOOSTED && event.action == GestureTouchAction.UP &&
            event.device == state.pressedDevice && state.enterTransactionId != null -> GestureBoostTransition(
            state.copy(phase = GestureBoostPhase.RESTORING),
            GestureBoostMachineEffect.RestoreEnterTransaction(state.enterTransactionId)
        )
        state.phase == GestureBoostPhase.ENTERING && event.action == GestureTouchAction.UP &&
            event.device == state.pressedDevice -> GestureBoostTransition(
            state.copy(releaseRequested = true),
            GestureBoostMachineEffect.None
        )
        else -> unchanged(state)
    }

    private fun onStop(state: GestureBoostMachineState): GestureBoostTransition = when (state.phase) {
        GestureBoostPhase.STOPPED -> unchanged(state)
        GestureBoostPhase.BOOSTED -> state.enterTransactionId?.let { transaction ->
            GestureBoostTransition(
                state.copy(phase = GestureBoostPhase.RESTORING, stopAfterRestore = true),
                GestureBoostMachineEffect.RestoreEnterTransaction(transaction)
            )
        } ?: recoveryRequired(state)
        GestureBoostPhase.ENTERING, GestureBoostPhase.RESTORING, GestureBoostPhase.RECOVERY_REQUIRED -> recoveryRequired(state)
        GestureBoostPhase.WATCHING -> transition(GestureBoostPhase.STOPPED, GestureBoostMachineEffect.Stopped)
    }

    private fun recoveryRequired(state: GestureBoostMachineState) = GestureBoostTransition(
        state.copy(phase = GestureBoostPhase.RECOVERY_REQUIRED),
        GestureBoostMachineEffect.RecoveryRequired
    )

    private fun transition(
        phase: GestureBoostPhase,
        effect: GestureBoostMachineEffect = GestureBoostMachineEffect.None
    ) = GestureBoostTransition(GestureBoostMachineState(phase), effect)

    private fun unchanged(state: GestureBoostMachineState) = GestureBoostTransition(state, GestureBoostMachineEffect.None)
}
