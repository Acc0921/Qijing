package com.qijing

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qijing.core.data.SharedPreferencesNewDataStore
import com.qijing.core.execution.BackendPreference
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.scene.SceneJournalLoad
import com.qijing.core.scene.SceneServicePhase
import com.qijing.core.scene.SceneServiceStateStore
import com.qijing.core.scene.SceneTaskPhase
import com.qijing.core.scene.SceneTriggerService
import com.qijing.core.scene.SharedPreferencesSceneTaskEventStore
import com.qijing.core.scene.SharedPreferencesSceneTransactionJournalStore
import com.qijing.core.scene.UsageStatsForegroundAppSource
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneAutomationEndToEndDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun prepare() {
        forceStopService()
        clearPreferences()
        runShell("appops set ${context.packageName} GET_USAGE_STATS allow")
        assertTrue(UsageStatsForegroundAppSource(context).accessState().granted)
        BackendPreference(context).select(ExecutionBackend.DRY_RUN)
    }

    @After
    fun cleanup() {
        requestOrderlyStop()
        waitUntil(5_000L) { SceneServiceStateStore(context).current().phase == SceneServicePhase.STOPPED }
        forceStopService()
        runShell("appops set ${context.packageName} GET_USAGE_STATS default")
        clearPreferences()
    }

    @Test
    fun enabledSceneIsMatchedPreviewedAndStoppedWithoutARealWriteJournal() {
        val scene = SceneProfile(
            id = SCENE_ID,
            name = "Settings preview acceptance",
            packageNames = setOf(SETTINGS_PACKAGE),
            memory = MemoryIntent(swappiness = 80),
            priority = 80,
            enabled = true
        )
        SharedPreferencesNewDataStore(context).saveScene(scene)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ContextCompat.startForegroundService(context, Intent(context, SceneTriggerService::class.java))
            assertTrue("Automation service did not enter RUNNING", waitUntil(8_000L) {
                SceneServiceStateStore(context).current().phase == SceneServicePhase.RUNNING
            })

            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val matched = waitUntil(10_000L) {
                SharedPreferencesSceneTaskEventStore(context).recent(100).any {
                    it.sceneId == SCENE_ID && it.phase == SceneTaskPhase.PREVIEWED
                }
            }
            assertTrue("The foreground Settings app did not produce a previewed scene", matched)

            val events = SharedPreferencesSceneTaskEventStore(context).recent(100).filter { it.sceneId == SCENE_ID }
            assertTrue(events.any { it.phase == SceneTaskPhase.MATCHED })
            assertTrue(events.any { it.phase == SceneTaskPhase.PREFLIGHT })
            assertTrue(events.any { it.phase == SceneTaskPhase.PREVIEWED })
            assertTrue(events.none { it.phase == SceneTaskPhase.ACTIVE })
            assertTrue(events.none { it.phase == SceneTaskPhase.RECOVERY_REQUIRED })
            assertTrue(SharedPreferencesSceneTransactionJournalStore(context).load() is SceneJournalLoad.None)

            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            requestOrderlyStop()
            assertTrue("Automation service did not stop cleanly", waitUntil(8_000L) {
                SceneServiceStateStore(context).current().phase == SceneServicePhase.STOPPED
            })
            assertTrue(SharedPreferencesSceneTransactionJournalStore(context).load() is SceneJournalLoad.None)
        }
    }

    private fun requestOrderlyStop() {
        if (SceneServiceStateStore(context).current().phase == SceneServicePhase.STOPPED) return
        context.startService(
            Intent(context, SceneTriggerService::class.java).setAction(SceneTriggerService.ACTION_STOP)
        )
    }

    private fun forceStopService() {
        context.stopService(Intent(context, SceneTriggerService::class.java))
        SceneServiceStateStore(context).markStopped("test cleanup")
    }

    private fun clearPreferences() {
        listOf(
            "qijing_data_v1",
            "qijing_scene_task_events_v1",
            "qijing_scene_service_v1",
            "qijing_scene_transaction_v1",
            "qijing_backend_v1"
        ).forEach { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun runShell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(100L)
        }
        return predicate()
    }

    private companion object {
        const val SCENE_ID = "emulator-e2e-settings"
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
