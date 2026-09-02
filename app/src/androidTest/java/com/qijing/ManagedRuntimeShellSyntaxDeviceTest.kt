package com.qijing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.PrivilegedWriteCommandMapper
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedRuntimeShellSyntaxDeviceTest {
    @Test fun managedLimiterConfigureAndRestoreUseValidAndroidShellSyntax() {
        val forward = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set",
            mapOf(
                "profile" to "balance_active",
                "policy" to "0",
                "min_khz" to "300000",
                "max_khz" to "1800000",
                "margins" to "150 600000:100 1200000:200",
                "excludes" to "0,1",
                "prefer" to "2",
                "core_ctl" to "absent",
                "ddr_boost" to "false"
            )
        )
        val restore = forward.copy(
            capability = "scheduler.profile.limiter.cluster.set.restore",
            arguments = forward.arguments + mapOf(
                "min_khz" to "300000",
                "max_khz" to "1200000",
                "expected_min_khz" to "300000",
                "expected_max_khz" to "1800000",
                "expected_core_ctl" to "absent"
            )
        )

        assertShellSyntax(forward)
        assertShellSyntax(restore)
    }

    @Test fun managedGestureConfigureAndRestoreUseValidAndroidShellSyntax() {
        val protocol = "getevent:EV_KEY:BTN_TOUCH:DOWN_UP:v1"
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val value = "1800000"
        val canonical = "qijing-gesture-v1|$protocol|enter|0|$path=$value|exit|restore=true"
        val contract = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val forward = CapabilityCommand(
            "scheduler.profile.gesture_boost.configure",
            mapOf(
                "contract_id" to contract,
                "event_protocol" to protocol,
                "enter_count" to "1",
                "exit_count" to "0",
                "restore_enter_on_up" to "true",
                "root_only" to "true",
                "enter_0_path" to path,
                "enter_0_value" to value
            )
        )
        val restore = forward.copy(
            capability = "scheduler.profile.gesture_boost.configure.restore",
            arguments = forward.arguments + ("expected" to "owned|$contract")
        )

        assertShellSyntax(forward)
        assertShellSyntax(restore)
    }

    private fun assertShellSyntax(command: CapabilityCommand) {
        val mapped = PrivilegedWriteCommandMapper.map(command)
        assertTrue("command must map: $mapped", mapped is PrivilegedWriteCommandMapper.Result.Command)
        val shell = (mapped as PrivilegedWriteCommandMapper.Result.Command).shell
        val process = ProcessBuilder("/system/bin/sh", "-n", "-c", shell).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }
}
