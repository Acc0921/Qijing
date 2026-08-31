package com.qijing.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.BackendRuntimeFactory
import com.qijing.core.logging.SharedPreferencesTaskLogStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.scene.CapabilityValueReader
import com.qijing.core.scene.SceneEngine
import com.qijing.core.scene.SceneSnapshotManager
import com.qijing.feature.tuning.CpuStatus
import com.qijing.feature.tuning.CpuStatusReader
import com.qijing.feature.tuning.MemoryStatus
import com.qijing.feature.tuning.MemoryStatusReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TuningTab { CPU, Memory }
private data class PendingTune(val label: String, val before: String, val after: String, val cpu: CpuIntent = CpuIntent(), val memory: MemoryIntent = MemoryIntent())

@Composable
internal fun TuningScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cpuReader = remember { CpuStatusReader() }
    val memoryReader = remember { MemoryStatusReader() }
    val backend = remember(context) { BackendPreference(context).selected() }
    var tab by remember { mutableStateOf(TuningTab.CPU) }
    var cpu by remember { mutableStateOf(CpuStatus(0, emptySet(), null, null)) }
    var memory by remember { mutableStateOf(MemoryStatus(null, null, null, emptySet())) }
    var pending by remember { mutableStateOf<PendingTune?>(null) }
    var applying by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultError by remember { mutableStateOf(false) }

    suspend fun refreshStatus() {
        val values = withContext(Dispatchers.IO) { cpuReader.read() to memoryReader.read() }
        cpu = values.first
        memory = values.second
    }

    LaunchedEffect(Unit) { refreshStatus() }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ScreenHeader(
                eyebrow = "M4 + M5 · TUNING",
                title = "先观察，再调节",
                summary = "展示真实状态；手动写入必须经过方案预览、原值快照和二次确认。",
                action = {
                    IconButton(onClick = { resultMessage = null; scope.launch { refreshStatus() } }) {
                        Icon(Icons.Rounded.Refresh, "刷新状态")
                    }
                }
            )
        }
        item {
            QijingPanel(elevated = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        modifier = Modifier.weight(1f).testTag("module-M4"),
                        selected = tab == TuningTab.CPU,
                        onClick = { tab = TuningTab.CPU },
                        label = { Text("CPU") },
                        leadingIcon = { Icon(Icons.Rounded.Speed, null) }
                    )
                    FilterChip(
                        modifier = Modifier.weight(1f).testTag("module-M5"),
                        selected = tab == TuningTab.Memory,
                        onClick = { tab = TuningTab.Memory },
                        label = { Text("内存与 ZRAM") },
                        leadingIcon = { Icon(Icons.Rounded.Memory, null) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge("后端 ${backend.displayName()}", if (backend == ExecutionBackend.DRY_RUN) BadgeTone.Info else BadgeTone.Warning)
                    StatusBadge("ZRAM 重建关闭", BadgeTone.Neutral)
                }
            }
        }
        item {
            when (tab) {
                TuningTab.CPU -> CpuTuningPanel(cpu, backend, applying) { pending = it; resultMessage = null }
                TuningTab.Memory -> MemoryTuningPanel(memory, applying) { pending = it; resultMessage = null }
            }
        }
        resultMessage?.let { message ->
            item {
                QijingPanel {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(if (resultError) Icons.Rounded.Security else Icons.Rounded.Check, null, tint = if (resultError) MaterialTheme.colorScheme.error else QijingMint)
                        Text(message, color = if (resultError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            QijingPanel {
                SectionHeader("安全边界", "真实写入只开放固定白名单")
                Text("• Governor、CPU 最低/最高频率、swappiness", style = MaterialTheme.typography.bodyMedium)
                Text("• 未读取到完整原值时整批拒绝，不进行部分写入", style = MaterialTheme.typography.bodyMedium)
                Text("• 写后读回不一致时立即按逆序恢复", style = MaterialTheme.typography.bodyMedium)
                Text("• ZRAM 重建、核心上下线和任意 shell 输入保持关闭", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    pending?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!applying) pending = null },
            icon = { Icon(Icons.Rounded.Security, null, tint = if (backend == ExecutionBackend.DRY_RUN) QijingBlue else QijingAmber) },
            title = { Text(if (backend == ExecutionBackend.DRY_RUN) "执行调节预览" else "确认特权调节") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusBadge("${plan.label} · ${backend.displayName()}", if (backend == ExecutionBackend.DRY_RUN) BadgeTone.Info else BadgeTone.Warning)
                    Text("原值", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(plan.before, style = MaterialTheme.typography.titleMedium)
                    Text("目标值", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(plan.after, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        if (backend == ExecutionBackend.DRY_RUN) "只记录计划，不修改系统。" else "将先读取原值并建立恢复命令；任何一步失败都会停止并尝试回滚。性能与功耗可能发生变化。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (backend == ExecutionBackend.DRY_RUN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(enabled = !applying, onClick = {
                    applying = true
                    scope.launch {
                        val result = applyManualTune(context, backend, plan)
                        resultMessage = result.first
                        resultError = !result.second
                        applying = false
                        pending = null
                        refreshStatus()
                    }
                }) { Text(if (applying) "执行中…" else if (backend == ExecutionBackend.DRY_RUN) "确认预览" else "确认并应用") }
            },
            dismissButton = { TextButton(enabled = !applying, onClick = { pending = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun CpuTuningPanel(status: CpuStatus, backend: ExecutionBackend, applying: Boolean, onPlan: (PendingTune) -> Unit) {
    var target by remember(status.currentGovernor) { mutableStateOf(status.currentGovernor.orEmpty()) }
    QijingPanel {
        SectionHeader("CPU 策略", "当前读取为只读；应用前会再次以特权后端获取快照")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("在线核心", status.onlineCores.toString(), "逻辑核心", Modifier.weight(1f), QijingMint)
            MetricTile("当前策略", status.currentGovernor ?: "受限", "Governor", Modifier.weight(1f), QijingBlue)
        }
        MetricTile(
            "硬件频率范围",
            if (status.minFrequencyKHz != null && status.maxFrequencyKHz != null) "${status.minFrequencyKHz / 1000}–${status.maxFrequencyKHz / 1000} MHz" else "读取受限",
            "来自 cpuinfo，不代表当前瞬时频率",
            accent = QijingAmber
        )
        if (status.governors.isNotEmpty()) {
            Text("可用策略", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status.governors.take(3).forEach { governor ->
                    FilterChip(selected = target == governor, onClick = { target = governor }, label = { Text(governor) })
                }
            }
        }
        val governorAccepted = target.isNotBlank() && (backend == ExecutionBackend.DRY_RUN || target in status.governors)
        OutlinedTextField(
            target,
            { target = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("目标 Governor") },
            supportingText = {
                Text(if (backend == ExecutionBackend.DRY_RUN) "预览模式可检查格式；真实写入只接受设备声明的策略" else "必须从设备可用策略中选择")
            }
        )
        Button(enabled = governorAccepted && !applying, onClick = {
            onPlan(PendingTune("CPU Governor", status.currentGovernor ?: "由特权快照读取", target, cpu = CpuIntent(governor = target)))
        }) { Text("预览调节方案") }
    }
}

@Composable
private fun MemoryTuningPanel(status: MemoryStatus, applying: Boolean, onPlan: (PendingTune) -> Unit) {
    var target by remember(status.swappiness) { mutableStateOf(status.swappiness?.toString().orEmpty()) }
    val usedRatio = if (status.totalBytes != null && status.availableBytes != null && status.totalBytes > 0) {
        ((status.totalBytes - status.availableBytes).toFloat() / status.totalBytes).coerceIn(0f, 1f)
    } else 0f
    QijingPanel {
        SectionHeader("内存与 ZRAM", "调整 swappiness；ZRAM 仅展示状态，不开放重建")
        Text("内存压力", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { usedRatio }, modifier = Modifier.fillMaxWidth(), color = if (usedRatio > 0.85f) QijingDanger else QijingMint)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("可用内存", formatBytes(status.availableBytes), "总计 ${formatBytes(status.totalBytes)}", Modifier.weight(1f), QijingMint)
            MetricTile("ZRAM", formatBytes(status.zramSizeBytes), status.zramAlgorithms.joinToString().ifEmpty { "算法受限" }, Modifier.weight(1f), QijingBlue)
        }
        OutlinedTextField(
            target,
            { target = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("目标 Swappiness") },
            supportingText = { Text("允许范围 0–200；当前 ${status.swappiness ?: "由特权快照读取"}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Button(enabled = target.toIntOrNull() in 0..200 && !applying, onClick = {
            onPlan(PendingTune("内存回收倾向", status.swappiness?.toString() ?: "由特权快照读取", target, memory = MemoryIntent(swappiness = target.toInt())))
        }) { Text("预览调节方案") }
    }
}

private suspend fun applyManualTune(context: Context, backend: ExecutionBackend, plan: PendingTune): Pair<String, Boolean> =
    withContext(Dispatchers.IO) {
        val runtime = BackendRuntimeFactory.create(context, backend)
        try {
            val snapshots = runtime.readCapability?.let { reader -> SceneSnapshotManager(CapabilityValueReader(reader)) }
            val engine = SceneEngine(runtime.broker, SharedPreferencesTaskLogStore(context), snapshots)
            val result = engine.apply(SceneProfile("manual-${System.currentTimeMillis()}", "手动调节", emptySet(), plan.cpu, plan.memory))
            when {
                result.failure == null -> "${plan.label}已由 ${backend.displayName()} 完成${if (backend == ExecutionBackend.DRY_RUN) "预览" else "应用"}。" to true
                result.rolledBack -> "执行失败，已恢复原值：${result.failure}" to false
                else -> "未执行或恢复未完成：${result.failure}" to false
            }
        } finally {
            runtime.close()
        }
    }

private fun ExecutionBackend.displayName(): String = when (this) {
    ExecutionBackend.DRY_RUN -> "预览"
    ExecutionBackend.ROOT -> "Root"
    ExecutionBackend.SHIZUKU -> "Shizuku"
    else -> name
}

internal fun formatBytes(value: Long?): String = value?.let { "%.1f GiB".format(it / 1024.0 / 1024.0 / 1024.0) } ?: "未知"
