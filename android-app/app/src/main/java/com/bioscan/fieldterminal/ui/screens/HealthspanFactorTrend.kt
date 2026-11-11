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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.setValue
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

/**
 * One metric behind a factor, as a dated series to chart. [lines] splits it into several drawn lines (grip: one per
 * hand); [daily] is a series measured most days (a wearable), the others are occasional (labs, grip, lifts).
 */
data class FactorMetric(
    val label: String,
    val unit: String,
    val color: Color,
    val points: List<Pair<LocalDate, Double>>,
    val lines: List<FactorLine>? = null,
    val daily: Boolean = false,
)

data class FactorLine(val label: String, val color: Color, val points: List<Pair<LocalDate, Double>>)

@Serializable
private data class GripLogRow(@SerialName("occurred_at") val occurredAt: String, val kg: Double, val hand: String? = null)

private fun parseDay(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

/** The time-series behind a factor's score. Empty when the factor has no chartable history (or is biological age). */
suspend fun loadHealthspanFactorMetrics(supabase: SupabaseClient, factorId: HealthspanFactorId): List<FactorMetric> {
    val analysis = AnalysisRepository(supabase)
    return when (factorId) {
        HealthspanFactorId.CARDIO_FITNESS -> {
            val vo2 = runCatching {
                // The newest 800 days (an ascending query with a limit read the oldest ones).
                supabase.postgrest.from("wearable_daily").select(Columns.list("date,vo2max")) {
                    filter { filterNot("vo2max", io.github.jan.supabase.postgrest.query.filter.FilterOperator.IS, "null") }
                    order("date", Order.DESCENDING); limit(800)
                }.decodeList<Vo2MaxRow>().sortedBy { it.date }
            }.getOrDefault(emptyList())
            val wd = runCatching { analysis.loadWearableDaily() }.getOrDefault(emptyList())
            val rhr = wd.mapNotNull { r -> parseDay(r.date)?.let { d -> r.rhr?.let { d to it } } }.sortedBy { it.first }
            listOf(
                FactorMetric("VO2MAX", "ml/kg/min", FT.Metric.Vo2max, vo2MaxSeries(vo2), daily = true),
                FactorMetric("RESTING HR", "bpm", FT.Metric.RestingHr, rhr, daily = true),
            ).filter { it.points.isNotEmpty() }
        }
        HealthspanFactorId.ACTIVITY -> {
            val wd = runCatching { analysis.loadWearableDaily() }.getOrDefault(emptyList())
            // A 0-step day is the band not worn, not a day of no movement.
            val steps = wd.mapNotNull { r -> parseDay(r.date)?.let { d -> r.steps?.takeIf { it > 0 }?.let { d to it } } }.sortedBy { it.first }
            listOf(FactorMetric("STEPS", "per day", FT.Metric.Steps, steps, daily = true)).filter { it.points.isNotEmpty() }
        }
        HealthspanFactorId.SLEEP -> {
            val sd = runCatching { analysis.loadSleepDaily() }.getOrDefault(emptyList())
            val hours = sd.mapNotNull { r -> parseDay(r.date)?.let { d -> r.hours?.takeIf { it > 0 }?.let { d to it } } }.sortedBy { it.first }
            val restorative = sd.mapNotNull { r ->
                val deep = r.deepMin; val rem = r.remMin
                if (deep == null || rem == null || deep + rem <= 0) null else parseDay(r.date)?.let { it to (deep + rem) / 60.0 }
            }.sortedBy { it.first }
            listOf(
                FactorMetric("SLEEP", "h", FT.Metric.SleepDuration, hours, daily = true),
                FactorMetric("DEEP + REM", "h", FT.Metric.StageRem, restorative, daily = true),
            ).filter { it.points.isNotEmpty() }
        }
        HealthspanFactorId.BODY_COMPOSITION -> {
            val bm = runCatching { analysis.loadBodyMetrics() }.getOrDefault(emptyList())
            val fat = bm.mapNotNull { r -> parseDay(r.date)?.let { d -> r.bodyFatPct?.let { d to it } } }.sortedBy { it.first }
            val wt = bm.mapNotNull { r -> parseDay(r.date)?.let { d -> r.weightKg?.let { d to it } } }.sortedBy { it.first }
            listOf(
                FactorMetric("BODY FAT", "%", FT.Metric.BodyFat, fat, daily = true),
                FactorMetric("WEIGHT", "kg", FT.Metric.Weight, wt, daily = true),
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
            // Grip is logged per hand: one line each (a single line through both zigzagged between the hands on every
            // day), the day's best squeeze per hand. occurred_at is a logged time: its digits are the local day.
            val rows = runCatching {
                supabase.postgrest.from("grip_strength_log").select(Columns.list("occurred_at,kg,hand")) { order("occurred_at", Order.DESCENDING); limit(500) }
                    .decodeList<GripLogRow>()
            }.getOrDefault(emptyList())
            fun perDay(hand: String?) = rows.filter { it.hand == hand }
                .mapNotNull { r -> com.bioscan.fieldterminal.domain.WallClock.date(r.occurredAt)?.let { it to r.kg } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.max() }.toList().sortedBy { it.first }
            val right = perDay("right")
            val left = perDay("left")
            val other = perDay(null)
            val best = (right + left + other).groupBy({ it.first }, { it.second }).mapValues { it.value.max() }.toList().sortedBy { it.first }
            val lines = listOfNotNull(
                right.takeIf { it.isNotEmpty() }?.let { FactorLine("RIGHT", FT.Metric.Grip, it) },
                left.takeIf { it.isNotEmpty() }?.let { FactorLine("LEFT", FT.Metric.Grip.copy(alpha = 0.55f), it) },
                other.takeIf { it.isNotEmpty() }?.let { FactorLine("UNSPECIFIED", FT.TextSecondary, it) },
            )
            listOfNotNull(best.takeIf { it.isNotEmpty() }?.let { FactorMetric("GRIP", "kg", FT.Metric.Grip, it, lines = lines) }) + liftOneRepMaxSeries(supabase)
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
        val day = com.bioscan.fieldterminal.domain.WallClock.date(s.startTime) ?: return@flatMap emptyList<SetE>()
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

internal enum class FactorRange(val label: String, val days: Long?) { D28("28 D", 28), D90("90 D", 90), Y1("1 Y", 365), ALL("ALL", null) }

private fun fmt(v: Double): String = if (kotlin.math.abs(v) >= 100) "%.0f".format(v) else "%.1f".format(v)
private val DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * The page a Healthspan factor opens: its score and basis, then each metric that feeds it over the range picked at the
 * top (28 days by default; all of it for the lab-based factors, whose draws are months apart): the latest reading, the
 * range's average, and the chart framed to the range.
 */
@Composable
fun HealthspanFactorOverlay(factor: HealthspanFactor, onClose: () -> Unit) {
    val metrics by produceState<List<FactorMetric>?>(null, factor.id) {
        value = runCatching { loadHealthspanFactorMetrics(SupabaseClientProvider.client, factor.id) }.getOrDefault(emptyList())
    }
    val labBased = factor.id == HealthspanFactorId.METABOLIC || factor.id == HealthspanFactorId.GLYCAEMIC
    var range by androidx.compose.runtime.saveable.rememberSaveable(factor.id) { androidx.compose.runtime.mutableStateOf(if (labBased) FactorRange.ALL else FactorRange.D28) }
    val today = androidx.compose.runtime.remember { LocalDate.now() }
    FullScreenOverlay(title = factor.id.label.uppercase(), onClose = onClose) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            for (r in FactorRange.entries) com.bioscan.fieldterminal.ui.screens.detail.Chip(r.label, r == range) { range = r }
        }
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
            else -> m.forEach { fm -> FactorMetricSection(fm, range, today) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun FactorMetricSection(fm: FactorMetric, range: FactorRange, today: LocalDate) {
    val from = range.days?.let { today.minusDays(it - 1) } ?: fm.points.minOfOrNull { it.first } ?: today
    fun clip(p: List<Pair<LocalDate, Double>>) = p.filter { !it.first.isBefore(from) && !it.first.isAfter(today) }
    val shown = clip(fm.points)
    val latest = fm.points.maxByOrNull { it.first }
    OverlaySection(fm.label) {
        latest?.let { (d, v) ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                Text(fmt(v), style = FTType.MetricMedium, color = FT.TextPrimary)
                Text(fm.unit, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                Text("· ${d.format(DAY)}", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        if (shown.size >= 2) {
            Text(
                "${range.label.lowercase().replace(" d", " days").replace("1 y", "the year").replace("all", "all of it")}: average ${fmt(shown.map { it.second }.average())}, " +
                    "${fmt(shown.minOf { it.second })}–${fmt(shown.maxOf { it.second })} ${fm.unit} · ${shown.size} readings",
                style = FTType.Caption, color = FT.TextSecondary,
            )
        }
        val lines = (fm.lines ?: listOf(FactorLine(fm.label, fm.color, fm.points))).map { it.copy(points = clip(it.points)) }.filter { it.points.isNotEmpty() }
        if (lines.isEmpty()) {
            Text("No readings in this range.", style = FTType.Caption, color = FT.TextMuted)
        } else {
            DateTrendLine(
                series = lines.map { l ->
                    com.bioscan.fieldterminal.ui.components.TrendSeries(l.points, l.color, label = l.label, dashed = l.label == "LEFT", maxGapDays = if (fm.daily) 3 else 400)
                },
                valueFormat = ::fmt,
                unit = fm.unit,
                legend = lines.size > 1,
                dateRange = from..today,
                chartHeight = 140.dp,
            )
        }
    }
}
