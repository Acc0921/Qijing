package com.scenepilot.feature.scene

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import com.scenepilot.core.model.MemoryIntent
import com.scenepilot.core.model.SceneProfile
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

    @Test fun `profile can be loaded back into an editable draft`() {
        val profile = SceneProfile(
            "id", "游戏", setOf("com.demo.game"),
            memory = MemoryIntent(zramEnabled = true, zramSizeBytes = 256L * 1024 * 1024, swappiness = 80),
            enabled = false
        )
        val draft = SceneDraft.fromProfile(profile)
        assertEquals("游戏", draft.name)
        assertEquals(setOf("com.demo.game"), draft.packages)
        assertEquals("256", draft.zramSizeMiB)
        assertEquals("80", draft.swappiness)
    }
}
