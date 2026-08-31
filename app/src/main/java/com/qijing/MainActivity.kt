package com.qijing

import android.os.Bundle
import android.os.Build
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qijing.core.data.NewDataStore
import com.qijing.core.data.SharedPreferencesNewDataStore
import com.qijing.core.device.AndroidDeviceCapabilityProbe
import com.qijing.core.device.LocalBackendDetector
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.ShizukuRuntime
import com.qijing.core.model.ExecutionBackend
import com.qijing.feature.apps.AppListController
import com.qijing.feature.apps.ApplicationCatalog
import com.qijing.feature.overview.OverviewPresenter
import com.qijing.feature.scene.SceneDraft
import com.qijing.feature.scene.SceneDraftStore
import com.qijing.core.logging.SharedPreferencesTaskLogStore
import com.qijing.feature.tuning.CpuStatusReader
import com.qijing.feature.tuning.MemoryStatusReader
import com.qijing.feature.telemetry.FpsMonitor
import com.qijing.feature.telemetry.FpsCsvExporter
import com.qijing.feature.telemetry.FpsSessionAnalyzer
import com.qijing.feature.telemetry.FpsWindowSample
import com.qijing.feature.telemetry.WindowFpsCollector
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QijingApp() }
    }
}

@Composable
private fun QijingApp() {
    val context = LocalContext.current
    val store = remember(context) { SharedPreferencesNewDataStore(context) }
    var selected by remember { mutableStateOf("M1") }
    var scenePackage by remember { mutableStateOf<String?>(null) }
    val modules = remember { listOf("设备总览" to "M1", "应用列表" to "M2", "应用场景" to "M3", "CPU 调节" to "M4", "内存与 ZRAM" to "M5", "FPS 监控" to "M8") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(state = listState, modifier = Modifier.testTag("home"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("栖境", style = MaterialTheme.typography.headlineLarge)
                    Text("设备性能场景控制台", style = MaterialTheme.typography.bodyMedium)
                    Text("当前模块：$selected", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                    SceneServiceControl()
                }
                items(modules) { (name, code) ->
                    Card(modifier = Modifier.fillMaxWidth().testTag("module-$code").clickable {
                        selected = code
                        scope.launch { listState.animateScrollToItem(modules.size + 1) }
                    }) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Text(code, color = MaterialTheme.colorScheme.primary) } }
                }
                item {
                    ModulePage(selected, store, scenePackage) { packageName ->
                        scenePackage = packageName
                        selected = "M3"
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneServiceControl() {
    val context = LocalContext.current
    val source = remember { com.qijing.core.scene.UsageStatsForegroundAppSource(context) }
    val backendPreference = remember { BackendPreference(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(source.accessState()) }
    var running by remember { mutableStateOf(false) }
    var selectedBackend by remember { mutableStateOf(backendPreference.selected()) }
    var shizukuStatus by remember { mutableStateOf(ShizukuRuntime.status()) }
    var backendAvailability by remember { mutableStateOf(LocalBackendDetector().detect()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            access = source.accessState()
            shizukuStatus = ShizukuRuntime.status()
            backendAvailability = LocalBackendDetector().detect()
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("场景自动化", style = MaterialTheme.typography.titleMedium)
        Text(if (access.granted) "Usage Stats：已授权" else "Usage Stats：未授权")
        Text("执行后端：${selectedBackend.name}", color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ExecutionBackend.DRY_RUN, ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU).forEach { backend ->
                Button(enabled = !running && selectedBackend != backend, onClick = {
                    backendPreference.select(backend)
                    selectedBackend = backend
                }) { Text(when (backend) { ExecutionBackend.DRY_RUN -> "预览"; ExecutionBackend.ROOT -> "Root"; else -> "Shizuku" }) }
            }
        }
        val rootReady = backendAvailability.firstOrNull { it.backend == ExecutionBackend.ROOT }?.available == true
        val backendReady = when (selectedBackend) {
            ExecutionBackend.DRY_RUN -> true
            ExecutionBackend.ROOT -> rootReady
            ExecutionBackend.SHIZUKU -> shizukuStatus.ready
            else -> false
        }
        when (selectedBackend) {
            ExecutionBackend.ROOT -> Text(if (rootReady) "检测到 su；启动场景后可能弹出 Root 授权" else "未检测到可执行 su", style = MaterialTheme.typography.bodySmall)
            ExecutionBackend.SHIZUKU -> {
                Text(shizukuStatus.detail, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !running && !shizukuStatus.ready, onClick = { ShizukuRuntime.requestPermission() }) { Text("授权 Shizuku") }
                    Button(enabled = !running, onClick = { shizukuStatus = ShizukuRuntime.status(); backendAvailability = LocalBackendDetector().detect() }) { Text("刷新状态") }
                }
            }
            else -> Text("预览模式不会写入系统", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text("权限设置") }
            Button(modifier = Modifier.testTag("service-toggle"), enabled = running || (access.granted && backendReady), onClick = {
                val intent = Intent(context, com.qijing.core.scene.SceneTriggerService::class.java)
                if (running) {
                    intent.action = com.qijing.core.scene.SceneTriggerService.ACTION_STOP
                    context.startService(intent)
                } else ContextCompat.startForegroundService(context, intent)
                running = !running
            }) { Text(if (running) "停止服务" else "启动服务") }
        }
        Text(if (running) "轮询运行中（${selectedBackend.name}）" else "服务未启动", color = MaterialTheme.colorScheme.primary)
    } }
}

@Composable
private fun ModulePage(code: String, store: NewDataStore, scenePackage: String?, onCreateScene: (String) -> Unit) {
    when (code) {
        "M1" -> {
            val overview = remember { OverviewPresenter(AndroidDeviceCapabilityProbe(), store).load() }
            Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("设备总览", style = MaterialTheme.typography.titleLarge)
                overview.device?.let { Text("${it.manufacturer} ${it.model} · Android ${it.androidVersion}") }
                Text("应用 ${overview.appCount} · 场景 ${overview.sceneCount}")
                Text("可用后端：${overview.device?.availableBackends?.joinToString { it.name } ?: ExecutionBackend.DRY_RUN.name}", color = MaterialTheme.colorScheme.primary)
                Text("只读能力：${overview.device?.capabilities?.joinToString().ifNullOrEmpty { "未发现" }}", style = MaterialTheme.typography.bodySmall)
            } }
            TaskLogCard()
        }
        "M2" -> AppListPage(store, onCreateScene)
        "M3" -> SceneEditorPage(store, scenePackage)
        "M4" -> CpuStatusPage()
        "M5" -> MemoryStatusPage()
        "M8" -> FpsMonitorPage(store)
    }
}

@Composable
private fun FpsMonitorPage(store: NewDataStore) {
    val context = LocalContext.current
    val activity = context as? Activity
    val monitor = remember(store) { FpsMonitor(store) }
    var collector by remember { mutableStateOf<WindowFpsCollector?>(null) }
    var activeSession by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<FpsWindowSample?>(null) }
    var summary by remember { mutableStateOf<com.qijing.feature.telemetry.FpsSessionSummary?>(null) }
    var sessionIds by remember(store) { mutableStateOf(store.telemetrySessionIds()) }
    DisposableEffect(Unit) {
        onDispose { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) collector?.stop() }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FPS 监控", style = MaterialTheme.typography.titleLarge)
            Text("采集当前应用窗口的 FrameMetrics；不会读取或注入其他应用。", style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || activity == null) {
                Text("当前 Android 版本不支持窗口帧指标。", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("fps-unsupported"))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.testTag("fps-start"), enabled = collector == null, onClick = {
                        val c = WindowFpsCollector(activity, monitor, onSample = { latest = it })
                        if (c.start()) {
                            collector = c
                            activeSession = monitor.currentSessionId()
                            summary = null
                        }
                    }) { Text("开始采集") }
                    Button(modifier = Modifier.testTag("fps-stop"), enabled = collector != null, onClick = {
                        collector?.stop()
                        collector = null
                        activeSession?.let { summary = FpsSessionAnalyzer(store).summarize(it) }
                        sessionIds = store.telemetrySessionIds()
                    }) { Text("结束并汇总") }
                }
                Text(if (collector != null) "状态：采集中" else "状态：待机", color = MaterialTheme.colorScheme.primary)
                activeSession?.let { Text("Session ${it.take(8)}…", style = MaterialTheme.typography.bodySmall) }
                latest?.let {
                    Text("最近窗口：${"%.1f".format(it.fps)} FPS · 平均帧耗时 ${"%.1f".format(it.averageFrameTimeMs)} ms")
                    Text("卡顿帧 ${it.jankCount} · 丢失报告 ${it.droppedReportCount}", style = MaterialTheme.typography.bodySmall)
                }
                summary?.let {
                    Text("会话平均 ${"%.1f".format(it.averageFps)} FPS · P95 ${"%.1f".format(it.p95FrameTimeMs)} ms")
                    Text("最低 ${"%.1f".format(it.minFps)} · 最高 ${"%.1f".format(it.maxFps)} · 卡顿 ${it.totalJank}", style = MaterialTheme.typography.bodySmall)
                }
                Text("历史 Session", style = MaterialTheme.typography.titleMedium)
                if (sessionIds.isEmpty()) {
                    Text("暂无完成采样的会话。", style = MaterialTheme.typography.bodySmall)
                }
                sessionIds.takeLast(5).asReversed().forEach { sessionId ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Session ${sessionId.take(8)}…", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { summary = FpsSessionAnalyzer(store).summarize(sessionId); activeSession = sessionId }) { Text("查看摘要") }
                                Button(onClick = {
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_SUBJECT, "栖境 FPS Session ${sessionId.take(8)}")
                                        putExtra(Intent.EXTRA_TEXT, FpsCsvExporter.export(store.telemetry(sessionId)))
                                    }
                                    context.startActivity(Intent.createChooser(share, "分享 FPS CSV"))
                                }) { Text("分享 CSV") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun String?.ifNullOrEmpty(default: () -> String): String = if (isNullOrEmpty()) default() else this

@Composable
private fun AppListPage(store: NewDataStore, onCreateScene: (String) -> Unit) {
    val context = LocalContext.current
    val controller = remember { AppListController(ApplicationCatalog(context), store) }
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(controller.refresh()) }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("应用列表", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(query, { query = it; state = controller.state(it, includeSystem) }, label = { Text("搜索名称或包名") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Switch(includeSystem, { includeSystem = it; state = controller.state(query, it) }); Text("包含系统应用") }
        Text("显示 ${state.items.size} 个应用")
        state.items.take(12).forEach { app ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onCreateScene(app.packageName) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(app.label)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    Text("版本 ${app.versionName.ifBlank { "未知" }} · ${if (app.isSystem) "系统应用" else "用户应用"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("点击为此应用创建场景", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } }
}

@Composable
private fun SceneEditorPage(store: NewDataStore, initialPackage: String?) {
    var draft by remember(initialPackage) { mutableStateOf(SceneDraft("scene-${System.currentTimeMillis()}", "", packages = initialPackage?.let(::setOf) ?: emptySet())) }
    var packageInput by remember(initialPackage) { mutableStateOf(initialPackage.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    val sceneStore = remember(store) { SceneDraftStore(store) }
    var scenes by remember(store) { mutableStateOf(sceneStore.load()) }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("应用场景", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("场景名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(packageInput, { packageInput = it; draft = draft.copy(packages = it.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) }, label = { Text("应用包名（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.governor, { draft = draft.copy(governor = it) }, label = { Text("Governor（可选）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.swappiness, { draft = draft.copy(swappiness = it) }, label = { Text("Swappiness 0-200") }, modifier = Modifier.fillMaxWidth())
        Button(modifier = Modifier.testTag("scene-save"), onClick = { val errors = sceneStore.save(draft); if (errors.isEmpty()) scenes = sceneStore.load(); message = if (errors.isEmpty()) "场景已保存" else errors.joinToString("；") }) { Text("保存场景") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text("已保存场景 ${scenes.size} 个", style = MaterialTheme.typography.titleMedium)
        if (scenes.isEmpty()) {
            Text("还没有场景。保存后可在这里启停或继续编辑。", style = MaterialTheme.typography.bodySmall)
        } else scenes.forEach { scene ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(scene.name, modifier = Modifier.weight(1f))
                        Switch(checked = scene.enabled, onCheckedChange = { enabled -> sceneStore.setEnabled(scene.id, enabled); scenes = sceneStore.load() })
                    }
                    Text(if (scene.packageNames.isEmpty()) "未绑定应用" else scene.packageNames.joinToString(), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { draft = SceneDraft.fromProfile(scene); packageInput = scene.packageNames.joinToString(",") }) { Text("编辑") }
                }
            }
        }
    } }
}

@Composable
private fun TaskLogCard() {
    val context = LocalContext.current
    val store = remember { SharedPreferencesTaskLogStore(context) }
    var logs by remember { mutableStateOf(store.recent(5)) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("最近任务", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { logs = store.recent(5) }) { Text("刷新") }
            }
            if (logs.isEmpty()) Text("暂无任务记录；场景服务运行后会在这里显示结果。", style = MaterialTheme.typography.bodySmall)
            logs.asReversed().forEach { log ->
                Text("${if (log.success) "✓" else "!"} ${log.stage} · ${log.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TuningPage(title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description)
        Text("状态：待执行", color = MaterialTheme.colorScheme.primary)
    } }
}

@Composable
private fun CpuStatusPage() {
    val reader = remember { CpuStatusReader() }
    var status by remember { mutableStateOf(reader.read()) }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CPU 调节", style = MaterialTheme.typography.titleLarge)
        Text("在线核心：${status.onlineCores}")
        Text("频率范围：${status.minFrequencyKHz ?: "未知"} - ${status.maxFrequencyKHz ?: "未知"} KHz")
        Text("Governor：${status.governors.joinToString().ifEmpty { "未读取" }}")
        Text("当前为只读模式", color = MaterialTheme.colorScheme.primary)
        Button(onClick = { status = reader.read() }) { Text("刷新") }
    } }
}

@Composable
private fun MemoryStatusPage() {
    val reader = remember { MemoryStatusReader() }
    var status by remember { mutableStateOf(reader.read()) }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("内存与 ZRAM", style = MaterialTheme.typography.titleLarge)
        Text("总内存：${formatBytes(status.totalBytes)}")
        Text("可用内存：${formatBytes(status.availableBytes)}")
        Text("ZRAM 容量：${formatBytes(status.zramSizeBytes)}")
        Text("压缩算法：${status.zramAlgorithms.joinToString().ifEmpty { "未读取" }}")
        Text("当前为只读模式", color = MaterialTheme.colorScheme.primary)
        Button(onClick = { status = reader.read() }) { Text("刷新") }
    } }
}

private fun formatBytes(value: Long?): String = value?.let { "%.1f GiB".format(it / 1024.0 / 1024.0 / 1024.0) } ?: "未知"
