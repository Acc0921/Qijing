package com.qijing.core.scene

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.qijing.core.data.SharedPreferencesNewDataStore
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.BackendRuntime
import com.qijing.core.execution.BackendRuntimeFactory
import com.qijing.core.logging.SharedPreferencesTaskLogStore
import com.qijing.core.logging.TaskLog
import com.qijing.core.model.ExecutionBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Opt-in service host for the polling loop. It does not start itself. */
@Suppress("DEPRECATION")
class SceneTriggerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var polling: ScenePollingLoop? = null
    private var coordinator: SceneActivationCoordinator? = null
    private var runtime: BackendRuntime? = null
    private lateinit var serviceState: SceneServiceStateStore
    private lateinit var taskLogs: SharedPreferencesTaskLogStore
    private var serviceBackend: ExecutionBackend? = null
    @Volatile private var stopRequested = false
    @Volatile private var shutdownFinalized = false
    @Volatile private var initialized = false

    override fun onCreate() {
        super.onCreate()
        serviceState = SceneServiceStateStore(this)
        taskLogs = SharedPreferencesTaskLogStore(this)
        val previous = serviceState.current()
        val resolved = SceneServiceStatePolicy.interruptedState(previous)
        if (resolved.phase == SceneServicePhase.RECOVERY_REQUIRED) {
            serviceState.markRecoveryRequired(resolved.backend, resolved.detail)
            startForeground(NOTIFICATION_ID, recoveryNotification(resolved))
            stopSelf()
            return
        }
        if (previous.phase != resolved.phase) serviceState.markStopped(resolved.detail)
        val selectedBackend = BackendPreference(this).selected()
        serviceBackend = selectedBackend
        startForeground(NOTIFICATION_ID, notification(selectedBackend))
        serviceState.markRunning(selectedBackend)
        val store = SharedPreferencesNewDataStore(this)
        val selectedRuntime = BackendRuntimeFactory.create(this, selectedBackend).also { runtime = it }
        val snapshots = selectedRuntime.readCapability?.let { reader -> SceneSnapshotManager(CapabilityValueReader(reader)) }
        val engine = SceneEngine(selectedRuntime.broker, taskLogs, snapshots)
        val sceneCoordinator = SceneActivationCoordinator(
            SceneSelector(),
            engine,
            if (selectedBackend == ExecutionBackend.DRY_RUN) SceneRestoreExecutor.Preview else BrokerSceneRestoreExecutor(selectedRuntime.broker)
        ).also { coordinator = it }
        val source = UsageStatsForegroundAppSource(this)
        initialized = true
        if (!source.accessState().granted) return
        polling = ScenePollingLoop(
            source = source,
            onSourceUnavailable = {
                scope.launch {
                    val result = runCatching { sceneCoordinator.restoreActive("foreground-source-unavailable") }.getOrNull()
                    handleRuntimeResult(result, "前台应用来源失效")
                }
            }
        ) { packageName ->
            scope.launch {
                val result = runCatching { sceneCoordinator.onForeground(packageName, store.scenes()) }.getOrNull()
                handleRuntimeResult(result, "场景协调")
            }
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!initialized) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (stopRequested) return START_NOT_STICKY
            stopRequested = true
            polling?.stop()
            polling = null
            serviceState.markStopping(serviceBackend)
            scope.launch {
                val outcome = runCatching { coordinator?.restoreActive("user-stop") }.getOrNull()
                finalizeRecovery(outcome, "用户停止")
                shutdownFinalized = true
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        polling?.stop(); polling = null
        if (initialized && !shutdownFinalized) {
            serviceState.markStopping(serviceBackend, "服务正在退出并尝试恢复已改变的能力")
            val outcome = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(RESTORE_TIMEOUT_MS) { coordinator?.restoreActive("service-destroy") }
                }
            }.getOrNull()
            finalizeRecovery(outcome, "服务退出")
        }
        runtime?.close()
        runtime = null
        coordinator = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(backend: ExecutionBackend): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "场景轮询", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("栖境场景轮询").setContentText("后端：${backend.name}；仅在服务启动后监控前台应用")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    }

    private fun recoveryNotification(snapshot: SceneServiceSnapshot): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "场景轮询", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("栖境需要确认恢复状态")
            .setContentText(snapshot.detail)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
    }

    private fun finalizeRecovery(outcome: SceneLifecycleResult?, reason: String) {
        when (outcome) {
            is SceneLifecycleResult.Idle,
            is SceneLifecycleResult.Restored -> serviceState.markStopped("$reason：恢复检查完成，自动化已停止")
            else -> {
                val detail = when (outcome) {
                    is SceneLifecycleResult.RestoreFailed -> "$reason：恢复不完整，${outcome.restore.failure}"
                    null -> "$reason：恢复等待超时或没有返回结果，无法确认原值"
                    else -> "$reason：返回了非恢复终态 ${outcome::class.simpleName}，无法确认原值"
                }
                serviceState.markRecoveryRequired(serviceBackend, detail)
                taskLogs.append(TaskLog(UUID.randomUUID().toString(), "recovery-required", detail, false, System.currentTimeMillis()))
            }
        }
    }

    private fun handleRuntimeResult(outcome: SceneLifecycleResult?, reason: String) {
        if (outcome !is SceneLifecycleResult.RestoreFailed) return
        val detail = "$reason：恢复不完整，${outcome.restore.failure}；已停止新的场景协调"
        serviceState.markRecoveryRequired(serviceBackend, detail)
        taskLogs.append(TaskLog(UUID.randomUUID().toString(), "recovery-required", detail, false, System.currentTimeMillis()))
        polling?.stop()
        polling = null
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.qijing.action.STOP_SCENE_SERVICE"
        private const val CHANNEL_ID = "scene_trigger"
        private const val NOTIFICATION_ID = 1001
        private const val RESTORE_TIMEOUT_MS = 30_000L
    }
}
