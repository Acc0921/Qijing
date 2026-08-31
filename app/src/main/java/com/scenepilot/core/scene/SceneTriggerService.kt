package com.scenepilot.core.scene

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.scenepilot.core.data.SharedPreferencesNewDataStore
import com.scenepilot.core.execution.DryRunExecutionBroker
import com.scenepilot.core.logging.SharedPreferencesTaskLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Opt-in service host for the polling loop. It does not start itself. */
@Suppress("DEPRECATION")
class SceneTriggerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var polling: ScenePollingLoop? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        val store = SharedPreferencesNewDataStore(this)
        val coordinator = SceneActivationCoordinator(SceneSelector(), SceneEngine(DryRunExecutionBroker(), SharedPreferencesTaskLogStore(this)))
        val source = UsageStatsForegroundAppSource(this)
        if (!source.accessState().granted) return
        polling = ScenePollingLoop(source) { packageName ->
            scope.launch { coordinator.onForeground(packageName, store.scenes()) }
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        polling?.stop(); polling = null; scope.cancel(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "场景轮询", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("帧域场景轮询").setContentText("仅在服务启动后监控前台应用")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    }

    companion object { private const val CHANNEL_ID = "scene_trigger"; private const val NOTIFICATION_ID = 1001 }
}
