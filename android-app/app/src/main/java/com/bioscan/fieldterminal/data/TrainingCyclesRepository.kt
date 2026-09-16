package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.TrainingCycleRow
import com.bioscan.fieldterminal.domain.FocusEntry
import com.bioscan.fieldterminal.domain.FocusQuality
import com.bioscan.fieldterminal.domain.FocusRole
import com.bioscan.fieldterminal.domain.TrainingCycle
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

// Phase A3 (Analysis Layer). `training_cycles` has no create/edit UI yet
// (see ROADMAP.md Phase A1 -- that's flagged there as future work, not part
// of this phase), so this is read-only: find whichever cycle's date range
// contains today, if any. A cycle with a malformed quality/role value is
// dropped from its own focus list rather than crashing the whole read --
// defensive parsing, same convention this app already uses for timestamp
// parsing elsewhere (domain/Log.kt's parseTimestamp()).
class TrainingCyclesRepository(private val supabase: SupabaseClient) {

    suspend fun loadActiveCycle(asOf: LocalDate = LocalDate.now()): TrainingCycle? {
        val rows = supabase.postgrest.from("training_cycles")
            .select(columns = Columns.list("start_date,end_date,focus")) {
                order("start_date", Order.DESCENDING)
                limit(50)
            }
            .decodeList<TrainingCycleRow>()

        return rows.mapNotNull { it.toDomain() }.firstOrNull { it.isActiveOn(asOf) }
    }
}

private fun TrainingCycleRow.toDomain(): TrainingCycle? {
    val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return null
    val end = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val entries = focus.mapNotNull { dto ->
        val quality = FocusQuality.fromDb(dto.quality) ?: return@mapNotNull null
        val role = when (dto.role) {
            "primary" -> FocusRole.Primary
            "maintained" -> FocusRole.Maintained
            else -> return@mapNotNull null
        }
        FocusEntry(quality, dto.weight, role)
    }
    return TrainingCycle(start, end, entries)
}
