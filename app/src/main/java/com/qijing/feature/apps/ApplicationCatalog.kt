package com.qijing.feature.apps

import android.content.Context
import android.content.Intent
import com.qijing.core.model.AppEntry

class ApplicationCatalog(private val context: Context) {
    @Suppress("DEPRECATION")
    fun list(): List<AppEntry> {
        val packageManager = context.packageManager
        val launcherPackages = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        ).mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
        return packageManager.getInstalledPackages(0).mapNotNull { info ->
            val label = info.applicationInfo?.loadLabel(packageManager)?.toString() ?: return@mapNotNull null
            AppEntry(
                packageName = info.packageName,
                label = label,
                versionName = info.versionName.orEmpty(),
                isSystem = (info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                isLaunchable = info.packageName in launcherPackages
            )
        }.sortedBy { it.label.lowercase() }
    }
}
