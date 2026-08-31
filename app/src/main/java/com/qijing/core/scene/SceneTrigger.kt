package com.qijing.core.scene

import com.qijing.core.model.SceneProfile

sealed interface SceneTriggerEvent {
    data class ForegroundApp(val packageName: String) : SceneTriggerEvent
}

data class SceneSelection(val scene: SceneProfile?, val reason: String)

/** Pure decision layer; applying the selected scene is handled by SceneEngine. */
class SceneSelector {
    fun select(scenes: Iterable<SceneProfile>, event: SceneTriggerEvent): SceneSelection {
        val candidates = when (event) {
            is SceneTriggerEvent.ForegroundApp -> scenes.filter { it.enabled && event.packageName in it.packageNames }
        }.sortedWith(compareByDescending<SceneProfile> { it.priority }.thenBy { it.id })
        val highestPriority = candidates.firstOrNull()?.priority
        if (highestPriority != null && candidates.count { it.priority == highestPriority } > 1) {
            return SceneSelection(null, "最高优先级存在冲突，已阻止自动执行")
        }
        return candidates.firstOrNull()?.let { SceneSelection(it, "匹配前台应用 ${event.packageName}") }
            ?: SceneSelection(null, "没有匹配的启用场景")
    }
}
