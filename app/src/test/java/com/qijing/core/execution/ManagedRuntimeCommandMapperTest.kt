package com.qijing.core.execution

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedRuntimeCommandMapperTest {
    @Test fun `gesture contract maps to detached owned watcher and reversible read state`() {
        val command = gestureCommand()

        val mapped = PrivilegedWriteCommandMapper.map(command) as PrivilegedWriteCommandMapper.Result.Command
        val read = PrivilegedReadCommandMapper.map(command)
        val restore = command.copy(
            capability = "scheduler.profile.gesture_boost.configure.restore",
            arguments = command.arguments + ("expected" to "owned|${command.arguments.getValue("contract_id")}")
        )

        assertTrue(mapped.shell.contains("qijing-gesture-v1"))
        assertTrue(mapped.shell.contains("getevent -lp"))
        assertTrue(mapped.shell.contains("{ nohup sh"))
        assertTrue(mapped.shell.contains("</dev/null & worker=\$!"))
        assertTrue(mapped.shell.contains("start_ticks") && mapped.shell.contains("armed"))
        assertTrue(read!!.contains("printf inactive"))
        assertTrue(read.contains("owned|%s"))
        val restoreShell = (PrivilegedWriteCommandMapper.map(restore) as PrivilegedWriteCommandMapper.Result.Command).shell
        assertTrue(restoreShell.contains("if [ ! -f '/data/local/tmp/qijing-gesture-v1/armed' ]"))
    }

    @Test fun `gesture parser rejects tampered id extra arguments and shell values`() {
        val command = gestureCommand()
        val badId = command.copy(arguments = command.arguments + ("contract_id" to "0".repeat(64)))
        val extra = command.copy(arguments = command.arguments + ("script" to "reboot"))
        val injection = command.copy(arguments = command.arguments + ("enter_0_value" to "1; reboot"))

        assertTrue(PrivilegedWriteCommandMapper.map(badId) is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(PrivilegedWriteCommandMapper.map(extra) is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(PrivilegedWriteCommandMapper.map(injection) is PrivilegedWriteCommandMapper.Result.Invalid)
    }

    @Test fun `Shizuku rejects Root owned runtimes before transport`() {
        val broker = ShizukuExecutionBroker(ShizukuTransport { error("transport must not run") })
        val limiter = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set",
            mapOf(
                "profile" to "p1", "policy" to "0", "min_khz" to "500000", "max_khz" to "1800000",
                "margins" to "150 600000:100", "excludes" to "0", "prefer" to "1",
                "core_ctl" to "absent", "ddr_boost" to "false"
            )
        )

        assertTrue(broker.validate(gestureCommand()) is ExecutionResult.Unsupported)
        assertTrue(broker.validate(limiter) is ExecutionResult.Unsupported)
        assertTrue(broker.validate(CapabilityCommand("scheduler.profile.limiter.clear", mapOf("scope" to "cpu_ddr"))) is ExecutionResult.Unsupported)
    }

    @Test fun `managed limiter owner id is stable and restore shell verifies process identity`() {
        val command = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set",
            mapOf(
                "profile" to "p1", "policy" to "0", "min_khz" to "500000", "max_khz" to "1800000",
                "margins" to "150 600000:100", "excludes" to "0", "prefer" to "1",
                "core_ctl" to "absent", "ddr_boost" to "false"
            )
        )
        val cluster = requireNotNull(ProfileLimiterCommandPolicy.parse(command))
        val first = ManagedLimiterRuntime.contractId(cluster, false)
        val second = ManagedLimiterRuntime.contractId(cluster, false)
        val forwardShell = (PrivilegedWriteCommandMapper.map(command) as PrivilegedWriteCommandMapper.Result.Command).shell
        val restore = command.copy(
            capability = "scheduler.profile.limiter.cluster.set.restore",
            arguments = command.arguments + mapOf(
                "min_khz" to "300000", "max_khz" to "1200000", "core_ctl" to "absent",
                "expected_min_khz" to "500000", "expected_max_khz" to "1800000", "expected_core_ctl" to "absent"
            )
        )
        val shell = (PrivilegedWriteCommandMapper.map(restore) as PrivilegedWriteCommandMapper.Result.Command).shell

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertTrue(forwardShell.indexOf("start_ticks") < forwardShell.indexOf("/armed"))
        assertTrue(shell.contains("start_ticks"))
        assertTrue(shell.contains("cmdline"))
        assertTrue(shell.contains("armed"))
        assertTrue(shell.contains("if [ ! -f '/data/local/tmp/qijing-managed-limiter-v1/policy0/armed' ]"))
        assertTrue(shell.contains("exit 5"))
    }

    @Test fun `health policy probes only managed runtimes and requires exact owner state`() = kotlinx.coroutines.runBlocking {
        val fixedLimiter = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set",
            mapOf(
                "profile" to "p1", "policy" to "0", "min_khz" to "500000", "max_khz" to "1800000",
                "margins" to "absent", "excludes" to "absent", "prefer" to "absent",
                "core_ctl" to "absent", "ddr_boost" to "false"
            )
        )
        val managedLimiter = fixedLimiter.copy(arguments = fixedLimiter.arguments + ("margins" to "150 600000:100"))
        val probes = ManagedRuntimeHealthPolicy.probes(listOf(fixedLimiter, managedLimiter, gestureCommand()))

        assertEquals(2, probes.size)
        assertTrue(probes.any { it.command.capability == "scheduler.profile.limiter.health" })
        assertTrue(probes.any { it.command.capability == "scheduler.profile.gesture_boost.health" })
        assertNull(ManagedRuntimeHealthPolicy.firstFailure(listOf(managedLimiter, gestureCommand())) { command ->
            probes.single { it.command == command }.expected
        })
        val failure = ManagedRuntimeHealthPolicy.firstFailure(listOf(managedLimiter)) { "fault|deadbeef" }
        assertNotNull(failure)
        assertTrue(failure!!.detail.contains("运行故障"))
    }

    @Test fun `health policy treats unreadable stale and wrong owner states as failures`() = kotlinx.coroutines.runBlocking {
        val command = gestureCommand()

        assertTrue(ManagedRuntimeHealthPolicy.firstFailure(listOf(command)) { null }!!.detail.contains("无法读取"))
        assertTrue(
            ManagedRuntimeHealthPolicy.firstFailure(listOf(command)) {
                "stale|${command.arguments.getValue("contract_id")}"
            }!!.detail.contains("已停止")
        )
        assertTrue(
            ManagedRuntimeHealthPolicy.firstFailure(listOf(command)) { "running|${"0".repeat(64)}" }!!
                .detail.contains("非预期")
        )
    }

    private fun gestureCommand(): CapabilityCommand {
        val protocol = "getevent:EV_KEY:BTN_TOUCH:DOWN_UP:v1"
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val value = "1800000"
        val canonical = "qijing-gesture-v1|$protocol|enter|0|$path=$value|exit|restore=true"
        val id = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        return CapabilityCommand(
            "scheduler.profile.gesture_boost.configure",
            mapOf(
                "contract_id" to id, "event_protocol" to protocol, "enter_count" to "1", "exit_count" to "0",
                "restore_enter_on_up" to "true", "root_only" to "true", "enter_0_path" to path, "enter_0_value" to value
            )
        )
    }
}
