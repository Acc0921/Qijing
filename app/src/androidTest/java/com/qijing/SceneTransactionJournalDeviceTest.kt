package com.qijing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionBackendProvider
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scene.SceneJournalLoad
import com.qijing.core.scene.SceneJournalPhase
import com.qijing.core.scene.SceneJournalRecord
import com.qijing.core.scene.SceneJournalRecovery
import com.qijing.core.scene.SceneTransactionJournal
import com.qijing.core.scene.SharedPreferencesSceneTransactionJournalStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneTransactionJournalDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clearBefore() = clearPrefs()
    @After fun clearAfter() = clearPrefs()

    @Test fun persistedLegacyWriteStartedJournalLoadsAndLocksAutomaticRecovery() = runBlocking {
        val first = SharedPreferencesSceneTransactionJournalStore(context)
        val journal = SceneTransactionJournal(
            transactionId = "device-tx",
            sceneId = "scene",
            sceneName = "Scene",
            packageName = "com.demo",
            backend = ExecutionBackend.ROOT,
            records = listOf(
                SceneJournalRecord(
                    capability = "memory.swappiness.set",
                    rollback = CapabilityCommand("memory.swappiness.set.restore", mapOf("value" to "60")),
                    phase = SceneJournalPhase.WRITE_STARTED
                )
            ),
            createdAtMs = 1L
        )
        assertTrue(first.save(journal))

        val second = SharedPreferencesSceneTransactionJournalStore(context)
        val calls = mutableListOf<String>()
        val result = SceneJournalRecovery(second, object : ExecutionBroker, ExecutionBackendProvider {
            override val executionBackend = ExecutionBackend.ROOT
            override suspend fun execute(command: CapabilityCommand): ExecutionResult {
                calls += command.capability
                return ExecutionResult.Applied(ExecutionBackend.ROOT)
            }
        }).recoverPending()

        assertEquals("JOURNAL_WRITE_STATE_UNVERIFIED", (result.failure as ExecutionResult.Failed).code)
        assertTrue(calls.isEmpty())
        assertTrue(second.load() is SceneJournalLoad.Loaded)
    }

    @Test fun corruptJournalFailsClosed() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, "not-json").commit()
        assertTrue(SharedPreferencesSceneTransactionJournalStore(context).load() is SceneJournalLoad.Corrupt)
    }

    private fun clearPrefs() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val PREFS = "qijing_scene_transaction_v1"
        const val KEY = "active_journal"
    }
}
