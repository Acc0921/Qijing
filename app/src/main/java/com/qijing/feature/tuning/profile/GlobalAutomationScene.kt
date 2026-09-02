package com.qijing.feature.tuning.profile

import android.content.Context
import com.qijing.core.device.observation.CpuObservation
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.scheduler.SchedulerProviderId

sealed interface GlobalAutomationSceneResolution {
    data class Ready(val scene: SceneProfile) : GlobalAutomationSceneResolution
    data class Blocked(val reason: String) : GlobalAutomationSceneResolution
}

/** Builds the lowest-priority foreground fallback used when no explicit scene matches. */
class GlobalAutomationSceneFactory {
    fun resolve(
        configuration: GlobalTuningConfiguration,
        packageName: String,
        cpu: CpuObservation?
    ): GlobalAutomationSceneResolution {
        if (!PACKAGE_NAME.matches(packageName)) {
            return GlobalAutomationSceneResolution.Blocked("前台应用标识无效")
        }
        if (!configuration.selectionKnown) {
            return GlobalAutomationSceneResolution.Blocked("全局模式状态未知，请重新选择并预演")
        }
        configuration.validationError()?.let { return GlobalAutomationSceneResolution.Blocked(it) }
        val target = if (configuration.provider == SchedulerProviderId.SYSTEM) {
            val observation = cpu
                ?: return GlobalAutomationSceneResolution.Blocked("缺少 CPU 能力快照，无法生成全局调节")
            when (val resolution = GlobalTuningResolver().resolve(configuration, observation)) {
                is GlobalTuningResolution.Blocked -> return GlobalAutomationSceneResolution.Blocked(resolution.reason)
                is GlobalTuningResolution.Ready -> resolution.target
            }
        } else {
            val selected = configuration.selected as? TuningProfileReference.BuiltIn
                ?: return GlobalAutomationSceneResolution.Blocked("配置调度引擎只接受四档全局模式")
            ResolvedGlobalTuning(
                provider = configuration.provider,
                mode = selected.mode,
                cpu = CpuIntent(),
                memory = MemoryIntent(),
                label = selected.mode.displayName()
            )
        }
        return GlobalAutomationSceneResolution.Ready(
            SceneProfile(
                id = "global-default:$packageName",
                name = "全局模式 · ${target.label}",
                packageNames = setOf(packageName),
                cpu = target.cpu,
                memory = target.memory,
                priority = Int.MIN_VALUE,
                enabled = true,
                schedulerProvider = target.provider,
                schedulerMode = target.mode,
                followsGlobalProfile = true
            )
        )
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}

/**
 * A saved global intent is executable by automation only after that exact revision was previewed
 * or verified with the same backend. Changing mode/provider or backend invalidates the approval.
 */
class GlobalAutomationApprovalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isApproved(configuration: GlobalTuningConfiguration, backend: ExecutionBackend): Boolean = synchronized(LOCK) {
        prefs.getLong(KEY_REVISION, -1L) == configuration.revision &&
            prefs.getString(KEY_SELECTED, null) == configuration.selected.stableId &&
            prefs.getString(KEY_PROVIDER, null) == configuration.provider.name &&
            prefs.getString(KEY_BACKEND, null) == backend.name
    }

    fun approve(configuration: GlobalTuningConfiguration, backend: ExecutionBackend): Boolean = synchronized(LOCK) {
        prefs.edit()
            .putLong(KEY_REVISION, configuration.revision)
            .putString(KEY_SELECTED, configuration.selected.stableId)
            .putString(KEY_PROVIDER, configuration.provider.name)
            .putString(KEY_BACKEND, backend.name)
            .commit()
    }

    fun clear(): Boolean = synchronized(LOCK) { prefs.edit().clear().commit() }

    private companion object {
        const val PREFS = "qijing_global_automation_approval_v1"
        const val KEY_REVISION = "revision"
        const val KEY_SELECTED = "selected"
        const val KEY_PROVIDER = "provider"
        const val KEY_BACKEND = "backend"
        val LOCK = Any()
    }
}
