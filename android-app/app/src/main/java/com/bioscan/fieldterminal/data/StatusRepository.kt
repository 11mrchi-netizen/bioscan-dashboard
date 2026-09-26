package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.HealthEventRow
import com.bioscan.fieldterminal.data.model.SleepDailyRow
import com.bioscan.fieldterminal.data.model.WearableDailyRow
import com.bioscan.fieldterminal.domain.ReadinessBand
import com.bioscan.fieldterminal.domain.computeHrvReadinessSeries
import com.bioscan.fieldterminal.domain.isHealthEventActive
import com.bioscan.fieldterminal.domain.readinessBand
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

data class StatusOverview(
    val readiness: ReadinessBand,
    val latestHrv: Double?,
    val latestRhr: Double?,
    val sleepHours: Double?,
    val activeHealthEvent: Boolean,
)

// No user_id filter anywhere here -- RLS already scopes every query to the
// signed-in user, same as the web dashboard relies on (see ROADMAP.md
// Foundation section).
class StatusRepository(private val supabase: io.github.jan.supabase.SupabaseClient) {

    suspend fun loadOverview(): StatusOverview {
        // 60 most recent days, most-recent-first from Postgrest, then
        // reversed to chronological order -- computeHrvReadinessSeries()
        // needs index i-1 to mean "the day before index i", matching how
        // index.html's own wearable.hrv array is built. 10 was too few:
        // readiness returned Unknown when only 3 of 10 days had valid HRV,
        // even with 60+ valid days in the DB.
        val wearable = supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,rhr,hrv")) {
                order("date", Order.DESCENDING)
                limit(60)
            }
            .decodeList<WearableDailyRow>()
            .reversed()

        val hrvSeries = computeHrvReadinessSeries(wearable.map { it.hrv })
        val readiness = readinessBand(hrvSeries.lastOrNull())

        val latestSleep = supabase.postgrest.from("sleep_daily")
            .select(columns = Columns.list("date,hours")) {
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<SleepDailyRow>()
            .firstOrNull()

        val today = LocalDate.now()
        val injuries = supabase.postgrest.from("injuries")
            .select(columns = Columns.list("status,end_date"))
            .decodeList<HealthEventRow>()
        val illnesses = supabase.postgrest.from("illnesses")
            .select(columns = Columns.list("status,end_date"))
            .decodeList<HealthEventRow>()
        val activeHealthEvent = (injuries + illnesses).any { row ->
            isHealthEventActive(row.status, row.endDate?.let(LocalDate::parse), today)
        }

        return StatusOverview(
            readiness = readiness,
            latestHrv = wearable.lastOrNull()?.hrv,
            latestRhr = wearable.lastOrNull()?.rhr,
            sleepHours = latestSleep?.hours,
            activeHealthEvent = activeHealthEvent,
        )
    }
}
