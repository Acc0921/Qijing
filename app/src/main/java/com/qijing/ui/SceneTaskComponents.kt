package com.qijing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.qijing.core.scene.SceneTaskEvent
import com.qijing.core.scene.SceneTaskPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SceneTaskTrail(events: List<SceneTaskEvent>, modifier: Modifier = Modifier) {
    if (events.isEmpty()) return
    val latest = events.last()
    val taskEvents = events.filter { it.taskId == latest.taskId }.takeLast(6)
    Surface(
        modifier = modifier.fillMaxWidth().testTag("scene-task-trail"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(latest.sceneName.ifBlank { "场景任务" }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(latest.packageName, latest.backend?.displayLabel()).joinToString(" · ").ifBlank { "自动化任务" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(latest.phase.userLabel(), style = MaterialTheme.typography.labelLarge, color = latest.phase.tone())
            }
            taskEvents.forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = when (event.phase) {
                            SceneTaskPhase.FAILED, SceneTaskPhase.RECOVERY_REQUIRED -> Icons.Rounded.ErrorOutline
                            SceneTaskPhase.RESTORED, SceneTaskPhase.VERIFIED, SceneTaskPhase.PREVIEWED -> Icons.Rounded.Check
                            else -> Icons.Rounded.RadioButtonChecked
                        },
                        contentDescription = null,
                        tint = event.phase.tone(),
                        modifier = Modifier.size(20.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(event.phase.userLabel(), style = MaterialTheme.typography.bodyMedium)
                        Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatEventTime(event.timestampMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun SceneTaskPhase.userLabel(): String = when (this) {
    SceneTaskPhase.MATCHED -> "已命中"
    SceneTaskPhase.PREFLIGHT -> "安全预检"
    SceneTaskPhase.SNAPSHOT -> "原值快照"
    SceneTaskPhase.APPLYING -> "执行中"
    SceneTaskPhase.VERIFIED -> "读回已验证"
    SceneTaskPhase.PREVIEWED -> "预演完成"
    SceneTaskPhase.ACTIVE -> "等待离场恢复"
    SceneTaskPhase.RESTORING -> "恢复中"
    SceneTaskPhase.RESTORED -> "已恢复"
    SceneTaskPhase.FAILED -> "执行失败"
    SceneTaskPhase.RECOVERY_REQUIRED -> "恢复不完整"
}

@Composable
private fun SceneTaskPhase.tone() = when (this) {
    SceneTaskPhase.FAILED, SceneTaskPhase.RECOVERY_REQUIRED -> MaterialTheme.colorScheme.error
    SceneTaskPhase.RESTORED, SceneTaskPhase.VERIFIED -> QijingMint
    SceneTaskPhase.PREVIEWED -> QijingBlue
    SceneTaskPhase.RESTORING -> QijingAmber
    else -> MaterialTheme.colorScheme.primary
}

private fun formatEventTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
