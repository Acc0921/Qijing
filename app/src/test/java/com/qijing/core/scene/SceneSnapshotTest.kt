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
        assertEquals("performance", prepared.rollback?.arguments?.get("expected"))
    }

    @Test fun `dynamic node snapshots remain distinct and preserve path in restore`() = kotlinx.coroutines.runBlocking {
        val manager = SceneSnapshotManager(CommandValueReader { command ->
            when (command.arguments["path"]) {
                "/sys/demo/a" -> "1"
                "/sys/demo/b" -> "2"
                else -> null
            }
        })
        val commands = listOf(
            CapabilityCommand("scheduler.node.write", mapOf("path" to "/sys/demo/a", "value" to "8")),
            CapabilityCommand("scheduler.node.write", mapOf("path" to "/sys/demo/b", "value" to "9"))
        )

        val attached = manager.attachRestore(commands, manager.capture(commands))

        assertEquals(mapOf("path" to "/sys/demo/a", "expected" to "8", "value" to "1"), attached[0].rollback?.arguments)
        assertEquals(mapOf("path" to "/sys/demo/b", "expected" to "9", "value" to "2"), attached[1].rollback?.arguments)
    }

    @Test fun `command reader never substitutes another node snapshot`() = runBlocking {
        val manager = SceneSnapshotManager(CommandValueReader { command ->
            if (command.arguments["path"] == "/sys/devices/system/cpu/cpufreq/boost") "0" else null
        })
        val commands = listOf(
            CapabilityCommand("scheduler.node.write", mapOf("path" to "/sys/devices/system/cpu/cpufreq/boost", "value" to "1")),
            CapabilityCommand("scheduler.node.write", mapOf("path" to "/sys/module/migt/parameters/glk_disable", "value" to "1"))
        )

        val attached = manager.attachRestore(commands, manager.capture(commands))

        assertEquals("0", attached[0].rollback?.arguments?.get("value"))
        assertEquals(null, attached[1].rollback)
    }

    @Test fun `thread restore keeps full identity and requires the applied value`() = runBlocking {
        val identity = mapOf(
            "package" to "com.example.game", "pid" to "10", "process_start" to "100",
            "tid" to "11", "start_ticks" to "101"
        )
        val command = CapabilityCommand(
            "scheduler.thread.affinity.set",
            identity + mapOf("expected" to "ff", "value" to "0f")
        )
        val manager = SceneSnapshotManager(CommandValueReader { "ff" })

        val rollback = manager.attachRestore(listOf(command), manager.capture(listOf(command))).single().rollback!!

        assertEquals("scheduler.thread.affinity.set.restore", rollback.capability)
        assertEquals(identity + mapOf("expected" to "0f", "value" to "ff"), rollback.arguments)
    }

    @Test fun `profile limiter snapshots stay distinct by policy and restore the whole range`() = runBlocking {
        fun command(policy: String) = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set",
            mapOf(
                "profile" to "p1", "policy" to policy,
                "min_khz" to "500000", "max_khz" to "1800000",
                "margins" to "absent", "excludes" to "absent", "prefer" to "absent",
                "core_ctl" to "1", "ddr_boost" to "false"
            )
        )
        val commands = listOf(command("0"), command("6"))
        val manager = SceneSnapshotManager(CommandValueReader { command ->
            if (command.arguments["policy"] == "0") "300000|1200000|0" else "700000|2200000|1"
        })

        val attached = manager.attachRestore(commands, manager.capture(commands))

        assertEquals("300000", attached[0].rollback?.arguments?.get("min_khz"))
        assertEquals("1200000", attached[0].rollback?.arguments?.get("max_khz"))
        assertEquals("0", attached[0].rollback?.arguments?.get("core_ctl"))
        assertEquals("500000", attached[0].rollback?.arguments?.get("expected_min_khz"))
        assertEquals("700000", attached[1].rollback?.arguments?.get("min_khz"))
        assertEquals("2200000", attached[1].rollback?.arguments?.get("max_khz"))
    }

    @Test fun `app frequency snapshot restores discovered policies with applied guards`() = runBlocking {
        val command = CapabilityCommand(
            "scheduler.profile.app_frequencies.set",
            mapOf(
                "package" to "com.example.game",
                "performance_khz" to "2400000",
                "efficiency_khz" to "1800000"
            )
        )
        val manager = SceneSnapshotManager(CommandValueReader { "0|2200000|6|3200000" })

        val rollback = manager.attachRestore(listOf(command), manager.capture(listOf(command))).single().rollback!!

        assertEquals("scheduler.profile.app_frequencies.set.restore", rollback.capability)
        assertEquals("com.example.game", rollback.arguments["package"])
        assertEquals("0", rollback.arguments["efficiency_policy"])
        assertEquals("2200000", rollback.arguments["efficiency_khz"])
        assertEquals("1800000", rollback.arguments["expected_efficiency_khz"])
        assertEquals("6", rollback.arguments["performance_policy"])
        assertEquals("3200000", rollback.arguments["performance_khz"])
        assertEquals("2400000", rollback.arguments["expected_performance_khz"])
    }

    @Test fun `managed limiter snapshot keeps stable owner state and original policy range`() = runBlocking {
        val command = CapabilityCommand(
            "scheduler.profile.limiter.cluster.set",
            mapOf(
                "profile" to "p1", "policy" to "0", "min_khz" to "500000", "max_khz" to "1800000",
                "margins" to "150 600000:100", "excludes" to "0", "prefer" to "1",
                "core_ctl" to "absent", "ddr_boost" to "false"
            )
        )
        val manager = SceneSnapshotManager(CommandValueReader { "inactive|300000|1200000|absent" })

        val rollback = manager.attachRestore(listOf(command), manager.capture(listOf(command))).single().rollback!!

        assertEquals("300000", rollback.arguments["min_khz"])
        assertEquals("1200000", rollback.arguments["max_khz"])
        assertEquals("500000", rollback.arguments["expected_min_khz"])
        assertEquals("1800000", rollback.arguments["expected_max_khz"])
    }
}
