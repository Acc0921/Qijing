package com.qijing.core.scheduler.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureBoostTest {
    @Test
    fun `sample gesture feature becomes typed configure and planned node contracts`() {
        val program = program(
            gesture(
                enter = listOf(row("${'$'}cpu_max_0", "^2400000"), row("${'$'}cpu_max_6", "^2841600"))
            )
        )

        val result = GestureBoostContractPlanner().plan(program, binding) as GestureBoostContractPlan.Configured
        val contract = result.contract

        assertEquals(GestureBoostContractPlanner.CONFIGURE_CAPABILITY, contract.configureCommand.capability)
        assertEquals(GestureBoostContractPlanner.EVENT_PROTOCOL, contract.configureCommand.arguments["event_protocol"])
        assertEquals("true", contract.configureCommand.arguments["root_only"])
        assertEquals("true", contract.configureCommand.arguments["restore_enter_on_up"])
        assertEquals("2", contract.configureCommand.arguments["enter_count"])
        assertEquals("0", contract.configureCommand.arguments["exit_count"])
        assertEquals("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", contract.configureCommand.arguments["enter_0_path"])
        assertEquals("2400000", contract.configureCommand.arguments["enter_0_value"])
        assertTrue(contract.configureCommand.arguments.getValue("contract_id").matches(Regex("[0-9a-f]{64}")))
        assertTrue(contract.restoreEnterOnUp)
        assertEquals(
            listOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "2400000",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq" to "2841600"
            ),
            contract.enterCommands.map { it.arguments.getValue("path") to it.arguments.getValue("value") }
        )
        assertTrue((contract.enterCommands + contract.exitCommands).all { it.capability == "scheduler.node.write" })
        assertTrue((contract.enterCommands + contract.exitCommands).all { it.rollback == null })
    }

    @Test
    fun `nonempty exit remains blocked until its persistence semantics are verified`() {
        val result = GestureBoostContractPlanner().plan(
            program(gesture(enter = listOf(row("${'$'}cpu_max_0", "2400000")), exit = listOf(row("${'$'}cpu_max_0", "1800000")))),
            binding
        ) as GestureBoostContractPlan.Rejected

        assertEquals("GESTURE_BOOST_EXIT_UNVERIFIED", result.code)
    }

    @Test
    fun `empty enabled sample is no-op while disabled feature cannot start runtime`() {
        val emptyEnabled = GestureBoostContractPlanner().plan(program(gesture()), binding)
        val disabled = GestureBoostContractPlanner().plan(program(gesture(enabled = false)), binding)

        assertEquals(GestureBoostContractPlan.NoOp, emptyEnabled)
        assertEquals(GestureBoostContractPlan.AbsentOrDisabled, disabled)
    }

    @Test
    fun `feature rejects shell direct paths unknown aliases objects and unknown fields`() {
        val shell = program(gesture(enter = listOf(row("sh /data/powercfg.sh", "1"))))
        val directPath = program(gesture(enter = listOf(row("/sys/devices/system/cpu/online", "1"))))
        val unknownAlias = program(gesture(enter = listOf(row("${'$'}missing", "1"))))
        val objectValue = program(gesture(enter = listOf(row("${'$'}cpu_max_0", obj("nested" to text("x"))))))
        val unknownField = program(
            obj(
                "enable" to bool(true),
                "enter" to array(),
                "exit" to array(),
                "script" to text("echo unsafe")
            )
        )

        assertRejected(shell, "GESTURE_BOOST_TARGET_INVALID")
        assertRejected(directPath, "GESTURE_BOOST_TARGET_INVALID")
        assertRejected(unknownAlias, "GESTURE_BOOST_ALIAS_UNKNOWN")
        assertRejected(objectValue, "GESTURE_BOOST_VALUE_INVALID")
        assertRejected(unknownField, "GESTURE_BOOST_FIELD_UNSUPPORTED")
    }

    @Test
    fun `runtime gate fails closed for backend input enumeration touch capability and journal lease`() {
        val touch = listOf(GestureInputDevice("/dev/input/event2", supportsBtnTouch = true))

        assertBlocked(
            GestureBoostRuntimeGate.evaluate(false, true, touch, true),
            GestureBoostBlockCode.ROOT_REQUIRED
        )
        assertBlocked(
            GestureBoostRuntimeGate.evaluate(true, false, touch, true),
            GestureBoostBlockCode.INPUT_ENUMERATION_FAILED
        )
        assertBlocked(
            GestureBoostRuntimeGate.evaluate(true, true, listOf(GestureInputDevice("/dev/input/event2", false)), true),
            GestureBoostBlockCode.TOUCH_INPUT_UNAVAILABLE
        )
        assertBlocked(
            GestureBoostRuntimeGate.evaluate(true, true, touch, false),
            GestureBoostBlockCode.JOURNAL_LEASE_UNAVAILABLE
        )
        val ready = GestureBoostRuntimeGate.evaluate(true, true, touch, true) as GestureBoostRuntimeReadiness.Ready
        assertEquals(setOf("/dev/input/event2"), ready.touchDevices)
    }

    @Test
    fun `getevent parser accepts only whitelisted BTN_TOUCH DOWN and UP records`() {
        val parser = GestureTouchEventParser(setOf("/dev/input/event2"))

        assertEquals(
            GestureTouchEvent("/dev/input/event2", GestureTouchAction.DOWN),
            parser.parse("[ 123.000] /dev/input/event2: EV_KEY BTN_TOUCH DOWN")
        )
        assertEquals(
            GestureTouchEvent("/dev/input/event2", GestureTouchAction.UP),
            parser.parse("/dev/input/event2: EV_KEY BTN_TOUCH UP")
        )
        assertEquals(null, parser.parse("/dev/input/event2: EV_KEY KEY_VOLUMEUP DOWN"))
        assertEquals(null, parser.parse("/dev/input/event3: EV_KEY BTN_TOUCH DOWN"))
        assertEquals(null, parser.parse("/dev/input/event2: EV_ABS ABS_MT_TRACKING_ID 00000001"))
        assertEquals(null, parser.parse("/dev/input/event2: EV_KEY BTN_TOUCH REPEAT"))
    }

    @Test
    fun `state machine restores verified enter transaction on UP and on stop`() {
        val watching = GestureBoostStateMachine.reduce(GestureBoostMachineState(), GestureBoostMachineEvent.Start).state
        val entering = GestureBoostStateMachine.reduce(
            watching,
            GestureBoostMachineEvent.Touch(GestureTouchEvent("/dev/input/event2", GestureTouchAction.DOWN))
        )
        assertEquals(GestureBoostPhase.ENTERING, entering.state.phase)
        assertEquals(GestureBoostMachineEffect.BeginEnterTransaction, entering.effect)

        val boosted = GestureBoostStateMachine.reduce(
            entering.state,
            GestureBoostMachineEvent.EnterApplied("gesture-tx-1")
        ).state
        val release = GestureBoostStateMachine.reduce(
            boosted,
            GestureBoostMachineEvent.Touch(GestureTouchEvent("/dev/input/event2", GestureTouchAction.UP))
        )
        assertEquals(GestureBoostPhase.RESTORING, release.state.phase)
        assertEquals(GestureBoostMachineEffect.RestoreEnterTransaction("gesture-tx-1"), release.effect)
        val restored = GestureBoostStateMachine.reduce(release.state, GestureBoostMachineEvent.RestoreVerified)
        assertEquals(GestureBoostPhase.WATCHING, restored.state.phase)

        val boostedAgain = GestureBoostStateMachine.reduce(
            entering.state,
            GestureBoostMachineEvent.EnterApplied("gesture-tx-2")
        ).state
        val stopping = GestureBoostStateMachine.reduce(boostedAgain, GestureBoostMachineEvent.Stop)
        assertTrue(stopping.state.stopAfterRestore)
        assertEquals(GestureBoostMachineEffect.RestoreEnterTransaction("gesture-tx-2"), stopping.effect)
        val stopped = GestureBoostStateMachine.reduce(stopping.state, GestureBoostMachineEvent.RestoreVerified)
        assertEquals(GestureBoostPhase.STOPPED, stopped.state.phase)
        assertEquals(GestureBoostMachineEffect.Stopped, stopped.effect)
    }

    @Test
    fun `quick UP and three enter outcomes cannot lose recovery responsibility`() {
        val entering = GestureBoostStateMachine.reduce(
            GestureBoostMachineState(GestureBoostPhase.WATCHING),
            GestureBoostMachineEvent.Touch(GestureTouchEvent("/dev/input/event2", GestureTouchAction.DOWN))
        ).state
        val releasedBeforeApply = GestureBoostStateMachine.reduce(
            entering,
            GestureBoostMachineEvent.Touch(GestureTouchEvent("/dev/input/event2", GestureTouchAction.UP))
        ).state
        assertTrue(releasedBeforeApply.releaseRequested)
        val immediateRestore = GestureBoostStateMachine.reduce(
            releasedBeforeApply,
            GestureBoostMachineEvent.EnterApplied("gesture-tx-3")
        )
        assertEquals(GestureBoostMachineEffect.RestoreEnterTransaction("gesture-tx-3"), immediateRestore.effect)

        val noWrite = GestureBoostStateMachine.reduce(entering, GestureBoostMachineEvent.EnterFailedWithoutWrite)
        val wroteAndRestored = GestureBoostStateMachine.reduce(entering, GestureBoostMachineEvent.EnterFailedRestored)
        val incomplete = GestureBoostStateMachine.reduce(entering, GestureBoostMachineEvent.EnterFailedRecoveryRequired)
        assertEquals(GestureBoostPhase.WATCHING, noWrite.state.phase)
        assertEquals(GestureBoostPhase.WATCHING, wroteAndRestored.state.phase)
        assertEquals(GestureBoostPhase.RECOVERY_REQUIRED, incomplete.state.phase)
        assertEquals(GestureBoostMachineEffect.RecoveryRequired, incomplete.effect)
    }

    private fun assertRejected(program: CompiledProfileProgram, code: String) {
        val result = GestureBoostContractPlanner().plan(program, binding) as GestureBoostContractPlan.Rejected
        assertEquals(code, result.code)
    }

    private fun assertBlocked(result: GestureBoostRuntimeReadiness, code: GestureBoostBlockCode) {
        assertEquals(code, (result as GestureBoostRuntimeReadiness.Blocked).code)
    }

    private fun program(gesture: ProfileFeatureValue.ObjectValue) = CompiledProfileProgram(
        aliases = mapOf(
            "cpu_max_0" to KernelNode("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"),
            "cpu_max_6" to KernelNode("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq")
        ),
        features = mapOf("gesture_boost" to gesture),
        reset = emptyList(),
        routes = emptyMap(),
        applicationRules = emptyList()
    )

    private fun gesture(
        enabled: Boolean = true,
        enter: List<ProfileFeatureValue.ArrayValue> = emptyList(),
        exit: List<ProfileFeatureValue.ArrayValue> = emptyList()
    ) = obj(
        "enable" to bool(enabled),
        "enter" to ProfileFeatureValue.ArrayValue(enter),
        "exit" to ProfileFeatureValue.ArrayValue(exit)
    )

    private fun row(target: String, value: String) = row(target, text(value))
    private fun row(target: String, value: ProfileFeatureValue) = array(text(target), value)
    private fun obj(vararg entries: Pair<String, ProfileFeatureValue>) = ProfileFeatureValue.ObjectValue(mapOf(*entries))
    private fun array(vararg entries: ProfileFeatureValue) = ProfileFeatureValue.ArrayValue(entries.toList())
    private fun text(value: String) = ProfileFeatureValue.StringValue(value)
    private fun bool(value: Boolean) = ProfileFeatureValue.BooleanValue(value)

    private companion object {
        val binding = ProfileDeviceBinding(
            policyIds = listOf(0, 6),
            availableFrequenciesKHz = mapOf(
                0 to listOf(1_800_000, 2_400_000),
                6 to listOf(2_000_000, 2_841_600)
            )
        )
    }
}
