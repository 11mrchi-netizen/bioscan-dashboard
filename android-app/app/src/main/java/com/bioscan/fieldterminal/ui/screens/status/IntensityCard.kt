package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.ZoneAnchorsRepository
import com.bioscan.fieldterminal.domain.HeartRateZone
import com.bioscan.fieldterminal.domain.IntensityBand
import com.bioscan.fieldterminal.domain.WeekIntensity
import com.bioscan.fieldterminal.domain.ZoneMethod
import com.bioscan.fieldterminal.domain.weeklyIntensity
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.heartRateZoneColor
import com.bioscan.fieldterminal.ui.components.softLoad
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Training › Load: how the last twelve weeks of endurance training split between low, moderate and high intensity
// (domain/IntensityDistribution.kt). Bars are minutes per week, stacked by band in the zone colours of a session's chart
// (low in Z2's, moderate in Z3's, high in Z5's); tap or drag to read a week. Loads its own data, so the rest of the tab
// never waits on it, and stays hidden when nothing can be shown.
private val WEEK_FMT = DateTimeFormatter.ofPattern("d MMM")

private val BAND_COLORS = mapOf(
    IntensityBand.LOW to heartRateZoneColor(HeartRateZone.Z2),
    IntensityBand.MODERATE to heartRateZoneColor(HeartRateZone.Z3),
    IntensityBand.HIGH to heartRateZoneColor(HeartRateZone.Z5),
)

private const val INFO =
    "Minutes of runs, rides and hikes per week by heart-rate intensity: low (below Zone 3), moderate (Zone 3) and high " +
        "(Zones 4 and 5), in the zones your setting picks (Setup › Training setup). Endurance athletes typically spend " +
        "about four fifths of their training at low intensity; this shows your split, it does not set a target."

private const val INFO_DETAILS =
    "Each workout's heart rate, second by second, is placed in the zones as they were on its date. Only workouts with a " +
        "Zepp heart-rate record are in the bars; the line under the chart says how many of the week's sessions that is. " +
        "Five zones are folded into three bands as an approximation of the three-zone model, which is defined by the two " +
        "lactate or ventilatory thresholds (Seiler 2010, Int J Sports Physiol Perform)."

@Composable
fun IntensityCard(modifier: Modifier = Modifier) {
    var weeks by remember { mutableStateOf<List<WeekIntensity>?>(null) }
    var method by remember { mutableStateOf(ZoneMethod.AUTO) }
    LaunchedEffect(Unit) {
        val client = SupabaseClientProvider.client
        val sessions = softLoad { AnalysisRepository(client).loadIntensitySessions() } ?: return@LaunchedEffect
        val anchors = softLoad { ZoneAnchorsRepository(client).load() } ?: return@LaunchedEffect
        method = softLoad { TrainingProgramRepository(client).loadZoneMethod() } ?: ZoneMethod.AUTO
        weeks = weeklyIntensity(sessions, anchors::anchorsFor, method, LocalDate.now())
    }
    val w = weeks ?: return
    if (w.none { it.totalSeconds > 0 }) return

    FTCard(title = "INTENSITY, LAST 12 WEEKS", info = INFO, infoDetails = INFO_DETAILS, methodology = "intensity_distribution", modifier = modifier) {
        var selected by remember(w) { mutableStateOf<Int?>(null) }
        val shown = selected ?: w.lastIndex
        val week = w[shown]
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("WEEK OF ${WEEK_FMT.format(week.weekStart).uppercase()}", style = FTType.Label, color = FT.TextMuted, modifier = Modifier.weight(1f))
                Text(formatMinutes(week.totalSeconds), style = FTType.Value, color = FT.TextPrimary)
            }
            Text(shareLine(week), style = FTType.BodySmall, color = FT.TextSecondary)
            StackedWeeks(w, shown, onSelect = { selected = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(WEEK_FMT.format(w.first().weekStart).uppercase(), style = FTType.Micro, color = FT.TextMuted)
                Text("SCALE 0-${formatMinutes(w.maxOf { it.totalSeconds })}", style = FTType.Micro, color = FT.TextMuted)
                Text(WEEK_FMT.format(w.last().weekStart).uppercase(), style = FTType.Micro, color = FT.TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IntensityBand.entries.forEach { band ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).background(BAND_COLORS.getValue(band), CircleShape))
                        Text(band.label, style = FTType.Caption, color = FT.TextSecondary)
                    }
                }
            }
            Text(
                "${week.withHeartRate} of ${week.sessions} endurance sessions that week have Zepp heart rate · zones: ${methodWords(method)}",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
        }
    }
}

@Composable
private fun StackedWeeks(weeks: List<WeekIntensity>, shown: Int, onSelect: (Int) -> Unit) {
    val top = weeks.maxOf { it.totalSeconds }.takeIf { it > 0 } ?: 1.0
    fun indexAt(x: Float, width: Float) = ((x / width) * weeks.size).toInt().coerceIn(0, weeks.size - 1)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(weeks) { detectTapGestures { onSelect(indexAt(it.x, size.width.toFloat())) } }
            .pointerInput(weeks) { detectHorizontalDragGestures { change, _ -> onSelect(indexAt(change.position.x, size.width.toFloat())) } },
    ) {
        val slot = size.width / weeks.size
        val barW = (slot * 0.62f).coerceAtMost(26.dp.toPx())
        val usable = size.height - 4.dp.toPx()
        val gap = 2.dp.toPx()
        weeks.forEachIndexed { i, wk ->
            val x = i * slot + (slot - barW) / 2f
            val alpha = if (i == shown) 1f else 0.55f
            if (wk.totalSeconds <= 0) {
                drawRoundRect(FT.GlassTrack, Offset(x, size.height - 2.dp.toPx()), Size(barW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                return@forEachIndexed
            }
            var y = size.height
            IntensityBand.entries.forEach { band ->
                val h = ((wk.seconds[band] ?: 0.0) / top).toFloat() * usable
                if (h > 0.5f) {
                    val drawn = (h - gap).coerceAtLeast(1f)
                    drawRoundRect(BAND_COLORS.getValue(band).copy(alpha = alpha), Offset(x, y - drawn), Size(barW, drawn), CornerRadius(2.dp.toPx()))
                    y -= h
                }
            }
        }
    }
}

private fun shareLine(w: WeekIntensity): String {
    if (w.totalSeconds <= 0) return "No endurance heart rate this week."
    return IntensityBand.entries.joinToString(" · ") { b -> "${((w.share(b) ?: 0.0) * 100).roundToInt()} % ${b.label.lowercase()}" }
}

private fun formatMinutes(seconds: Double): String {
    val min = (seconds / 60).roundToInt()
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

private fun methodWords(m: ZoneMethod): String = when (m) {
    ZoneMethod.AUTO -> "auto (threshold for runs, else reserve)"
    ZoneMethod.LTHR -> "threshold (runs; reserve otherwise)"
    ZoneMethod.HRR -> "heart-rate reserve"
    ZoneMethod.MAX -> "% of maximum"
}
