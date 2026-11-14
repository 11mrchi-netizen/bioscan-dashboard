package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.ui.components.ActivityHeatmap
import com.bioscan.fieldterminal.ui.components.DayActivity
import com.bioscan.fieldterminal.ui.components.RefreshOnCacheUpdates
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

internal fun List<WellbeingAnalysisRow>.wbSeries(pick: (WellbeingAnalysisRow) -> Int?): Series =
    mapNotNull { r -> pick(r)?.let { LocalDate.parse(r.date) to it.toDouble() } }.toMap()

private const val SELF_RATED = "Your own 1-10 rating from the morning check-in, read against your own usual answers."

@Composable
fun WellbeingScreen(onBack: () -> Unit) {
    val page = remember { WellbeingState() }
    val guard = page.guard
    RefreshOnCacheUpdates(guard)
    LaunchedEffect(guard.attempt) { page.load() }
    DetailScreen("WELLBEING", onBack, eyebrow = "BALANCE", domain = FT.Domain.Balance) {
        val wb = page.wellbeing
        if (wb == null) {
            Loading(guard)
            return@DetailScreen
        }
        val stressSeries = remember(page.stress) { page.stress.mapNotNull { d -> d.summary.avg?.let { d.date to it.toDouble() } }.toMap() }
        MetricPanel(
            "BODY STRESS · DAILY AVERAGE", stressSeries, "/ 100", Fmt.int, minSd = 3.0, color = FT.Metric.Stress, betterHigher = false,
            info = "Your band's stress score (0-100, from heart-rate variability through the day), daily average. It is the body's " +
                "reading, kept apart from the stress you rate yourself below. The curve through a single day is on BALANCE › Stress through the day.",
        )
        Section("HOW YOU FELT", info = SELF_RATED) {
            MetricRow("Energy", wb.wbSeries { it.energy }, "/10", Fmt.int, minSd = 0.5, color = FT.Category.Wellbeing.c500, betterHigher = true, info = SELF_RATED)
            MetricRow("Mood", wb.wbSeries { it.mood }, "/10", Fmt.int, minSd = 0.5, color = FT.Category.Wellbeing.c500, betterHigher = true, info = SELF_RATED)
            MetricRow("Stress (felt)", wb.wbSeries { it.stress }, "/10", Fmt.int, minSd = 0.5, color = FT.Category.Wellbeing.c500, betterHigher = false, info = SELF_RATED)
            MetricRow("Soreness", wb.wbSeries { it.soreness }, "/10", Fmt.int, minSd = 0.5, color = FT.Category.Wellbeing.c500, betterHigher = false, info = SELF_RATED)
        }
    }
}

private const val SEX_INFO = "Self-quantification only: counts and your own ratings, with no clinical meaning implied."

@Composable
fun SexualHealthScreen(onBack: () -> Unit) {
    val page = remember { SexualHealthState() }
    val guard = page.guard
    RefreshOnCacheUpdates(guard)
    LaunchedEffect(guard.attempt) { page.load() }
    DetailScreen("SEXUAL HEALTH", onBack, eyebrow = "RECOVERY", domain = FT.Domain.Cardio) {
        val wb = page.wellbeing
        val sa = page.activity
        if (wb == null || sa == null) {
            Loading(guard)
            return@DetailScreen
        }
        val today = LocalDate.now()
        val days = LocalRangeDays.current
        val from = today.minusDays((days - 1).toLong())
        val inRange = sa.filter { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { !it.isBefore(from) } == true }

        Section("LAST $days DAYS", info = SEX_INFO) {
            val dates = inRange.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.distinct().sorted()
            val gap = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.takeIf { it.isNotEmpty() }?.average()
            StatTiles(
                listOf(
                    Triple("INTERCOURSE DAYS", inRange.count { it.activityType == "intercourse" }.toString(), null),
                    Triple("MASTURBATION DAYS", inRange.count { it.activityType == "masturbation" }.toString(), null),
                    Triple("ORGASMS", inRange.sumOf { r -> r.instances.count { it.orgasm } }.toString(), null),
                    Triple("AVERAGE GAP", gap?.let { "%.1f d".format(it) } ?: "—", "between active days"),
                ),
            )
        }

        Section("BY WEEK", info = "Active days per week, split by kind. Tap a week to read it.") {
            val weeks = (days / 7).coerceIn(4, 26)
            val columns = (weeks - 1 downTo 0).map { k ->
                val start = today.minusDays((k * 7 + 6).toLong())
                val end = today.minusDays((k * 7).toLong())
                val inWeek = sa.filter { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(end) } == true }
                shortDate(start) to listOf(inWeek.count { it.activityType == "intercourse" }.toDouble(), inWeek.count { it.activityType == "masturbation" }.toDouble())
            }
            StackedBars(columns, listOf(FT.Category.Arousal.c500, FT.Category.Wellbeing.c500), listOf("Intercourse", "Masturbation"), Fmt.int, "days")
        }

        Section("CALENDAR · 12 WEEKS", info = "Each square a day; red intercourse, blue masturbation, darker = more that day.") {
            val byDate = sa.mapNotNull { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { it to r.activityType } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, t) -> DayActivity(intercourseCount = t.count { it == "intercourse" }, masturbationCount = t.count { it == "masturbation" }) }
            ActivityHeatmap(byDate)
        }

        Section("MORNING SIGNS", info = SELF_RATED) {
            MetricRow("Morning erection", wb.wbSeries { it.morningErectionQuality }, "/10", Fmt.int, minSd = 0.5, color = FT.Category.Arousal.c300, betterHigher = true,
                info = "Morning erection quality, 1-10. It tends to follow sleep and recovery, which is why it feeds RECOVERY a little.")
            MetricRow("Arousal", wb.wbSeries { it.arousalLevel }, "/10", Fmt.int, minSd = 0.5, color = FT.Category.Arousal.c300, betterHigher = true, info = SELF_RATED)
        }

        val intensities = inRange.flatMap { it.instances }.mapNotNull { it.orgasmIntensity }
        val loads = inRange.flatMap { it.instances }.mapNotNull { it.loadSize }
        if (intensities.isNotEmpty() || loads.isNotEmpty()) {
            Section("HOW IT FELT", info = "Self-reported intensity (0-10) and load size (1-5) across the range.") {
                if (intensities.isNotEmpty()) {
                    Text("INTENSITY", style = FTType.Micro, color = FT.TextMuted)
                    val buckets = listOf("0–2" to 0..2, "3–4" to 3..4, "5–6" to 5..6, "7–8" to 7..8, "9–10" to 9..10)
                    ShareBars(buckets.map { (l, r) -> intensities.count { it in r }.let { Triple(l, it.toDouble(), it.toString()) } }, intensities.size.toDouble(), FT.Category.Arousal.c500)
                }
                if (loads.isNotEmpty()) {
                    Text("LOAD SIZE", style = FTType.Micro, color = FT.TextMuted)
                    ShareBars((1..5).map { n -> loads.count { it == n }.let { Triple("$n", it.toDouble(), it.toString()) } }, loads.size.toDouble(), FT.Category.Wellbeing.c500)
                }
            }
        }
    }
}
