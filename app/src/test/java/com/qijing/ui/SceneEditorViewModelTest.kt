package com.qijing.ui

import androidx.lifecycle.SavedStateHandle
import com.qijing.core.model.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneEditorViewModelTest {
    @Test fun `draft survives recreation while preparation is not part of editor state`() {
        val handle = SavedStateHandle()
        val first = SceneEditorViewModel(handle)
        first.selectApp(AppEntry("com.example.game", "示例游戏", "1.0", false, true))
        first.draft = first.draft.copy(name = "游戏场景草稿", swappiness = "80", priority = 70)
        first.selectedIntent = "custom"

        val restored = SceneEditorViewModel(handle)

        assertTrue(restored.editorOpen)
        assertEquals("com.example.game", restored.targetApp?.packageName)
        assertEquals("游戏场景草稿", restored.draft.name)
        assertEquals("80", restored.draft.swappiness)
        assertEquals(70, restored.draft.priority)
        assertEquals("custom", restored.selectedIntent)
    }

    @Test fun `changing trigger app preserves tuning intent instead of discarding draft`() {
        val editor = SceneEditorViewModel(SavedStateHandle())
        editor.selectApp(AppEntry("a", "应用 A", "", false))
        editor.draft = editor.draft.copy(swappiness = "60")

        editor.selectApp(AppEntry("b", "应用 B", "", false))

        assertEquals(setOf("b"), editor.draft.packages)
        assertEquals("60", editor.draft.swappiness)
        editor.closeEditor()
        assertFalse(editor.editorOpen)
        assertTrue(editor.hasRecoverableDraft)
    }
}
