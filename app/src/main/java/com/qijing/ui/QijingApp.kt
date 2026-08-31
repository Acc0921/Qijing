package com.qijing.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.qijing.core.data.SharedPreferencesNewDataStore
import com.qijing.core.model.AppEntry

private enum class MainDestination(val code: String, val label: String, val icon: ImageVector) {
    Overview("M1", "总览", Icons.Rounded.Dashboard),
    Apps("M2", "应用", Icons.Rounded.Apps),
    Scenes("M3", "场景", Icons.Rounded.AutoAwesomeMotion),
    Tuning("M4", "调节", Icons.Rounded.Tune),
    Monitor("M8", "监控", Icons.Rounded.MonitorHeart)
}

@Composable
fun QijingApp() {
    val context = LocalContext.current
    val store = remember(context) { SharedPreferencesNewDataStore(context) }
    var destination by remember { mutableStateOf(MainDestination.Overview) }
    var sceneApp by remember { mutableStateOf<AppEntry?>(null) }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                0.24f to MaterialTheme.colorScheme.background,
                1f to MaterialTheme.colorScheme.background
            )
        )
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)) {
                    MainDestination.entries.forEach { item ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("module-${item.code}"),
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Crossfade(destination, label = "main-destination", modifier = Modifier.fillMaxSize().padding(padding)) { current ->
                when (current) {
                    MainDestination.Overview -> OverviewScreen(
                        store = store,
                        onOpenScenes = { destination = MainDestination.Scenes },
                        onOpenTuning = { destination = MainDestination.Tuning }
                    )
                    MainDestination.Apps -> AppsScreen(store) { app ->
                        sceneApp = app
                        destination = MainDestination.Scenes
                    }
                    MainDestination.Scenes -> ScenesScreen(
                        store = store,
                        initialApp = sceneApp,
                        onAppConsumed = { sceneApp = null },
                        onChooseApp = { destination = MainDestination.Apps }
                    )
                    MainDestination.Tuning -> TuningScreen()
                    MainDestination.Monitor -> MonitorScreen(store)
                }
            }
        }
    }
}
