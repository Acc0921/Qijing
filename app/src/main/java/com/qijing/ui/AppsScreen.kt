package com.qijing.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppTaskFilter { All, Configured, Unconfigured }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsScreen(store: NewDataStore, onCreateScene: (AppEntry) -> Unit) {
    val context = LocalContext.current
    val controller = remember(context, store) { AppListController(ApplicationCatalog(context), store) }
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var includeNonLaunchable by remember { mutableStateOf(false) }
    var taskFilter by remember { mutableStateOf(AppTaskFilter.All) }
    var state by remember { mutableStateOf(com.qijing.feature.apps.AppListState()) }
    var loading by remember { mutableStateOf(true) }
    var refreshToken by remember { mutableStateOf(0) }
    var showFilters by remember { mutableStateOf(false) }
    val scenes = store.scenes()
    val boundPackages = scenes.flatMapTo(mutableSetOf()) { it.packageNames }
    val visibleItems = state.items.filter { app ->
        when (taskFilter) {
            AppTaskFilter.All -> true
            AppTaskFilter.Configured -> app.packageName in boundPackages
            AppTaskFilter.Unconfigured -> app.packageName !in boundPackages
        }
    }

    LaunchedEffect(controller, refreshToken) {
        loading = true
        withContext(Dispatchers.IO) { controller.refresh() }
        state = controller.state(query, includeSystem, includeNonLaunchable)
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        QijingTopAppBar(
            title = "应用",
            subtitle = "选择触发对象并建立调节关系",
            accent = QijingViolet,
            actions = {
                IconButton(onClick = { refreshToken += 1 }, enabled = !loading) {
                    Icon(Icons.Rounded.Refresh, "刷新应用列表")
                }
                IconButton(onClick = { showFilters = true }) { Icon(Icons.Rounded.FilterList, "筛选应用") }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f).testTag("app-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; state = controller.state(it, includeSystem, includeNonLaunchable) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text("搜索应用或包名") },
                        shape = MaterialTheme.shapes.large,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = QijingViolet,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${visibleItems.size} 个结果 · ${taskFilter.label()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        // Kept as a direct compatibility action for the existing user-journey test.
                        TextButton(
                            modifier = Modifier.testTag("apps-all-apps"),
                            onClick = {
                                includeSystem = true
                                includeNonLaunchable = true
                                state = controller.state(query, true, true)
                            }
                        ) { Text(if (includeSystem && includeNonLaunchable) "全部软件包" else "显示全部") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (loading) {
                item { NativeListRow("正在读取应用", "扫描可作为前台触发对象的软件包", "加载中") }
            } else if (visibleItems.isEmpty()) {
                item { EmptyState("没有匹配结果", "尝试应用名称、包名，或在筛选中显示系统应用。", Modifier.padding(16.dp)) }
            } else {
                itemsIndexed(visibleItems, key = { _, app -> app.packageName }) { index, app ->
                    AppsNativeRow(
                        app = app,
                        sceneCount = scenes.count { app.packageName in it.packageNames },
                        first = index == 0,
                        last = index == visibleItems.lastIndex,
                        onCreateScene = onCreateScene
                    )
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
                Text("筛选应用", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                AppsFilterSection("应用范围") {
                    AppsFilterRow("用户应用", !includeSystem) {
                        includeSystem = false
                        state = controller.state(query, false, includeNonLaunchable)
                    }
                    AppsFilterRow("全部应用（包含系统）", includeSystem) {
                        includeSystem = true
                        state = controller.state(query, true, includeNonLaunchable)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                AppsFilterSection("触发资格") {
                    AppsFilterRow("有桌面入口", !includeNonLaunchable) {
                        includeNonLaunchable = false
                        state = controller.state(query, includeSystem, false)
                    }
                    AppsFilterRow("全部软件包（专家）", includeNonLaunchable) {
                        includeNonLaunchable = true
                        state = controller.state(query, includeSystem, true)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                AppsFilterSection("场景状态") {
                    AppsFilterRow("全部", taskFilter == AppTaskFilter.All) { taskFilter = AppTaskFilter.All }
                    AppsFilterRow("已有场景", taskFilter == AppTaskFilter.Configured) { taskFilter = AppTaskFilter.Configured }
                    AppsFilterRow("未配置", taskFilter == AppTaskFilter.Unconfigured) { taskFilter = AppTaskFilter.Unconfigured }
                }
                TextButton(onClick = { showFilters = false }, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) { Text("完成") }
            }
        }
    }
}

@Composable
private fun AppsNativeRow(app: AppEntry, sceneCount: Int, first: Boolean, last: Boolean, onCreateScene: (AppEntry) -> Unit) {
    val corner = 20.dp
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp,
        topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp,
        bottomEnd = if (last) corner else 0.dp
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("app-row-${app.packageName}")
            .clickable { onCreateScene(app) },
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (first) 1.dp else 0.dp
    ) {
        Column {
            ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (sceneCount > 0) Text("$sceneCount 个场景", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append("版本 ${app.versionName.ifBlank { "未知" }}")
                        if (app.isSystem) append(" · 系统应用")
                        if (!app.isLaunchable) append(" · 触发资格未知")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (app.isLaunchable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
                )
            }
        },
        leadingContent = { AppIcon(app) },
        trailingContent = {
            Icon(
                if (sceneCount > 0) Icons.Rounded.Add else Icons.Rounded.ChevronRight,
                if (sceneCount > 0) "为该应用再建场景" else "为该应用建立场景",
                tint = MaterialTheme.colorScheme.primary
            )
        },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            if (!last) HorizontalDivider(modifier = Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AppsFilterSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        content()
    }
}

@Composable
private fun AppsFilterRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(label) },
        trailingContent = { if (selected) Icon(Icons.Rounded.Check, "已选择", tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun AppTaskFilter.label(): String = when (this) {
    AppTaskFilter.All -> "全部状态"
    AppTaskFilter.Configured -> "已有场景"
    AppTaskFilter.Unconfigured -> "未配置"
}

@Composable
private fun AppIcon(app: AppEntry) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(app.packageName)
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { target ->
                    drawable.setBounds(0, 0, target.width, target.height)
                    drawable.draw(Canvas(target))
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    val resolvedBitmap = bitmap
    if (resolvedBitmap != null) {
        Image(resolvedBitmap, contentDescription = "${app.label}图标", modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small))
    } else {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.medium,
            color = if (app.isSystem) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(app.label.firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold)
            }
        }
    }
}
