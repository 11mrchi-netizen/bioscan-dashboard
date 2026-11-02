package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ScoresView
import com.bioscan.fieldterminal.domain.score.BowelTiming
import com.bioscan.fieldterminal.domain.score.DomainKey
import com.bioscan.fieldterminal.domain.score.StoolLane
import com.bioscan.fieldterminal.domain.score.StoolPattern
import com.bioscan.fieldterminal.domain.score.bowelTiming
import com.bioscan.fieldterminal.domain.score.bowelTimingHeadline
import com.bioscan.fieldterminal.domain.score.stoolPattern
import com.bioscan.fieldterminal.domain.score.stoolPatternHeadline
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.ui.screens.status.HypnogramCard
import com.bioscan.fieldterminal.ui.screens.status.PhysiologicalStressCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

// Scoring system (docs/scoring-system/02-page-mockups.md §3-§4): THE FULL PICTURE of a domain. Each is a one-line tile on
// the domain's overlay that opens its own level 3 overlay: views that explain the score but aren't scored themselves.

const val PICTURE_DAYS = 14L

enum class FullPicture(val title: String, val domain: DomainKey) {
    LAST_NIGHT("Last night", DomainKey.SLEEP),
    DIGESTIVE_PATTERN("Digestive pattern · 14 days", DomainKey.FUEL),
    BOWEL_TIMING("Bowel timing · 14 days", DomainKey.FUEL),
    STRESS_DAY("Stress through the day", DomainKey.BALANCE),
}

fun picturesFor(key: DomainKey): List<FullPicture> = FullPicture.entries.filter { it.domain == key }

/** The day a domain's views are about: the night before the morning (SLEEP), else the day before it. */
private fun ScoresView.aboutDay(): LocalDate = day.minusDays(1)

fun ScoresView.stoolWindow(zone: ZoneId = ZoneId.systemDefault()): StoolPattern =
    stoolPattern(stool, aboutDay().minusDays(PICTURE_DAYS - 1), aboutDay(), zone)

fun ScoresView.timingWindow(zone: ZoneId = ZoneId.systemDefault()): BowelTiming =
    bowelTiming(stool, aboutDay(), PICTURE_DAYS.toInt(), zone)

/** The tile's headline for each view. */
fun ScoresView.pictureHeadline(picture: FullPicture): String = when (picture) {
    FullPicture.LAST_NIGHT -> lastSleep?.let { row ->
        val h = row.hours ?: return@let "no sleep recorded"
        val total = (h * 60).toInt()
        "${total / 60}h${(total % 60).toString().padStart(2, '0')}" + (row.deepMin?.let { " · deep ${it.toInt()} min" } ?: "")
    } ?: "no sleep recorded"
    FullPicture.DIGESTIVE_PATTERN -> stoolPatternHeadline(stoolWindow())
    FullPicture.BOWEL_TIMING -> bowelTimingHeadline(timingWindow())
    FullPicture.STRESS_DAY -> stressDays.firstOrNull { it.date == aboutDay() }?.summary?.avg?.let { "average $it" } ?: "no readings"
}

@Composable
fun PictureOverlay(view: ScoresView, picture: FullPicture, onBack: () -> Unit, onClose: () -> Unit) {
    FullScreenOverlay(title = picture.title.uppercase(), trailing = picture.domain.label, onClose = onClose, onBack = onBack) {
        when (picture) {
            FullPicture.LAST_NIGHT -> HypnogramCard(view.lastSleep)
            FullPicture.DIGESTIVE_PATTERN -> {
                val pattern = view.stoolWindow()
                Text(stoolPatternHeadline(pattern), style = FTType.RowTitle, color = FT.TextPrimary)
                OverlaySection("BY FORM, OVER TIME") { StoolHypnogram(pattern) }
                OverlaySection("HOW IT SITS") {
                    for (lane in StoolLane.DRAWN) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(lane.label, style = FTType.Label, color = laneColor(lane), modifier = Modifier.width(72.dp))
                            Text("Bristol ${if (lane.types.first == lane.types.last) "${lane.types.first}" else "${lane.types.first}–${lane.types.last}"}",
                                style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.weight(1f))
                            Text("${pattern.counts[lane] ?: 0}", style = FTType.Value, color = FT.TextPrimary)
                        }
                    }
                    val untyped = pattern.marks.size - pattern.typed
                    if (untyped > 0) Text("$untyped logged without a type (shown on the timing view only).", style = FTType.Caption, color = FT.TextMuted)
                }
                OverlaySection("WHAT IS IT?") {
                    Text(
                        "Every logged bowel movement over 14 days, placed by its form: liquid on top, then loose, healthy and " +
                            "hard at the bottom, joined in time like a hypnogram. One reading means little; the pattern is " +
                            "the signal. Descriptive only, not a diagnosis.",
                        style = FTType.BodySmall, color = FT.TextSecondary,
                    )
                }
            }
            FullPicture.BOWEL_TIMING -> {
                val timing = view.timingWindow()
                Text(bowelTimingHeadline(timing), style = FTType.RowTitle, color = FT.TextPrimary)
                OverlaySection("BY TIME OF DAY") { BowelTimingChart(timing) }
                OverlaySection("WHAT IS IT?") {
                    Text(
                        "Each day's bowel movements by time of day. The shaded band is your usual window for the first one, " +
                            "learned from the 60 days before. Steady means this fortnight moved no more than usual.",
                        style = FTType.BodySmall, color = FT.TextSecondary,
                    )
                }
            }
            FullPicture.STRESS_DAY -> {
                val day = view.stressDays.firstOrNull { it.date == view.aboutDay() }
                if (day == null) Text("No stress readings for the day.", style = FTType.Body, color = FT.TextMuted)
                else PhysiologicalStressCard(analyzeStressDay(day, view.stressDays.filter { it.date.isBefore(day.date) }, view.exerciseWindows), day)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

fun laneColor(lane: StoolLane): Color = when (lane) {
    StoolLane.LIQUID -> FT.Critical
    StoolLane.LOOSE -> FT.Warning
    StoolLane.HEALTHY -> FT.Emerald
    StoolLane.HARD -> FT.Warning
}

/** The stool hypnogram: lanes top to bottom LIQUID, LOOSE, HEALTHY, HARD; marks at their time, joined in order. */
@Composable
fun StoolHypnogram(pattern: StoolPattern, zone: ZoneId = ZoneId.systemDefault()) {
    val lanes = StoolLane.DRAWN
    val start = pattern.from.atStartOfDay(zone).toInstant()
    val spanMin = Duration.between(start, pattern.to.plusDays(1).atStartOfDay(zone).toInstant()).toMinutes().toFloat()
    val typed = pattern.marks.filter { it.lane != null }
    Row(modifier = Modifier.fillMaxWidth().height(132.dp)) {
        Column(modifier = Modifier.width(60.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceAround) {
            lanes.forEach { Text(it.label, style = FTType.Micro, color = laneColor(it)) }
        }
        Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val laneH = size.height / lanes.size
            fun x(at: java.time.Instant) = size.width * Duration.between(start, at).toMinutes() / spanMin
            fun yMid(lane: StoolLane) = lanes.indexOf(lane) * laneH + laneH / 2
            // Lane guides and day separators.
            lanes.indices.forEach { i -> drawLine(FT.GlassTrack, Offset(0f, (i + 1) * laneH), Offset(size.width, (i + 1) * laneH), strokeWidth = 1f) }
            for (d in 0..pattern.days) {
                val xd = size.width * d / pattern.days
                drawLine(FT.GlassTrack, Offset(xd, 0f), Offset(xd, size.height), strokeWidth = 1f)
            }
            // The healthy lane is the reference band.
            drawRect(FT.Emerald.copy(alpha = 0.08f), Offset(0f, lanes.indexOf(StoolLane.HEALTHY) * laneH), Size(size.width, laneH))
            typed.zipWithNext().forEach { (a, b) ->
                drawLine(FT.GlassBorder, Offset(x(a.at), yMid(a.lane!!)), Offset(x(b.at), yMid(b.lane!!)), strokeWidth = 1.5f)
            }
            val markW = (size.width / pattern.days * 0.22f).coerceIn(4f, 18f)
            typed.forEach { m ->
                val lane = m.lane!!
                drawRect(laneColor(lane), Offset(x(m.at) - markW / 2, lanes.indexOf(lane) * laneH + laneH * 0.18f), Size(markW, laneH * 0.64f))
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = 60.dp)) {
        listOf(pattern.from, pattern.from.plusDays(pattern.days / 2L), pattern.to).forEachIndexed { i, d ->
            Text(
                "${d.dayOfMonth} ${d.month.name.take(3)}", style = FTType.Micro, color = FT.TextMuted,
                modifier = Modifier.weight(1f), textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End },
            )
        }
    }
}

/** One row per day, dots at each movement's time of day, the usual window shaded. */
@Composable
fun BowelTimingChart(timing: BowelTiming) {
    val rowH = 14.dp
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.width(44.dp)) {
            timing.days.forEach { (d, _) ->
                Text("${d.dayOfWeek.name.take(2)} ${d.dayOfMonth}", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.height(rowH))
            }
        }
        Canvas(modifier = Modifier.weight(1f).height(rowH * timing.days.size)) {
            val h = size.height / timing.days.size.coerceAtLeast(1)
            fun x(minute: Int) = size.width * minute / (24f * 60f)
            if (timing.usualStart != null && timing.usualEnd != null) {
                drawRect(FT.Emerald.copy(alpha = 0.12f), Offset(x(timing.usualStart), 0f), Size(x(timing.usualEnd) - x(timing.usualStart), size.height))
            }
            for (hour in listOf(0, 6, 12, 18, 24)) drawLine(FT.GlassTrack, Offset(x(hour * 60), 0f), Offset(x(hour * 60), size.height), strokeWidth = 1f)
            timing.days.forEachIndexed { i, (_, minutes) ->
                minutes.forEachIndexed { j, m ->
                    val first = j == 0
                    val inside = timing.inWindow(m)
                    val color = when {
                        !first -> FT.TextSecondary
                        inside == false -> FT.Warning
                        else -> FT.Emerald
                    }
                    drawCircle(color, radius = if (first) 5f else 3.5f, center = Offset(x(m), i * h + h / 2))
                }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = 44.dp)) {
        listOf("00", "06", "12", "18", "24").forEachIndexed { i, label ->
            Text(label, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f),
                textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center })
        }
    }
}
