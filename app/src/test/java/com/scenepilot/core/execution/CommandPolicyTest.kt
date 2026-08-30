package com.scenepilot.core.execution

import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {
    @Test fun `read capability is allowed`() {
        assertTrue(CommandPolicy().check(CapabilityCommand("memory.status.read")).isSuccess)
    }

    @Test fun `write capability is rejected`() {
        assertTrue(CommandPolicy().check(CapabilityCommand("cpu.governor.set")).isFailure)
    }
}
