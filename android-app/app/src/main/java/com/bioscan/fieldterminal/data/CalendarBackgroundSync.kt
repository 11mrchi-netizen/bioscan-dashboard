package com.bioscan.fieldterminal.data

import android.content.Context

// The calendar cache (CalendarSyncRepository -> calendar_events) and the GPX course previews built from it
// (PlannedRouteRepository -> planned_routes) had no caller, so both tables stayed empty: events could not be suggested
// from the calendar and calendar sessions could not be planned for. Status's NEXT UP already asks for a Google token on
// every visit; with one in hand this runs both, at most every [MIN_INTERVAL_MS]. Failures are swallowed: the next visit
// tries again, and NEXT UP itself reads the calendar live.
object CalendarBackgroundSync {
    private const val PREFS = "ft_calendar_sync"
    private const val KEY_LAST = "last_run_ms"
    private const val KEY_TRIED = "last_try_ms"
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    // A failed run is retried after this, not on every Status visit: a failing upload (the calendar_events POST answered
    // 400 on each visit) cost a token fetch, a calendar read and a rejected write each time the page opened.
    private const val RETRY_AFTER_FAILURE_MS = 60 * 60 * 1000L

    fun due(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return nowMs - prefs.getLong(KEY_LAST, 0L) >= MIN_INTERVAL_MS && nowMs - prefs.getLong(KEY_TRIED, 0L) >= RETRY_AFTER_FAILURE_MS
    }

    suspend fun runIfDue(context: Context, accessToken: String) {
        if (!due(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_TRIED, System.currentTimeMillis()).apply()
        val supabase = SupabaseClientProvider.client
        val synced = CalendarSyncRepository(accessToken, supabase).sync()
        if (synced is CalendarSyncResult.Success) {
            runCatching { PlannedRouteRepository(accessToken, supabase).runPipeline() }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        } else if (synced is CalendarSyncResult.Failure) {
            // The reason the server gave, kept in logcat (the upload to calendar_events has answered 400).
            android.util.Log.w("CalendarSync", "sync failed: ${synced.message}")
        }
    }
}
