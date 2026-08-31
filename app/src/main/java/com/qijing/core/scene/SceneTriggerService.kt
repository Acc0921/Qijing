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
import com.qijing.core.model.ExecutionBackend
import com.qijing.feature.tuning.SysfsCapabilityValueReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Opt-in service host for the polling loop. It does not start itself. */
@Suppress("DEPRECATION")
class SceneTriggerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var polling: ScenePollingLoop? = null
    private var coordinator: SceneActivationCoordinator? = null
    private var runtime: BackendRuntime? = null

    override fun onCreate() {
        super.onCreate()
        val selectedBackend = BackendPreference(this).selected()
        startForeground(NOTIFICATION_ID, notification(selectedBackend))
        val store = SharedPreferencesNewDataStore(this)
        val selectedRuntime = BackendRuntimeFactory.create(this, selectedBackend).also { runtime = it }
        val snapshots = if (selectedBackend == ExecutionBackend.DRY_RUN) null else SceneSnapshotManager(SysfsCapabilityValueReader())
        val engine = SceneEngine(selectedRuntime.broker, SharedPreferencesTaskLogStore(this), snapshots)
        val sceneCoordinator = SceneActivationCoordinator(
            SceneSelector(),
            engine,
            if (selectedBackend == ExecutionBackend.DRY_RUN) SceneRestoreExecutor.Preview else BrokerSceneRestoreExecutor(selectedRuntime.broker)
        ).also { coordinator = it }
        val source = UsageStatsForegroundAppSource(this)
        if (!source.accessState().granted) return
        polling = ScenePollingLoop(
            source = source,
            onSourceUnavailable = { scope.launch { sceneCoordinator.restoreActive("foreground-source-unavailable") } }
        ) { packageName ->
            scope.launch { sceneCoordinator.onForeground(packageName, store.scenes()) }
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                coordinator?.restoreActive("user-stop")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        polling?.stop()
        polling = null
        if (coordinator?.activeScene != null) {
            runBlocking(Dispatchers.IO) { withTimeoutOrNull(3_000L) { coordinator?.restoreActive("service-destroy") } }
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

    companion object {
        const val ACTION_STOP = "com.qijing.action.STOP_SCENE_SERVICE"
        private const val CHANNEL_ID = "scene_trigger"
        private const val NOTIFICATION_ID = 1001
    }
}
