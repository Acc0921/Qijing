package com.qijing.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qijing.core.data.NewDataStore
import com.qijing.core.device.AndroidDeviceCapabilityProbe
import com.qijing.core.device.LocalBackendDetector
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.BackendSelectionResult
import com.qijing.core.execution.ShizukuRuntime
import com.qijing.core.logging.SharedPreferencesTaskLogStore
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scene.SceneTriggerService
import com.qijing.core.scene.SceneServicePhase
import com.qijing.core.scene.SceneServiceStateStore
import com.qijing.core.scene.UsageStatsForegroundAppSource
import com.qijing.feature.overview.OverviewPresenter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun OverviewScreen(store: NewDataStore, onOpenScenes: () -> Unit, onOpenTuning: () -> Unit) {
    val context = LocalContext.current
    val overview = remember(store) { OverviewPresenter(AndroidDeviceCapabilityProbe(), store).load() }
    val logsStore = remember(context) { SharedPreferencesTaskLogStore(context) }
    var logs by remember { mutableStateOf(logsStore.recent(4)) }

    LazyColumn(
        modifier = Modifier.testTag("home"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ScreenHeader(
                eyebrow = "QIJING CONTROL",
                title = "晚上好，设备已就绪",
                summary = "先看状态，再决定是否调节。所有特权操作都有快照与回滚边界。"
            )
        }
        item {
            QijingHero {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(overview.device?.model ?: "设备信息读取中", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            listOfNotNull(overview.device?.manufacturer, overview.device?.soc, overview.device?.androidVersion?.let { "Android $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    StatusBadge(if (overview.lastError == null) "状态正常" else "读取受限", if (overview.lastError == null) BadgeTone.Good else BadgeTone.Warning)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("应用", overview.appCount.toString(), "已建立索引", Modifier.weight(1f), QijingBlue)
                    MetricTile("场景", overview.sceneCount.toString(), "自动化规则", Modifier.weight(1f), QijingMint)
                    MetricTile("能力", overview.device?.capabilities?.size?.toString() ?: "—", "只读探测", Modifier.weight(1f), QijingAmber)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onOpenTuning) { Text("打开调节中心"); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 6.dp)) }
                    FilledTonalButton(onClick = onOpenScenes) { Text("管理场景") }
                }
            }
        }
        item { AutomationControlCard() }
        item {
            QijingPanel {
                SectionHeader("最近执行", "只展示最近 4 条任务结果") {
                    IconButton(onClick = { logs = logsStore.recent(4) }) { Icon(Icons.Rounded.History, "刷新任务记录") }
                }
                if (logs.isEmpty()) {
                    EmptyState("还没有执行记录", "启动自动化并命中场景后，执行与回滚结果会出现在这里。")
                } else {
                    logs.asReversed().forEach { log ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusBadge(if (log.success) "成功" else "异常", if (log.success) BadgeTone.Good else BadgeTone.Danger)
                            Column(Modifier.weight(1f)) {
                                Text(log.stage, style = MaterialTheme.typography.titleMedium)
                                Text(log.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestampMs)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun AutomationControlCard() {
    val context = LocalContext.current
    val source = remember(context) { UsageStatsForegroundAppSource(context) }
    val preference = remember(context) { BackendPreference(context) }
    val serviceStateStore = remember(context) { SceneServiceStateStore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(source.accessState()) }
    var selectedBackend by remember { mutableStateOf(preference.selected()) }
    var availability by remember { mutableStateOf(LocalBackendDetector().detect()) }
    var serviceState by remember { mutableStateOf(serviceStateStore.current()) }
    var backendMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = source.accessState()
                availability = LocalBackendDetector().detect()
                serviceState = serviceStateStore.current()
            }
        }
        val observation = serviceStateStore.observe { serviceState = it }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            observation.close()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val backendReady = selectedBackend == ExecutionBackend.DRY_RUN || availability.firstOrNull { it.backend == selectedBackend }?.available == true
    val backendLocked = serviceState.phase != SceneServicePhase.STOPPED
    QijingPanel(elevated = true) {
        SectionHeader("自动化引擎", "按前台应用匹配场景；离场或停止时恢复原值") {
            val (label, tone) = when (serviceState.phase) {
                SceneServicePhase.RUNNING -> "运行中" to BadgeTone.Good
                SceneServicePhase.STOPPING -> "恢复中" to BadgeTone.Warning
                SceneServicePhase.RECOVERY_REQUIRED -> "恢复未确认" to BadgeTone.Danger
                SceneServicePhase.STOPPED -> "未启动" to BadgeTone.Neutral
            }
            StatusBadge(label, tone)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ExecutionBackend.DRY_RUN, ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU).forEach { backend ->
                val available = backend == ExecutionBackend.DRY_RUN || availability.firstOrNull { it.backend == backend }?.available == true
                FilterChip(
                    selected = selectedBackend == backend,
                    onClick = {
                        when (preference.select(backend)) {
                            BackendSelectionResult.SELECTED -> {
                                selectedBackend = backend
                                backendMessage = "已选择执行方式；已有启用场景已停用，需要重新预演。"
                            }
                            BackendSelectionResult.UNCHANGED -> backendMessage = null
                            BackendSelectionResult.BLOCKED_SERVICE_ACTIVE -> backendMessage = "自动化尚未安全停止，当前不能切换执行方式。"
                        }
                    },
                    enabled = !backendLocked && available,
                    label = { Text(when (backend) { ExecutionBackend.DRY_RUN -> "预览"; ExecutionBackend.ROOT -> "Root"; else -> "Shizuku" }) },
                    leadingIcon = if (selectedBackend == backend) ({ Icon(Icons.Rounded.Check, null) }) else null
                )
            }
        }
        Text(
            "执行方式是全局环境；切换会停用已启用场景，并要求重新运行安全预演。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        backendMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (serviceState.phase != SceneServicePhase.STOPPED) {
            Text(
                serviceState.detail.ifBlank { "自动化状态正在更新" },
                style = MaterialTheme.typography.bodySmall,
                color = if (serviceState.phase == SceneServicePhase.RECOVERY_REQUIRED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Security, null, tint = if (access.granted) QijingMint else QijingAmber)
            Column(Modifier.weight(1f)) {
                Text(if (access.granted) "前台应用访问已授权" else "需要前台应用访问权限", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        !access.granted -> "用于判断当前应用，不读取屏幕内容。"
                        selectedBackend == ExecutionBackend.ROOT && backendReady -> "Root 可用；写入前会读取快照，失败自动回滚。"
                        selectedBackend == ExecutionBackend.SHIZUKU -> ShizukuRuntime.status().detail
                        else -> "预览模式只记录计划，不修改系统。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!access.granted) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("去授权") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.testTag("service-toggle"),
                enabled = serviceState.phase == SceneServicePhase.RUNNING ||
                    (serviceState.phase == SceneServicePhase.STOPPED && access.granted && backendReady),
                onClick = {
                    val intent = Intent(context, SceneTriggerService::class.java)
                    if (serviceState.phase == SceneServicePhase.RUNNING) {
                        intent.action = SceneTriggerService.ACTION_STOP
                        context.startService(intent)
                    } else ContextCompat.startForegroundService(context, intent)
                }
            ) {
                Text(when (serviceState.phase) {
                    SceneServicePhase.RUNNING -> "停止并恢复"
                    SceneServicePhase.STOPPING -> "正在恢复"
                    SceneServicePhase.RECOVERY_REQUIRED -> "需要处理恢复"
                    SceneServicePhase.STOPPED -> "启动自动化"
                })
            }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("权限设置") }
        }
    }
}
