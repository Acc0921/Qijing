package com.qijing.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qijing.core.data.NewDataStore
import com.qijing.feature.telemetry.FpsCsvExporter
import com.qijing.feature.telemetry.FpsMonitor
import com.qijing.feature.telemetry.FpsSessionAnalyzer
import com.qijing.feature.telemetry.FpsSessionSummary
import com.qijing.feature.telemetry.FpsWindowSample
import com.qijing.feature.telemetry.WindowFpsCollector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MonitorScreen(store: NewDataStore) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val monitor = remember(store) { FpsMonitor(store) }
    val analyzer = remember(store) { FpsSessionAnalyzer(store) }
    var collector by remember { mutableStateOf<WindowFpsCollector?>(null) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<FpsWindowSample?>(null) }
    var selectedSummary by remember { mutableStateOf<FpsSessionSummary?>(null) }
    var sessionIds by remember(store) { mutableStateOf(store.telemetrySessionIds()) }

    DisposableEffect(Unit) {
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) collector?.stop()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ScreenHeader(
                eyebrow = "M8 · FRAME MONITOR",
                title = "窗口流畅度",
                summary = "用系统 FrameMetrics 观察栖境自身窗口。采样只保存在本机，不读取或注入其他应用。"
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || activity == null) {
            item {
                QijingPanel(elevated = true) {
                    StatusBadge("当前设备不可用", BadgeTone.Danger)
                    Text(
                        "窗口帧指标需要 Android 7.0 或更高版本，并且只能在 Activity 窗口中采集。",
                        modifier = Modifier.testTag("fps-unsupported"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            return@LazyColumn
        }

        item {
            FpsLivePanel(
                running = collector != null,
                sample = latest,
                activeSessionId = activeSessionId,
                onStart = {
                    val next = WindowFpsCollector(activity, monitor, onSample = { latest = it })
                    if (next.start()) {
                        collector = next
                        activeSessionId = monitor.currentSessionId()
                        latest = null
                        selectedSummary = null
                    }
                },
                onStop = {
                    val stoppedSession = activeSessionId
                    collector?.stop()
                    collector = null
                    stoppedSession?.let { selectedSummary = analyzer.summarize(it) }
                    sessionIds = store.telemetrySessionIds()
                }
            )
        }

        selectedSummary?.let { summary ->
            item { SessionSummaryPanel(summary) }
        }

        item {
            SectionHeader(
                title = "历史会话",
                detail = "最近 ${sessionIds.size.coerceAtMost(5)} 次 · 最多在本机保留 50 次"
            )
        }

        if (sessionIds.isEmpty()) {
            item { EmptyState("还没有采样", "开始一次监控并至少产生一个采样窗口后，会话会出现在这里。") }
        } else {
            items(sessionIds.takeLast(5).asReversed(), key = { it }) { sessionId ->
                val samples = remember(sessionId, sessionIds) { store.telemetry(sessionId) }
                val summary = remember(sessionId, sessionIds) { analyzer.summarize(sessionId) }
                HistorySessionPanel(
                    sessionId = sessionId,
                    timestampMs = samples.firstOrNull()?.timestampMs,
                    summary = summary,
                    onOpen = {
                        activeSessionId = sessionId
                        selectedSummary = summary
                    },
                    onShare = { context.shareCsv(sessionId, FpsCsvExporter.export(samples)) }
                )
            }
        }
    }
}

@Composable
private fun FpsLivePanel(
    running: Boolean,
    sample: FpsWindowSample?,
    activeSessionId: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val tone = when {
        !running -> BadgeTone.Neutral
        sample == null -> BadgeTone.Info
        sample.jankCount == 0 -> BadgeTone.Good
        sample.jankCount <= 2 -> BadgeTone.Warning
        else -> BadgeTone.Danger
    }
    val state = when {
        !running -> "待机"
        sample == null -> "等待帧数据"
        sample.jankCount == 0 -> "流畅"
        sample.jankCount <= 2 -> "轻微波动"
        else -> "卡顿明显"
    }

    QijingHero {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("实时 FPS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    sample?.fps?.oneDecimal() ?: "--",
                    fontSize = 68.sp,
                    lineHeight = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            StatusBadge(state, tone)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "平均帧耗时",
                value = sample?.averageFrameTimeMs?.let { "${it.oneDecimal()} ms" } ?: "--",
                detail = "最近 1 秒窗口",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "卡顿 / 丢失",
                value = sample?.let { "${it.jankCount} / ${it.droppedReportCount}" } ?: "--",
                detail = sample?.let { "${it.frameCount} 帧参与统计" } ?: "等待采样",
                modifier = Modifier.weight(1f),
                accent = if ((sample?.jankCount ?: 0) > 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f).testTag("fps-start"),
                enabled = !running,
                onClick = onStart
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Text("开始")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f).testTag("fps-stop"),
                enabled = running,
                onClick = onStop
            ) {
                Icon(Icons.Rounded.Pause, null)
                Text("停止并汇总")
            }
        }
        activeSessionId?.let {
            Text("Session ${it.take(8)}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SessionSummaryPanel(summary: FpsSessionSummary) {
    QijingPanel(elevated = true) {
        SectionHeader("会话摘要", "${summary.sampleCount} 个采样窗口") {
            StatusBadge("已完成", BadgeTone.Good)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("平均 FPS", summary.averageFps.oneDecimal(), "${summary.minFps.oneDecimal()}–${summary.maxFps.oneDecimal()}", Modifier.weight(1f))
            MetricTile("P95 帧耗时", "${summary.p95FrameTimeMs.oneDecimal()} ms", "95% 的窗口不高于此值", Modifier.weight(1f), QijingBlue)
            MetricTile("累计卡顿", summary.totalJank.toString(), "整个会话", Modifier.weight(1f), if (summary.totalJank > 0) QijingAmber else QijingMint)
        }
    }
}

@Composable
private fun HistorySessionPanel(
    sessionId: String,
    timestampMs: Long?,
    summary: FpsSessionSummary?,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    QijingPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.QueryStats, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Session ${sessionId.take(8)}…", style = MaterialTheme.typography.titleMedium)
                Text(
                    timestampMs?.let(::formatSessionTime) ?: "时间未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            summary?.let { StatusBadge("${it.averageFps.oneDecimal()} FPS", BadgeTone.Info) }
        }
        summary?.let {
            Text(
                "P95 ${it.p95FrameTimeMs.oneDecimal()} ms · 卡顿 ${it.totalJank} · ${it.sampleCount} 个窗口",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onOpen, enabled = summary != null, modifier = Modifier.weight(1f)) {
                Text("查看摘要")
            }
            Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.IosShare, null)
                Text("分享 CSV")
            }
        }
    }
}

private fun Double.oneDecimal(): String = String.format(Locale.US, "%.1f", this)

private fun formatSessionTime(timestampMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.shareCsv(sessionId: String, csv: String) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, "栖境 FPS Session ${sessionId.take(8)}")
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    startActivity(Intent.createChooser(share, "分享 FPS CSV"))
}
