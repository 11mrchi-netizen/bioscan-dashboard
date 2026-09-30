package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.FocusEntryDto
import com.bioscan.fieldterminal.data.model.NewTrainingCycleRow
import com.bioscan.fieldterminal.data.model.TrainingCycleRow
import com.bioscan.fieldterminal.domain.FocusEntry
import com.bioscan.fieldterminal.domain.FocusQuality
import com.bioscan.fieldterminal.domain.FocusRole
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.toDb
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

// Phase A3 (Analysis Layer). A cycle with a malformed quality/role value is
// dropped from its own focus list rather than crashing the whole read --
// defensive parsing, same convention this app already uses for timestamp
// parsing elsewhere (domain/Log.kt's parseTimestamp()).
//
// Real create/edit UI added per user request once training_cycles started
// getting real rows (manually entered + a separate Notion historical
// import) -- insertCycle/updateCycle only support a single stated focus
// (role=Primary, weight=1.0), not DAV-291's full concurrent-multi-focus
// shape, since no form/picker convention exists yet for editing a list of
// typed entries. Multi-focus blocks remain read-correctly (loadCycles still
// parses every focus entry), just not editable as multi-focus through this
// form -- a real, smaller-than-ideal scope, not silently dropped.
class TrainingCyclesRepository(private val supabase: SupabaseClient) {

    suspend fun loadCycles(): List<TrainingCycle> {
        val rows = supabase.postgrest.from("training_cycles")
            .select(columns = Columns.list("id,start_date,end_date,focus,goal_metric,starting_value,target_value")) {
                order("start_date", Order.DESCENDING)
                limit(50)
            }
            .decodeList<TrainingCycleRow>()
        return rows.mapNotNull { it.toDomain() }
    }

    suspend fun loadActiveCycle(asOf: LocalDate = LocalDate.now()): TrainingCycle? =
        loadCycles().firstOrNull { it.isActiveOn(asOf) }

    suspend fun insertCycle(
        startDate: LocalDate,
        endDate: LocalDate?,
        focus: FocusQuality?,
        goalMetric: String?,
        startingValue: Double?,
        targetValue: Double?,
    ) {
        supabase.postgrest.from("training_cycles").insert(
            NewTrainingCycleRow(
                startDate = startDate.toString(),
                endDate = endDate?.toString(),
                focus = focus?.let { listOf(FocusEntryDto(it.toDb(), 1.0, "primary")) } ?: emptyList(),
                goalMetric = goalMetric,
                startingValue = startingValue,
                targetValue = targetValue,
            ),
        )
    }

    suspend fun updateCycle(
        id: Long,
        startDate: LocalDate,
        endDate: LocalDate?,
        focus: FocusQuality?,
        goalMetric: String?,
        startingValue: Double?,
        targetValue: Double?,
    ) {
        supabase.postgrest.from("training_cycles").update(
            NewTrainingCycleRow(
                startDate = startDate.toString(),
                endDate = endDate?.toString(),
                focus = focus?.let { listOf(FocusEntryDto(it.toDb(), 1.0, "primary")) } ?: emptyList(),
                goalMetric = goalMetric,
                startingValue = startingValue,
                targetValue = targetValue,
            ),
        ) { filter { eq("id", id) } }
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
    return TrainingCycle(start, end, entries, goalMetric, startingValue, targetValue, id)
}
