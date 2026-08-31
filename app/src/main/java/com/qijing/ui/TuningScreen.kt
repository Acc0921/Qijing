package com.qijing.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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

@OptIn(ExperimentalMaterial3Api::class)
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
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            QijingTopAppBar(
                title = "调节",
                actions = {
                    IconButton(onClick = { resultMessage = null; scope.launch { refreshStatus() } }) {
                        Icon(Icons.Rounded.Refresh, "刷新设备状态")
                    }
                }
            )
        }
        item {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Rounded.Security, null, tint = if (backend == ExecutionBackend.DRY_RUN) QijingBlue else QijingAmber)
                    Column(Modifier.weight(1f)) {
                        Text("${backend.displayName()}执行", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (backend == ExecutionBackend.DRY_RUN) "只生成变化计划，不会修改设备" else "真实写入前必须完成快照与恢复计划",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TabRow(selectedTabIndex = tab.ordinal) {
                    Tab(
                        modifier = Modifier.testTag("module-M4"),
                        selected = tab == TuningTab.CPU,
                        onClick = { tab = TuningTab.CPU },
                        text = { Text("CPU") },
                        icon = { Icon(Icons.Rounded.Speed, null) }
                    )
                    Tab(
                        modifier = Modifier.testTag("module-M5"),
                        selected = tab == TuningTab.Memory,
                        onClick = { tab = TuningTab.Memory },
                        text = { Text("内存与 ZRAM") },
                        icon = { Icon(Icons.Rounded.Memory, null) }
                    )
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                        Icon(if (resultError) Icons.Rounded.Security else Icons.Rounded.Check, null, tint = if (resultError) MaterialTheme.colorScheme.error else QijingMint)
                        Text(message, color = if (resultError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                PageSectionHeader("安全边界", "真实写入只开放固定白名单")
                Text("• Governor、CPU 最低/最高频率、swappiness", style = MaterialTheme.typography.bodyMedium)
                Text("• 未读取到完整原值时整批拒绝，不进行部分写入", style = MaterialTheme.typography.bodyMedium)
                Text("• 写后读回不一致时立即按逆序恢复", style = MaterialTheme.typography.bodyMedium)
                Text("• ZRAM 重建、核心上下线和任意 shell 输入保持关闭", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    pending?.let { plan ->
        ModalBottomSheet(
            onDismissRequest = { if (!applying) pending = null },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (backend == ExecutionBackend.DRY_RUN) "调节预演" else "确认特权调节", style = MaterialTheme.typography.titleLarge)
                Text("${plan.label} · ${backend.displayName()}", color = if (backend == ExecutionBackend.DRY_RUN) QijingBlue else QijingAmber)
                NativeListRow(title = "原值", supporting = plan.before)
                NativeListRow(title = "目标值", supporting = plan.after, status = "待执行")
                Text(
                    if (backend == ExecutionBackend.DRY_RUN) "只记录计划，不修改系统。" else "执行阶段：快照 → 写入 → 读回验证。失败时立即尝试恢复原值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (backend == ExecutionBackend.DRY_RUN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                Button(modifier = Modifier.fillMaxWidth(), enabled = !applying, onClick = {
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
                TextButton(modifier = Modifier.fillMaxWidth(), enabled = !applying, onClick = { pending = null }) { Text("取消") }
            }
        }
    }
}

@Composable
private fun CpuTuningPanel(status: CpuStatus, backend: ExecutionBackend, applying: Boolean, onPlan: (PendingTune) -> Unit) {
    var target by remember(status.currentGovernor) { mutableStateOf(status.currentGovernor.orEmpty()) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PageSectionHeader("当前状态", "执行前会重新读取特权快照", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        NativeListRow(title = "在线核心", supporting = "设备当前可见的逻辑核心", status = status.onlineCores.toString())
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        NativeListRow(title = "当前策略", supporting = "CPU Governor", status = status.currentGovernor ?: "读取受限")
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        NativeListRow(
            title = "硬件频率范围",
            supporting = "来自 cpuinfo，不代表瞬时频率",
            status = if (status.minFrequencyKHz != null && status.maxFrequencyKHz != null) "${status.minFrequencyKHz / 1000}–${status.maxFrequencyKHz / 1000} MHz" else "读取受限"
        )

        PageSectionHeader("目标策略", "只可选择设备实际声明的候选", Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        if (status.governors.isNotEmpty()) {
            status.governors.sorted().forEachIndexed { index, governor ->
                NativeListRow(
                    title = governor,
                    supporting = if (governor == status.currentGovernor) "当前正在使用" else "设备声明可用",
                    onClick = { target = governor },
                    trailing = { RadioButton(selected = target == governor, onClick = { target = governor }) }
                )
                if (index != status.governors.size - 1) {
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        } else {
            Text(
                "未读取到可用 Governor，真实执行已阻止。请检查设备能力与当前执行方式。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        val governorAccepted = target.isNotBlank() && (backend == ExecutionBackend.DRY_RUN || target in status.governors)
        Button(modifier = Modifier.fillMaxWidth().padding(16.dp), enabled = governorAccepted && !applying, onClick = {
            onPlan(PendingTune("CPU Governor", status.currentGovernor ?: "由特权快照读取", target, cpu = CpuIntent(governor = target)))
        }) { Text("预览调节方案") }
    }
}

@Composable
private fun MemoryTuningPanel(status: MemoryStatus, applying: Boolean, onPlan: (PendingTune) -> Unit) {
    var target by remember(status.swappiness) { mutableStateOf((status.swappiness ?: 60).coerceIn(0, 200).toFloat()) }
    val usedRatio = if (status.totalBytes != null && status.availableBytes != null && status.totalBytes > 0) {
        ((status.totalBytes - status.availableBytes).toFloat() / status.totalBytes).coerceIn(0f, 1f)
    } else 0f
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PageSectionHeader("当前状态", "ZRAM 只读展示，不开放重建", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("内存使用", style = MaterialTheme.typography.titleMedium)
                Text("可用 ${formatBytes(status.availableBytes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { usedRatio }, modifier = Modifier.fillMaxWidth(), color = if (usedRatio > 0.85f) QijingDanger else QijingMint)
            Text("总计 ${formatBytes(status.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        NativeListRow(
            title = "ZRAM",
            supporting = status.zramAlgorithms.joinToString().ifEmpty { "压缩算法读取受限" },
            status = formatBytes(status.zramSizeBytes)
        )
        PageSectionHeader("内存回收倾向", "低值更少换出，高值更积极换出；不承诺性能收益", Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("目标 Swappiness", style = MaterialTheme.typography.titleMedium)
                Text(target.toInt().toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = target, onValueChange = { target = it }, valueRange = 0f..200f, steps = 19)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("减少换出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("当前 ${status.swappiness ?: "需特权快照"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("积极换出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(modifier = Modifier.fillMaxWidth().padding(16.dp), enabled = !applying, onClick = {
            onPlan(PendingTune("内存回收倾向", status.swappiness?.toString() ?: "由特权快照读取", target.toInt().toString(), memory = MemoryIntent(swappiness = target.toInt())))
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
