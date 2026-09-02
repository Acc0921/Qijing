package com.qijing.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qijing.core.data.NewDataStore
import com.qijing.core.model.TelemetrySample
import com.qijing.feature.telemetry.FpsCsvExporter
import com.qijing.feature.telemetry.FpsMonitor
import com.qijing.feature.telemetry.FpsSessionAnalyzer
import com.qijing.feature.telemetry.FpsSessionSummary
import com.qijing.feature.telemetry.FpsWindowSample
import com.qijing.feature.telemetry.WindowFpsCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonitorScreen(store: NewDataStore) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val monitor = remember(store) { FpsMonitor(store) }
    val analyzer = remember(store) { FpsSessionAnalyzer(store) }
    var collector by remember { mutableStateOf<WindowFpsCollector?>(null) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<FpsWindowSample?>(null) }
    val recentSamples = remember { mutableStateListOf<FpsWindowSample>() }
    var selectedSummary by remember { mutableStateOf<FpsSessionSummary?>(null) }
    var sessionIds by remember(store) { mutableStateOf<List<String>>(emptyList()) }
    var history by remember(store) { mutableStateOf<Map<String, MonitorHistoryEntry>>(emptyMap()) }
    var pendingSummarySession by remember { mutableStateOf<String?>(null) }
    var historyRefreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(store, historyRefreshToken) {
        val loaded = withContext(Dispatchers.IO) {
            val ids = store.telemetrySessionIds()
            ids to ids.takeLast(5).associateWith { sessionId ->
                val samples = store.telemetry(sessionId)
                MonitorHistoryEntry(samples, analyzer.summarize(sessionId))
            }
        }
        sessionIds = loaded.first
        history = loaded.second
        pendingSummarySession?.let { sessionId ->
            selectedSummary = loaded.second[sessionId]?.summary
            pendingSummarySession = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) collector?.stop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        QijingTopAppBar(
            title = "监控",
            subtitle = "栖境自身窗口的渲染观察会话",
            accent = QijingRose
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || activity == null) {
            UnsupportedMonitor(Modifier.weight(1f))
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                WindowBoundaryNotice()
            }
            item {
                LiveReading(
                    running = collector != null,
                    sample = latest,
                    samples = recentSamples,
                    activeSessionId = activeSessionId,
                    onToggle = {
                        if (collector == null) {
                            recentSamples.clear()
                            val next = WindowFpsCollector(activity, monitor, onSample = {
                                latest = it
                                recentSamples += it
                                if (recentSamples.size > 30) recentSamples.removeAt(0)
                            })
                            if (next.start()) {
                                collector = next
                                activeSessionId = monitor.currentSessionId()
                                latest = null
                                selectedSummary = null
                            }
                        } else {
                            val stoppedSession = activeSessionId
                            collector?.stop()
                            collector = null
                            pendingSummarySession = stoppedSession
                            historyRefreshToken += 1
                        }
                    }
                )
            }
            selectedSummary?.let { summary ->
                item {
                    SessionSummary(summary)
                }
            }
            item {
                SectionLabel(
                    title = "历史会话",
                    detail = if (sessionIds.isEmpty()) "暂无记录" else "最近 ${sessionIds.size.coerceAtMost(5)} 次"
                )
            }
            if (sessionIds.isEmpty()) {
                item {
                    Text(
                        "开始监控并产生至少一个采样窗口后，记录会显示在这里。",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(sessionIds.takeLast(5).asReversed(), key = { it }) { sessionId ->
                    val entry = history[sessionId]
                    HistorySessionRow(
                        sessionId = sessionId,
                        timestampMs = entry?.samples?.firstOrNull()?.timestampMs,
                        summary = entry?.summary,
                        onOpen = {
                            activeSessionId = sessionId
                            selectedSummary = entry?.summary
                        },
                        shareEnabled = entry != null,
                        onShare = { entry?.let { context.shareCsv(sessionId, FpsCsvExporter.export(it.samples)) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun UnsupportedMonitor(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.error)
        Text("此设备无法采集窗口帧指标", style = MaterialTheme.typography.titleLarge)
        Text(
            "FrameMetrics 需要 Android 7.0 或更高版本，并且只能在 Activity 窗口中工作。",
            modifier = Modifier.testTag("fps-unsupported"),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WindowBoundaryNotice() {
    ListItem(
        headlineContent = { Text("仅测量栖境自身窗口") },
        supportingContent = { Text("不读取其他应用，也不代表外部游戏；静止页面只产生少量新帧，低渲染 FPS 不等于卡顿，请结合帧耗时与 P95 判断。") },
        leadingContent = { Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun LiveReading(
    running: Boolean,
    sample: FpsWindowSample?,
    samples: List<FpsWindowSample>,
    activeSessionId: String?,
    onToggle: () -> Unit
) {
    val stateLabel = when {
        !running && activeSessionId != null -> "本次会话已完成"
        !running -> "未记录"
        sample == null -> "正在等待帧数据"
        sample.jankCount == 0 -> "记录中 · 无卡顿报告"
        sample.jankCount <= 2 -> "记录中 · 发现少量卡顿帧"
        else -> "记录中 · 卡顿帧较多"
    }
    val stateColor = when {
        !running && activeSessionId != null -> QijingMint
        !running -> MaterialTheme.colorScheme.onSurfaceVariant
        sample == null -> MaterialTheme.colorScheme.primary
        sample.jankCount == 0 -> QijingMint
        sample.jankCount <= 2 -> QijingAmber
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FiberManualRecord, null, tint = stateColor)
            Text(stateLabel, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = stateColor)
            Spacer(Modifier.weight(1f))
            activeSessionId?.let {
                Text("${it.take(8)}…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                sample?.fps?.oneDecimal() ?: "--",
                fontSize = 38.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(" 渲染 FPS", modifier = Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        FpsTrend(samples = samples, running = running)

        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                CompactMetric("帧耗时", sample?.averageFrameTimeMs?.let { "${it.oneDecimal()} ms" } ?: "--", Modifier.weight(1f).padding(horizontal = 10.dp))
                VerticalDivider(Modifier.height(38.dp), color = MaterialTheme.colorScheme.outlineVariant)
                CompactMetric("卡顿帧", sample?.jankCount?.toString() ?: "--", Modifier.weight(1f).padding(horizontal = 10.dp))
                VerticalDivider(Modifier.height(38.dp), color = MaterialTheme.colorScheme.outlineVariant)
                CompactMetric("丢失报告", sample?.droppedReportCount?.toString() ?: "--", Modifier.weight(1f).padding(horizontal = 10.dp))
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth().testTag(if (running) "fps-stop" else "fps-start"),
            onClick = onToggle
        ) {
            Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
            Text(if (running) "停止并生成摘要" else "开始记录", modifier = Modifier.padding(start = 8.dp))
        }
    }
    }
}

@Composable
private fun FpsTrend(samples: List<FpsWindowSample>, running: Boolean) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(0f, size.height * 0.25f), end = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.25f))
            drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(0f, size.height * 0.75f), end = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.75f))
            if (samples.size > 1) {
                val values = samples.map { it.averageFrameTimeMs.coerceIn(0.0, 50.0) }
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = size.width * index / (values.size - 1).coerceAtLeast(1)
                    val y = size.height * (value / 50.0).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 4f))
            }
        }
        if (samples.size < 2) {
            Text(
                if (running) "等待形成帧时间趋势" else "开始记录后显示帧时间趋势",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionSummary(summary: FpsSessionSummary) {
    QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("本次会话摘要", "${summary.sampleCount} 个采样窗口", horizontalPadding = 0.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CompactMetric("平均渲染 FPS", summary.averageFps.oneDecimal(), Modifier.weight(1f))
            CompactMetric("渲染最低 / 最高", "${summary.minFps.oneDecimal()} / ${summary.maxFps.oneDecimal()}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CompactMetric(
                if (summary.hasPerFramePercentile) "P95 帧耗时" else "P95 窗口均值",
                "${summary.p95FrameTimeMs.oneDecimal()} ms",
                Modifier.weight(1f)
            )
            CompactMetric("累计卡顿", summary.totalJank.toString(), Modifier.weight(1f))
        }
    }
    }
}

@Composable
private fun SectionLabel(title: String, detail: String, horizontalPadding: androidx.compose.ui.unit.Dp = 24.dp) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistorySessionRow(
    sessionId: String,
    timestampMs: Long?,
    summary: FpsSessionSummary?,
    onOpen: () -> Unit,
    shareEnabled: Boolean,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
    ListItem(
        headlineContent = { Text(timestampMs?.let(::formatSessionTime) ?: "时间未知") },
        supportingContent = {
            Text(
                summary?.let {
                    val percentile = if (it.hasPerFramePercentile) "帧 P95" else "窗口 P95"
                    "渲染 ${it.averageFps.oneDecimal()} FPS · $percentile ${it.p95FrameTimeMs.oneDecimal()} ms · 卡顿 ${it.totalJank}"
                }
                    ?: "Session ${sessionId.take(8)}…"
            )
        },
        leadingContent = { Icon(Icons.Rounded.QueryStats, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpen, enabled = summary != null) {
                    Icon(Icons.Rounded.Visibility, "查看摘要")
                }
                TextButton(onClick = onShare, enabled = shareEnabled) {
                    Icon(Icons.Rounded.IosShare, null)
                    Text("CSV", modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
    }
}

private data class MonitorHistoryEntry(
    val samples: List<TelemetrySample>,
    val summary: FpsSessionSummary?
)

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
