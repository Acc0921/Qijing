package com.qijing.core.scene

import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionBackendProvider
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
        val Preview = SceneRestoreExecutor { _ ->
            SceneRestoreResult(attemptedCommands = 0, restoredCommands = 0)
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
class BrokerSceneRestoreExecutor(
    private val broker: ExecutionBroker,
    private val journalStore: SceneTransactionJournalStore? = null,
    private val events: SceneTaskEventStore? = null
) : SceneRestoreExecutor {
    override suspend fun restore(activeScene: ActiveScene): SceneRestoreResult {
        val appliedCommands = activeScene.transaction.plan.commands.take(activeScene.transaction.applied.size)
        record(activeScene, SceneTaskPhase.RESTORING, "开始按逆序恢复 ${appliedCommands.size} 项能力")
        val expectedBackend = activeScene.transaction.applied.firstOrNull()?.backend
        val provider = broker as? ExecutionBackendProvider
        if (appliedCommands.isNotEmpty() && (expectedBackend == null || provider?.executionBackend != expectedBackend)) {
            record(activeScene, SceneTaskPhase.RECOVERY_REQUIRED, "恢复后端身份与原事务不一致")
            return SceneRestoreResult(
                attemptedCommands = appliedCommands.size,
                restoredCommands = 0,
                failure = ExecutionResult.Failed("scene_restore_backend_mismatch", "恢复后端身份与原事务不一致")
            )
        }
        val session = journalStore?.let { SceneJournalSession.resume(it, activeScene.transaction.plan.id) }
        if (journalStore != null && session == null && appliedCommands.isNotEmpty()) {
            record(activeScene, SceneTaskPhase.RECOVERY_REQUIRED, "活动场景缺少匹配的恢复 journal")
            return SceneRestoreResult(
                attemptedCommands = appliedCommands.size,
                restoredCommands = 0,
                failure = ExecutionResult.Failed("scene_restore_journal_missing", "活动场景缺少匹配的恢复 journal")
            )
        }
        var restored = 0
        appliedCommands.withIndex().toList().asReversed().forEach { (index, command) ->
            val rollback = command.rollback ?: return SceneRestoreResult(
                attemptedCommands = appliedCommands.size,
                restoredCommands = restored,
                failure = ExecutionResult.Failed(
                    code = "scene_restore_missing_command",
                    message = "No restore command for ${command.capability}"
                )
            ).also { record(activeScene, SceneTaskPhase.RECOVERY_REQUIRED, "${command.capability} 缺少恢复命令") }
            when (val result = broker.execute(rollback)) {
                is ExecutionResult.Applied -> {
                    if (result.backend != expectedBackend) {
                        record(activeScene, SceneTaskPhase.RECOVERY_REQUIRED, "恢复结果后端与原事务不一致")
                        return SceneRestoreResult(
                            appliedCommands.size,
                            restored,
                            ExecutionResult.Failed("scene_restore_result_backend_mismatch", "恢复结果后端与原事务不一致")
                        )
                    }
                    if (session != null && !session.markRestored(index)) {
                        record(activeScene, SceneTaskPhase.RECOVERY_REQUIRED, "恢复已执行但无法持久化进度")
                        return SceneRestoreResult(
                            appliedCommands.size,
                            restored,
                            ExecutionResult.Failed("scene_restore_journal_progress", "恢复已执行但无法持久化进度")
                        )
                    }
                    restored += 1
                    record(activeScene, SceneTaskPhase.RESTORING, "${rollback.capability} 已恢复并验证")
                }
                else -> {
                    record(activeScene, SceneTaskPhase.RECOVERY_REQUIRED, result.toString())
                    return SceneRestoreResult(appliedCommands.size, restored, result)
                }
            }
        }
        if (session != null && !session.clear()) {
            record(activeScene, SceneTaskPhase.RECOVERY_REQUIRED, "恢复完成但无法清除 journal")
            return SceneRestoreResult(
                appliedCommands.size,
                restored,
                ExecutionResult.Failed("scene_restore_journal_clear", "恢复完成但无法清除 journal")
            )
        }
        record(activeScene, SceneTaskPhase.RESTORED, "已恢复 $restored/${appliedCommands.size} 项能力")
        return SceneRestoreResult(appliedCommands.size, restored)
    }

    private fun record(active: ActiveScene, phase: SceneTaskPhase, detail: String) {
        events?.append(
            SceneTaskEvent(
                taskId = active.transaction.plan.id,
                sceneId = active.scene.id,
                sceneName = active.scene.name,
                packageName = active.scene.packageNames.firstOrNull(),
                backend = active.transaction.applied.firstOrNull()?.backend,
                phase = phase,
                detail = detail
            )
        )
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
