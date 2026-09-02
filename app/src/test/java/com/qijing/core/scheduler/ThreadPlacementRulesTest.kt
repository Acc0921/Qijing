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
}
