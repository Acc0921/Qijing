package com.qijing.core.scene

import com.qijing.core.model.SceneProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface ForegroundAppSource { fun currentPackageName(): String? }

/** Owns the apply/deduplicate/restore lifecycle for foreground-triggered scenes. */
class SceneActivationCoordinator(
    private val selector: SceneSelector,
    private val engine: SceneEngine,
    private val restoreExecutor: SceneRestoreExecutor = SceneRestoreExecutor.Unavailable
) {
    private val transitionMutex = Mutex()
    @Volatile private var current: ActiveScene? = null

    val activeScene: ActiveScene? get() = current

    suspend fun poll(source: ForegroundAppSource, scenes: Iterable<SceneProfile>): SceneLifecycleResult? =
        source.currentPackageName()?.let { onForeground(it, scenes) }

    suspend fun onForeground(packageName: String, scenes: Iterable<SceneProfile>): SceneLifecycleResult =
        transitionMutex.withLock {
            val selected = selector.select(scenes, SceneTriggerEvent.ForegroundApp(packageName)).scene
            val previous = current

            if (selected == null) return@withLock restoreOrIdle(packageName, previous)
            if (previous?.scene?.id == selected.id) return@withLock SceneLifecycleResult.AlreadyActive(previous)

            if (previous != null) {
                val restore = restoreExecutor.restore(previous)
                if (!restore.succeeded) {
                    return@withLock SceneLifecycleResult.RestoreFailed(previous, selected, restore)
                }
                current = null
                val transaction = engine.apply(selected)
                if (transaction.failure != null) {
                    return@withLock SceneLifecycleResult.SwitchActivationFailed(previous, selected, restore, transaction)
                }
                val active = ActiveScene(selected, transaction)
                current = active
                return@withLock SceneLifecycleResult.Switched(previous, active, restore)
            }

            val transaction = engine.apply(selected)
            if (transaction.failure != null) {
                return@withLock SceneLifecycleResult.ActivationFailed(selected, transaction)
            }
            val active = ActiveScene(selected, transaction)
            current = active
            SceneLifecycleResult.Activated(active)
        }

    suspend fun restoreActive(reason: String = "manual-stop"): SceneLifecycleResult =
        transitionMutex.withLock { restoreOrIdle(reason, current) }

    private suspend fun restoreOrIdle(packageName: String, previous: ActiveScene?): SceneLifecycleResult {
        if (previous == null) return SceneLifecycleResult.Idle(packageName)
        val restore = restoreExecutor.restore(previous)
        if (!restore.succeeded) return SceneLifecycleResult.RestoreFailed(previous, null, restore)
        current = null
        return SceneLifecycleResult.Restored(previous, restore)
    }
}
