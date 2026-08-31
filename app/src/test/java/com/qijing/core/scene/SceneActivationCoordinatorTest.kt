package com.qijing.core.scene

import com.qijing.core.execution.DryRunExecutionBroker
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.logging.InMemoryTaskLogStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.SceneProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneActivationCoordinatorTest {
    private val game = SceneProfile(
        "game", "Game", setOf("com.demo.game"), cpu = CpuIntent(governor = "performance")
    )
    private val reader = SceneProfile(
        "reader", "Reader", setOf("com.demo.reader"), cpu = CpuIntent(governor = "powersave")
    )

    @Test fun `entering a matching app activates and records its scene`() = runBlocking {
        val restore = RecordingRestoreExecutor()
        val coordinator = coordinator(restore)

        val result = coordinator.onForeground("com.demo.game", listOf(game))

        assertTrue(result is SceneLifecycleResult.Activated)
        assertEquals("game", coordinator.activeScene?.scene?.id)
        assertEquals(0, restore.calls.size)
    }

    @Test fun `same scene is not applied twice`() = runBlocking {
        val coordinator = coordinator(RecordingRestoreExecutor())
        coordinator.onForeground("com.demo.game", listOf(game))
        val first = coordinator.activeScene

        val result = coordinator.onForeground("com.demo.game", listOf(game))

        assertTrue(result is SceneLifecycleResult.AlreadyActive)
        assertSame(first, coordinator.activeScene)
    }

    @Test fun `leaving matched apps restores and clears active scene`() = runBlocking {
        val restore = RecordingRestoreExecutor()
        val coordinator = coordinator(restore)
        coordinator.onForeground("com.demo.game", listOf(game))

        val result = coordinator.onForeground("com.demo.home", listOf(game))

        assertTrue(result is SceneLifecycleResult.Restored)
        assertEquals(listOf("game"), restore.calls)
        assertNull(coordinator.activeScene)
    }

    @Test fun `switch restores previous scene before activating next`() = runBlocking {
        val events = mutableListOf<String>()
        val restore = RecordingRestoreExecutor(events = events)
        val broker = object : ExecutionBroker {
            override suspend fun execute(command: com.qijing.core.execution.CapabilityCommand): ExecutionResult {
                events += "apply-${command.arguments["value"]}"
                return ExecutionResult.Applied(ExecutionBackend.DRY_RUN)
            }
        }
        val engine = SceneEngine(broker, InMemoryTaskLogStore())
        val coordinator = SceneActivationCoordinator(SceneSelector(), engine, restore)
        coordinator.onForeground("com.demo.game", listOf(game, reader))
        events.clear()

        val result = coordinator.onForeground("com.demo.reader", listOf(game, reader))

        assertTrue(result is SceneLifecycleResult.Switched)
        assertEquals(listOf("restore-game", "apply-powersave"), events)
        assertEquals("reader", coordinator.activeScene?.scene?.id)
    }

    @Test fun `restore failure is explicit and prevents scene switch`() = runBlocking {
        val failure = ExecutionResult.Failed("restore_denied", "backend denied restore")
        val restore = RecordingRestoreExecutor(failure)
        val coordinator = coordinator(restore)
        coordinator.onForeground("com.demo.game", listOf(game, reader))

        val result = coordinator.onForeground("com.demo.reader", listOf(game, reader))

        assertTrue(result is SceneLifecycleResult.RestoreFailed)
        assertEquals("restore_denied", (result as SceneLifecycleResult.RestoreFailed).restore.failure.let { it as ExecutionResult.Failed }.code)
        assertEquals("game", coordinator.activeScene?.scene?.id)
    }

    private fun coordinator(restore: SceneRestoreExecutor) = SceneActivationCoordinator(
        SceneSelector(),
        SceneEngine(DryRunExecutionBroker(), InMemoryTaskLogStore()),
        restore
    )

    private class RecordingRestoreExecutor(
        private val failure: ExecutionResult? = null,
        private val events: MutableList<String>? = null
    ) : SceneRestoreExecutor {
        val calls = mutableListOf<String>()

        override suspend fun restore(activeScene: ActiveScene): SceneRestoreResult {
            calls += activeScene.scene.id
            events?.add("restore-${activeScene.scene.id}")
            return SceneRestoreResult(1, if (failure == null) 1 else 0, failure)
        }
    }
}
