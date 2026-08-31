package com.scenepilot.core.scene

import com.scenepilot.core.execution.TransactionResult
import com.scenepilot.core.model.SceneProfile

interface ForegroundAppSource { fun currentPackageName(): String? }

/** Coordinates a foreground event with the pure selector and transactional engine. */
class SceneActivationCoordinator(private val selector: SceneSelector, private val engine: SceneEngine) {
    suspend fun onForeground(packageName: String, scenes: Iterable<SceneProfile>): TransactionResult? {
        val selected = selector.select(scenes, SceneTriggerEvent.ForegroundApp(packageName)).scene ?: return null
        return engine.apply(selected)
    }
}
