package com.scenepilot

import android.os.Bundle
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
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.scenepilot.core.data.NewDataStore
import com.scenepilot.core.data.SharedPreferencesNewDataStore
import com.scenepilot.core.device.AndroidDeviceCapabilityProbe
import com.scenepilot.core.model.ExecutionBackend
import com.scenepilot.feature.apps.AppListController
import com.scenepilot.feature.apps.ApplicationCatalog
import com.scenepilot.feature.overview.OverviewPresenter
import com.scenepilot.feature.scene.SceneDraft
import com.scenepilot.feature.scene.SceneDraftStore
import com.scenepilot.feature.tuning.CpuStatusReader
import com.scenepilot.feature.tuning.MemoryStatusReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScenePilotApp() }
    }
}

@Composable
private fun ScenePilotApp() {
    val context = LocalContext.current
    val store = remember(context) { SharedPreferencesNewDataStore(context) }
    var selected by remember { mutableStateOf("M1") }
    val modules = remember { listOf("设备总览" to "M1", "应用列表" to "M2", "应用场景" to "M3", "CPU 调节" to "M4", "内存与 ZRAM" to "M5", "FPS 监控" to "M8") }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("帧域", style = MaterialTheme.typography.headlineLarge)
                    Text("设备性能场景控制台", style = MaterialTheme.typography.bodyMedium)
                    Text("当前模块：$selected", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                }
                items(modules) { (name, code) ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { selected = code }) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Text(code, color = MaterialTheme.colorScheme.primary) } }
                }
                item { ModulePage(selected, store) }
            }
        }
    }
}

@Composable
private fun ModulePage(code: String, store: NewDataStore) {
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
        }
        "M2" -> AppListPage(store)
        "M3" -> SceneEditorPage(store)
        "M4" -> CpuStatusPage()
        "M5" -> MemoryStatusPage()
        "M8" -> TuningPage("FPS 监控", "启动 session 后可记录 FPS、frame time 和 jank。")
    }
}

private fun String?.ifNullOrEmpty(default: () -> String): String = if (isNullOrEmpty()) default() else this

@Composable
private fun AppListPage(store: NewDataStore) {
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
        state.items.take(8).forEach { Text("${it.label} · ${it.packageName}", style = MaterialTheme.typography.bodySmall) }
    } }
}

@Composable
private fun SceneEditorPage(store: NewDataStore) {
    var draft by remember { mutableStateOf(SceneDraft("scene-${System.currentTimeMillis()}", "")) }
    var packageInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("应用场景", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("场景名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(packageInput, { packageInput = it; draft = draft.copy(packages = it.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) }, label = { Text("应用包名（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.governor, { draft = draft.copy(governor = it) }, label = { Text("Governor（可选）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.swappiness, { draft = draft.copy(swappiness = it) }, label = { Text("Swappiness 0-200") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { val errors = SceneDraftStore(store).save(draft); message = if (errors.isEmpty()) "场景已保存" else errors.joinToString("；") }) { Text("保存场景") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    } }
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
