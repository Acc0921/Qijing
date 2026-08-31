package com.qijing.feature.apps

import android.content.Context
import com.qijing.core.model.AppEntry

class ApplicationCatalog(private val context: Context) {
    fun list(): List<AppEntry> = context.packageManager.getInstalledPackages(0).mapNotNull { info ->
        val label = info.applicationInfo?.loadLabel(context.packageManager)?.toString() ?: return@mapNotNull null
        AppEntry(info.packageName, label, info.versionName.orEmpty(), (info.applicationInfo?.flags ?: 0) and 1 != 0)
    }.sortedBy { it.label.lowercase() }
}
