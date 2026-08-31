package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneSnapshotTest {
    @Test fun `snapshot attaches restore command with previous value`() = runBlocking {
        val reader = CapabilityValueReader { capability -> if (capability == "cpu.governor.set") "schedutil" else null }
        val command = CapabilityCommand("cpu.governor.set", mapOf("value" to "performance"))
        val manager = SceneSnapshotManager(reader)
        val prepared = manager.attachRestore(listOf(command), manager.capture(listOf(command))).single()
        assertEquals("cpu.governor.set.restore", prepared.rollback?.capability)
        assertEquals("schedutil", prepared.rollback?.arguments?.get("value"))
    }
}
