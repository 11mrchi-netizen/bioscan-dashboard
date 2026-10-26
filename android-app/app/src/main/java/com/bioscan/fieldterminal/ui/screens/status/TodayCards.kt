package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.domain.HeartRateDay
import com.bioscan.fieldterminal.domain.minuteHrSummary
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.components.MetricCardHeader
import com.bioscan.fieldterminal.ui.components.MinuteHeartRateChart
import com.bioscan.fieldterminal.ui.components.RedesignCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter

// Redesign (spec §4): the intraday cards that left the Health page now live on Today.

private const val INTRADAY_HR_INFO =
    "Your band's heart rate minute by minute for the newest day it has synced, from midnight to midnight. Minutes " +
        "without a reading (band off the wrist, or no measurement) are left out: the line breaks there. Tap or drag the " +
        "chart to read a minute."

/** Trend Card: the newest day's minute-by-minute heart rate (moved from Health › Cardio). */
@Composable
internal fun IntradayHeartRateCard(day: HeartRateDay?) {
    if (day == null) return
    val summary = minuteHrSummary(day.points)
    RedesignCard {
        MetricCardHeader(
            title = "HEART RATE · ${day.date.format(DateTimeFormatter.ofPattern("d MMM")).uppercase()}",
            latest = day.points.lastOrNull()?.bpm?.toString(),
            unit = "bpm",
            helpBody = INTRADAY_HR_INFO,
        )
        MinuteHeartRateChart(points = day.points, color = FT.DomainHeart)
        summary?.let {
            Text(
                "Worn ${it.wornMinutes / 60} h ${it.wornMinutes % 60} min · ${it.low}–${it.high} bpm · average ${it.average} bpm",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
        }
    }
}

/**
 * Hypnogram Card. A real hypnogram (stage lanes through the night, wake time, cycles, heart-rate and breathing overlays)
 * needs timed stage intervals, which are not stored yet (docs/ft-redesign/model-plans.md, section 1). Until they are, it
 * shows last night's time in each stage, and says so.
 */
@Composable
internal fun HypnogramCard(night: SleepAnalysisRow?) {
    val deep = night?.deepMin
    val rem = night?.remMin
    val light = night?.lightMin
    RedesignCard {
        MetricCardHeader(
            title = "SLEEP PHASE BREAKDOWN",
            latest = night?.hours?.let { "%.1f".format(it) },
            unit = "h",
            helpBody = "Time in each sleep stage last night. The night-long stage chart, with wake time, estimated sleep cycles and " +
                "heart-rate and breathing overlays, arrives once the band's timed stages are stored.",
        )
        if (deep != null && rem != null && light != null) {
            FTSegmentBar(
                listOf(
                    FTSegment("Deep ${deep.toInt()} min", deep, FT.Category.Sleep.c700),
                    FTSegment("REM ${rem.toInt()} min", rem, FT.Category.Sleep.c500),
                    FTSegment("Light ${light.toInt()} min", light, FT.Category.Sleep.c300),
                ),
            )
        } else {
            Text("No stage data for last night yet.", style = FTType.Caption, color = FT.TextMuted)
        }
    }
}

/** Glucose-spiking model slot, grouped by meal. The estimate needs its own model (docs/ft-redesign/model-plans.md, section 2). */
@Composable
internal fun GlucoseSpikingCard() {
    RedesignCard {
        MetricCardHeader(
            title = "GLUCOSE SPIKING",
            latest = null,
            helpBody = "Will estimate, meal by meal, how large a glucose rise your food is likely to bring, from its carbohydrate, " +
                "fibre, protein and fat. It will be a relative estimate, not a measured glucose.",
        )
        Text("Not available yet.", style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.padding(top = 2.dp))
    }
}
