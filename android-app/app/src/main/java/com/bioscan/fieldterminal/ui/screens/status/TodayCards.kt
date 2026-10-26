package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.HypnogramNight
import com.bioscan.fieldterminal.domain.MINUTE_HR_MAX_GAP_MINUTES
import com.bioscan.fieldterminal.domain.SleepStage
import com.bioscan.fieldterminal.domain.clockLabel
import com.bioscan.fieldterminal.domain.stageMinutes
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import java.time.LocalDate
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
import java.time.OffsetDateTime
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.domain.GlycaemicBand
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.estimateMealResponses
import com.bioscan.fieldterminal.ui.components.FTStatePill

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

private const val HYPNOGRAM_INFO =
    "Last night through the night: which stage you were in minute by minute (awake, REM, light, deep), when you woke, and the sleep " +
        "cycles (estimated: the band does not report cycles, so each is taken to end where a REM period ends). Switch on heart rate to " +
        "lay your overnight heart rate over the stages. Breathing rate is not available: the band does not record it through the night."

private enum class HypnogramOverlay(val label: String) { None("STAGES"), HeartRate("+ HEART RATE") }

/**
 * Hypnogram Card: the night's timed stages as four lanes (awake, REM, light, deep), the wake time, estimated cycles and an
 * optional heart-rate overlay. A night without stored stage intervals falls back to its minutes in each stage.
 */
@Composable
internal fun HypnogramCard(latest: SleepAnalysisRow?) {
    val date = latest?.date?.let { LocalDate.parse(it) }
    val night by produceState<HypnogramNight?>(initialValue = null, date) {
        value = date?.let { runCatching { AnalysisRepository(SupabaseClientProvider.client).loadHypnogramNight(it) }.getOrNull() }
    }
    var overlay by rememberSaveable { mutableStateOf(HypnogramOverlay.None.name) }
    RedesignCard {
        MetricCardHeader(title = "SLEEP PHASE BREAKDOWN", latest = latest?.hours?.let { "%.1f".format(it) }, unit = "h", helpBody = HYPNOGRAM_INFO)
        val n = night
        if (n != null) {
            if (n.heartRate.isNotEmpty()) {
                SegmentedToggle(HypnogramOverlay.entries, HypnogramOverlay.valueOf(overlay), { it.label }, { overlay = it.name })
            }
            HypnogramChart(n, showHeartRate = overlay == HypnogramOverlay.HeartRate.name)
        } else {
            val deep = latest?.deepMin
            val rem = latest?.remMin
            val light = latest?.lightMin
            if (deep != null && rem != null && light != null) {
                FTSegmentBar(
                    listOf(
                        FTSegment("Deep ${deep.toInt()} min", deep, FT.Category.Sleep.c700),
                        FTSegment("REM ${rem.toInt()} min", rem, FT.Category.Sleep.c500),
                        FTSegment("Light ${light.toInt()} min", light, FT.Category.Sleep.c300),
                    ),
                )
                Text("Timed stages are not stored for this night, so only the time in each stage shows.", style = FTType.Caption, color = FT.TextMuted)
            } else {
                Text("No stage data for last night yet.", style = FTType.Caption, color = FT.TextMuted)
            }
        }
    }
}

private fun stageColor(stage: SleepStage) = when (stage) {
    SleepStage.Awake -> FT.Warning
    SleepStage.Rem -> FT.Category.Sleep.c500
    SleepStage.Light -> FT.Category.Sleep.c300
    SleepStage.Deep -> FT.Category.Sleep.c700
}

@Composable
private fun HypnogramChart(night: HypnogramNight, showHeartRate: Boolean) {
    val lanes = listOf(SleepStage.Awake, SleepStage.Rem, SleepStage.Light, SleepStage.Deep)
    val span = night.spanMin.coerceAtLeast(1).toFloat()
    val hrLo = night.heartRate.minOfOrNull { it.bpm } ?: 0
    val hrHi = night.heartRate.maxOfOrNull { it.bpm } ?: 1
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.height(120.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceAround) {
            lanes.forEach { Text(it.label, style = FTType.Micro, color = stageColor(it)) }
        }
        Canvas(Modifier.weight(1f).height(120.dp)) {
            val laneH = size.height / lanes.size
            fun x(min: Int) = min / span * size.width
            val sorted = night.blocks.sortedBy { it.startMin }
            sorted.forEach { b ->
                val lane = lanes.indexOf(b.stage)
                drawRect(stageColor(b.stage), Offset(x(b.startMin), lane * laneH + laneH * 0.15f), Size(maxOf(x(b.durationMin), 1.5f), laneH * 0.7f))
            }
            sorted.zipWithNext().forEach { (a, c) ->
                val ya = lanes.indexOf(a.stage) * laneH + laneH / 2
                val yc = lanes.indexOf(c.stage) * laneH + laneH / 2
                drawLine(FT.GlassBorder, Offset(x(a.endMin), ya), Offset(x(c.startMin), yc), strokeWidth = 1f)
            }
            night.cycles.forEach { drawLine(FT.TextMuted, Offset(x(it.endMin), 0f), Offset(x(it.endMin), size.height), strokeWidth = 1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
            if (showHeartRate && hrHi > hrLo) {
                night.heartRate.zipWithNext().filter { (a, c) -> c.minute - a.minute <= MINUTE_HR_MAX_GAP_MINUTES }.forEach { (a, c) ->
                    fun y(bpm: Int) = size.height - (bpm - hrLo).toFloat() / (hrHi - hrLo) * size.height
                    drawLine(FT.DomainHeart, Offset(x(a.minute), y(a.bpm)), Offset(x(c.minute), y(c.bpm)), strokeWidth = 1.5.dp.toPx())
                }
            }
            drawLine(FT.TextPrimary, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), strokeWidth = 2f)
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 52.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        (0..4).forEach { i -> Text(clockLabel(night.bedtime, night.tzOffsetMin, (night.spanMin * i) / 4), style = FTType.Micro, color = FT.TextMuted) }
    }
    val m = stageMinutes(night.blocks)
    Text(
        "Asleep to ${clockLabel(night.bedtime, night.tzOffsetMin, night.spanMin)} · " +
            lanes.filter { it != SleepStage.Awake }.joinToString(" · ") { "${it.label.lowercase().replaceFirstChar { c -> c.uppercase() }} ${m[it] ?: 0} min" } +
            " · awake ${m[SleepStage.Awake] ?: 0} min · ${night.cycles.size} estimated cycles (dashed)" +
            if (showHeartRate) " · heart rate $hrLo–$hrHi bpm" else "",
        style = FTType.Caption,
        color = FT.TextSecondary,
    )
}

private const val GLUCOSE_INFO =
    "An estimate, meal by meal, of how large a glucose rise today's food is likely to bring, worked from its carbohydrate, fibre, sugar, " +
        "protein and fat. It is a relative load (LOW, MODERATE or HIGH), not measured glucose and not a number in mg/dL: how one person " +
        "responds to the same meal varies widely, and the effect of protein, fat and fibre is a rough allowance. Meals within 90 minutes " +
        "count as one. A nutrient you did not log is left out (not counted as zero) and lowers the confidence. Not a health assessment."

/** Glucose spiking: one row per meal (meals within 90 minutes grouped), each a relative load band with what drove it. */
@Composable
internal fun GlucoseSpikingCard(meals: List<MealRow>) {
    val rows = remember(meals) { estimateMealResponses(meals) }
    RedesignCard {
        MetricCardHeader(title = "GLUCOSE SPIKING", latest = null, helpBody = GLUCOSE_INFO)
        if (rows.isEmpty()) {
            Text("No meals logged yet today.", style = FTType.Caption, color = FT.TextMuted)
        }
        rows.forEach { e ->
            val time = runCatching { OffsetDateTime.parse(e.startedAt).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("—")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(time, style = FTType.Value, color = FT.TextPrimary)
                    Text(e.description, style = FTType.BodySmall, color = FT.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    FTStatePill(
                        e.band?.let { b ->
                            StateWord(MetricState.Neutral, when (b) { GlycaemicBand.Low -> "▽ LOW"; GlycaemicBand.Moderate -> "◇ MODERATE"; GlycaemicBand.High -> "△ HIGH" })
                        } ?: StateWord(MetricState.Unavailable, "NO CARBS LOGGED"),
                    )
                }
                if (e.drivers.isNotEmpty()) Text(e.drivers.joinToString(" · "), style = FTType.Caption, color = FT.TextSecondary)
                Text(
                    "ESTIMATE · ${e.confidence.name.uppercase()} CONFIDENCE" + if (e.missing.isNotEmpty()) " · NOT LOGGED: ${e.missing.joinToString(", ")}" else "",
                    style = FTType.Micro, color = FT.TextMuted,
                )
            }
        }
        Text("Estimate from carbs, fibre, protein and fat. Not measured glucose.", style = FTType.Caption, color = FT.TextMuted)
    }
}
