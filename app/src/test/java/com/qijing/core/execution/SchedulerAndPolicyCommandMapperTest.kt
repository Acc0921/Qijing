package com.qijing.core.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerAndPolicyCommandMapperTest {
    @Test fun `policy path is derived only from bounded numeric id`() {
        val mapped = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("cpu.policy.4.governor.set", mapOf("value" to "schedutil"))
        ) as PrivilegedWriteCommandMapper.Result.Command

        assertTrue(mapped.shell.contains("policy4/scaling_governor"))
        assertTrue(PrivilegedWriteCommandMapper.map(CapabilityCommand("cpu.policy.999.governor.set", mapOf("value" to "schedutil"))) is PrivilegedWriteCommandMapper.Result.Invalid)
        assertNull(PrivilegedReadCommandMapper.map("cpu.policy.x.governor.set"))
    }

    @Test fun `scheduler values cannot inject shell`() {
        val rejected = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("scheduler.uperf.mode.set", mapOf("value" to "fast; reboot"))
        )
        val accepted = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("scheduler.uperf.mode.set", mapOf("value" to "fast"))
        ) as PrivilegedWriteCommandMapper.Result.Command

        assertTrue(rejected is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(accepted.shell.contains("/data/powercfg.sh 'fast'"))
        assertFalse(accepted.shell.contains("reboot"))
    }

    @Test fun `uperf gt uses fixed identity and mode contract`() {
        val mapped = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("scheduler.uperf_gt.mode.set", mapOf("value" to "balance"))
        ) as PrivilegedWriteCommandMapper.Result.Command
        assertTrue(mapped.shell.contains("name=Uperf Game Turbo"))
        assertTrue(mapped.shell.contains("/data/powercfg.sh 'balance'"))
    }

    @Test fun `configuration bridge cannot run without fixed contract and readback`() {
        val mapped = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("scheduler.config_bridge.mode.set", mapOf("value" to "performance"))
        ) as PrivilegedWriteCommandMapper.Result.Command
        val rejected = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("scheduler.config_bridge.mode.set", mapOf("value" to "performance; reboot"))
        )

        assertTrue(mapped.shell.contains("qijing-scheduler-bridge-v1"))
        assertTrue(mapped.shell.contains("apply-mode' 'performance'"))
        assertTrue(mapped.shell.contains("current_mode"))
        assertFalse(mapped.shell.contains("powercfg.sh"))
        assertTrue(rejected is PrivilegedWriteCommandMapper.Result.Invalid)
    }

    @Test fun `configuration bridge snapshot uses only fixed verified state`() {
        val read = PrivilegedReadCommandMapper.map("scheduler.config_bridge.mode.set")

        assertTrue(read!!.contains("Scene_Config_replace/module.prop"))
        assertTrue(read.contains("qijing-scheduler-bridge-v1"))
        assertTrue(read.contains("current_mode"))
    }

    @Test fun `typed scheduler node write accepts bounded system nodes and verifies readback`() {
        val command = CapabilityCommand(
            "scheduler.node.write",
            mapOf("path" to "/sys/devices/system/cpu/cpu6/online", "value" to "1")
        )
        val write = PrivilegedWriteCommandMapper.map(command) as PrivilegedWriteCommandMapper.Result.Command
        val read = PrivilegedReadCommandMapper.map(command)

        assertTrue(write.shell.contains("cpu6/online"))
        assertTrue(write.shell.contains("tr -d"))
        assertTrue(read!!.contains("cpu6/online"))
    }

    @Test fun `typed scheduler node write rejects traversal shell data and destructive control nodes`() {
        fun mapped(path: String, value: String = "1") = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("scheduler.node.write", mapOf("path" to path, "value" to value))
        )

        assertTrue(mapped("/sys/devices/../power/state") is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(mapped("/sys/bus/cpu/drivers/cpu/unbind") is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(mapped("/sys/devices/system/cpu/cpu0/online", "1; reboot") is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(mapped("/data/local/tmp/node") is PrivilegedWriteCommandMapper.Result.Invalid)
    }

    @Test fun `thread mutations require reusable identities expected value and bounded target`() {
        val args = mapOf(
            "package" to "com.example.game", "pid" to "123", "process_start" to "9001",
            "tid" to "124", "start_ticks" to "9002", "expected" to "ff", "value" to "0f"
        )
        val command = CapabilityCommand("scheduler.thread.affinity.set", args)
        val write = PrivilegedWriteCommandMapper.map(command) as PrivilegedWriteCommandMapper.Result.Command
        val read = PrivilegedReadCommandMapper.map(command)

        assertTrue(write.shell.contains("9001"))
        assertTrue(write.shell.contains("9002"))
        assertTrue(write.shell.contains("taskset -p '0f' '124'"))
        assertTrue(read!!.contains("taskset -p '124'"))
        assertTrue(PrivilegedWriteCommandMapper.map(command.copy(arguments = args + ("package" to "x; reboot"))) is PrivilegedWriteCommandMapper.Result.Invalid)
    }

    @Test fun `thread cpuset can create only bounded qijing groups`() {
        val base = mapOf(
            "package" to "com.example.game", "pid" to "123", "process_start" to "9001",
            "tid" to "124", "start_ticks" to "9002", "expected" to "/top-app"
        )
        val accepted = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("scheduler.thread.cpuset.set", base + ("value" to "/qijing_abcdef@0-5"))
        ) as PrivilegedWriteCommandMapper.Result.Command
        val rejected = PrivilegedWriteCommandMapper.map(
            CapabilityCommand("scheduler.thread.cpuset.set", base + ("value" to "/foreign@0-5"))
        )

        assertTrue(accepted.shell.contains("/dev/cpuset/qijing_abcdef"))
        assertTrue(rejected is PrivilegedWriteCommandMapper.Result.Invalid)
    }

    @Test fun `basic profile limiter writes one fixed policy range and explicit core ctl`() {
        val command = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set",
            limiterArguments(policy = "6", coreCtl = "1")
        )

        val mapped = PrivilegedWriteCommandMapper.map(command) as PrivilegedWriteCommandMapper.Result.Command
        val read = PrivilegedReadCommandMapper.map(command)

        assertTrue(mapped.shell.contains("policy6/scaling_min_freq"))
        assertTrue(mapped.shell.contains("policy6/scaling_max_freq"))
        assertTrue(mapped.shell.contains("cpu6/core_ctl/enable"))
        assertTrue(mapped.shell.contains("tr -d"))
        assertTrue(read!!.contains("printf '%s|%s|%s'"))
        assertTrue(read.contains("cpu6/core_ctl/enable"))
    }

    @Test fun `profile limiter restore is guarded by the applied range`() {
        val restore = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set.restore",
            limiterArguments(policy = "0", coreCtl = "absent") + mapOf(
                "min_khz" to "300000",
                "max_khz" to "1000000",
                "expected_min_khz" to "500000",
                "expected_max_khz" to "1800000",
                "expected_core_ctl" to "absent"
            )
        )

        val mapped = PrivilegedWriteCommandMapper.map(restore) as PrivilegedWriteCommandMapper.Result.Command

        assertTrue(mapped.shell.contains("cur_min"))
        assertTrue(mapped.shell.contains("500000"))
        assertTrue(mapped.shell.contains("else exit 5"))
    }

    @Test fun `advanced limiter fields use owned managed runtime and reject injection`() {
        val advanced = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set",
            limiterArguments() + ("margins" to "150 600000:100")
        )
        val injected = advanced.copy(arguments = advanced.arguments + ("margins" to "150; reboot"))

        val mapped = PrivilegedWriteCommandMapper.map(advanced) as PrivilegedWriteCommandMapper.Result.Command
        assertTrue(mapped.shell.contains("qijing-managed-limiter-v1"))
        assertTrue(mapped.shell.contains("nohup sh"))
        assertTrue(mapped.shell.contains("owned|%s"))
        assertTrue(PrivilegedWriteCommandMapper.map(injected) is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(PrivilegedReadCommandMapper.map(advanced)!!.contains("inactive|%s|%s|%s"))
    }

    @Test fun `app frequencies use discovered edge policies with readback`() {
        val app = CapabilityCommand(
            "scheduler.profile.app_frequencies.set",
            mapOf("package" to "com.example.game", "performance_khz" to "2400000", "efficiency_khz" to "1800000")
        )

        val mapped = PrivilegedWriteCommandMapper.map(app) as PrivilegedWriteCommandMapper.Result.Command
        val read = PrivilegedReadCommandMapper.map(app)

        assertTrue(mapped.shell.contains("sort -n -u"))
        assertTrue(mapped.shell.contains("scaling_available_frequencies"))
        assertTrue(mapped.shell.contains("policy\$efficiency_policy/scaling_max_freq"))
        assertTrue(mapped.shell.contains("policy\$performance_policy/scaling_max_freq"))
        assertTrue(mapped.shell.contains("tr -d '[:space:]'"))
        assertTrue(read!!.contains("printf '%s|%s|%s|%s'"))
    }

    @Test fun `app frequency restore checks policy identity and applied values`() {
        val restore = CapabilityCommand(
            "scheduler.profile.app_frequencies.set.restore",
            mapOf(
                "package" to "com.example.game",
                "performance_policy" to "6", "performance_khz" to "3200000",
                "efficiency_policy" to "0", "efficiency_khz" to "2200000",
                "expected_performance_khz" to "2400000", "expected_efficiency_khz" to "1800000"
            )
        )

        val mapped = PrivilegedWriteCommandMapper.map(restore) as PrivilegedWriteCommandMapper.Result.Command

        assertTrue(mapped.shell.contains("[ \"\$efficiency_policy\" = '0' ]"))
        assertTrue(mapped.shell.contains("[ \"\$performance_policy\" = '6' ]"))
        assertTrue(mapped.shell.contains("restore_cap"))
        assertTrue(mapped.shell.contains("else return 5"))
        assertTrue(mapped.shell.contains("'3200000' '2400000'"))
        assertTrue(mapped.shell.contains("'2200000' '1800000'"))
    }

    @Test fun `limiter clear and malformed app frequencies fail closed`() {
        val clear = CapabilityCommand("scheduler.profile.limiter.clear", mapOf("scope" to "cpu_ddr"))
        val badClear = clear.copy(arguments = mapOf("scope" to "cpu_ddr; reboot"))
        val app = CapabilityCommand(
            "scheduler.profile.app_frequencies.set",
            mapOf("package" to "com.example.game", "performance_khz" to "2400000", "efficiency_khz" to "1800000")
        )
        val badApp = app.copy(arguments = app.arguments + ("package" to "x; reboot"))

        assertTrue(PrivilegedWriteCommandMapper.map(clear) is PrivilegedWriteCommandMapper.Result.Command)
        assertTrue(PrivilegedWriteCommandMapper.map(badClear) is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(PrivilegedWriteCommandMapper.map(app) is PrivilegedWriteCommandMapper.Result.Command)
        assertTrue(PrivilegedWriteCommandMapper.map(badApp) is PrivilegedWriteCommandMapper.Result.Invalid)
        assertTrue(PrivilegedReadCommandMapper.map(app) != null)
    }

    private fun limiterArguments(policy: String = "0", coreCtl: String = "absent") = mapOf(
        "profile" to "p1",
        "policy" to policy,
        "min_khz" to "500000",
        "max_khz" to "1800000",
        "margins" to "absent",
        "excludes" to "absent",
        "prefer" to "absent",
        "core_ctl" to coreCtl,
        "ddr_boost" to "false"
    )
}
