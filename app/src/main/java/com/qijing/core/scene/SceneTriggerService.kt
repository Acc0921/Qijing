package com.qijing.core.scene

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.qijing.core.data.SharedPreferencesNewDataStore
import com.qijing.core.device.observation.CpuObservationReader
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.BackendRuntime
import com.qijing.core.execution.BackendRuntimeFactory
import com.qijing.core.execution.ManagedRuntimeHealthFailure
import com.qijing.core.execution.ManagedRuntimeHealthPolicy
import com.qijing.core.logging.SharedPreferencesTaskLogStore
import com.qijing.core.logging.TaskLog
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.SceneProfile
import com.qijing.core.scheduler.profile.InstalledProfileSceneExpander
import com.qijing.feature.tuning.profile.GlobalAutomationApprovalStore
import com.qijing.feature.tuning.profile.GlobalAutomationSceneFactory
import com.qijing.feature.tuning.profile.GlobalAutomationSceneResolution
import com.qijing.feature.tuning.profile.GlobalTuningLoad
import com.qijing.feature.tuning.profile.SharedPreferencesGlobalTuningProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Opt-in service host for the polling loop. It does not start itself. */
@Suppress("DEPRECATION")
class SceneTriggerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var polling: ScenePollingLoop? = null
    private var coordinator: SceneActivationCoordinator? = null
    private var runtime: BackendRuntime? = null
    private var heartbeat: Job? = null
    private lateinit var serviceState: SceneServiceStateStore
    private lateinit var taskLogs: SharedPreferencesTaskLogStore
    private lateinit var transactionJournal: SharedPreferencesSceneTransactionJournalStore
    private lateinit var taskEvents: SharedPreferencesSceneTaskEventStore
    private var serviceBackend: ExecutionBackend? = null
    @Volatile private var stopRequested = false
    @Volatile private var shutdownFinalized = false
    @Volatile private var initialized = false
    @Volatile private var recoveryRequired = false
    @Volatile private var lastInteractive: Boolean? = null
    private val reconciliationMutex = Mutex()
    private var observedThreadPackage: String? = null
    private var observedThreadIdentities: Set<String> = emptySet()
    private var lastGlobalFallbackIssue: String? = null
    private var healthTransactionId: String? = null
    private var lastHealthCheckAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        serviceState = SceneServiceStateStore(this)
        taskLogs = SharedPreferencesTaskLogStore(this)
        transactionJournal = SharedPreferencesSceneTransactionJournalStore(this)
        taskEvents = SharedPreferencesSceneTaskEventStore(this)
        when (val pending = transactionJournal.load()) {
            SceneJournalLoad.None -> Unit
            is SceneJournalLoad.Corrupt -> {
                val detail = "恢复 journal 损坏：${pending.reason}；真实执行已锁定"
                serviceState.markRecoveryRequired(null, detail)
                startForeground(NOTIFICATION_ID, recoveryNotification(serviceState.current()))
                taskLogs.append(TaskLog(UUID.randomUUID().toString(), "recovery-required", detail, false, System.currentTimeMillis()))
                taskEvents.append(SceneTaskEvent(taskId = "journal-corrupt", sceneId = "unknown", sceneName = "未完成事务", packageName = null, backend = null, phase = SceneTaskPhase.RECOVERY_REQUIRED, detail = detail))
                stopSelf()
                return
            }
            is SceneJournalLoad.Loaded -> {
                val possiblyWritten = pending.journal.records.any {
                    it.phase == SceneJournalPhase.WRITE_STARTED || it.phase == SceneJournalPhase.APPLIED
                }
                if (possiblyWritten) recoverPersistedTransaction(pending.journal)
                else finishJournalWithoutRecovery(pending.journal)
                return
            }
        }
        val previous = serviceState.current()
        val resolved = SceneServiceStatePolicy.interruptedState(previous)
        if (resolved.phase == SceneServicePhase.RECOVERY_REQUIRED) {
            serviceState.markRecoveryRequired(resolved.backend, resolved.detail)
            taskEvents.append(
                SceneTaskEvent(
                    taskId = "interrupted-${System.currentTimeMillis()}",
                    sceneId = "unknown",
                    sceneName = "中断的场景事务",
                    packageName = null,
                    backend = resolved.backend,
                    phase = SceneTaskPhase.RECOVERY_REQUIRED,
                    detail = resolved.detail
                )
            )
            startForeground(NOTIFICATION_ID, recoveryNotification(resolved))
            stopSelf()
            return
        }
        if (previous.phase != resolved.phase) serviceState.markStopped(resolved.detail)
        val selectedBackend = BackendPreference(this).selected()
        serviceBackend = selectedBackend
        startForeground(NOTIFICATION_ID, notification(selectedBackend))
        val source = UsageStatsForegroundAppSource(this)
        if (!source.accessState().granted) {
            val detail = "未授予前台应用访问权限，自动化未启动"
            serviceState.markStopped(detail)
            taskLogs.append(TaskLog(UUID.randomUUID().toString(), "service-not-started", detail, false, System.currentTimeMillis()))
            shutdownFinalized = true
            stopSelf()
            return
        }
        serviceState.markRunning(selectedBackend)
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                serviceState.markRunning(selectedBackend)
            }
        }
        val store = SharedPreferencesNewDataStore(this)
        val globalProfileStore = SharedPreferencesGlobalTuningProfileStore(this)
        val globalApprovalStore = GlobalAutomationApprovalStore(this)
        val globalSceneFactory = GlobalAutomationSceneFactory()
        val cpuReader = CpuObservationReader()
        val selectedRuntime = BackendRuntimeFactory.create(this, selectedBackend).also { runtime = it }
        val powerManager = getSystemService(PowerManager::class.java)
        val snapshots = selectedRuntime.readCommand?.let { reader -> SceneSnapshotManager(CommandValueReader(reader)) }
            ?: selectedRuntime.readCapability?.let { reader -> SceneSnapshotManager(CapabilityValueReader(reader)) }
        val engine = SceneEngine(
            selectedRuntime.broker,
            taskLogs,
            snapshots,
            transactionJournal,
            taskEvents,
            InstalledProfileSceneExpander(
                this,
                selectedRuntime.readCommand,
                enableThreadRuntime = selectedBackend == ExecutionBackend.ROOT,
                phaseProvider = { powerManager.isInteractive.let { if (it) com.qijing.core.scheduler.profile.ProfilePhase.ACTIVE else com.qijing.core.scheduler.profile.ProfilePhase.INACTIVE } },
                executionBackend = selectedBackend
            )
        )
        val sceneCoordinator = SceneActivationCoordinator(
            SceneSelector(),
            engine,
            if (selectedBackend == ExecutionBackend.DRY_RUN) SceneRestoreExecutor.Preview
            else BrokerSceneRestoreExecutor(selectedRuntime.broker, transactionJournal, taskEvents)
        ).also { coordinator = it }
        initialized = true
        polling = ScenePollingLoop(
            source = source,
            onSourceUnavailable = {
                scope.launch {
                    try {
                        val result = sceneCoordinator.restoreActive("foreground-source-unavailable")
                        handleRuntimeResult(result, "前台应用来源失效")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        handleRuntimeException(error, "前台应用来源失效恢复")
                    }
                }
            }
        ) { packageName ->
            scope.launch {
                if (!reconciliationMutex.tryLock()) return@launch
                try {
                managedRuntimeFailure(sceneCoordinator, selectedBackend, selectedRuntime)?.let { failure ->
                    handleManagedRuntimeFailure(sceneCoordinator, failure)
                    return@launch
                }
                val interactive = powerManager.isInteractive
                val phaseChanged = lastInteractive?.let { it != interactive } == true
                lastInteractive = interactive
                if (phaseChanged) observedThreadIdentities = emptySet()
                val threadPopulationChanged = interactive && !phaseChanged && shouldReconcileThreads(
                    packageName,
                    sceneCoordinator,
                    selectedBackend,
                    selectedRuntime
                )
                if ((phaseChanged || threadPopulationChanged) && sceneCoordinator.activeScene != null) {
                    val restoreReason = if (phaseChanged) "interaction-state-change" else "thread-population-change"
                    val restore = sceneCoordinator.restoreActive(restoreReason)
                    handleRuntimeResult(restore, if (phaseChanged) "活动状态切换恢复" else "新线程纳入前恢复")
                    if (restore !is SceneLifecycleResult.Restored) return@launch
                }
                val scenes = buildList {
                    addAll(store.scenes())
                    globalFallbackScene(
                        packageName,
                        selectedBackend,
                        globalProfileStore,
                        globalApprovalStore,
                        globalSceneFactory,
                        cpuReader
                    )?.let(::add)
                }
                val result = sceneCoordinator.onForeground(packageName, scenes)
                handleRuntimeResult(result, "场景协调")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    handleRuntimeException(error, "场景协调")
                } finally {
                    reconciliationMutex.unlock()
                }
            }
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestOrderlyStop("user-stop", "用户停止")
            return START_NOT_STICKY
        }
        // A restart never blindly resumes a real write session: onCreate first resolves journal and
        // persistent RUNNING state, restoring or locking execution before polling can continue.
        return START_STICKY
    }

    override fun onDestroy() {
        polling?.stop(); polling = null
        heartbeat?.cancel(); heartbeat = null
        if (initialized && !shutdownFinalized && !recoveryRequired) {
            if (serviceBackend == ExecutionBackend.DRY_RUN) {
                serviceState.markStopped("预览服务已退出；预览没有修改系统")
            } else {
                val detail = "服务非预期退出，无法在生命周期回调内确认恢复；已锁定真实执行，重新启动后将按事务记录恢复"
                recoveryRequired = true
                serviceState.markRecoveryRequired(serviceBackend, detail)
                taskLogs.append(TaskLog(UUID.randomUUID().toString(), "recovery-required", detail, false, System.currentTimeMillis()))
                taskEvents.append(
                    SceneTaskEvent(
                        taskId = "unexpected-exit-${System.currentTimeMillis()}",
                        sceneId = "unknown",
                        sceneName = "中断的自动化任务",
                        packageName = null,
                        backend = serviceBackend,
                        phase = SceneTaskPhase.RECOVERY_REQUIRED,
                        detail = detail
                    )
                )
            }
        }
        runtime?.close()
        runtime = null
        coordinator = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        requestOrderlyStop("system-timeout", "系统限制到期")
    }

    private fun requestOrderlyStop(reason: String, label: String) {
        if (!initialized) {
            shutdownFinalized = true
            serviceState.markStopped("$label：服务尚未进入运行状态")
            stopSelf()
            return
        }
        if (stopRequested) return
        stopRequested = true
        polling?.stop()
        polling = null
        heartbeat?.cancel()
        heartbeat = null
        serviceState.markStopping(serviceBackend, "$label：正在恢复已改变的能力")
        scope.launch {
            val outcome = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                runCatching { coordinator?.restoreActive(reason) }.getOrNull()
            }
            finalizeRecovery(outcome, label)
            shutdownFinalized = true
            stopSelf()
        }
    }

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
                recoveryRequired = true
                serviceState.markRecoveryRequired(serviceBackend, detail)
                taskLogs.append(TaskLog(UUID.randomUUID().toString(), "recovery-required", detail, false, System.currentTimeMillis()))
            }
        }
    }

    private fun handleRuntimeResult(outcome: SceneLifecycleResult?, reason: String) {
        val failureDetail = when (outcome) {
            is SceneLifecycleResult.RestoreFailed -> "恢复不完整，${outcome.restore.failure}"
            is SceneLifecycleResult.ActivationFailed,
            is SceneLifecycleResult.SwitchActivationFailed -> when (val pending = transactionJournal.load()) {
                SceneJournalLoad.None -> null
                is SceneJournalLoad.Corrupt -> "事务失败且 journal 损坏：${pending.reason}"
                is SceneJournalLoad.Loaded -> "事务失败且仍有 ${pending.journal.records.count { it.phase != SceneJournalPhase.PENDING && it.phase != SceneJournalPhase.RESTORED }} 项恢复未确认"
            }
            else -> null
        } ?: return
        val detail = "$reason：$failureDetail；已停止新的场景协调"
        recoveryRequired = true
        serviceState.markRecoveryRequired(serviceBackend, detail)
        val taskId = when (outcome) {
            is SceneLifecycleResult.RestoreFailed -> outcome.previous.transaction.plan.id
            is SceneLifecycleResult.ActivationFailed -> outcome.transaction.plan.id
            is SceneLifecycleResult.SwitchActivationFailed -> outcome.transaction.plan.id
            else -> UUID.randomUUID().toString()
        }
        taskLogs.append(TaskLog(taskId, "recovery-required", detail, false, System.currentTimeMillis()))
        val scene = when (outcome) {
            is SceneLifecycleResult.RestoreFailed -> outcome.previous.scene
            is SceneLifecycleResult.ActivationFailed -> outcome.scene
            is SceneLifecycleResult.SwitchActivationFailed -> outcome.requested
            else -> null
        }
        taskEvents.append(
            SceneTaskEvent(
                taskId = taskId,
                sceneId = scene?.id ?: "unknown",
                sceneName = scene?.name ?: "未完成事务",
                packageName = scene?.packageNames?.firstOrNull(),
                backend = serviceBackend,
                phase = SceneTaskPhase.RECOVERY_REQUIRED,
                detail = detail
            )
        )
        polling?.stop()
        polling = null
        stopSelf()
    }

    private fun handleRuntimeException(error: Throwable, reason: String) {
        val detail = SceneRuntimeFailurePolicy.recoveryDetail(reason, error)
        recoveryRequired = true
        serviceState.markRecoveryRequired(serviceBackend, detail)
        val pending = transactionJournal.load()
        val journal = (pending as? SceneJournalLoad.Loaded)?.journal
        val taskId = journal?.transactionId ?: "runtime-exception-${System.currentTimeMillis()}"
        taskLogs.append(TaskLog(taskId, "recovery-required", detail, false, System.currentTimeMillis()))
        taskEvents.append(
            SceneTaskEvent(
                taskId = taskId,
                sceneId = journal?.sceneId ?: "unknown",
                sceneName = journal?.sceneName ?: "未完成事务",
                packageName = journal?.packageName,
                backend = serviceBackend,
                phase = SceneTaskPhase.RECOVERY_REQUIRED,
                detail = detail
            )
        )
        polling?.stop()
        polling = null
        heartbeat?.cancel()
        heartbeat = null
        stopSelf()
    }

    private suspend fun shouldReconcileThreads(
        packageName: String,
        coordinator: SceneActivationCoordinator,
        backend: ExecutionBackend,
        runtime: BackendRuntime
    ): Boolean {
        val active = coordinator.activeScene?.scene
        if (backend != ExecutionBackend.ROOT || active?.schedulerProvider != com.qijing.core.scheduler.SchedulerProviderId.QIJING_PROFILE) {
            observedThreadPackage = null
            observedThreadIdentities = emptySet()
            return false
        }
        if (observedThreadPackage != packageName) {
            observedThreadPackage = packageName
            observedThreadIdentities = emptySet()
        }
        val raw = runtime.readCommand?.invoke(
            com.qijing.core.execution.CapabilityCommand("scheduler.thread.snapshot", mapOf("package" to packageName))
        ) ?: return false
        val identities = raw.lineSequence().mapNotNull { line ->
            val fields = line.split('|')
            if (fields.size == 11) fields.take(4).joinToString(":") else null
        }.toSet()
        if (identities.isEmpty()) return false
        val added = observedThreadIdentities.isNotEmpty() && identities.any { it !in observedThreadIdentities }
        observedThreadIdentities = identities
        return added
    }

    private suspend fun managedRuntimeFailure(
        coordinator: SceneActivationCoordinator,
        backend: ExecutionBackend,
        runtime: BackendRuntime
    ): ManagedRuntimeHealthFailure? {
        if (backend != ExecutionBackend.ROOT) return null
        val active = coordinator.activeScene ?: run {
            healthTransactionId = null
            lastHealthCheckAtMs = 0L
            return null
        }
        val reader = runtime.readCommand ?: return ManagedRuntimeHealthFailure("托管调度", null)
        val transactionId = active.transaction.plan.id
        val now = System.currentTimeMillis()
        if (healthTransactionId == transactionId && now - lastHealthCheckAtMs < MANAGED_HEALTH_INTERVAL_MS) return null
        healthTransactionId = transactionId
        lastHealthCheckAtMs = now
        val appliedCommands = active.transaction.plan.commands.take(active.transaction.applied.size)
        return ManagedRuntimeHealthPolicy.firstFailure(appliedCommands, reader)
    }

    private suspend fun handleManagedRuntimeFailure(
        coordinator: SceneActivationCoordinator,
        failure: ManagedRuntimeHealthFailure
    ) {
        polling?.stop()
        polling = null
        heartbeat?.cancel()
        heartbeat = null
        val active = coordinator.activeScene
        val taskId = active?.transaction?.plan?.id ?: "managed-runtime-${System.currentTimeMillis()}"
        val restore = coordinator.restoreActive("managed-runtime-unhealthy")
        if (restore is SceneLifecycleResult.Restored || restore is SceneLifecycleResult.Idle) {
            val detail = "${failure.detail}；已恢复场景原值并停止自动化，请检查任务记录后再启动"
            serviceState.markStopped(detail)
            taskLogs.append(TaskLog(taskId, "managed-runtime-unhealthy", detail, false, System.currentTimeMillis()))
            taskEvents.append(
                SceneTaskEvent(
                    taskId = taskId,
                    sceneId = active?.scene?.id ?: "unknown",
                    sceneName = active?.scene?.name ?: "托管调度",
                    packageName = active?.scene?.packageNames?.firstOrNull(),
                    backend = serviceBackend,
                    phase = SceneTaskPhase.RESTORED,
                    detail = detail
                )
            )
            shutdownFinalized = true
            stopSelf()
        } else {
            handleRuntimeResult(restore, failure.detail)
        }
    }

    private fun globalFallbackScene(
        packageName: String,
        backend: ExecutionBackend,
        profileStore: SharedPreferencesGlobalTuningProfileStore,
        approvalStore: GlobalAutomationApprovalStore,
        factory: GlobalAutomationSceneFactory,
        cpuReader: CpuObservationReader
    ): SceneProfile? {
        val configuration = when (val loaded = profileStore.load()) {
            GlobalTuningLoad.None -> return null
            is GlobalTuningLoad.Corrupt -> {
                reportGlobalFallbackIssue("全局模式配置损坏：${loaded.reason}")
                return null
            }
            is GlobalTuningLoad.Loaded -> loaded.configuration
        }
        if (!approvalStore.isApproved(configuration, backend)) {
            reportGlobalFallbackIssue("全局模式尚未使用当前执行方式完成预演或写入验证；仅运行已启用的应用场景")
            return null
        }
        val cpu = if (configuration.provider == com.qijing.core.scheduler.SchedulerProviderId.SYSTEM) {
            cpuReader.read()
        } else null
        return when (val resolved = factory.resolve(configuration, packageName, cpu)) {
            is GlobalAutomationSceneResolution.Ready -> {
                lastGlobalFallbackIssue = null
                resolved.scene
            }
            is GlobalAutomationSceneResolution.Blocked -> {
                reportGlobalFallbackIssue("全局模式未参与自动化：${resolved.reason}")
                null
            }
        }
    }

    private fun reportGlobalFallbackIssue(detail: String) {
        if (lastGlobalFallbackIssue == detail) return
        lastGlobalFallbackIssue = detail
        taskLogs.append(
            TaskLog(
                UUID.randomUUID().toString(),
                "global-fallback-blocked",
                detail,
                false,
                System.currentTimeMillis()
            )
        )
    }

    private fun finishJournalWithoutRecovery(journal: SceneTransactionJournal) {
        startForeground(NOTIFICATION_ID, notification(journal.backend))
        val detail = if (transactionJournal.clear(journal.transactionId, journal.revision)) {
            "未发现已开始的系统写入，已安全清理事务记录；自动化保持停止"
        } else {
            recoveryRequired = true
            "事务尚未开始系统写入，但 journal 无法清理；真实执行已锁定"
        }
        if (recoveryRequired) {
            serviceState.markRecoveryRequired(journal.backend, detail)
            taskLogs.append(TaskLog(journal.transactionId, "recovery-required", detail, false, System.currentTimeMillis()))
            taskEvents.append(
                SceneTaskEvent(
                    taskId = journal.transactionId,
                    sceneId = journal.sceneId,
                    sceneName = journal.sceneName,
                    packageName = journal.packageName,
                    backend = journal.backend,
                    phase = SceneTaskPhase.RECOVERY_REQUIRED,
                    detail = detail
                )
            )
        } else {
            serviceState.markStopped(detail)
            taskLogs.append(TaskLog(journal.transactionId, "journal-cleared-without-write", detail, true, System.currentTimeMillis()))
        }
        shutdownFinalized = true
        stopSelf()
    }

    private fun recoverPersistedTransaction(journal: SceneTransactionJournal) {
        val recovering = SceneServiceSnapshot(
            phase = SceneServicePhase.STOPPING,
            backend = journal.backend,
            detail = "发现未完成事务，正在按持久化原值恢复",
            updatedAtMs = System.currentTimeMillis()
        )
        serviceState.markStopping(journal.backend, recovering.detail)
        startForeground(NOTIFICATION_ID, recoveryNotification(recovering))
        scope.launch(Dispatchers.IO) {
            var recoveryRuntime: BackendRuntime? = null
            var recoveryFailure = "等待超时"
            val result = try {
                recoveryRuntime = BackendRuntimeFactory.create(this@SceneTriggerService, journal.backend)
                withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                    SceneJournalRecovery(
                        transactionJournal,
                        recoveryRuntime.broker,
                        recoveryRuntime.readCommand?.let(::CommandValueReader)
                    ).recoverPending()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recoveryFailure = error.message ?: error::class.java.simpleName
                null
            } finally {
                recoveryRuntime?.close()
            }
            when {
                result?.succeeded == true -> {
                    val detail = "进程中断恢复完成：已恢复 ${result.restoredCommands} 项；自动化保持停止"
                    serviceState.markStopped(detail)
                    taskLogs.append(TaskLog(journal.transactionId, "recovered-after-restart", detail, true, System.currentTimeMillis()))
                    taskEvents.append(SceneTaskEvent(taskId = journal.transactionId, sceneId = journal.sceneId, sceneName = journal.sceneName, packageName = journal.packageName, backend = journal.backend, phase = SceneTaskPhase.RESTORED, detail = detail))
                }
                else -> {
                    val detail = "进程中断恢复不完整：${result?.failure ?: recoveryFailure}；真实执行已锁定"
                    serviceState.markRecoveryRequired(journal.backend, detail)
                    taskLogs.append(TaskLog(journal.transactionId, "recovery-required", detail, false, System.currentTimeMillis()))
                    taskEvents.append(SceneTaskEvent(taskId = journal.transactionId, sceneId = journal.sceneId, sceneName = journal.sceneName, packageName = journal.packageName, backend = journal.backend, phase = SceneTaskPhase.RECOVERY_REQUIRED, detail = detail))
                }
            }
            stopSelf()
        }
    }

    companion object {
        const val ACTION_STOP = "com.qijing.action.STOP_SCENE_SERVICE"
        private const val CHANNEL_ID = "scene_trigger"
        private const val NOTIFICATION_ID = 1001
        private const val RESTORE_TIMEOUT_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val MANAGED_HEALTH_INTERVAL_MS = 10_000L
    }
}

internal object SceneRuntimeFailurePolicy {
    fun recoveryDetail(reason: String, error: Throwable): String {
        val safeReason = reason.trim().take(80).ifBlank { "自动化运行" }
        val safeError = (error.message ?: error::class.java.simpleName)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(240)
            .ifBlank { "未知异常" }
        return "$safeReason 异常：$safeError；已停止新的场景协调并锁定真实执行，请按事务记录恢复"
    }
}
