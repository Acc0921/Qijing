package com.qijing.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qijing.core.data.NewDataStore
import com.qijing.core.model.AppEntry
import com.qijing.feature.apps.AppListController
import com.qijing.feature.apps.ApplicationCatalog

private enum class AppTaskFilter { All, Configured, Unconfigured }

@Composable
internal fun AppsScreen(store: NewDataStore, onCreateScene: (AppEntry) -> Unit) {
    val context = LocalContext.current
    val controller = remember(context, store) { AppListController(ApplicationCatalog(context), store) }
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var taskFilter by remember { mutableStateOf(AppTaskFilter.All) }
    var state by remember { mutableStateOf(controller.refresh()) }
    val scenes = store.scenes()
    val boundPackages = scenes.flatMapTo(mutableSetOf()) { it.packageNames }
    val visibleItems = state.items.filter { app ->
        when (taskFilter) {
            AppTaskFilter.All -> true
            AppTaskFilter.Configured -> app.packageName in boundPackages
            AppTaskFilter.Unconfigured -> app.packageName !in boundPackages
        }
    }

    LazyColumn(
        modifier = Modifier.testTag("app-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(
                eyebrow = "M2 · APPLICATIONS",
                title = "选择应用，创建场景",
                summary = "直接从已安装应用进入场景配置，不再复制包名。",
                action = {
                    IconButton(onClick = { state = controller.refresh().let { controller.state(query, includeSystem) } }) {
                        Icon(Icons.Rounded.Refresh, "刷新应用列表")
                    }
                }
            )
        }
        item {
            QijingPanel(elevated = true) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; state = controller.state(it, includeSystem) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    label = { Text("搜索应用或包名") },
                    supportingText = { Text("当前显示 ${visibleItems.size} 个应用") }
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !includeSystem, onClick = { includeSystem = false; state = controller.state(query, false) }, label = { Text("用户应用") })
                    FilterChip(modifier = Modifier.testTag("apps-all-apps"), selected = includeSystem, onClick = { includeSystem = true; state = controller.state(query, true) }, label = { Text("全部应用") })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = taskFilter == AppTaskFilter.All, onClick = { taskFilter = AppTaskFilter.All }, label = { Text("全部") })
                    FilterChip(selected = taskFilter == AppTaskFilter.Configured, onClick = { taskFilter = AppTaskFilter.Configured }, label = { Text("已有场景") })
                    FilterChip(selected = taskFilter == AppTaskFilter.Unconfigured, onClick = { taskFilter = AppTaskFilter.Unconfigured }, label = { Text("未配置") })
                }
            }
        }
        if (visibleItems.isEmpty()) {
            item { EmptyState("没有匹配结果", "尝试应用名称、包名，或切换到全部应用。") }
        } else {
            items(visibleItems, key = { it.packageName }) { app ->
                AppRow(app, scenes.count { app.packageName in it.packageNames }, onCreateScene)
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, sceneCount: Int, onCreateScene: (AppEntry) -> Unit) {
    QijingPanel(
        modifier = Modifier.testTag("app-row-${app.packageName}").clickable { onCreateScene(app) }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(app)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    StatusBadge(
                        when {
                            sceneCount > 0 -> "${sceneCount} 个场景"
                            app.isSystem -> "系统"
                            else -> "未配置"
                        },
                        if (sceneCount > 0) BadgeTone.Good else if (app.isSystem) BadgeTone.Info else BadgeTone.Neutral
                    )
                }
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("版本 ${app.versionName.ifBlank { "未知" }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (sceneCount > 0) Icons.Rounded.Add else Icons.Rounded.ChevronRight,
                    if (sceneCount > 0) "为该应用再建场景" else "为该应用建立场景",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(app.packageName)
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { target ->
                drawable.setBounds(0, 0, target.width, target.height)
                drawable.draw(Canvas(target))
            }.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = "${app.label}图标", modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small))
    } else {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = if (app.isSystem) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(app.label.firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold)
            }
        }
    }
}
