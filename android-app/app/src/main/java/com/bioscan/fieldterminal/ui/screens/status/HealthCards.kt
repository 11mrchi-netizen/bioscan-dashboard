package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.analysis.MIN_CORRELATION_PAIRS
import com.bioscan.fieldterminal.domain.analysis.pearsonCorrelation
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.StressRhythmResult
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// What is left of the old Health page (its tabs became the pages in ui/screens/detail): the sexual-health correlations
// Lab › N=1 shows, and the physiological stress card under BALANCE › Stress through the day.

// App restructure: shown in Lab › N=1 with the other "what goes with what" questions.
@Composable
internal fun SexualHealthCorrelationsCard(wb: List<WellbeingAnalysisRow>, sa: List<LogSexualActivityRow>, w: List<WearableAnalysisRow>, s: List<SleepAnalysisRow>) {
    val wbByDate = wb.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }.toMap()
    val sByDate = s.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }.toMap()
    val wByDate = w.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }.toMap()

    fun pairs(x: (LocalDate) -> Double?, y: (LocalDate) -> Double?): List<Pair<Double, Double>> {
        val dates = wbByDate.keys + sByDate.keys + wByDate.keys
        return dates.mapNotNull { d -> val xv = x(d); val yv = y(d); if (xv != null && yv != null) xv to yv else null }
    }

    val erectionVsSleepHours = pearsonCorrelation(pairs({ wbByDate[it]?.morningErectionQuality?.toDouble() }, { sByDate[it]?.hours }))
    val erectionVsDeepSleep = pearsonCorrelation(pairs({ wbByDate[it]?.morningErectionQuality?.toDouble() }, { sByDate[it]?.deepMin }))
    val arousalVsHrv = pearsonCorrelation(pairs({ wbByDate[it]?.arousalLevel?.toDouble() }, { wByDate[it]?.hrv }))
    val arousalVsRhr = pearsonCorrelation(pairs({ wbByDate[it]?.arousalLevel?.toDouble() }, { wByDate[it]?.rhr }))

    val gapByDate: Map<LocalDate, Double> = run {
        val sorted = sa.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull() }.distinct().sorted()
        sorted.zipWithNext { a, b -> b to ChronoUnit.DAYS.between(a, b).toDouble() }.toMap()
    }
    val intensityByDate: Map<LocalDate, Double> = sa.mapNotNull { row ->
        runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { date ->
            row.instances.mapNotNull { it.orgasmIntensity }.maxOrNull()?.let { date to it.toDouble() }
        }
    }.toMap()
    val intensityVsGap = pearsonCorrelation(gapByDate.keys.mapNotNull { d -> intensityByDate[d]?.let { it to gapByDate.getValue(d) } })

    val rows = listOfNotNull(
        erectionVsSleepHours?.let { Triple("Morning erection quality × sleep duration", it, "research-supported pairing") },
        erectionVsDeepSleep?.let { Triple("Morning erection quality × deep sleep", it, "research-supported pairing") },
        arousalVsHrv?.let { Triple("Arousal × HRV", it, "observed in your data only") },
        arousalVsRhr?.let { Triple("Arousal × resting heart rate", it, "observed in your data only") },
        intensityVsGap?.let { Triple("Orgasm intensity × days since last activity", it, "observed in your data only") },
    )

    FTCard(title = "CORRELATIONS", info = "Pearson r with its 95% interval, minimum $MIN_CORRELATION_PAIRS paired days. A strength word is shown only when the interval excludes zero. Only morning erection quality × sleep has real supporting research -- everything else is observed in your own data, not a validated link, and a correlation never shows cause.") {
        if (rows.isEmpty()) {
            Text("Not enough paired history yet (need $MIN_CORRELATION_PAIRS+ overlapping days per pair).", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            rows.forEach { (label, result, caption) ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                        Text("r = %.2f".format(result.r), style = FTType.Value, color = FT.TextSecondary)
                    }
                    Text(
                        "${result.strength} · 95% interval ${"%.2f".format(result.lower)} to ${"%.2f".format(result.upper)} · n=${result.n} · $caption",
                        style = FTType.Caption,
                        color = FT.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PhysiologicalStressCard(result: StressRhythmResult, today: StressDay) {
    // "PHYSIOLOGICAL STRESS" not "STRESS" -- never visually conflated with
    // the subjective SubjectiveCard("STRESS") above it (DAV-246's own rule).
    FTCard(dataQuality = result.confidence, title = "PHYSIOLOGICAL STRESS") {
        val summary = today.summary
        if (summary.avg != null) {
            FTMetricValue(DisplayValue(primary = "${summary.avg}", unit = "/ 100 avg today"))
        }
        val relax = summary.relaxProportion
        val normal = summary.normalProportion
        val medium = summary.mediumProportion
        val high = summary.highProportion
        if (relax != null && normal != null && medium != null && high != null) {
            // Ordinal intensity (relax -> high): one hue, rising opacity, not a
            // state ramp -- the legend carries the labels and percentages.
            val stressHue = FT.Category.Wellbeing.c500
            FTSegmentBar(
                listOf(
                    FTSegment("Relax", relax.toDouble(), stressHue.copy(alpha = 0.30f)),
                    FTSegment("Normal", normal.toDouble(), stressHue.copy(alpha = 0.50f)),
                    FTSegment("Medium", medium.toDouble(), stressHue.copy(alpha = 0.75f)),
                    FTSegment("High", high.toDouble(), stressHue),
                ),
            )
        }
        if (result.peakMagnitude != null) {
            FTMetricRow("Today's baseline", "%.0f".format(result.baseline))
            FTMetricRow("Peak above baseline", "+${result.peakMagnitude}")
            result.peakDurationMinutes?.let { FTMetricRow("Peak duration", "${it} min") }
            result.eveningDownRegulationPct?.let { FTMetricRow("Evening wind-down", "%.0f%%".format(it)) }
            result.deviationFromPersonalPattern?.let { FTMetricRow("Vs. your own recent average", "%+.0f".format(it)) }
        } else {
            Text(
                "Not enough intraday samples yet for the diurnal pattern -- daily summary above is still real.",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
        }
    }
}

// Live check: HealthConnectDailySyncRepository.syncSleep() already computes
// real per-night deep/rem/light minutes from SleepSessionRecord.stages and
// syncs them to sleep_daily -- nothing in the app ever displayed them. Shows
// the last 14 real nights' average as a proportion bar rather than one more
// day-by-day trend line (this is a composition question -- "where does sleep
// time go" -- not a day-to-day trend one).
// Live check: wearable_daily.steps has 1,000+ real synced days, never read
// anywhere. No evaluate*() function exists for steps (no baseline/SWC
// concept defined for it, unlike HRV/RHR) -- a plain daily count and trend,
// not a full EvalCard, is the honest treatment.
