package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.compositeInputs
import com.bioscan.fieldterminal.domain.computeCircadianAlignment
import com.bioscan.fieldterminal.domain.demotes
import com.bioscan.fieldterminal.domain.evaluateSri
import com.bioscan.fieldterminal.domain.sleepIndex
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.screens.status.rememberScorecards
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val DEEP = FT.Metric.StageDeep
private val LIGHT = FT.Metric.StageLight
private val REM = FT.Metric.StageRem

internal fun List<SleepAnalysisRow>.series(pick: (SleepAnalysisRow) -> Double?): Series =
    mapNotNull { r -> pick(r)?.let { LocalDate.parse(r.date) to it } }.toMap()

internal fun List<WearableAnalysisRow>.wSeries(pick: (WearableAnalysisRow) -> Double?): Series =
    mapNotNull { r -> pick(r)?.let { LocalDate.parse(r.date) to it } }.toMap()

/** Night columns over [days]: per night up to 60 days, else the weekly mean, oldest first. */
internal fun stageColumns(rows: List<SleepAnalysisRow>, today: LocalDate, days: Int): List<Pair<String, List<Double>>> {
    val from = today.minusDays((days - 1).toLong())
    val nights = rows.mapNotNull { r -> LocalDate.parse(r.date).takeIf { !it.isBefore(from) && !it.isAfter(today) }?.let { it to r } }
        .filter { (_, r) -> (r.deepMin ?: 0.0) + (r.lightMin ?: 0.0) + (r.remMin ?: 0.0) > 0 }
        .sortedBy { it.first }
    fun parts(r: SleepAnalysisRow) = listOf(r.deepMin ?: 0.0, r.lightMin ?: 0.0, r.remMin ?: 0.0)
    if (days <= 60) return nights.map { (d, r) -> shortDate(d) to parts(r) }
    return nights.groupBy { ChronoUnit.WEEKS.between(from, it.first) }.toSortedMap().map { (_, week) ->
        val p = week.map { parts(it.second) }
        shortDate(week.first().first) to (0..2).map { i -> p.map { it[i] }.average() }
    }
}

private const val SLEEP_INFO_STAGES =
    "Minutes in deep, light and REM sleep each night, from your band. Up to two months shows one column per night; a year " +
        "shows the weekly average. Tap a column to read it. Stage estimates from a wrist band are approximate: read the pattern, not one night."

private const val SLEEP_INFO_DURATION =
    "Hours asleep each night. The bold line is the week's average, the shaded band your normal (the middle of your own " +
        "last 60 nights) and the dashed lines the 7 to 9 hours most adults need."

private const val SLEEP_INFO_INDEX =
    "A 0-100 summary of duration, regularity, breathing steadiness and stage mix, each scored against your own history. " +
        "When its scorecard shows it predicts tomorrow no better than one of its parts, the score is hidden and only the parts are shown."

private const val SLEEP_INFO_CIRCADIAN =
    "How regular your daily timing is: sleep (half the weight), meals and training. A regularity measure from your logs, " +
        "not a measured body-clock phase."

@Composable
fun SleepScreen(onBack: () -> Unit) {
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var meals by remember { mutableStateOf<List<MealRow>>(emptyList()) }
    var breakStarts by remember { mutableStateOf(SeriesBreakStarts.NONE) }
    var loadSpans by remember { mutableStateOf(LoadSpans.STANDARD) }
    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        guard.run {
            val repo = AnalysisRepository(SupabaseClientProvider.client)
            parallel(
                { sleep = repo.loadSleepDaily() },
                { wearable = repo.loadWearableDaily() },
                { wellbeing = repo.loadWellbeingDaily() },
                { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                { meals = NutritionRepository(SupabaseClientProvider.client).loadRecentMeals(limit = 200) },
                { breakStarts = repo.loadSeriesBreakStarts() },
                { loadSpans = repo.loadLoadSpans() },
            )
        }
    }
    val scorecards by rememberScorecards(wearable, sleep, wellbeing, sessions, breakStarts, loadSpans)
    DetailScreen("SLEEP", onBack, eyebrow = "SLEEP", domain = FT.Domain.Sleep) {
        val s = sleep
        val w = wearable
        val wb = wellbeing
        val ses = sessions
        if (s == null || w == null || wb == null || ses == null) {
            Loading(guard)
            return@DetailScreen
        }
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val days = LocalRangeDays.current

        LastNightSection(s.filter { it.hours != null }.maxByOrNull { it.date }, zone)

        Section("STAGES", info = SLEEP_INFO_STAGES) {
            val columns = remember(s, days) { stageColumns(s, today, days) }
            StackedBars(columns, listOf(DEEP, LIGHT, REM), listOf("Deep", "Light", "REM"), Fmt.int, "min")
        }

        MetricPanel(
            "DURATION", remember(s) { s.series { it.hours } }, "", Fmt.hm, info = SLEEP_INFO_DURATION,
            minSd = 0.25, color = FT.Metric.SleepDuration, betterHigher = true, target = 7.0 to 9.0, targetLabel = "7–9 h",
        )

        Section("EACH STAGE") {
            MetricRow("Deep", s.series { it.deepMin }, "min", Fmt.int, color = DEEP, minSd = 8.0, betterHigher = true,
                info = "Minutes of deep (slow-wave) sleep a night: the most physically restorative stage, front-loaded in the night.")
            MetricRow("Light", s.series { it.lightMin }, "min", Fmt.int, color = LIGHT, minSd = 10.0,
                info = "Minutes of light sleep a night, usually about half of it.")
            MetricRow("REM", s.series { it.remMin }, "min", Fmt.int, color = REM, minSd = 8.0, betterHigher = true,
                info = "Minutes of REM (dreaming) sleep, which lengthens towards morning; a short night cuts REM most.")
            MetricRow("Breathing", s.series { it.respiratoryRate }, "/min", Fmt.one, color = FT.Metric.Respiratory, minSd = 0.3,
                info = "Breaths per minute asleep. It is very steady night to night, so a jump of more than one breath a minute is worth noticing (illness, alcohol, a hard day).")
        }

        val index = remember(s, w, wb, ses, breakStarts, loadSpans) { compositeInputs(w, s, wb, ses, breakStarts, loadSpans).sleepIndex() }
        val demoted = scorecards?.sleepIndex.demotes()
        Section("SLEEP INDEX", info = SLEEP_INFO_INDEX, trailing = if (demoted) "INPUTS ONLY" else index.score?.let { "${it.roundToInt()} / 100" }) {
            if (index.score == null) {
                EmptyLine("Needs at least two of duration, regularity, breathing and stage mix.")
            } else {
                ShareBars(index.components.map { (c, sc) -> Triple(c.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, sc.score, sc.score.roundToInt().toString()) }, 100.0, FT.Category.Sleep.c300)
            }
        }

        val circadian = remember(s, meals, ses) {
            val sri = evaluateSri(s.mapNotNull { row ->
                val bed = row.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val wake = row.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                if (bed != null && wake != null) SleepNight(LocalDate.parse(row.date), bed, wake) else null
            })
            computeCircadianAlignment(
                sri,
                meals.mapNotNull { WallClock.zoned(it.loggedAt, zone)?.toOffsetDateTime() },
                ses.mapNotNull { WallClock.zoned(it.startTime, zone)?.toOffsetDateTime() },
            )
        }
        Section("DAILY RHYTHM", info = SLEEP_INFO_CIRCADIAN, trailing = circadian.score?.let { "${it.roundToInt()} / 100" }) {
            if (circadian.components.isEmpty()) EmptyLine("Needs a few weeks of sleep, meal and training times.")
            else ShareBars(circadian.components.map { (c, sc) ->
                Triple(c.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, sc.score, sc.score.roundToInt().toString())
            }, 100.0)
        }
    }
}

@Composable
private fun LastNightSection(row: SleepAnalysisRow?, zone: ZoneId) {
    Section("LAST NIGHT", trailing = row?.date?.let { shortDate(LocalDate.parse(it)) }) {
        if (row == null) {
            EmptyLine("No night recorded yet.")
            return@Section
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.hours?.let(Fmt.hm) ?: "—", style = FTType.MetricMedium, color = FT.TextPrimary)
            val bed = row.bedtime?.let { runCatching { Instant.parse(it).atZone(zone).toLocalTime() }.getOrNull() }
            val wake = row.wakeTime?.let { runCatching { Instant.parse(it).atZone(zone).toLocalTime() }.getOrNull() }
            if (bed != null && wake != null) Text("%02d:%02d → %02d:%02d".format(bed.hour, bed.minute, wake.hour, wake.minute), style = FTType.Telemetry, color = FT.TextSecondary)
        }
        val parts = listOf(row.deepMin ?: 0.0, row.lightMin ?: 0.0, row.remMin ?: 0.0)
        val total = parts.sum()
        if (total > 0) {
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                var x = 0f
                parts.forEachIndexed { i, v ->
                    val w = (v / total).toFloat() * size.width
                    drawRoundRect(listOf(DEEP, LIGHT, REM)[i], Offset(x, 0f), Size((w - 2f).coerceAtLeast(1f), size.height), CornerRadius(4f))
                    x += w
                }
            }
            Legend(listOf("Deep ${parts[0].roundToInt()}′" to DEEP, "Light ${parts[1].roundToInt()}′" to LIGHT, "REM ${parts[2].roundToInt()}′" to REM))
        }
    }
}
