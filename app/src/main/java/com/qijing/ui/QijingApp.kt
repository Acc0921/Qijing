package com.qijing.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qijing.core.data.SharedPreferencesNewDataStore
import com.qijing.feature.scene.SharedPreferencesSceneEditorStateStore

private enum class MainDestination(
    val route: String,
    val code: String,
    val label: String,
    val icon: ImageVector
) {
    Overview("overview", "M1", "总览", Icons.Rounded.Dashboard),
    Apps("apps", "M2", "应用", Icons.Rounded.Apps),
    Scenes("scenes", "M3", "场景", Icons.Rounded.AutoAwesomeMotion),
    Tuning("tuning", "M4", "调节", Icons.Rounded.Tune),
    Monitor("monitor", "M8", "监控", Icons.Rounded.MonitorHeart)
}

@Composable
fun QijingApp() {
    val context = LocalContext.current
    val store = remember(context) { SharedPreferencesNewDataStore(context) }
    val navController = rememberNavController()
    val sceneEditor: SceneEditorViewModel = viewModel()
    val sceneEditorStore = remember(context) { SharedPreferencesSceneEditorStateStore(context) }
    LaunchedEffect(sceneEditor, sceneEditorStore) { sceneEditor.attachPersistence(sceneEditorStore) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: MainDestination.Overview.route

    fun navigate(destination: MainDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Every primary screen owns a TopAppBar, which already consumes the status-bar inset.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    MainDestination.entries.forEach { item ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("module-${item.code}"),
                            selected = currentRoute == item.route,
                            onClick = { navigate(item) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.Overview.route,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            composable(MainDestination.Overview.route) {
                OverviewScreen(
                    store = store,
                    onOpenScenes = { navigate(MainDestination.Scenes) },
                    onOpenTuning = { navigate(MainDestination.Tuning) }
                )
            }
            composable(MainDestination.Apps.route) {
                AppsScreen(store) { app ->
                    sceneEditor.selectApp(app)
                    navigate(MainDestination.Scenes)
                }
            }
            composable(MainDestination.Scenes.route) {
                ScenesScreen(
                    store = store,
                    editor = sceneEditor,
                    onChooseApp = { navigate(MainDestination.Apps) }
                )
            }
            composable(MainDestination.Tuning.route) { TuningScreen(store) }
            composable(MainDestination.Monitor.route) { MonitorScreen(store) }
        }
    }
}
