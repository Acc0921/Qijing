package com.qijing.core.scheduler.thread

import com.qijing.core.scheduler.ThreadPlacementJsonParser
import com.qijing.core.scheduler.ThreadPlacementLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadRuntimeBridgeTest {
    @Test
    fun `runtime bridge emits typed low-priority command and recognizes managed state idempotently`() {
        val rules = (ThreadPlacementJsonParser().parse(
            """[{
              "friendly":"Sample game","packages":["com.example.game"],
              "cpuset":{"trashy":["Async*"],"other":"0-3"}
            }]""",
            (0..7).toSet()
        ) as ThreadPlacementLoad.Loaded).ruleSet
        val initial = "100|1000|101|1001|AsyncWorker|0-7|/top-app|0-7|0|0|0"

        val first = ThreadRuntimeBridge().parseAndPlan(
            "com.example.game", (0..7).toSet(), rules, initial, capturedAtElapsedRealtimeMs = 10
        ) as ThreadRuntimePlan.Commands

        val nice = first.commands.single { it.capability == "scheduler.thread.nice.set" }
        val cpuset = first.commands.single { it.capability == "scheduler.thread.cpuset.set" }
        assertEquals("10", nice.arguments["value"])
        assertEquals("0", nice.arguments["expected"])
        assertEquals("1000", nice.arguments["process_start"])
        assertEquals("1001", nice.arguments["start_ticks"])

        val managedGroup = cpuset.arguments.getValue("value").substringBefore('@')
        val applied = "100|1000|101|1001|AsyncWorker|0-3|$managedGroup|0-3|0|10|0"
        val second = ThreadRuntimeBridge().parseAndPlan(
            "com.example.game", (0..7).toSet(), rules, applied, capturedAtElapsedRealtimeMs = 20
        ) as ThreadRuntimePlan.Commands

        assertTrue(second.commands.isEmpty())
    }

    @Test
    fun `runtime bridge identifies process main thread using tid equal to pid`() {
        val rules = (ThreadPlacementJsonParser().parse(
            """[{
              "friendly":"Sample game","packages":["com.example.game"],
              "cpuset":{"main_thread":"7","other":"0-3"}
            }]""",
            (0..7).toSet()
        ) as ThreadPlacementLoad.Loaded).ruleSet
        val snapshot = "100|1000|100|1001|com.example.game|0-7|/top-app|0-7|0|0|0"

        val result = ThreadRuntimeBridge().parseAndPlan(
            "com.example.game", (0..7).toSet(), rules, snapshot, capturedAtElapsedRealtimeMs = 10
        ) as ThreadRuntimePlan.Commands

        val affinity = result.commands.single { it.capability == "scheduler.thread.affinity.set" }
        assertEquals("80", affinity.arguments["value"])
    }
}
