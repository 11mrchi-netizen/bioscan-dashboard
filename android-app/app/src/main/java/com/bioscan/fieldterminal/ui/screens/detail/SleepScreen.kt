package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.compositeInputs
import com.bioscan.fieldterminal.domain.computeCircadianAlignment
import com.bioscan.fieldterminal.domain.demotes
import com.bioscan.fieldterminal.domain.evaluateSri
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.domain.sleepIndex
import com.bioscan.fieldterminal.ui.components.RefreshOnCacheUpdates
import com.bioscan.fieldterminal.ui.screens.status.rememberScorecards
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

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

private const val SLEEP_INFO_MEASURES =
    "Deep (slow-wave) sleep is the most restorative stage and front-loaded in the night. Light is usually about half of the night. " +
        "REM lengthens towards morning and a short night cuts it most. Breathing is breaths a minute asleep: it is very steady, so a jump of " +
        "more than one a minute is worth noticing (illness, alcohol, a hard day)."

private const val SLEEP_INFO_INDEX =
    "A 0-100 summary of duration, regularity, breathing steadiness and stage mix, each scored against your own history. " +
        "When its scorecard shows it predicts tomorrow no better than one of its parts, the score is hidden and only the parts are shown."

private const val SLEEP_INFO_CIRCADIAN =
    "How regular your daily timing is: sleep (half the weight), meals and training. A regularity measure from your logs, " +
        "not a measured body-clock phase."

@Composable
fun SleepScreen(onBack: () -> Unit) {
    val page = remember { SleepState() }
    val guard = page.guard
    RefreshOnCacheUpdates(guard)
    LaunchedEffect(guard.attempt) { page.load() }
    val scorecards by rememberScorecards(page.wearable, page.sleep, page.wellbeing, page.sessions, page.breakStarts, page.loadSpans)
    DetailScreen("SLEEP", onBack, eyebrow = "SLEEP", domain = FT.Domain.Sleep) {
        val s = page.sleep
        val w = page.wearable
        val wb = page.wellbeing
        val ses = page.sessions
        if (s == null || w == null || wb == null || ses == null) {
            Loading(guard)
            return@DetailScreen
        }
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val days = LocalRangeDays.current

        val lastNight = s.filter { it.hours != null }.maxByOrNull { it.date }

        // Lead and support (contract A10): the measure picked from the list leads; duration to begin with.
        FocusMetrics(
            "sleep",
            listOf(
                FocusMetric("duration", "DURATION", remember(s) { s.series { it.hours } }, "", Fmt.hm, FT.Metric.SleepDuration,
                    minSd = 0.25, betterHigher = true, target = 7.0 to 9.0, targetLabel = "7–9 h"),
                FocusMetric("deep", "DEEP", s.series { it.deepMin }, "min", Fmt.int, DEEP, minSd = 8.0, betterHigher = true),
                FocusMetric("light", "LIGHT", s.series { it.lightMin }, "min", Fmt.int, LIGHT, minSd = 10.0),
                FocusMetric("rem", "REM", s.series { it.remMin }, "min", Fmt.int, REM, minSd = 8.0, betterHigher = true),
                FocusMetric("breathing", "BREATHING", s.series { it.respiratoryRate }, "/min", Fmt.one, FT.Metric.Respiratory, minSd = 0.3),
            ),
            today,
        ) { key -> if (key == "duration") LastNightExtra(lastNight, zone) }

        // Depth: folded, one line each.
        FocusDepth("sleep:stages", "STAGES BY NIGHT", lastNight?.let { r -> "deep ${(r.deepMin ?: 0.0).roundToInt()}′ · REM ${(r.remMin ?: 0.0).roundToInt()}′" }) {
            val columns = remember(s, days) { stageColumns(s, today, days) }
            StackedBars(columns, listOf(DEEP, LIGHT, REM), listOf("Deep", "Light", "REM"), Fmt.int, "min")
        }

        val index = remember(s, w, wb, ses, page.breakStarts, page.loadSpans) { compositeInputs(w, s, wb, ses, page.breakStarts, page.loadSpans).sleepIndex() }
        val demoted = scorecards?.sleepIndex.demotes()
        FocusDepth("sleep:index", "SLEEP INDEX", if (demoted) "INPUTS ONLY" else index.score?.let { "${it.roundToInt()} / 100" }) {
            if (index.score == null) {
                EmptyLine("Needs at least two of duration, regularity, breathing and stage mix.")
            } else {
                ShareBars(index.components.map { (c, sc) -> Triple(c.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, sc.score, sc.score.roundToInt().toString()) }, 100.0, FT.Category.Sleep.c300)
            }
        }

        val circadian = remember(s, page.meals, ses) {
            val sri = evaluateSri(s.mapNotNull { row ->
                val bed = row.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val wake = row.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                if (bed != null && wake != null) SleepNight(LocalDate.parse(row.date), bed, wake) else null
            })
            computeCircadianAlignment(
                sri,
                page.meals.mapNotNull { WallClock.zoned(it.loggedAt, zone)?.toOffsetDateTime() },
                ses.mapNotNull { WallClock.zoned(it.startTime, zone)?.toOffsetDateTime() },
            )
        }
        FocusDepth("sleep:rhythm", "DAILY RHYTHM", circadian.score?.let { "${it.roundToInt()} / 100" }) {
            if (circadian.components.isEmpty()) EmptyLine("Needs a few weeks of sleep, meal and training times.")
            else ShareBars(circadian.components.map { (c, sc) ->
                Triple(c.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, sc.score, sc.score.roundToInt().toString())
            }, 100.0)
        }

        FocusHowBuilt("sleep:how", listOf(
            "Duration" to SLEEP_INFO_DURATION,
            "Stages" to SLEEP_INFO_STAGES,
            "Sleep index" to SLEEP_INFO_INDEX,
            "Daily rhythm" to SLEEP_INFO_CIRCADIAN,
            "Each measure" to SLEEP_INFO_MEASURES,
        ))
    }
}

/** What only the duration lead has: last night's bed and wake times and its stage bar (it was the LAST NIGHT card). */
@Composable
private fun LastNightExtra(row: SleepAnalysisRow?, zone: ZoneId) {
    if (row == null) {
        EmptyLine("No night recorded yet.")
        return
    }
    val bed = row.bedtime?.let { runCatching { Instant.parse(it).atZone(zone).toLocalTime() }.getOrNull() }
    val wake = row.wakeTime?.let { runCatching { Instant.parse(it).atZone(zone).toLocalTime() }.getOrNull() }
    if (bed != null && wake != null) Text("%02d:%02d → %02d:%02d".format(bed.hour, bed.minute, wake.hour, wake.minute), style = FTType.Telemetry, color = FT.TextSecondary)
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
