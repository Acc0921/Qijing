package com.scenepilot.feature.apps

import com.scenepilot.core.data.NewDataStore
import com.scenepilot.core.model.AppEntry

data class AppListState(val query: String = "", val includeSystem: Boolean = false, val items: List<AppEntry> = emptyList())

class AppListController(private val catalog: ApplicationCatalog, private val store: NewDataStore) {
    private var source: List<AppEntry> = emptyList()
    fun refresh(): AppListState { source = catalog.list(); store.saveApps(source); return state() }
    fun state(query: String = "", includeSystem: Boolean = false): AppListState {
        val normalized = query.trim().lowercase()
        val filtered = source.filter { app ->
            (includeSystem || !app.isSystem) && (normalized.isEmpty() || app.label.lowercase().contains(normalized) || app.packageName.contains(normalized))
        }
        return AppListState(query, includeSystem, filtered)
    }
}
