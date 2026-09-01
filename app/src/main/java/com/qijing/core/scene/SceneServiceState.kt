package com.qijing.core.scene

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.qijing.core.model.ExecutionBackend
import java.io.Closeable

enum class SceneServicePhase { STOPPED, RUNNING, STOPPING, RECOVERY_REQUIRED }

data class SceneServiceSnapshot(
    val phase: SceneServicePhase,
    val backend: ExecutionBackend? = null,
    val detail: String = "",
    val updatedAtMs: Long = 0L
) {
    val locksBackend: Boolean get() = phase != SceneServicePhase.STOPPED
}

/** Persistent, fail-closed source of truth shared by the service and its controls. */
class SceneServiceStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): SceneServiceSnapshot {
        val phase = prefs.getString(KEY_PHASE, null)
            ?.let { runCatching { SceneServicePhase.valueOf(it) }.getOrNull() }
            ?: SceneServicePhase.STOPPED
        val backend = prefs.getString(KEY_BACKEND, null)
            ?.let { runCatching { ExecutionBackend.valueOf(it) }.getOrNull() }
        return SceneServiceSnapshot(
            phase = phase,
            backend = backend,
            detail = prefs.getString(KEY_DETAIL, "").orEmpty(),
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    /** Reconciles persisted UI state with the service heartbeat after process death. */
    fun calibratedCurrent(nowMs: Long = System.currentTimeMillis()): SceneServiceSnapshot {
        val current = current()
        val calibrated = SceneServiceStatePolicy.staleState(current, nowMs)
        if (calibrated != current) write(calibrated.phase, calibrated.backend, calibrated.detail)
        return calibrated
    }

    fun markRunning(backend: ExecutionBackend, detail: String = "自动化正在观察前台应用") =
        write(SceneServicePhase.RUNNING, backend, detail)

    fun markStopping(backend: ExecutionBackend?, detail: String = "正在停止并恢复已改变的能力") =
        write(SceneServicePhase.STOPPING, backend, detail)

    fun markStopped(detail: String = "自动化已停止，当前没有待恢复任务") =
        write(SceneServicePhase.STOPPED, null, detail)

    fun markRecoveryRequired(backend: ExecutionBackend?, detail: String) =
        write(SceneServicePhase.RECOVERY_REQUIRED, backend, detail)

    fun observe(observer: (SceneServiceSnapshot) -> Unit): Closeable {
        val mainHandler = Handler(Looper.getMainLooper())
        fun publish() {
            val snapshot = current()
            if (Looper.myLooper() == Looper.getMainLooper()) observer(snapshot)
            else mainHandler.post { observer(snapshot) }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in OBSERVED_KEYS) publish()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        publish()
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun write(phase: SceneServicePhase, backend: ExecutionBackend?, detail: String): Boolean =
        prefs.edit()
            .putString(KEY_PHASE, phase.name)
            .putString(KEY_BACKEND, backend?.name)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()

    private companion object {
        const val PREFS = "qijing_scene_service_v1"
        const val KEY_PHASE = "phase"
        const val KEY_BACKEND = "backend"
        const val KEY_DETAIL = "detail"
        const val KEY_UPDATED_AT = "updated_at"
        val OBSERVED_KEYS = setOf(KEY_PHASE, KEY_BACKEND, KEY_DETAIL, KEY_UPDATED_AT)
    }
}

object SceneServiceStatePolicy {
    fun canSwitchBackend(snapshot: SceneServiceSnapshot): Boolean = !snapshot.locksBackend

    fun interruptedState(previous: SceneServiceSnapshot): SceneServiceSnapshot = when {
        previous.phase !in setOf(SceneServicePhase.RUNNING, SceneServicePhase.STOPPING) -> previous
        previous.backend == ExecutionBackend.DRY_RUN -> SceneServiceSnapshot(
            SceneServicePhase.STOPPED,
            detail = "上次预览服务已中断；预览没有修改系统"
        )
        else -> SceneServiceSnapshot(
            SceneServicePhase.RECOVERY_REQUIRED,
            backend = previous.backend,
            detail = "服务在恢复完成前中断，无法确认系统是否已恢复；真实执行已锁定"
        )
    }

    fun staleState(
        previous: SceneServiceSnapshot,
        nowMs: Long,
        timeoutMs: Long = 90_000L
    ): SceneServiceSnapshot = when {
        previous.phase != SceneServicePhase.RUNNING -> previous
        previous.updatedAtMs <= 0L || nowMs - previous.updatedAtMs <= timeoutMs -> previous
        previous.backend == ExecutionBackend.DRY_RUN -> SceneServiceSnapshot(
            phase = SceneServicePhase.STOPPED,
            detail = "自动化心跳已中断；预览没有修改系统",
            updatedAtMs = nowMs
        )
        else -> SceneServiceSnapshot(
            phase = SceneServicePhase.RECOVERY_REQUIRED,
            backend = previous.backend,
            detail = "自动化心跳已中断，无法确认系统是否已恢复；真实执行已锁定",
            updatedAtMs = nowMs
        )
    }
}
