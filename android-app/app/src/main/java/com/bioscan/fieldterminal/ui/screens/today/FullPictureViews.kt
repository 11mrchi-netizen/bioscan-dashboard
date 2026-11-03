package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import com.bioscan.fieldterminal.domain.score.ScoreKeys
import com.bioscan.fieldterminal.domain.score.sparkline
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

/** A detail page a full-picture tile opens (a pushed page of its own, ui/screens/detail), rather than an overlay. */
enum class DetailPage(val route: String) {
    SLEEP("detail_sleep"), CARDIO("detail_cardio"), SEXUAL("detail_sexual"), WELLBEING("detail_wellbeing"),
    LOAD("detail_load"), INJURY("detail_injury"), NUTRITION("detail_nutrition"), HYDRATION("detail_hydration"), DIGESTION("detail_digestion"),
}

enum class FullPicture(val title: String, val domain: DomainKey, val detail: DetailPage? = null) {
    LAST_NIGHT("Last night", DomainKey.SLEEP),
    SLEEP_CHARTS("Sleep", DomainKey.SLEEP, DetailPage.SLEEP),
    CARDIO_CHARTS("Heart", DomainKey.RECOVERY, DetailPage.CARDIO),
    SEXUAL_HEALTH("Sexual health", DomainKey.RECOVERY, DetailPage.SEXUAL),
    LOAD_CHARTS("Training load", DomainKey.LOAD, DetailPage.LOAD),
    INJURY("Injury", DomainKey.LOAD, DetailPage.INJURY),
    NUTRITION_CHARTS("Nutrition", DomainKey.FUEL, DetailPage.NUTRITION),
    HYDRATION_CHARTS("Hydration", DomainKey.FUEL, DetailPage.HYDRATION),
    DIGESTION("Digestion", DomainKey.FUEL, DetailPage.DIGESTION),
    STRESS_DAY("Stress through the day", DomainKey.BALANCE),
    WELLBEING_CHARTS("Wellbeing", DomainKey.BALANCE, DetailPage.WELLBEING),
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
    FullPicture.DIGESTION -> stoolPatternHeadline(stoolWindow()) + " · " + bowelTimingHeadline(timingWindow())
    FullPicture.STRESS_DAY -> stressDays.firstOrNull { it.date == aboutDay() }?.summary?.avg?.let { "average $it" } ?: "no readings"
    // The "every chart" tiles open a whole page: their thumbnail says enough.
    else -> ""
}

@Composable
fun PictureOverlay(view: ScoresView, picture: FullPicture, onBack: () -> Unit, onClose: () -> Unit) {
    FullScreenOverlay(title = picture.title.uppercase(), trailing = picture.domain.label, onClose = onClose, onBack = onBack) {
        when (picture) {
            FullPicture.LAST_NIGHT -> {
                HypnogramCard(view.lastSleep)
                NightTiles(view)
            }
            // Detail pages are pushed pages, opened from the tile; nothing to draw here.
            FullPicture.SLEEP_CHARTS, FullPicture.CARDIO_CHARTS, FullPicture.SEXUAL_HEALTH, FullPicture.LOAD_CHARTS,
            FullPicture.INJURY, FullPicture.NUTRITION_CHARTS, FullPicture.HYDRATION_CHARTS, FullPicture.DIGESTION, FullPicture.WELLBEING_CHARTS -> Unit
            FullPicture.STRESS_DAY -> {
                val day = view.stressDays.firstOrNull { it.date == view.aboutDay() }
                if (day == null) Text("No stress readings for the day.", style = FTType.Body, color = FT.TextMuted)
                else {
                    val result = analyzeStressDay(day, view.stressDays.filter { it.date.isBefore(day.date) }, view.exerciseWindows)
                    Text("Average ${day.summary.avg ?: "—"} through the day", style = FTType.RowTitle, color = FT.TextPrimary)
                    OverlaySection("THROUGH THE DAY") { StressCurve(day, result, view.exerciseWindows) }
                    PhysiologicalStressCard(result, day)
                }
                OverlaySection("LAST 7 DAYS · BY ZONE") { StressWeek(view) }
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

// ---- 14. the stress curve ----

/**
 * The day's stress readings as a line (07:00 to 23:00), exercise shaded and left out, the day's baseline dashed and
 * labelled, the peak marked with how far it rose above the baseline, and the evening wind-down window shaded.
 */
@Composable
fun StressCurve(
    day: com.bioscan.fieldterminal.domain.stress.StressDay,
    result: com.bioscan.fieldterminal.domain.stress.StressRhythmResult,
    exercise: List<Pair<java.time.Instant, java.time.Instant>>,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val start = day.date.atTime(6, 0).atZone(zone).toInstant()
    val end = day.date.plusDays(1).atStartOfDay(zone).toInstant()
    val span = Duration.between(start, end).toMinutes().toFloat()
    val inExercise = { t: java.time.Instant -> exercise.any { (a, b) -> !t.isBefore(a) && !t.isAfter(b) } }
    val samples = day.samples.filter { !it.timestamp.isBefore(start) && it.timestamp.isBefore(end) }.sortedBy { it.timestamp }
    val peak = samples.filterNot { inExercise(it.timestamp) }.maxByOrNull { it.value }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(150.dp)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            fun x(t: java.time.Instant) = size.width * Duration.between(start, t).toMinutes() / span
            fun y(v: Double) = (size.height * (1 - v / 100.0)).toFloat()
            // Stress levels as faint bands (Zepp's relaxed / normal / medium / high cut-offs).
            for ((lo, hi, a) in listOf(Triple(0.0, 25.0, 0.0f), Triple(60.0, 80.0, 0.06f), Triple(80.0, 100.0, 0.10f))) {
                if (a > 0f) drawRect(FT.Critical.copy(alpha = a), Offset(0f, y(hi)), Size(size.width, y(lo) - y(hi)))
            }
            // Evening wind-down: the last three hours before midnight.
            val eve = day.date.atTime(21, 0).atZone(zone).toInstant()
            drawRect(FT.Info.copy(alpha = 0.08f), Offset(x(eve), 0f), Size(size.width - x(eve), size.height))
            for ((a, b) in exercise) {
                if (b.isBefore(start) || a.isAfter(end)) continue
                drawRect(FT.GlassStrongFill, Offset(x(maxOf(a, start)), 0f), Size(x(minOf(b, end)) - x(maxOf(a, start)), size.height))
            }
            result.baseline?.let { b ->
                drawLine(FT.TextMuted, Offset(0f, y(b)), Offset(size.width, y(b)), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            val path = Path()
            var open = false
            samples.forEach { s ->
                if (inExercise(s.timestamp)) { open = false; return@forEach }
                val p = Offset(x(s.timestamp), y(s.value.toDouble()))
                if (!open) { path.moveTo(p.x, p.y); open = true } else path.lineTo(p.x, p.y)
            }
            drawPath(path, FT.Info, style = Stroke(width = 3f))
            peak?.let { drawCircle(FT.Critical, 7f, Offset(x(it.timestamp), y(it.value.toDouble()))) }
            for (h in listOf(6, 9, 12, 15, 18, 21)) {
                val t = day.date.atTime(h, 0).atZone(zone).toInstant()
                drawLine(FT.GlassTrack, Offset(x(t), size.height - 6f), Offset(x(t), size.height), strokeWidth = 1.5f)
            }
        }
        result.baseline?.let { Text("baseline ${it.toInt()}", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(start = 4.dp, top = (150 * (1 - it / 100.0) - 14).coerceAtLeast(0.0).dp)) }
        peak?.let { p ->
            val above = result.baseline?.let { " · +${(p.value - it).toInt()} over baseline" } ?: ""
            Text("peak ${p.value} outside exercise$above", style = FTType.Micro, color = FT.Critical, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
    // The chart runs 06:00 to midnight, so evenly spread labels land on their hours.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (h in listOf("06", "09", "12", "15", "18", "21", "24")) Text(h, style = FTType.Micro, color = FT.TextMuted)
    }
    Text(
        listOfNotNull(
            "grey = exercise, left out",
            "blue = evening wind-down" + (result.eveningDownRegulationPct?.let { " (${it.toInt()} % down)" } ?: ""),
        ).joinToString(" · "),
        style = FTType.Micro, color = FT.TextMuted,
    )
}

// ---- 11. a full-picture tile's thumbnail ----

@Composable
fun PictureThumb(view: ScoresView, picture: FullPicture) {
    val about = view.day.minusDays(1)
    when (picture) {
        FullPicture.LAST_NIGHT -> Sparkline(sparkline(view.series[ScoreKeys.SLEEP_HOURS], view.day, 7).map { it.second }, color = FT.Category.Sleep.c300)
        FullPicture.SLEEP_CHARTS -> Sparkline(sparkline(view.series[ScoreKeys.SLEEP_HOURS], view.day, 30).map { it.second }, color = FT.Category.Sleep.c300)
        FullPicture.CARDIO_CHARTS -> Sparkline(sparkline(view.series[ScoreKeys.HRV], view.day, 30).map { it.second })
        FullPicture.SEXUAL_HEALTH -> Sparkline(sparkline(view.series[ScoreKeys.MORNING_ERECTION], view.day, 30).map { it.second }, color = FT.Category.Arousal.c300)
        FullPicture.LOAD_CHARTS -> Sparkline(sparkline(view.series[ScoreKeys.TSB], view.day, 30).map { it.second }, color = FT.Category.Activity.c300)
        FullPicture.INJURY -> Unit
        FullPicture.DIGESTION -> MiniStool(view.stoolWindow())
        FullPicture.NUTRITION_CHARTS -> Sparkline(sparkline(view.series[ScoreKeys.ENERGY_RATIO], view.day, 30).map { it.second }, color = FT.DomainFuel)
        FullPicture.HYDRATION_CHARTS -> Sparkline(sparkline(view.series[ScoreKeys.WATER_RATIO], view.day, 30).map { it.second }, color = FT.DomainFuel)
        FullPicture.STRESS_DAY -> Sparkline(view.stressDays.firstOrNull { it.date == about }?.samples?.sortedBy { it.timestamp }?.map { it.value.toDouble() }.orEmpty(), color = FT.Info)
        FullPicture.WELLBEING_CHARTS -> Sparkline(sparkline(view.series[ScoreKeys.STRESSED_SHARE], view.day, 30).map { it.second }, color = FT.Analysis)
    }
}

/** The stool hypnogram in miniature: lanes as rows of ticks. */
@Composable
private fun MiniStool(pattern: StoolPattern, zone: ZoneId = ZoneId.systemDefault()) {
    val start = pattern.from.atStartOfDay(zone).toInstant()
    val spanMin = Duration.between(start, pattern.to.plusDays(1).atStartOfDay(zone).toInstant()).toMinutes().toFloat()
    Canvas(Modifier.width(84.dp).height(24.dp)) {
        val laneH = size.height / 4
        val lanes = StoolLane.DRAWN
        drawRect(FT.Emerald.copy(alpha = 0.12f), Offset(0f, lanes.indexOf(StoolLane.HEALTHY) * laneH), Size(size.width, laneH))
        pattern.marks.filter { it.lane != null }.forEach { m ->
            val x = size.width * Duration.between(start, m.at).toMinutes() / spanMin
            drawRect(laneColor(m.lane!!), Offset(x - 1.5f, lanes.indexOf(m.lane) * laneH + 1f), Size(3f, laneH - 2f))
        }
    }
}

// ---- Last night: resting heart rate and breathing under the hypnogram ----

/** Two tiles under the hypnogram: last night's resting heart rate and breathing rate against your normal, 14 nights' line. */
@Composable
private fun NightTiles(view: ScoresView) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NightTile("RESTING HR", view.series[ScoreKeys.RHR].orEmpty(), view.day, "bpm", { it.toInt().toString() }, 1.0, FT.DomainHeart, Modifier.weight(1f))
        NightTile("BREATHING", view.series[ScoreKeys.SLEEP_BREATHING].orEmpty(), view.day, "/min", { "%.1f".format(it) }, 0.3, FT.Category.Sleep.c300, Modifier.weight(1f))
    }
}

@Composable
private fun NightTile(
    label: String,
    series: Map<LocalDate, Double>,
    day: LocalDate,
    unit: String,
    format: (Double) -> String,
    minSd: Double,
    color: Color,
    modifier: Modifier,
) {
    val reading = com.bioscan.fieldterminal.domain.readMetric(series, day, minSd = minSd, maxAgeDays = 0)
    Column(
        modifier.background(FT.GlassFill, androidx.compose.foundation.shape.RoundedCornerShape(FT.RadiusModule)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = FTType.LabelCaps, color = FT.TextMuted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reading.latest?.let(format) ?: "—", style = FTType.MetricMedium, color = FT.TextPrimary)
            Text(unit, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(
            com.bioscan.fieldterminal.ui.screens.detail.positionGlyph(reading.position) + " " +
                com.bioscan.fieldterminal.ui.screens.detail.positionWords(reading.position) +
                (reading.normal?.let { " (${format(it.first)}–${format(it.second)})" } ?: ""),
            style = FTType.Caption, color = FT.TextSecondary,
        )
        Sparkline(sparkline(series, day, 14).map { it.second }, color = color)
    }
}

// ---- Stress through the day: the last seven days by zone ----

private fun stressZoneColor(z: com.bioscan.fieldterminal.domain.stress.StressZone): Color = when (z) {
    com.bioscan.fieldterminal.domain.stress.StressZone.HIGH -> FT.Critical
    com.bioscan.fieldterminal.domain.stress.StressZone.MEDIUM -> FT.Warning
    com.bioscan.fieldterminal.domain.stress.StressZone.NORMAL -> FT.Info
    com.bioscan.fieldterminal.domain.stress.StressZone.RELAXED -> FT.Emerald
}

/**
 * Seven small hypnograms, one per day (newest at the bottom), each running 06:00 to midnight across Zepp's four stress
 * zones, high on top and relaxed at the bottom, 30-minute averages joined in time. Exercise is not removed here: a
 * session shows as the high stretch it is.
 */
@Composable
private fun StressWeek(view: ScoresView, zone: ZoneId = ZoneId.systemDefault()) {
    val about = view.day.minusDays(1)
    val lanes = com.bioscan.fieldterminal.domain.stress.StressZone.DRAWN
    val days = (6 downTo 0).map { about.minusDays(it.toLong()) }
    val slotsByDay = days.associateWith { d ->
        view.stressDays.firstOrNull { it.date == d }?.let { com.bioscan.fieldterminal.domain.stress.stressSlots(it, zone) }.orEmpty()
    }
    if (slotsByDay.values.all { it.isEmpty() }) {
        Text("No stress readings in the last seven days.", style = FTType.BodySmall, color = FT.TextMuted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in days) {
            val slots = slotsByDay.getValue(d)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.dayOfWeek.name.take(3), style = FTType.Micro, color = if (d == about) FT.TextPrimary else FT.TextMuted, modifier = Modifier.width(32.dp))
                Canvas(Modifier.weight(1f).height(34.dp)) {
                    val laneH = size.height / lanes.size
                    fun x(m: Int) = size.width * (m - 360) / (18f * 60f)
                    lanes.forEachIndexed { i, z -> drawRect(stressZoneColor(z).copy(alpha = 0.07f), Offset(0f, i * laneH), Size(size.width, laneH - 1f)) }
                    if (slots.isEmpty()) return@Canvas
                    val path = Path()
                    slots.forEachIndexed { k, s ->
                        val y = lanes.indexOf(s.zone) * laneH + laneH / 2
                        val x0 = x(s.minuteOfDay)
                        val x1 = x(s.minuteOfDay + 30)
                        if (k == 0) path.moveTo(x0, y) else path.lineTo(x0, y)
                        path.lineTo(x1, y)
                    }
                    drawPath(path, FT.TextSecondary.copy(alpha = 0.5f), style = Stroke(width = 1.5f))
                    for (s in slots) {
                        val y = lanes.indexOf(s.zone) * laneH + laneH / 2
                        drawLine(stressZoneColor(s.zone), Offset(x(s.minuteOfDay), y), Offset(x(s.minuteOfDay + 30), y), strokeWidth = laneH * 0.6f)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (h in listOf("06", "09", "12", "15", "18", "21", "24")) Text(h, style = FTType.Micro, color = FT.TextMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (z in lanes) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.width(10.dp).height(4.dp).background(stressZoneColor(z)))
                    Text(z.label.lowercase(), style = FTType.Micro, color = FT.TextSecondary)
                }
            }
        }
        val week = slotsByDay.values.flatten()
        val mins = com.bioscan.fieldterminal.domain.stress.minutesByZone(week)
        val total = mins.values.sum().coerceAtLeast(1)
        Text(
            "Across the week: " + lanes.joinToString(" · ") { "${(mins.getValue(it) * 100 / total)} % ${it.label.lowercase()}" },
            style = FTType.Caption, color = FT.TextSecondary,
        )
    }
}
