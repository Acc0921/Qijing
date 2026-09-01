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
}
