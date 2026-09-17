package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.LabDrawIdRow
import com.bioscan.fieldterminal.data.model.LabDrawRow
import com.bioscan.fieldterminal.data.model.LabResultRow
import com.bioscan.fieldterminal.data.model.NewLabDrawRow
import com.bioscan.fieldterminal.data.model.NewLabResultRow
import com.bioscan.fieldterminal.domain.MarkerComparison
import com.bioscan.fieldterminal.domain.mergeMarkersAcrossDraws
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

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

    // DAV-84. A real panel reports many markers for one draw date -- entering
    // them one at a time here should add to that same draw, not fragment
    // into a new one-marker draw per entry, so this finds-or-creates by date
    // rather than always inserting a fresh lab_draws row.
    suspend fun addLabResult(date: LocalDate, markerName: String, value: Double?, unit: String?, refLow: Double?, refHigh: Double?) {
        val drawId = findOrCreateDraw(date)
        // Only ever high/low/normal, computed from this one value against
        // its own range -- "watch" (a real 3rd flag this table already uses
        // elsewhere) means something more than a single reading can justify,
        // so a manual entry never assigns it.
        val flag = when {
            refLow != null && value != null && value < refLow -> "low"
            refHigh != null && value != null && value > refHigh -> "high"
            value != null && (refLow != null || refHigh != null) -> "normal"
            else -> null
        }
        supabase.postgrest.from("lab_results").insert(
            NewLabResultRow(drawId = drawId, markerName = markerName, value = value, unit = unit, refLow = refLow, refHigh = refHigh, flag = flag)
        )
    }

    private suspend fun findOrCreateDraw(date: LocalDate): Long {
        val existing = supabase.postgrest.from("lab_draws")
            .select(columns = Columns.list("id")) { filter { eq("draw_date", date.toString()) } }
            .decodeList<LabDrawIdRow>()
            .firstOrNull()
        if (existing != null) return existing.id

        return supabase.postgrest.from("lab_draws")
            .insert(NewLabDrawRow(drawDate = date.toString(), labName = "Manual entry")) { select(Columns.list("id")) }
            .decodeSingle<LabDrawIdRow>()
            .id
    }
}
