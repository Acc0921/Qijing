package com.qijing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scene.ScenePreparation
import kotlin.math.roundToInt

data class IntentTrackOption(
    val id: String,
    val title: String,
    val detail: String,
    val available: Boolean = true
)

@Composable
fun SceneChainRail(
    appName: String,
    packageName: String,
    intent: String,
    priority: Int,
    backend: ExecutionBackend,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().testTag("scene-chain")) {
        SceneChainNode(Icons.Rounded.Check, "触发对象", appName, packageName, QijingMint, "scene-chain-app")
        ChainConnector()
        SceneChainNode(Icons.Rounded.Tune, "调节意图", intent, "只使用第一版结构化白名单", QijingBlue, "scene-chain-intent")
        ChainConnector()
        SceneChainNode(Icons.Rounded.PriorityHigh, "冲突优先级", priority.toString(), "数值越高越优先", QijingAmber, "scene-chain-priority")
        ChainConnector()
        SceneChainNode(
            Icons.Rounded.Security,
            "执行保障",
            backend.displayLabel(),
            if (backend == ExecutionBackend.DRY_RUN) "只生成计划，不修改系统" else "命中时快照，写后读回验证",
            if (backend == ExecutionBackend.DRY_RUN) QijingBlue else QijingAmber,
            "scene-chain-backend"
        )
        ChainConnector()
        SceneChainNode(Icons.Rounded.Restore, "离场恢复", "恢复原值", "离场、切换或停止服务时触发", QijingMint, "scene-chain-restore")
    }
}

@Composable
private fun SceneChainNode(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String,
    accent: Color,
    testTag: String
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(Modifier.size(42.dp), shape = CircleShape, color = accent.copy(alpha = 0.14f), contentColor = accent) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(21.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChainConnector() {
    Box(Modifier.padding(start = 20.dp).width(2.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
fun IntentTrack(
    options: List<IntentTrackOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("scene-intent-track"),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        options.forEach { option ->
            val selected = option.id == selectedId
            Surface(
                modifier = Modifier
                    .width(142.dp)
                    .heightIn(min = 88.dp)
                    .testTag("scene-intent-${option.id}")
                    .selectable(
                        selected = selected,
                        enabled = option.available,
                        role = Role.RadioButton,
                        onClick = { onSelect(option.id) }
                    ),
                shape = MaterialTheme.shapes.medium,
                color = when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    option.available -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(17.dp))
                        Text(option.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        if (option.available) option.detail else "设备未声明对应策略",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PriorityTrack(value: Int, conflictCount: Int, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("scene-priority-track"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("冲突优先级", style = MaterialTheme.typography.titleMedium)
                Text("数值越高，重叠时越优先", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(value.toString(), if (conflictCount > 0) BadgeTone.Warning else BadgeTone.Info)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
            steps = 9
        )
        Text(
            if (conflictCount == 0) "当前没有与目标应用重叠的其他场景。" else "发现 $conflictCount 个重叠场景，预演会说明最终胜出者。",
            style = MaterialTheme.typography.bodySmall,
            color = if (conflictCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else QijingAmber
        )
    }
}

@Composable
fun RehearsalReport(
    preparation: ScenePreparation,
    backend: ExecutionBackend,
    conflictMessage: String?,
    modifier: Modifier = Modifier
) {
    val failure = preparation.failure
    Column(
        modifier.fillMaxWidth().testTag(if (preparation.ready) "scene-preflight-ready" else "scene-preflight-blocked"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("安全预演", style = MaterialTheme.typography.titleLarge)
                Text("计划、快照与恢复命令来自真实执行同一路径", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(
                if (preparation.ready) if (backend == ExecutionBackend.DRY_RUN) "预览可运行" else "可以启用" else "已阻止",
                if (preparation.ready) if (backend == ExecutionBackend.DRY_RUN) BadgeTone.Info else BadgeTone.Good else BadgeTone.Danger
            )
        }
        if (failure != null) {
            Text(failure.userMessage(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } else if (preparation.plan.commands.isEmpty()) {
            Text("没有需要改变的能力，请先选择调节意图。", color = MaterialTheme.colorScheme.error)
        } else {
            preparation.plan.commands.forEachIndexed { index, command ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val original = preparation.snapshot?.values?.get(command.capability)
                val target = command.arguments["value"] ?: command.arguments["khz"] ?: "—"
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(command.capability.userLabel(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (backend == ExecutionBackend.DRY_RUN) "当前值不作为写入依据 → 目标 $target" else "原值 ${original ?: "未读取"} → 目标 $target",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (backend == ExecutionBackend.DRY_RUN) "命中时只记录计划，不修改系统" else "命中时重新快照；读回不一致会立即恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        conflictMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = QijingAmber) }
        Text(
            "自动场景真正命中时会重新读取当时原值；快照不完整则本次不写入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun String.userLabel(): String = when (this) {
    "cpu.governor.set" -> "CPU 调度策略"
    "cpu.min_frequency.set" -> "CPU 最低频率"
    "cpu.max_frequency.set" -> "CPU 最高频率"
    "memory.swappiness.set" -> "内存回收倾向"
    "memory.zram.enabled", "memory.zram.size", "memory.zram.algorithm.set" -> "ZRAM（第一版不开放写入）"
    else -> this
}

private fun com.qijing.core.execution.ExecutionResult.userMessage(): String = when (this) {
    is com.qijing.core.execution.ExecutionResult.Failed -> when (code) {
        "SNAPSHOT_INCOMPLETE" -> "无法取得完整原值快照，未执行任何写入。请检查执行方式和设备节点权限。"
        else -> "$code：$message"
    }
    is com.qijing.core.execution.ExecutionResult.Unsupported -> "${capability.userLabel()} 不在第一版安全白名单中：$reason"
    is com.qijing.core.execution.ExecutionResult.Applied -> "预演不应产生写入结果"
}

internal fun ExecutionBackend.displayLabel(): String = when (this) {
    ExecutionBackend.DRY_RUN -> "预览模式"
    ExecutionBackend.ROOT -> "Root"
    ExecutionBackend.SHIZUKU -> "Shizuku"
    else -> name
}
