package com.bioscan.fieldterminal.data.routine

import com.bioscan.fieldterminal.domain.routine.PhoneUsageDay
import com.bioscan.fieldterminal.domain.routine.SleepWindow
import com.bioscan.fieldterminal.domain.routine.minutesByCategory
import com.bioscan.fieldterminal.domain.routine.phoneDayStart
import com.bioscan.fieldterminal.domain.routine.phoneQueryRange
import com.bioscan.fieldterminal.domain.routine.summarisePhoneDay
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Routine intelligence R1: writes public.phone_usage_daily and public.phone_app_usage_daily
// (supabase/migrations/20261104_routine_capture.sql) from PhoneUsageCollector, and reads them back for the Setup card.
@Serializable
data class PhoneUsageDailyRow(
    val date: String,
    @SerialName("coverage_pct") val coveragePct: Int,
    @SerialName("screen_min") val screenMin: Double,
    val unlocks: Int,
    @SerialName("first_unlock_at") val firstUnlockAt: String? = null,
    @SerialName("last_use_before_sleep_at") val lastUseBeforeSleepAt: String? = null,
    @SerialName("bed_window_min") val bedWindowMin: Double? = null,
    @SerialName("night_screen_min") val nightScreenMin: Double? = null,
    @SerialName("night_unlocks") val nightUnlocks: Int? = null,
    @SerialName("longest_free_min") val longestFreeMin: Double? = null,
    @SerialName("by_category") val byCategory: Map<String, Double> = emptyMap(),
)

@Serializable
data class PhoneAppUsageRow(
    val date: String,
    @SerialName("package") val pkg: String,
    @SerialName("app_label") val appLabel: String? = null,
    val category: String,
    @SerialName("foreground_min") val foregroundMin: Double,
    val launches: Int,
    @SerialName("bed_window_min") val bedWindowMin: Double? = null,
    @SerialName("after_21_min") val after21Min: Double,
)

@Serializable
private data class SleepTimesRow(
    val date: String,
    val bedtime: String? = null,
    @SerialName("wake_time") val wakeTime: String? = null,
)

@Serializable
private data class CategoryOverrideRow(@SerialName("package") val pkg: String, val category: String)

sealed interface PhoneUsageSyncResult {
    data class Success(val daysWritten: Int) : PhoneUsageSyncResult
    data object NoAccess : PhoneUsageSyncResult
    data class Failed(val message: String) : PhoneUsageSyncResult
}

class PhoneUsageRepository(private val supabase: SupabaseClient) {

    // Recomputes the last few completed days (the system keeps the detailed events only for a short while, and a day's bed
    // window needs the night after it, which syncs the next morning). Upserts, so re-running is harmless.
    suspend fun syncRecentDays(
        collector: PhoneUsageCollector,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
        lookbackDays: Long = 3,
    ): PhoneUsageSyncResult {
        if (!collector.hasAccess()) return PhoneUsageSyncResult.NoAccess
        return try {
            val today = now.atZone(zone).toLocalDate()
            val days = (lookbackDays downTo 1).map { today.minusDays(it) }
                .filter { phoneDayStart(it.plusDays(1), zone) <= now }
            if (days.isEmpty()) return PhoneUsageSyncResult.Success(0)
            val sleep = loadSleep(days.first(), today)
            val overrides = loadCategoryOverrides()
            val launchers = collector.launcherPackages()
            var written = 0
            for (date in days) {
                val night = sleep[date.plusDays(1)]
                // Without the night's sleep record yet, wait until the afternoon after for it to sync.
                if (night == null && now < phoneDayStart(date.plusDays(1), zone).plusSeconds(10 * 3600)) continue
                val range = phoneQueryRange(date, zone, night)
                val events = collector.read(range.first, range.last + 1)
                if (events.isEmpty()) continue // unknown, not a day without the phone
                val day = summarisePhoneDay(date, zone, events, night, sleep[date]?.wake, launchers)
                val categoryOf = { pkg: String -> overrides[pkg] ?: collector.declaredCategory(pkg) }
                upsertDay(day, categoryOf, collector::label)
                written++
            }
            PhoneUsageSyncResult.Success(written)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PhoneUsageSyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun upsertDay(day: PhoneUsageDay, categoryOf: (String) -> String, labelOf: (String) -> String?) {
        val date = day.date.toString()
        supabase.postgrest.from("phone_usage_daily").upsert(
            PhoneUsageDailyRow(
                date = date,
                coveragePct = 100,
                screenMin = day.screenMin,
                unlocks = day.unlocks,
                firstUnlockAt = day.firstUnlockAt?.toString(),
                lastUseBeforeSleepAt = day.lastUseBeforeSleepAt?.toString(),
                bedWindowMin = day.bedWindowMin,
                nightScreenMin = day.nightScreenMin,
                nightUnlocks = day.nightUnlocks,
                longestFreeMin = day.longestFreeMin,
                byCategory = minutesByCategory(day.apps, categoryOf),
            ),
        ) { onConflict = "user_id,date" }
        if (day.apps.isEmpty()) return
        supabase.postgrest.from("phone_app_usage_daily").upsert(
            day.apps.map {
                PhoneAppUsageRow(
                    date = date,
                    pkg = it.pkg,
                    appLabel = labelOf(it.pkg),
                    category = categoryOf(it.pkg),
                    foregroundMin = it.foregroundMin,
                    launches = it.launches,
                    bedWindowMin = it.bedWindowMin,
                    after21Min = it.after21Min,
                )
            },
        ) { onConflict = "user_id,date,package" }
    }

    private suspend fun loadSleep(from: LocalDate, to: LocalDate): Map<LocalDate, SleepWindow> =
        supabase.postgrest.from("sleep_daily")
            .select(columns = Columns.list("date,bedtime,wake_time")) {
                filter {
                    gte("date", from.toString())
                    lte("date", to.toString())
                }
            }
            .decodeList<SleepTimesRow>()
            .mapNotNull { row ->
                val onset = row.bedtime?.let(::parseTimestamp) ?: return@mapNotNull null
                val wake = row.wakeTime?.let(::parseTimestamp) ?: return@mapNotNull null
                if (!wake.isAfter(onset)) return@mapNotNull null
                LocalDate.parse(row.date) to SleepWindow(onset, wake)
            }
            .toMap()

    private suspend fun loadCategoryOverrides(): Map<String, String> =
        supabase.postgrest.from("phone_app_categories")
            .select(columns = Columns.list("package,category"))
            .decodeList<CategoryOverrideRow>()
            .associate { it.pkg to it.category }

    suspend fun setCategory(pkg: String, category: String) {
        supabase.postgrest.from("phone_app_categories")
            .upsert(CategoryOverrideRow(pkg, category)) { onConflict = "user_id,package" }
    }

    suspend fun latestDay(): PhoneUsageDailyRow? =
        supabase.postgrest.from("phone_usage_daily")
            .select(columns = Columns.list("date,coverage_pct,screen_min,unlocks,first_unlock_at,last_use_before_sleep_at,bed_window_min,night_screen_min,night_unlocks,longest_free_min,by_category")) {
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<PhoneUsageDailyRow>()
            .firstOrNull()

    suspend fun topApps(date: String, limit: Long = 5): List<PhoneAppUsageRow> =
        supabase.postgrest.from("phone_app_usage_daily")
            .select(columns = Columns.list("date,package,app_label,category,foreground_min,launches,bed_window_min,after_21_min")) {
                filter { eq("date", date) }
                order("foreground_min", Order.DESCENDING)
                limit(limit)
            }
            .decodeList<PhoneAppUsageRow>()

    // DELETE ALL in Setup: every phone-use row of this account (RLS limits a delete to the caller's own rows).
    suspend fun deleteAll() {
        supabase.postgrest.from("phone_app_usage_daily").delete { filter { gte("date", "1900-01-01") } }
        supabase.postgrest.from("phone_usage_daily").delete { filter { gte("date", "1900-01-01") } }
        supabase.postgrest.from("phone_app_categories").delete { filter { neq("package", "") } }
        supabase.postgrest.from("collector_heartbeats").delete { filter { eq("stream", "phone_usage") } }
    }
}

// Postgres returns timestamptz as "2026-10-20T15:30:00+00:00"; Android's Instant.parse accepts only a trailing Z.
internal fun parseTimestamp(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
