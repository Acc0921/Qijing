package com.scenepilot.feature.scene

import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDraftTest {
    @Test fun `blank name and invalid swappiness are rejected`() {
        val errors = SceneDraft("id", "", swappiness = "300").validate()
        assertTrue(errors.size >= 2)
    }

    @Test fun `valid draft preserves bound packages`() {
        val profile = SceneDraft("id", "游戏", packages = setOf("com.demo.game"), swappiness = "60").toProfile()
        assertTrue("com.demo.game" in profile.packageNames)
    }
}
