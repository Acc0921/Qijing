package com.qijing.feature.apps

import com.qijing.core.data.NewDataStore
import com.qijing.core.model.AppEntry

data class AppListState(
    val query: String = "",
    val includeSystem: Boolean = false,
    val includeNonLaunchable: Boolean = false,
    val items: List<AppEntry> = emptyList()
)

class AppListController(private val catalog: ApplicationCatalog, private val store: NewDataStore) {
    private var source: List<AppEntry> = emptyList()
    fun refresh(): AppListState { source = catalog.list(); store.saveApps(source); return state() }
    fun state(query: String = "", includeSystem: Boolean = false, includeNonLaunchable: Boolean = false): AppListState {
        val normalized = query.trim().lowercase()
        val filtered = source.filter { app ->
            (includeSystem || !app.isSystem) &&
                (includeNonLaunchable || app.isLaunchable) &&
                (normalized.isEmpty() || app.label.lowercase().contains(normalized) || app.packageName.contains(normalized))
        }
        return AppListState(query, includeSystem, includeNonLaunchable, filtered)
    }
}
