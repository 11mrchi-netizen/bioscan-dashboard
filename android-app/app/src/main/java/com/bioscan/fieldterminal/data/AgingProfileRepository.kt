package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import com.bioscan.fieldterminal.domain.aging.BiologicalAgeResult
import com.bioscan.fieldterminal.domain.aging.LabDraw
import com.bioscan.fieldterminal.domain.aging.PHENOAGE_REQUIRED_MARKERS
import com.bioscan.fieldterminal.domain.aging.cardioFunctionalAge
import com.bioscan.fieldterminal.domain.aging.chronologicalAgeYears
import com.bioscan.fieldterminal.domain.aging.computePhenoAge
import com.bioscan.fieldterminal.domain.aging.toPhenoAgeInputs
import com.bioscan.fieldterminal.domain.analysis.Provenance
import com.bioscan.fieldterminal.domain.vo2MaxSeries
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

data class AgingProfileOverview(
    val chronologicalAgeYears: Int?,
    // Latest available result and full per-draw history are both exposed --
    // the User page card shows the headline, AgingProfileScreen's History
    // section shows every real draw (available or explicitly incomplete),
    // sorted oldest to newest. Sparse on purpose: this account has exactly 2
    // lab draws, and DAV-227 explicitly forbids implying a smooth trend from
    // that.
    val phenoAge: BiologicalAgeResult?,
    val phenoAgeHistory: List<BiologicalAgeResult>,
    val cardioAge: BiologicalAgeResult?,
)

@kotlinx.serialization.Serializable
private data class LabDrawRow(val id: Long, @kotlinx.serialization.SerialName("draw_date") val drawDate: String)

@kotlinx.serialization.Serializable
private data class LabResultRow(
    val id: Long,
    @kotlinx.serialization.SerialName("draw_id") val drawId: Long,
    @kotlinx.serialization.SerialName("marker_name") val markerName: String,
    val value: Double? = null,
)

// DAV-222/223/226 (09A Aging Profile, Phase 1). Two small real tables (see
// AnalysisRepository.kt's own "no server-side date filtering needed at this
// account's real volume" precedent) plus the account's VO2max series
// (domain/Training.kt's existing latestNonNullVo2Max, not a second query
// path) and the new user_profile table.
class AgingProfileRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): AgingProfileOverview {
        val profile = UserProfileRepository(supabase).loadProfile()
        val dateOfBirth = profile?.dateOfBirth?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val chronoAge = dateOfBirth?.let { chronologicalAgeYears(it) }

        if (chronoAge == null) {
            return AgingProfileOverview(null, null, emptyList(), null)
        }
        // chronoAge derives from dateOfBirth?.let{...}, so chronoAge != null implies dateOfBirth != null.
        checkNotNull(dateOfBirth)

        val drawRows = supabase.postgrest.from("lab_draws")
            .select(columns = Columns.list("id,draw_date")) {
                order("draw_date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<LabDrawRow>()

        val resultRows = supabase.postgrest.from("lab_results")
            .select(columns = Columns.list("id,draw_id,marker_name,value")) {
                limit(500)
            }
            .decodeList<LabResultRow>()
            .filter { it.markerName in PHENOAGE_REQUIRED_MARKERS && it.value != null }
            .groupBy { it.drawId }

        val draws = drawRows.mapNotNull { row ->
            val date = runCatching { LocalDate.parse(row.drawDate) }.getOrNull() ?: return@mapNotNull null
            val results = resultRows[row.id].orEmpty()
            LabDraw(
                id = row.id,
                date = date,
                markerValues = results.associate { it.markerName to it.value!! },
                markerIds = results.associate { it.markerName to it.id },
            )
        }

        // Every draw, not just the most complete one -- age computed AT that
        // draw's own date (not today), since a year-old draw should use the
        // age the person actually was then.
        val phenoAgeHistory = draws.sortedBy { it.date }.map { draw ->
            computePhenoAge(
                inputs = draw.toPhenoAgeInputs(),
                chronologicalAgeYears = chronologicalAgeYears(dateOfBirth, asOf = draw.date).toDouble(),
                observedAt = draw.date,
                provenance = Provenance("lab_results", null, null),
                inputObservationIds = draw.markerIds.values.toList(),
            )
        }
        val phenoAge = phenoAgeHistory.lastOrNull { it.isAvailable } ?: phenoAgeHistory.lastOrNull()

        val vo2Rows = supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,vo2max")) {
                order("date", Order.DESCENDING)
                limit(180)
            }
            .decodeList<Vo2MaxRow>()
            .reversed()
        val latestVo2Max = vo2MaxSeries(vo2Rows).lastOrNull()
        val cardioAge = latestVo2Max?.let { (date, vo2max) ->
            cardioFunctionalAge(vo2max, profile.sex, chronologicalAgeYears(dateOfBirth, asOf = date).toDouble(), date, Provenance("wearable_daily", null, null))
        }

        return AgingProfileOverview(chronoAge, phenoAge, phenoAgeHistory, cardioAge)
    }
}
