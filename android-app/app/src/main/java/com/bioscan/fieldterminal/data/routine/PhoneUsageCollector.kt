package com.bioscan.fieldterminal.data.routine

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.bioscan.fieldterminal.domain.routine.UsageEvent
import com.bioscan.fieldterminal.domain.routine.UsageEventType

// Routine intelligence R1 (docs/routine-intelligence/01-research-and-plan.md, section 3.2): reads Android's own usage
// events for a span and maps them onto domain/routine/PhoneUsage.kt's events. Needs Usage access, which the person grants
// in Settings (there is no runtime dialog for it), and QUERY_ALL_PACKAGES for other apps' labels and categories (Android
// 11+ hides other packages otherwise; fine for this sideloaded app). The raw events are never stored; only the day's totals are.
class PhoneUsageCollector(private val context: Context) {

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION") // deprecated in API 37 with no replacement that takes a package name
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun accessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun read(fromMs: Long, toMs: Long): List<UsageEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val raw = usm.queryEvents(fromMs, toMs)
        val out = mutableListOf<UsageEvent>()
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            val type = when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventType.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventType.UNLOCK
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.APP_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventType.APP_PAUSED
                else -> null
            } ?: continue
            out += UsageEvent(e.timeStamp, type, e.packageName)
        }
        return out
    }

    // Launchers (home screens) are screen time but not an app the person chose to use, as in Digital Wellbeing.
    fun launcherPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
    }

    fun label(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    // The category the app declares for itself (API 26+); "other" without one.
    fun declaredCategory(pkg: String): String {
        val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return "other"
        return when (info.category) {
            ApplicationInfo.CATEGORY_GAME -> "game"
            ApplicationInfo.CATEGORY_AUDIO -> "audio"
            ApplicationInfo.CATEGORY_VIDEO -> "video"
            ApplicationInfo.CATEGORY_IMAGE -> "image"
            ApplicationInfo.CATEGORY_SOCIAL -> "social"
            ApplicationInfo.CATEGORY_NEWS -> "news"
            ApplicationInfo.CATEGORY_MAPS -> "maps"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
            else -> "other"
        }
    }
}
