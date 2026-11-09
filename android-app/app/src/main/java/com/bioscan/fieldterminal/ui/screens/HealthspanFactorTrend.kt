package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import com.bioscan.fieldterminal.domain.aging.FactorStatus
import com.bioscan.fieldterminal.domain.aging.HealthspanFactor
import com.bioscan.fieldterminal.domain.aging.HealthspanFactorId
import com.bioscan.fieldterminal.domain.vo2MaxSeries
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.screens.today.FullScreenOverlay
import com.bioscan.fieldterminal.ui.screens.today.OverlaySection
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

// ME › Healthspan: tapping a factor opens this page -- the score and what it rests on, then the trend of each metric
// that feeds it, so the number is never a dead end. The series are read straight from the same sources the score uses
// (analysis-core's daily tables, the lab results, grip and strength logs). Biological age is not here: it opens the
// Aging page, which already carries the biological-age history and its inputs.

/** One metric behind a factor, as a dated series to chart. */
data class FactorMetric(val label: String, val unit: String, val color: Color, val points: List<Pair<LocalDate, Double>>)

@Serializable
private data class GripLogRow(@SerialName("occurred_at") val occurredAt: String, val kg: Double)

private fun parseDay(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

/** The time-series behind a factor's score. Empty when the factor has no chartable history (or is biological age). */
suspend fun loadHealthspanFactorMetrics(supabase: SupabaseClient, factorId: HealthspanFactorId): List<FactorMetric> {
    val analysis = AnalysisRepository(supabase)
    return when (factorId) {
        HealthspanFactorId.CARDIO_FITNESS -> {
            val vo2 = runCatching {
                supabase.postgrest.from("wearable_daily").select(Columns.list("date,vo2max")) { order("date", Order.ASCENDING); limit(400) }
                    .decodeList<Vo2MaxRow>()
            }.getOrDefault(emptyList())
            val wd = runCatching { analysis.loadWearableDaily() }.getOrDefault(emptyList())
            val rhr = wd.mapNotNull { r -> parseDay(r.date)?.let { d -> r.rhr?.let { d to it } } }.sortedBy { it.first }
            listOf(
                FactorMetric("VO2MAX", "ml/kg/min", FT.Metric.Vo2max, vo2MaxSeries(vo2)),
                FactorMetric("RESTING HR", "bpm", FT.Metric.RestingHr, rhr),
            ).filter { it.points.isNotEmpty() }
        }
        HealthspanFactorId.ACTIVITY -> {
            val wd = runCatching { analysis.loadWearableDaily() }.getOrDefault(emptyList())
            // A 0-step day is the band not worn, not a day of no movement.
            val steps = wd.mapNotNull { r -> parseDay(r.date)?.let { d -> r.steps?.takeIf { it > 0 }?.let { d to it } } }.sortedBy { it.first }
            listOf(FactorMetric("STEPS", "per day", FT.Metric.Steps, steps)).filter { it.points.isNotEmpty() }
        }
        HealthspanFactorId.SLEEP -> {
            val sd = runCatching { analysis.loadSleepDaily() }.getOrDefault(emptyList())
            val hours = sd.mapNotNull { r -> parseDay(r.date)?.let { d -> r.hours?.let { d to it } } }.sortedBy { it.first }
            listOf(FactorMetric("SLEEP", "h", FT.Metric.SleepDuration, hours)).filter { it.points.isNotEmpty() }
        }
        HealthspanFactorId.BODY_COMPOSITION -> {
            val bm = runCatching { analysis.loadBodyMetrics() }.getOrDefault(emptyList())
            val fat = bm.mapNotNull { r -> parseDay(r.date)?.let { d -> r.bodyFatPct?.let { d to it } } }.sortedBy { it.first }
            val wt = bm.mapNotNull { r -> parseDay(r.date)?.let { d -> r.weightKg?.let { d to it } } }.sortedBy { it.first }
            listOf(
                FactorMetric("BODY FAT", "%", FT.Metric.BodyFat, fat),
                FactorMetric("WEIGHT", "kg", FT.Metric.Weight, wt),
            ).filter { it.points.isNotEmpty() }
        }
        HealthspanFactorId.METABOLIC -> listOf(
            FactorMetric("TRIGLYCERIDES", "mg/dL", FT.Metric.BloodPressure, labSeries(analysis, listOf("Triglyceride", "Triglycerides"))),
            FactorMetric("HDL", "mg/dL", FT.Metric.Spo2, labSeries(analysis, listOf("HDL Cholesterol", "HDL"))),
        ).filter { it.points.isNotEmpty() }
        HealthspanFactorId.GLYCAEMIC -> listOf(
            FactorMetric("FASTING GLUCOSE", "mg/dL", FT.Metric.MealTiming, labSeries(analysis, listOf("Fasting blood sugar", "Fasting glucose"))),
            FactorMetric("HBA1C", "%", FT.Metric.SkinTemp, labSeries(analysis, listOf("HbA1c", "Hemoglobin A1c"))),
        ).filter { it.points.isNotEmpty() }
        HealthspanFactorId.STRENGTH -> {
            val grip = runCatching {
                supabase.postgrest.from("grip_strength_log").select(Columns.list("occurred_at,kg")) { order("occurred_at", Order.ASCENDING); limit(200) }
                    .decodeList<GripLogRow>()
            }.getOrDefault(emptyList())
                .mapNotNull { r -> runCatching { OffsetDateTime.parse(r.occurredAt).toLocalDate() }.getOrNull()?.let { it to r.kg } }
                .sortedBy { it.first }
            listOf(FactorMetric("GRIP", "kg", FT.Metric.Grip, grip)) + liftOneRepMaxSeries(supabase)
        }
        // Biological age opens the Aging page instead (it already has the history and inputs).
        HealthspanFactorId.BIOLOGICAL_AGE -> emptyList()
    }
}

private suspend fun labSeries(analysis: AnalysisRepository, names: List<String>): List<Pair<LocalDate, Double>> {
    val draws = runCatching { analysis.loadLabDraws() }.getOrDefault(emptyList())
        .mapNotNull { d -> parseDay(d.drawDate)?.let { d.id to it } }.toMap()
    return runCatching { analysis.loadLabResults() }.getOrDefault(emptyList())
        .filter { r -> names.any { r.markerName.equals(it, ignoreCase = true) } }
        .mapNotNull { r -> val d = draws[r.drawId]; val v = r.value; if (d != null && v != null) d to v else null }
        .sortedBy { it.first }
}

// Best estimated 1RM (Epley) per day for the two most-logged lifts of the last year.
private val LIFT_COLORS = listOf(FT.Metric.Power, FT.Metric.MusclePotential)

private suspend fun liftOneRepMaxSeries(supabase: SupabaseClient): List<FactorMetric> {
    val rows = runCatching {
        supabase.postgrest.from("exercise_sessions").select(Columns.list("id,type,start_time,source,details")) {
            filter { eq("type", "strength"); gte("start_time", LocalDate.now().minusDays(365).toString()) }
            limit(1000)
        }.decodeList<ExerciseSessionRow>()
    }.getOrDefault(emptyList())
    data class SetE(val lift: String, val day: LocalDate, val oneRm: Double)
    val sets = rows.flatMap { s ->
        val day = runCatching { OffsetDateTime.parse(s.startTime).toLocalDate() }.getOrNull() ?: return@flatMap emptyList<SetE>()
        s.details.exercises.orEmpty().flatMap { ex ->
            ex.sets.filter { it.reps in 1..12 && it.weightKg > 0 }.map { SetE(ex.name, day, it.weightKg * (1 + it.reps / 30.0)) }
        }
    }
    val byLift = sets.groupBy { it.lift }
    return byLift.entries
        .sortedByDescending { it.value.map { s -> s.day }.distinct().size }
        .take(2)
        .mapIndexed { i, (lift, es) ->
            val perDay = es.groupBy { it.day }.mapValues { (_, v) -> v.maxOf { it.oneRm } }.toList().sortedBy { it.first }
            FactorMetric(lift.uppercase(), "kg 1RM", LIFT_COLORS[i % LIFT_COLORS.size], perDay)
        }
        .filter { it.points.size >= 2 }
}

private fun FactorStatus.pair(): Pair<String, Color> = when (this) {
    FactorStatus.FAVORABLE -> "FAVORABLE" to FT.Emerald
    FactorStatus.NEUTRAL -> "NEUTRAL" to FT.TextSecondary
    FactorStatus.OPPORTUNITY -> "OPPORTUNITY" to FT.Warning
    FactorStatus.UNASSESSED -> "NOT ASSESSED" to FT.TextMuted
}

/** The page a Healthspan factor opens: its score and basis, then a trend for each metric that feeds it. */
@Composable
fun HealthspanFactorOverlay(factor: HealthspanFactor, onClose: () -> Unit) {
    val metrics by produceState<List<FactorMetric>?>(null, factor.id) {
        value = runCatching { loadHealthspanFactorMetrics(SupabaseClientProvider.client, factor.id) }.getOrDefault(emptyList())
    }
    FullScreenOverlay(title = factor.id.label.uppercase(), onClose = onClose) {
        val (tag, color) = factor.status.pair()
        Text(factor.score?.let { "$it / 100 · $tag" } ?: tag, style = FTType.LabelCaps, color = color)
        factor.reading?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
        factor.basis?.let { Text("Scored against ${it.label}.", style = FTType.Caption, color = FT.TextMuted) }
        if (factor.missingInputs.isNotEmpty()) Text("Needs: " + factor.missingInputs.joinToString("; "), style = FTType.Caption, color = FT.TextMuted)
        factor.notes.forEach { Text(it, style = FTType.Caption, color = FT.TextMuted) }
        factor.action?.let { Text(it, style = FTType.Caption, color = if (factor.status == FactorStatus.OPPORTUNITY) FT.Warning else FT.TextMuted) }

        val m = metrics
        when {
            m == null -> Text("Loading the trends…", style = FTType.BodySmall, color = FT.TextMuted)
            m.isEmpty() -> Text("No history to chart yet for this factor.", style = FTType.BodySmall, color = FT.TextMuted)
            else -> m.forEach { fm ->
                OverlaySection(fm.label) {
                    if (fm.points.size < 2) {
                        Text("Only one reading so far.", style = FTType.Caption, color = FT.TextMuted)
                    } else {
                        DateTrendLine(
                            points = fm.points,
                            color = fm.color,
                            unit = fm.unit,
                            valueFormat = { if (kotlin.math.abs(it) >= 100) "%.0f".format(it) else "%.1f".format(it) },
                            maxGapDays = 400,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
