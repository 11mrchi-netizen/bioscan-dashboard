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
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun due(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L) >= MIN_INTERVAL_MS

    suspend fun runIfDue(context: Context, accessToken: String) {
        if (!due(context)) return
        val supabase = SupabaseClientProvider.client
        val synced = CalendarSyncRepository(accessToken, supabase).sync()
        if (synced is CalendarSyncResult.Success) {
            runCatching { PlannedRouteRepository(accessToken, supabase).runPipeline() }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        }
    }
}
