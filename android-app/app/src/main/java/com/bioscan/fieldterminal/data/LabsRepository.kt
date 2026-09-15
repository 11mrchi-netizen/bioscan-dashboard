package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.LabDrawRow
import com.bioscan.fieldterminal.data.model.LabResultRow
import com.bioscan.fieldterminal.domain.MarkerComparison
import com.bioscan.fieldterminal.domain.mergeMarkersAcrossDraws
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

data class LabsOverview(
    val earlierDraw: LabDrawRow?,
    val latestDraw: LabDrawRow?,
    val markers: List<MarkerComparison>,
)

class LabsRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): LabsOverview {
        val draws = supabase.postgrest.from("lab_draws")
            .select(columns = Columns.list("id,draw_date,lab_name")) {
                order("draw_date", Order.ASCENDING)
            }
            .decodeList<LabDrawRow>()

        if (draws.isEmpty()) return LabsOverview(null, null, emptyList())

        val allResults = supabase.postgrest.from("lab_results")
            .select(columns = Columns.list("draw_id,marker_name,value,value_text,unit,ref_low,ref_high,flag"))
            .decodeList<LabResultRow>()

        val earlierDraw = draws.first()
        val latestDraw = draws.last() // same draw as earlierDraw if only one exists
        val earlierResults = allResults.filter { it.drawId == earlierDraw.id }
        val latestResults = allResults.filter { it.drawId == latestDraw.id }

        val markers = if (draws.size == 1) {
            mergeMarkersAcrossDraws(emptyList(), latestResults)
        } else {
            mergeMarkersAcrossDraws(earlierResults, latestResults)
        }

        return LabsOverview(
            earlierDraw = if (draws.size > 1) earlierDraw else null,
            latestDraw = latestDraw,
            markers = markers,
        )
    }
}
