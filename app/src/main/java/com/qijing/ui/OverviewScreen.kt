package com.qijing.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qijing.core.data.NewDataStore
import com.qijing.core.device.AndroidDeviceCapabilityProbe
import com.qijing.core.device.BackendAvailability
import com.qijing.core.device.LocalBackendDetector
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.BackendSelectionResult
import com.qijing.core.execution.ShizukuRuntime
import com.qijing.core.logging.SharedPreferencesTaskLogStore
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scene.SceneServicePhase
import com.qijing.core.scene.SceneServiceStateStore
import com.qijing.core.scene.SceneTriggerService
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
    val device = overview.device

    LazyColumn(
        modifier = Modifier.testTag("home"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            QijingTopAppBar(
                title = "栖境",
                actions = {
                    IconButton(onClick = { logs = logsStore.recent(4) }) {
                        Icon(Icons.Rounded.History, "刷新任务记录")
                    }
                }
            )
        }

        item { AutomationControlSection() }

        item { OverviewPageSection("当前设备") }
        item {
            OverviewNativeGroup {
                OverviewStatusRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = device?.model ?: "设备信息读取中",
                    detail = listOfNotNull(device?.manufacturer, device?.androidVersion?.let { "Android $it" }).joinToString(" · ").ifBlank { "等待设备状态" },
                    trailing = device?.soc ?: "—"
                )
                OverviewDivider()
                OverviewStatusRow(
                    icon = Icons.Rounded.Tune,
                    title = "CPU",
                    detail = "查看设备支持范围并进行安全预演",
                    trailing = "只读",
                    onClick = onOpenTuning
                )
                OverviewDivider()
                OverviewStatusRow(
                    icon = Icons.Rounded.Security,
                    title = "内存与 ZRAM",
                    detail = "ZRAM 仅展示状态，不开放重建",
                    trailing = if (device?.capabilities?.isNotEmpty() == true) "正常" else "读取受限",
                    onClick = onOpenTuning
                )
            }
        }

        item { OverviewPageSection("应用与场景") }
        item {
            OverviewNativeGroup {
                OverviewStatusRow(Icons.Rounded.Apps, "应用索引", "用于选择自动化触发对象", "${overview.appCount} 个")
                OverviewDivider()
                OverviewStatusRow(Icons.Rounded.AutoAwesomeMotion, "场景", "启用只表示等待命中", "${overview.sceneCount} 个", onOpenScenes)
            }
        }

        item {
            OverviewPageSection("最近执行") {
                IconButton(onClick = { logs = logsStore.recent(4) }) { Icon(Icons.Rounded.History, "刷新任务记录") }
            }
        }
        item {
            OverviewNativeGroup {
                if (logs.isEmpty()) {
                    ListItem(
                        headlineContent = { Text("还没有执行记录") },
                        supportingContent = { Text("命中场景后的预检、执行与恢复结果会显示在这里。") },
                        colors = nativeListColors()
                    )
                } else {
                    logs.asReversed().forEachIndexed { index, log ->
                        ListItem(
                            headlineContent = { Text(log.stage, maxLines = 1) },
                            supportingContent = { Text(log.message, maxLines = 2) },
                            leadingContent = { StatusBadge(if (log.success) "成功" else "异常", if (log.success) BadgeTone.Good else BadgeTone.Danger) },
                            trailingContent = {
                                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestampMs)), style = MaterialTheme.typography.labelMedium)
                            },
                            colors = nativeListColors()
                        )
                        if (index != logs.lastIndex) OverviewDivider()
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationControlSection() {
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
    var showBackendSheet by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = source.accessState()
                availability = LocalBackendDetector().detect()
                serviceState = serviceStateStore.current()
                selectedBackend = preference.selected()
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
    val summaryTitle: String
    val summaryDetail: String
    val summaryValue: String
    val summaryTone: BadgeTone
    when {
        serviceState.phase == SceneServicePhase.RECOVERY_REQUIRED -> {
            summaryTitle = "恢复任务需要处理"
            summaryDetail = serviceState.detail.ifBlank { "上次恢复结果未确认，真实操作已锁定" }
            summaryValue = "查看任务"
            summaryTone = BadgeTone.Danger
        }
        !access.granted -> {
            summaryTitle = "自动化尚未就绪"
            summaryDetail = "还缺少前台应用访问权限"
            summaryValue = "去授权"
            summaryTone = BadgeTone.Warning
        }
        !backendReady -> {
            summaryTitle = "执行方式不可用"
            summaryDetail = "请重新选择当前设备可用的执行方式"
            summaryValue = "选择方式"
            summaryTone = BadgeTone.Warning
        }
        serviceState.phase == SceneServicePhase.RUNNING -> {
            summaryTitle = "自动化运行中"
            summaryDetail = "命中场景时会按预演、验证和恢复闭环执行"
            summaryValue = selectedBackend.displayLabel()
            summaryTone = BadgeTone.Good
        }
        else -> {
            summaryTitle = "设备已就绪"
            summaryDetail = "权限与执行方式可用，可以启动自动化"
            summaryValue = selectedBackend.displayLabel()
            summaryTone = BadgeTone.Good
        }
    }

    Column(Modifier.fillMaxWidth()) {
        QijingStatusSummary(
            title = summaryTitle,
            value = summaryValue,
            detail = summaryDetail,
            tone = summaryTone,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OverviewPageSection("自动化")
        OverviewNativeGroup {
        OverviewStatusRow(
            icon = Icons.Rounded.Tune,
            title = "执行方式",
            detail = if (backendLocked) "自动化运行期间已锁定" else "切换后所有已启用场景会停用",
            trailing = selectedBackend.displayLabel(),
            onClick = { showBackendSheet = true }
        )
        OverviewDivider()
        ListItem(
            headlineContent = { Text(if (access.granted) "前台应用访问已授权" else "需要前台应用访问权限") },
            supportingContent = { Text(if (access.granted) "只用于判断当前前台应用，不读取屏幕内容。" else "授权返回后会自动刷新状态。") },
            leadingContent = { Icon(Icons.Rounded.Security, null, tint = if (access.granted) QijingMint else QijingAmber) },
            trailingContent = {
                if (access.granted) StatusBadge("已授权", BadgeTone.Good)
                else TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("去授权") }
            },
            colors = nativeListColors()
        )
        OverviewDivider()
        ListItem(
            headlineContent = { Text("场景自动化") },
            supportingContent = {
                Text(
                    when {
                        serviceState.phase == SceneServicePhase.RECOVERY_REQUIRED -> serviceState.detail.ifBlank { "上次恢复未确认，请先处理。" }
                        selectedBackend == ExecutionBackend.ROOT && backendReady -> "Root 可用；每次写入前读取快照。"
                        selectedBackend == ExecutionBackend.SHIZUKU -> ShizukuRuntime.status().detail
                        else -> "预览模式只记录计划，不修改系统。"
                    }
                )
            },
            leadingContent = { Icon(if (serviceState.phase == SceneServicePhase.RUNNING) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null) },
            trailingContent = {
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
                    Text(if (serviceState.phase == SceneServicePhase.RUNNING) "停止" else "启动")
                }
            },
            colors = nativeListColors()
        )
        backendMessage?.let {
            OverviewDivider()
            Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        }
    }

    if (showBackendSheet) {
        ModalBottomSheet(onDismissRequest = { showBackendSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("选择执行方式", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(
                    "执行方式是全局安全环境。自动化未停止时不可切换；切换会停用已启用场景。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                listOf(ExecutionBackend.DRY_RUN, ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU).forEach { backend ->
                    val status = availability.firstOrNull { it.backend == backend }
                    val available = backend == ExecutionBackend.DRY_RUN || status?.available == true
                    BackendSheetRow(
                        backend = backend,
                        availability = status,
                        selected = selectedBackend == backend,
                        enabled = !backendLocked && available,
                        onClick = {
                            when (preference.select(backend)) {
                                BackendSelectionResult.SELECTED -> {
                                    selectedBackend = backend
                                    backendMessage = "已选择执行方式；已有启用场景已停用，需要重新预演。"
                                    showBackendSheet = false
                                }
                                BackendSelectionResult.UNCHANGED -> showBackendSheet = false
                                BackendSelectionResult.BLOCKED_SERVICE_ACTIVE -> backendMessage = "自动化尚未安全停止，当前不能切换执行方式。"
                            }
                        }
                    )
                }
                val shizuku = ShizukuRuntime.status()
                if (!shizuku.ready) {
                    TextButton(
                        onClick = {
                            ShizukuRuntime.requestPermission()
                            backendMessage = "已请求 Shizuku 授权；返回应用后会刷新状态。"
                            showBackendSheet = false
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) { Text("请求 Shizuku 授权") }
                }
            }
        }
    }
}

@Composable
private fun BackendSheetRow(
    backend: ExecutionBackend,
    availability: BackendAvailability?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(backend.displayLabel()) },
        supportingContent = {
            Text(
                when (backend) {
                    ExecutionBackend.DRY_RUN -> "只验证计划并记录预演，不修改系统"
                    else -> availability?.reason ?: if (availability?.available == true) "后端已就绪" else "当前不可用"
                }
            )
        },
        leadingContent = { Icon(if (backend == ExecutionBackend.DRY_RUN) Icons.Rounded.Security else Icons.Rounded.Settings, null) },
        trailingContent = { if (selected) Icon(Icons.Rounded.Check, "当前执行方式", tint = MaterialTheme.colorScheme.primary) },
        colors = nativeListColors()
    )
}

@Composable
private fun OverviewNativeGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun OverviewPageSection(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun OverviewStatusRow(icon: ImageVector, title: String, detail: String, trailing: String, onClick: (() -> Unit)? = null) {
    ListItem(
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(detail, maxLines = 2) },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onClick != null) Icon(Icons.Rounded.ChevronRight, null)
            }
        },
        colors = nativeListColors()
    )
}

@Composable
private fun OverviewDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun nativeListColors() = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
