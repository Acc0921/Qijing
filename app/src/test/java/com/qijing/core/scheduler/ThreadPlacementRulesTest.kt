package com.qijing.core.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadPlacementRulesTest {
    @Test fun `bounded parser creates deterministic exact prefix and fallback decisions`() {
        val raw = """
            [{
              "friendly": "Demo game",
              "packages": ["com.example.game"],
              "cpuset": {
                "comm": {
                  "7": ["MainThread"],
                  "4-6": ["Render*", "Worker"],
                  "6": ["RenderThread"]
                },
                "rr": ["Render*"],
                "ni": ["MainThread"],
                "other": "0-3"
              }
            }]
        """.trimIndent()

        val loaded = ThreadPlacementJsonParser().parse(raw, (0..7).toSet()) as ThreadPlacementLoad.Loaded
        val main = loaded.ruleSet.decide("com.example.game", "MainThread")!!
        val render = loaded.ruleSet.decide("com.example.game", "RenderThread")!!
        val other = loaded.ruleSet.decide("com.example.game", "Audio")!!

        assertEquals("7", main.cpuSet.canonical)
        assertTrue(main.requestNiceAdjustment)
        assertFalse(main.requestRoundRobin)
        assertEquals("6", render.cpuSet.canonical)
        assertTrue(render.requestRoundRobin)
        assertEquals("0,1,2,3", other.cpuSet.canonical)
        assertNull(other.matchedPattern)
        assertNull(loaded.ruleSet.decide("com.other.app", "MainThread"))
    }

    @Test fun `parser rejects unavailable cores duplicate packages and ambiguous matchers`() {
        val unavailableCore = """[{"friendly":"A","packages":["com.example.a"],"cpuset":{"comm":{"8":["Main"]},"other":"0-3"}}]"""
        val duplicatePackage = """[
            {"friendly":"A","packages":["com.example.same"],"cpuset":{"other":"0-3"}},
            {"friendly":"B","packages":["com.example.same"],"cpuset":{"other":"0-3"}}
        ]"""
        val duplicateMatcher = """[{"friendly":"A","packages":["com.example.a"],"cpuset":{"comm":{"4":["Main"],"5":["Main"]},"other":"0-3"}}]"""

        assertTrue(ThreadPlacementJsonParser().parse(unavailableCore, (0..7).toSet()) is ThreadPlacementLoad.Rejected)
        assertTrue(ThreadPlacementJsonParser().parse(duplicatePackage, (0..7).toSet()) is ThreadPlacementLoad.Rejected)
        assertTrue(ThreadPlacementJsonParser().parse(duplicateMatcher, (0..7).toSet()) is ThreadPlacementLoad.Rejected)
    }

    @Test fun `patterns allow only exact names or a single trailing wildcard`() {
        assertTrue(ThreadNamePattern.parse("Render*")!!.matches("RenderThread"))
        assertFalse(ThreadNamePattern.parse("Render")!!.matches("RenderThread"))
        assertNull(ThreadNamePattern.parse("*Render"))
        assertNull(ThreadNamePattern.parse("Ren*der"))
        assertNull(ThreadNamePattern.parse("*") )
    }

    @Test fun `trashy is a discoverable demotion rule with explicit comm or fallback CPU placement`() {
        val raw = """
            [{
              "friendly":"Sample game",
              "packages":["com.example.game"],
              "cpuset":{
                "comm":{"4-5":["AsyncWorker"]},
                "trashy":["AsyncWorker","Pool*"],
                "other":"0-3"
              }
            }]
        """.trimIndent()
        val loaded = ThreadPlacementJsonParser().parse(raw, (0..7).toSet()) as ThreadPlacementLoad.Loaded

        val explicit = loaded.ruleSet.decide("com.example.game", "AsyncWorker")!!
        val trashyOnly = loaded.ruleSet.decide("com.example.game", "Pool-2")!!
        val fallback = loaded.ruleSet.decide("com.example.game", "Audio")!!

        assertEquals("4,5", explicit.cpuSet.canonical)
        assertEquals(ThreadPlacementSource.COMM, explicit.placementSource)
        assertTrue(explicit.requestTrashyDemotion)
        assertEquals("AsyncWorker", explicit.trashyPattern!!.value)
        assertEquals("0,1,2,3", trashyOnly.cpuSet.canonical)
        assertEquals(ThreadPlacementSource.TRASHY, trashyOnly.placementSource)
        assertTrue(trashyOnly.requestTrashyDemotion)
        assertEquals("Pool", trashyOnly.matchedPattern!!.value)
        assertEquals(ThreadPlacementSource.FALLBACK, fallback.placementSource)
        assertFalse(fallback.requestTrashyDemotion)
        assertNull(fallback.matchedPattern)
    }

    @Test fun `main_thread and unity_main coexist only when their CPU placement agrees`() {
        val compatible = """[{
          "friendly":"Compatible","packages":["com.example.game"],
          "cpuset":{"main_thread":"7","unity_main":"7","other":"0-5"}
        }]"""
        val conflicting = """[{
          "friendly":"Conflict","packages":["com.example.game"],
          "cpuset":{"main_thread":"6","unity_main":"7","other":"0-5"}
        }]"""

        val loaded = ThreadPlacementJsonParser().parse(compatible, (0..7).toSet()) as ThreadPlacementLoad.Loaded
        val processMain = loaded.ruleSet.decide("com.example.game", "com.example.game", isProcessMainThread = true)!!
        val unityMain = loaded.ruleSet.decide("com.example.game", "UnityMain", isProcessMainThread = false)!!
        val mainNamedUnity = loaded.ruleSet.decide("com.example.game", "UnityMain", isProcessMainThread = true)!!

        assertEquals("7", processMain.cpuSet.canonical)
        assertEquals(ThreadPlacementSource.MAIN_THREAD, processMain.placementSource)
        assertEquals("7", unityMain.cpuSet.canonical)
        assertEquals(ThreadPlacementSource.UNITY_MAIN, unityMain.placementSource)
        assertEquals(ThreadPlacementSource.MAIN_THREAD, mainNamedUnity.placementSource)
        assertTrue(ThreadPlacementJsonParser().parse(conflicting, (0..7).toSet()) is ThreadPlacementLoad.Rejected)
    }

    @Test fun `trashy cannot also request boost nice or realtime scheduling`() {
        val niceConflict = """[{
          "friendly":"Conflict","packages":["com.example.game"],
          "cpuset":{"trashy":["Async*"],"ni":["AsyncWorker"],"other":"0-5"}
        }]"""
        val rrConflict = """[{
          "friendly":"Conflict","packages":["com.example.game"],
          "cpuset":{"trashy":["Pool"],"rr":["Pool"],"other":"0-5"}
        }]"""

        assertTrue(ThreadPlacementJsonParser().parse(niceConflict, (0..7).toSet()) is ThreadPlacementLoad.Rejected)
        assertTrue(ThreadPlacementJsonParser().parse(rrConflict, (0..7).toSet()) is ThreadPlacementLoad.Rejected)
    }
}
