package com.bioscan.fieldterminal.ui.screens.today

import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.ReadCache
import com.bioscan.fieldterminal.data.ScoresView
import com.bioscan.fieldterminal.data.TodayFuel
import com.bioscan.fieldterminal.data.TodayPreviews
import java.time.LocalDate

// TODAY's finished page, kept for the life of the app process. Building it takes seconds (eight months of rows, the
// score engine twice, every scorecard), and it used to be rebuilt each time the tab was opened. Now the page is shown
// from here at once, and rebuilt in the background only when it went stale:
//   - a write to one of the tables it reads (a Health Connect sync, a log, the check-in), seen through ReadCache's
//     write stamp, which every write through the Supabase client bumps;
//   - a new day;
//   - or [STALE_MS] since it was built (stress samples and calendar entries arrive without a write from this app).
// The process going away (Android reclaiming it) clears it, and the next open builds it once.

data class TodaySnapshot(
    val day: LocalDate,
    val view: ScoresView?,
    val nut: NutritionOverview?,
    val fuel: TodayFuel?,
    val previews: TodayPreviews?,
    val closed: Set<LocalDate>,
    val builtAtMs: Long,
    val stamp: Long,
)

object TodayStore {
    const val STALE_MS = 15 * 60_000L

    /** Every table TODAY's page reads. A write to any of them makes the page stale. */
    val TABLES: Set<String> = setOf(
        "wearable_daily", "sleep_daily", "wellbeing_daily", "exercise_sessions", "planned_sessions", "meals", "meal_items",
        "hydration_daily", "evening_checkins", "stool_log", "zepp_raw_extracts", "calendar_events", "supplement_log",
        "supplements", "body_metrics", "health_concerns", "health_observations", "injuries", "illnesses",
    )

    @Volatile
    var snapshot: TodaySnapshot? = null

    /** The kept page if it is today's, fresh or not: shown at once while a stale one is rebuilt. */
    fun forDay(today: LocalDate): TodaySnapshot? = snapshot?.takeIf { it.day == today }

    fun isFresh(s: TodaySnapshot?, today: LocalDate, nowMs: Long = System.currentTimeMillis(), stamp: Long = ReadCache.writeStamp(TABLES)): Boolean =
        isFresh(s?.day, s?.builtAtMs, s?.stamp, today, nowMs, stamp)

    /** Pure form, for tests. */
    fun isFresh(day: LocalDate?, builtAtMs: Long?, builtStamp: Long?, today: LocalDate, nowMs: Long, stamp: Long): Boolean =
        day == today && builtAtMs != null && nowMs - builtAtMs in 0 until STALE_MS && builtStamp == stamp

    fun clear() {
        snapshot = null
    }
}
