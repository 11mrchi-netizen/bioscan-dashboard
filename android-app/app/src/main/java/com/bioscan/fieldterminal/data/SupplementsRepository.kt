package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.isSupplementActive
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
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
            .select(columns = Columns.list("name,dose,time_of_day,status,end_date"))
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
}
