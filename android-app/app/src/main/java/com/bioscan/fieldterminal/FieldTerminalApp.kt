package com.bioscan.fieldterminal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.bioscan.fieldterminal.data.MetricRegistryStore
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.util.Perf

// Performance pass, Stage 3. Everything that used to run on the main thread before the first frame (building the Supabase
// client, five notification-channel IPC calls, parsing the 37 kB metric registry on the first help button) now starts here,
// on one background thread, while the Activity is being created. All of it is idempotent and lazy-safe: if a screen asks for
// the client or the registry first, it simply takes the same lock and gets the same instance.
class FieldTerminalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Perf.enabled = BuildConfig.DEBUG // logcat tag "perf"; see util/Perf.kt
        Thread({
            Perf.timed("startup.warm") {
                runCatching { SupabaseClientProvider.client }
                runCatching { MetricRegistryStore.get(this, "hrv") }
                runCatching { createNotificationChannels() }
            }
        }, "ft-warmup").start()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        listOf(
            Triple(NotificationKind.SUPPLEMENT_DUE, "Supplement Reminders", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.CHECKIN_MORNING, "Morning Check-in", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.CHECKIN_EVENING, "Evening Check-in", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.WELLBEING_CHECKIN, "Wellbeing Check-in", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.EVENING_LOG, "Evening Log", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.EVENING_CHECKIN, "Evening Check-in (structured)", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.BODY_CHECKIN, "Monthly Body Check-in", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.HEALTH_CHECKIN, "Health Check-in", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.FUEL_SESSION, "Fuel before and after sessions", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(NotificationKind.FUEL_EVENT, "Race week fuelling", NotificationManager.IMPORTANCE_HIGH),
        ).forEach { (id, name, importance) ->
            nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}
