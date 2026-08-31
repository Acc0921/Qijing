package com.qijing.core.scene

import com.qijing.core.execution.CommandPlan
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.model.SceneProfile
import com.qijing.core.model.ExecutionBackend

/**
 * Read-only result of preparing a scene transaction.
 *
 * Preparation may validate commands and read rollback values, but it never calls the broker's
 * execute function. A null [failure] is the only condition that makes this preparation [ready].
 */
data class ScenePreparation(
    val scene: SceneProfile,
    val backend: ExecutionBackend?,
    val plan: CommandPlan,
    val snapshot: SceneSnapshot?,
    val failure: ExecutionResult? = null
) {
    val ready: Boolean get() = failure == null
}
