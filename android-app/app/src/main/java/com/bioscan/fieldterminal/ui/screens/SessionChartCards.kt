package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ZeppWorkoutSummary
import com.bioscan.fieldterminal.domain.SessionDetail
import com.bioscan.fieldterminal.domain.SessionSplit
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.domain.heartRateZoneBreakdown
import com.bioscan.fieldterminal.domain.heartRateZoneFor
import com.bioscan.fieldterminal.domain.initialZoneMethod
import com.bioscan.fieldterminal.domain.paceSeries
import com.bioscan.fieldterminal.domain.zoneBounds
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.HeartRateZoneDonutChart
import com.bioscan.fieldterminal.ui.components.HeartRateZoneMethodSwitch
import com.bioscan.fieldterminal.ui.components.LineChart
import com.bioscan.fieldterminal.ui.components.MinMaxAverageBar
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SessionZoneInputs
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.heartRateZoneColor
import com.bioscan.fieldterminal.ui.screens.session.EF_SESSION_HELP
import com.bioscan.fieldterminal.ui.screens.session.GAP_HELP
import com.bioscan.fieldterminal.ui.screens.session.GROUND_CONTACT_HELP
import com.bioscan.fieldterminal.ui.screens.session.HR_DECOUPLING_HELP
import com.bioscan.fieldterminal.ui.screens.session.LACTATE_THRESHOLD_HELP
import com.bioscan.fieldterminal.ui.screens.session.STRIDE_LENGTH_HELP
import com.bioscan.fieldterminal.ui.screens.session.VERTICAL_RATIO_HELP
import com.bioscan.fieldterminal.ui.screens.session.formatSplitPace
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// A session's page: the charts over the session (pace, heart rate, power...), its splits and its running dynamics.

private enum class PerfSignal(val label: String, val unit: String, val color: Color) {
    HR("HR", "bpm", FT.DomainHeart),
    PACE("PACE", "min/km", FT.Category.Activity.c500),
    POWER("POWER", "W", FT.Category.Activity.c300),
    CADENCE("CADENCE", "spm", FT.TextSecondary),
    VERTICAL_RATIO("V.RATIO", "%", FT.TextSecondary),
}

// Pace reads as m:ss /km (not a decimal like 4.8), everything else as value + unit.
private fun formatSignalValue(signal: PerfSignal, v: Double): String =
    if (signal == PerfSignal.PACE) {
        val s = Math.round(v * 60).toInt()
        "%d:%02d /km".format(s / 60, s % 60)
    } else {
        "%.1f %s".format(v, signal.unit)
    }

// One chart, switchable rather than four stacked ones -- only signals this
// session actually recorded appear as options. PACE is derived from the
// same speed samples SPEED used to plot directly (min/km = 60/kmh), matching
// this app's existing averagePaceMinPerKmSince convention (domain/
// Training.kt) rather than showing raw km/h, since the ticket calls for PACE.
// 28/9: no longer its own "PERFORMANCE" FTCard -- folded into SUMMARY as a
// plain content section per direct request (HR/pace/cadence "in the summary").
// HR zone breakdown: line-vs-zones is its own tiny local toggle, not reusing
// PerfSignal (zones only ever apply to HR, never PACE/POWER/CADENCE).
private enum class ChartColorMode(val label: String) { Line("LINE"), Zones("ZONES") }

@Composable
internal fun PerformanceChartSection(d: SessionDetail, zones: SessionZoneInputs?) {
    val pace = remember(d.speedKmh) { paceSeries(d.speedKmh) }
    val seriesBySignal = mapOf(
        PerfSignal.HR to d.heartRate,
        PerfSignal.PACE to pace,
        PerfSignal.POWER to d.powerW,
        PerfSignal.CADENCE to d.cadenceSpm,
        PerfSignal.VERTICAL_RATIO to d.verticalRatioPct,
    )
    val available = PerfSignal.entries.filter { seriesBySignal[it]?.isNotEmpty() == true }
    if (available.isEmpty()) return
    var selected by remember(d) { mutableStateOf(available.first()) }
    if (selected !in available) selected = available.first()

    // Live check: switching tabs made a sensor that only reported for part
    // of the run look exactly as "full" as one spanning the whole session --
    // each chart stretched to its own first/last sample. The widest real
    // coverage across every available signal becomes the shared axis every
    // tab draws against, so a partial one shows up short instead.
    val sessionStart = available.minOf { seriesBySignal.getValue(it).first().offsetSeconds }
    val sessionEnd = available.maxOf { seriesBySignal.getValue(it).last().offsetSeconds }

    SubTabRow(items = available, selected = selected, label = { it.label }, onSelect = { selected = it })
    val points = seriesBySignal.getValue(selected)
    val values = points.map { it.value }
    MinMaxAverageBar(
        min = values.min(),
        average = values.average(),
        max = values.max(),
        color = selected.color,
        format = { v -> formatSignalValue(selected, v) },
    )

    // The zone method starts on the person's default (or what AUTO picks for this session) and can be switched here.
    var zoneMethod by remember(d, zones) { mutableStateOf(zones?.let { initialZoneMethod(it.defaultMethod, it.anchors, it.sport) }) }
    val bounds = remember(zoneMethod, zones) { zones?.let { z -> zoneMethod?.let { zoneBounds(it, z.anchors, z.sport) } } }
    val showZoneUi = selected == PerfSignal.HR && bounds != null
    var colorMode by remember(d) { mutableStateOf(ChartColorMode.Line) }
    if (showZoneUi) {
        SegmentedToggle(
            options = ChartColorMode.entries,
            selected = colorMode,
            labelOf = { it.label },
            onSelect = { colorMode = it },
            modifier = Modifier.padding(top = 8.dp),
        )
        HeartRateZoneMethodSwitch(zones!!, bounds!!.method, onSelect = { zoneMethod = it }, modifier = Modifier.padding(top = 6.dp))
    }

    LineChart(
        points = points,
        color = selected.color,
        xRange = sessionStart to sessionEnd,
        segmentColor = if (showZoneUi && colorMode == ChartColorMode.Zones) { hr -> heartRateZoneColor(heartRateZoneFor(hr, bounds!!)) } else null,
        pauses = d.pauses.map { it.startOffset to it.endOffset },
        modifier = Modifier.padding(top = 4.dp),
        valueFormat = { v -> formatSignalValue(selected, v) },
        invertY = selected == PerfSignal.PACE,
        unit = if (selected == PerfSignal.PACE) "pace · min:sec per km" else selected.unit,
    )
    if (points.first().offsetSeconds > sessionStart || points.last().offsetSeconds < sessionEnd) {
        Text(
            "${selected.label} only reported for part of this session.",
            style = FTType.Caption,
            color = FT.TextSecondary,
        )
    }

    if (showZoneUi) {
        val breakdown = remember(points, bounds, d.pauses) { heartRateZoneBreakdown(points, bounds!!, d.pauses) }
        HeartRateZoneDonutChart(breakdown, modifier = Modifier.padding(top = 12.dp), bounds = bounds)
    }
}

@Composable
internal fun SplitsCard(splits: List<SessionSplit>) {
    if (splits.isEmpty()) return
    val fastestKm = splits.minByOrNull { it.durationSec }?.km
    FTCard(title = "SPLITS") {
        splits.forEach { split ->
            val isFastest = split.km == fastestKm
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("KM ${split.km}", style = FTType.BodySmall, color = FT.TextSecondary)
                Text(
                    formatSplitPace(split.durationSec) + (split.avgHr?.let { "  ·  %.0f bpm".format(it) } ?: "") + (if (isFastest) "  ★" else ""),
                    style = if (isFastest) FTType.Telemetry else FTType.Value,
                    color = if (isFastest) FT.Ok else FT.TextPrimary,
                )
            }
        }
    }
}

// DAV-115/123: Zepp's own run-dynamics + lactate-threshold summary averages
// (docs/zepp-integration/03-workout-detail-field-decode.md) -- workout-level
// scalars, not a TimePoint series, so they get their own small card rather
// than forcing them into PerformanceChartCard's per-second signals. Each row
// renders only when that field is non-null -- a device/sport that doesn't
// report ground contact time (say) just omits that row, same optional-stat
// convention SummaryCard/TrailCard already use in this file. Lactate
// threshold is Zepp's own rolling estimate as of this workout, not a
// dedicated one-off test -- labeled that way rather than implying otherwise.
@Composable
internal fun ZeppDynamicsCard(summary: ZeppWorkoutSummary) {
    val rows = buildList {
        summary.gapMinPerKm?.let { add(Triple("Grade-adjusted pace", formatSplitPace((it * 60).toLong()), "Grade-adjusted pace" to GAP_HELP)) }
        summary.efficiencyFactor?.let { add(Triple("Efficiency factor", "%.2f".format(it), "Efficiency factor" to EF_SESSION_HELP)) }
        summary.hrDecouplingPct?.let { add(Triple("HR decoupling", "%+.1f%%".format(it), "HR decoupling" to HR_DECOUPLING_HELP)) }
        summary.smoothedAscentM?.let { add(Triple("Smoothed ascent", "${it.toInt()} m", null)) }
        summary.avgCadenceSpm?.let { add(Triple("Avg cadence", "%.0f spm".format(it), null)) }
        summary.maxCadenceSpm?.let { add(Triple("Max cadence", "%.0f spm".format(it), null)) }
        summary.avgGroundContactMs?.let { add(Triple("Ground contact", "%.0f ms".format(it), "Ground contact time" to GROUND_CONTACT_HELP)) }
        summary.avgStrideLengthCm?.let { add(Triple("Stride length", "%.0f cm".format(it), "Stride length" to STRIDE_LENGTH_HELP)) }
        summary.avgVerticalStrideRatioPct?.let { add(Triple("Vertical ratio", "%.1f%%".format(it), "Vertical oscillation ratio" to VERTICAL_RATIO_HELP)) }
        if (summary.lactateThresholdHrBpm != null || summary.lactateThresholdPaceSecPerKm != null) {
            val hr = summary.lactateThresholdHrBpm?.let { "%.0f bpm".format(it) }
            val pace = summary.lactateThresholdPaceSecPerKm?.let { formatSplitPace(it.toLong()) }
            add(Triple("Lactate threshold (as of this run)", listOfNotNull(hr, pace).joinToString("  ·  "), "Lactate threshold" to LACTATE_THRESHOLD_HELP))
        }
    }
    if (rows.isEmpty()) return

    FTCard(title = "RUN DYNAMICS") {
        rows.forEach { (label, value, help) ->
            if (help != null) StatLineHelp(label, value, help.first, help.second) else FTMetricRow(label, value)
        }
    }
}
