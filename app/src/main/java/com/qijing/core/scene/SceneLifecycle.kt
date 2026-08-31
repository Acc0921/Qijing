package com.qijing.core.scene

import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.TransactionResult
import com.qijing.core.model.SceneProfile

/** The successful transaction retained for a scene until it is restored. */
data class ActiveScene(
    val scene: SceneProfile,
    val transaction: TransactionResult
)

data class SceneRestoreResult(
    val attemptedCommands: Int,
    val restoredCommands: Int,
    val failure: ExecutionResult? = null
) {
    val succeeded: Boolean get() = failure == null
}

fun interface SceneRestoreExecutor {
    suspend fun restore(activeScene: ActiveScene): SceneRestoreResult

    companion object {
        val Preview = SceneRestoreExecutor { active ->
            SceneRestoreResult(active.transaction.applied.size, active.transaction.applied.size)
        }

        /** Explicitly fails until the host wires a backend capable of executing restore commands. */
        val Unavailable = SceneRestoreExecutor { active ->
            if (active.transaction.applied.isEmpty()) {
                SceneRestoreResult(attemptedCommands = 0, restoredCommands = 0)
            } else {
                SceneRestoreResult(
                    attemptedCommands = active.transaction.applied.size,
                    restoredCommands = 0,
                    failure = ExecutionResult.Failed(
                        code = "scene_restore_unavailable",
                        message = "No scene restore executor is configured"
                    )
                )
            }
        }
    }
}

/** Restores only commands confirmed as applied, in reverse application order. */
class BrokerSceneRestoreExecutor(private val broker: ExecutionBroker) : SceneRestoreExecutor {
    override suspend fun restore(activeScene: ActiveScene): SceneRestoreResult {
        val appliedCommands = activeScene.transaction.plan.commands.take(activeScene.transaction.applied.size)
        var restored = 0
        appliedCommands.asReversed().forEach { command ->
            val rollback = command.rollback ?: return SceneRestoreResult(
                attemptedCommands = appliedCommands.size,
                restoredCommands = restored,
                failure = ExecutionResult.Failed(
                    code = "scene_restore_missing_command",
                    message = "No restore command for ${command.capability}"
                )
            )
            when (val result = broker.execute(rollback)) {
                is ExecutionResult.Applied -> restored += 1
                else -> return SceneRestoreResult(appliedCommands.size, restored, result)
            }
        }
        return SceneRestoreResult(appliedCommands.size, restored)
    }
}

sealed interface SceneLifecycleResult {
    data class Idle(val packageName: String) : SceneLifecycleResult
    data class Activated(val active: ActiveScene) : SceneLifecycleResult
    data class ActivationFailed(val scene: SceneProfile, val transaction: TransactionResult) : SceneLifecycleResult
    data class AlreadyActive(val active: ActiveScene) : SceneLifecycleResult
    data class Restored(val previous: ActiveScene, val restore: SceneRestoreResult) : SceneLifecycleResult
    data class RestoreFailed(
        val previous: ActiveScene,
        val requested: SceneProfile?,
        val restore: SceneRestoreResult
    ) : SceneLifecycleResult
    data class Switched(
        val previous: ActiveScene,
        val active: ActiveScene,
        val restore: SceneRestoreResult
    ) : SceneLifecycleResult
    data class SwitchActivationFailed(
        val previous: ActiveScene,
        val requested: SceneProfile,
        val restore: SceneRestoreResult,
        val transaction: TransactionResult
    ) : SceneLifecycleResult
}
