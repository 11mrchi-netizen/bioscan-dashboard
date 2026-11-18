package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.domain.comparison.PopulationContext
import com.bioscan.fieldterminal.domain.comparison.comparePopulation
import com.bioscan.fieldterminal.domain.evaluateRespiratoryAnomaly
import com.bioscan.fieldterminal.ui.components.RefreshOnCacheUpdates
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

private const val HRV_INFO =
    "Heart-rate variability overnight, in milliseconds: how much the gap between beats varies. Higher than your normal " +
        "usually means well recovered; a drop of a few days running often follows hard training, poor sleep, alcohol or illness. " +
        "It is personal, so read it against your own band, never someone else's number."

private const val HR_INFO =
    "Each bar runs from the lowest to the highest heart rate your band recorded that day."

@Composable
fun CardioScreen(onBack: () -> Unit) {
    val page = remember { CardioState() }
    val guard = page.guard
    RefreshOnCacheUpdates(guard)
    LaunchedEffect(guard.attempt) { page.load() }
    DetailScreen("HEART", onBack, eyebrow = "RECOVERY", domain = FT.Domain.Cardio) {
        val w = page.wearable
        val s = page.sleep
        if (w == null || s == null) {
            Loading(guard)
            return@DetailScreen
        }
        val today = LocalDate.now()
        MetricPanel("HRV", remember(w) { w.wSeries { it.hrv } }, "ms", Fmt.int, info = HRV_INFO, log = true, minSd = 0.03, color = FT.Metric.Hrv, betterHigher = true)
        Section("HEART RATE · DAY RANGE", info = HR_INFO) { RangeBars(page.ranges, today, LocalRangeDays.current, "bpm") }
        Section("MORE") {
            MetricRow("Resting HR", w.wSeries { it.rhr }, "bpm", Fmt.int, minSd = 1.0, color = FT.Metric.RestingHr, betterHigher = false,
                info = "Your lowest steady heart rate, overnight. A rise of a few beats over your normal for several days often means fatigue, heat, alcohol or an illness coming.")
            MetricRow("SpO2", w.wSeries { it.spo2 }, "%", Fmt.int, minSd = 0.5, color = FT.Metric.Spo2,
                info = "Blood-oxygen saturation from your band, nightly average. Most healthy people sit at 95-100 %; wrist readings are noisy, so look for runs of low nights, not one.")
            val stepSeries = w.wSeries { it.steps }
            val recent = stepSeries.filterKeys { !it.isBefore(today.minusDays(15)) }.values
            val band = if (recent.size >= 2) runCatching {
                comparePopulation("steps", recent.average(), PopulationContext(ageYears = null, sex = null, geography = null), page.steps).bandLabel
            }.getOrNull()?.replace('_', ' ')?.lowercase() else null
            MetricRow("Steps", stepSeries, "", Fmt.kSteps, minSd = 500.0, color = FT.Metric.Steps, betterHigher = true,
                note = band?.let { "15-day average: $it" },
                info = "Steps a day." + (band?.let { " Your 15-day average falls in the population band \"$it\" (Tudor-Locke and Bassett's steps-a-day categories)." } ?: ""))
            val rr = s.series { it.respiratoryRate }
            val anomaly = evaluateRespiratoryAnomaly(rr.entries.sortedBy { it.key }.map { it.key to it.value }).flagged
            MetricRow("Breathing", rr, "/min", Fmt.one, minSd = 0.3, color = FT.Metric.Respiratory, flag = if (anomaly) "two nights well outside your normal" else null,
                info = "Breaths per minute asleep. Flagged when two nights in a row sit more than 2 SD from your baseline. Not a diagnosis.")
        }
    }
}
