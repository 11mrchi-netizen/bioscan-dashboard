package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.NewSupplementRosterRow
import com.bioscan.fieldterminal.data.model.SupplementLogDateRow
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.isSupplementActive
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

data class SupplementsOverview(
    val active: List<SupplementRow>,
    val ended: List<SupplementRow>, // recently-ended only (within the 7-day cutoff) never appears
                                     // here -- it's still counted "active" by isSupplementActive,
                                     // matching the web dashboard's own display-filter semantics.
)

class SupplementsRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): SupplementsOverview {
        val rows = supabase.postgrest.from("supplements")
            .select(columns = Columns.list("id,name,dose,time_of_day,status,end_date,ai_note,every_n_days"))
            .decodeList<SupplementRow>()

        val today = LocalDate.now()
        val (active, ended) = rows.partition { row ->
            isSupplementActive(row.status, row.endDate?.let(LocalDate::parse), today)
        }

        return SupplementsOverview(
            active = active.sortedBy { it.name },
            ended = ended.sortedByDescending { it.endDate },
        )
    }

    // DAV-81: the roster itself, previously only ever readable (loadOverview()
    // above) -- AddEntrySheet.kt's logging flow already assumed this roster
    // existed, with no UI anywhere to actually build or edit it.
    suspend fun addSupplement(name: String, dose: String, timeOfDay: String, startDate: LocalDate, aiNote: String? = null, everyNDays: Int? = null) {
        supabase.postgrest.from("supplements").insert(
            NewSupplementRosterRow(name = name, dose = dose, timeOfDay = timeOfDay, status = "active", startDate = startDate.toString(), aiNote = aiNote, everyNDays = everyNDays)
        )
    }

    suspend fun updateSupplement(id: Long, name: String, dose: String, timeOfDay: String, everyNDays: Int? = null) {
        supabase.postgrest.from("supplements").update(
            buildJsonObject {
                put("name", name)
                put("dose", dose)
                put("time_of_day", timeOfDay)
                put("every_n_days", everyNDays)
            }
        ) { filter { eq("id", id) } }
    }

    // Returns the most recent taken date per supplement id for the given ids,
    // looking back up to 30 days -- enough for any interval supplement.
    suspend fun loadRecentTakenDates(supplementIds: List<Long>): Map<Long, LocalDate> {
        if (supplementIds.isEmpty()) return emptyMap()
        val since = LocalDate.now().minusDays(30).toString()
        return supabase.postgrest.from("supplement_log")
            .select(columns = Columns.list("supplement_id,taken_at")) {
                filter {
                    isIn("supplement_id", supplementIds)
                    gte("taken_at", since)
                }
                order("taken_at", Order.DESCENDING)
                limit(500)
            }
            .decodeList<SupplementLogDateRow>()
            .filter { it.supplementId != null }
            .groupBy { it.supplementId!! }
            .mapValues { (_, rows) -> LocalDate.parse(rows.first().takenAt.take(10)) }
    }

    // A supplement is "ended," never deleted -- its past supplement_log rows
    // (Log tab history) stay meaningful either way, and the ENDED section
    // already exists in the UI to hold it, matching how this table's own
    // status/end_date columns are meant to be used.
    suspend fun endSupplement(id: Long, endDate: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("supplements").update(
            buildJsonObject {
                put("status", "ended")
                put("end_date", endDate.toString())
            }
        ) { filter { eq("id", id) } }
    }
}
