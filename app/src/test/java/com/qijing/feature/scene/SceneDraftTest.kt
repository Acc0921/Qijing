package com.qijing.feature.scene

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.data.InMemoryNewDataStore
import com.qijing.core.execution.DryRunExecutionBroker
import com.qijing.core.logging.InMemoryTaskLogStore
import com.qijing.core.scene.SceneEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SceneDraftTest {
    @Test fun `blank name and invalid swappiness are rejected`() {
        val errors = SceneDraft("id", "", swappiness = "300").validate()
        assertTrue(errors.size >= 2)
    }

    @Test fun `valid draft preserves bound packages`() {
        val profile = SceneDraft("id", "游戏", packages = setOf("com.demo.game"), swappiness = "60", priority = 70).toProfile()
        assertTrue("com.demo.game" in profile.packageNames)
        assertEquals(70, profile.priority)
        assertFalse(profile.enabled)
    }

    @Test fun `profile can be loaded back into an editable draft`() {
        val profile = SceneProfile(
            "id", "游戏", setOf("com.demo.game"),
            memory = MemoryIntent(zramEnabled = true, zramSizeBytes = 256L * 1024 * 1024, compressionAlgorithm = "lz4", swappiness = 80),
            priority = 80,
            enabled = false
        )
        val draft = SceneDraft.fromProfile(profile)
        assertEquals("游戏", draft.name)
        assertEquals(setOf("com.demo.game"), draft.packages)
        assertEquals("256", draft.zramSizeMiB)
        assertEquals("80", draft.swappiness)
        assertEquals("lz4", draft.compressionAlgorithm)
        assertEquals(80, draft.priority)
        assertFalse(draft.enabled)
    }

    @Test fun `ordinary save cannot silently enable a scene`() {
        val data = InMemoryNewDataStore()
        val store = SceneDraftStore(data)

        store.save(SceneDraft("id", "游戏", setOf("com.demo.game"), governor = "schedutil", enabled = true))

        assertFalse(data.scenes().single().enabled)
    }

    @Test fun `approval is rejected after draft changes`() = runBlocking {
        val data = InMemoryNewDataStore()
        val store = SceneDraftStore(data)
        val draft = SceneDraft("id", "游戏", setOf("com.demo.game"), governor = "schedutil")
        val preparation = SceneEngine(DryRunExecutionBroker(), InMemoryTaskLogStore()).prepare(draft.toProfile())

        val errors = store.enableApproved(draft.copy(governor = "performance"), preparation, ExecutionBackend.DRY_RUN)

        assertTrue(errors.any { it.contains("重新预演") })
        assertTrue(data.scenes().isEmpty())
    }

    @Test fun `matching approved rehearsal can enable a scene`() = runBlocking {
        val data = InMemoryNewDataStore()
        val store = SceneDraftStore(data)
        val draft = SceneDraft("id", "游戏", setOf("com.demo.game"), governor = "schedutil")
        val preparation = SceneEngine(DryRunExecutionBroker(), InMemoryTaskLogStore()).prepare(draft.toProfile())

        assertTrue(store.enableApproved(draft, preparation, ExecutionBackend.DRY_RUN).isEmpty())
        assertTrue(data.scenes().single().enabled)
    }
}
