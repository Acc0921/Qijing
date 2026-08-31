package com.scenepilot.core.scene

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class UsageAccessState(val granted: Boolean, val reason: String? = null)

@Suppress("DEPRECATION")
class UsageStatsForegroundAppSource(private val context: Context) : ForegroundAppSource {
    fun accessState(): UsageAccessState {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_ALLOWED) UsageAccessState(true) else UsageAccessState(false, "需要在系统设置中授予使用情况访问权限")
    }

    override fun currentPackageName(): String? {
        if (!accessState().granted) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        return manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 15_000, now)
            ?.maxByOrNull { it.lastTimeUsed }?.packageName
    }
}
