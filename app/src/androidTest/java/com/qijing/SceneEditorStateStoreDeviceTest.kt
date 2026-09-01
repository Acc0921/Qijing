package com.qijing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qijing.core.model.AppEntry
import com.qijing.feature.scene.SceneDraft
import com.qijing.feature.scene.SceneEditorState
import com.qijing.feature.scene.SharedPreferencesSceneEditorStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneEditorStateStoreDeviceTest {
    @Test
    fun unfinishedDraftRoundTripsAndCanBeDiscarded() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SharedPreferencesSceneEditorStateStore(context)
        store.clear()
        store.save(
            SceneEditorState(
                app = AppEntry("com.example.reader", "阅读器", "2.0", false, true),
                draft = SceneDraft(
                    id = "scene-device-test",
                    name = "真机草稿",
                    packages = setOf("com.example.reader"),
                    swappiness = "70",
                    priority = 65,
                    enabled = false
                ),
                selectedIntent = "custom",
                editorOpen = false
            )
        )

        val restored = store.load()!!
        assertEquals("com.example.reader", restored.app.packageName)
        assertEquals("真机草稿", restored.draft.name)
        assertEquals("70", restored.draft.swappiness)
        assertEquals(65, restored.draft.priority)
        assertEquals("custom", restored.selectedIntent)
        assertFalse(restored.editorOpen)

        store.clear()
        assertNull(store.load())
    }
}
