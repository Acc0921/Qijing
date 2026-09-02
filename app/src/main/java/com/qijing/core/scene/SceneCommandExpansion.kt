package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.model.SceneProfile

sealed interface SceneCommandExpansion {
    data class Commands(val commands: List<CapabilityCommand>) : SceneCommandExpansion
    data class Blocked(val code: String, val reason: String) : SceneCommandExpansion
}

/** Adds device-pack operations to a scene without exposing shell to the scene model or UI. */
fun interface SceneCommandExpander {
    suspend fun expand(scene: SceneProfile): SceneCommandExpansion
}
