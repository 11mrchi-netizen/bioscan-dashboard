package com.bioscan.fieldterminal.ui.screens.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.ConcernStateDto
import com.bioscan.fieldterminal.data.model.ContextLanesDto
import com.bioscan.fieldterminal.data.model.EpisodeDto
import com.bioscan.fieldterminal.domain.health.SeverityPoint
import com.bioscan.fieldterminal.domain.health.recoveryCurves
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Health concerns (plan §5.3-5.4): the concern page's charts, drawn with the shared chart rules (R4): labelled y values,
// a line that breaks where nothing was checked in, explicit zeros as ticks on the baseline (a symptom-free day is data),
// recalled zeros hollow, episodes as shaded spans, sessions as marks underneath on the same time axis.

private val MONTH_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun DrawScope.axisLabel(text: String, x: Float, y: Float, color: Color) {
    val paint = android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        textSize = 26f
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

/** Severity over time with episodes and sessions (the timeline). */
@Composable
fun SeverityTimeline(points: List<SeverityPoint>, episodes: List<EpisodeDto>, sessionDays: Set<LocalDate>, height: Dp = 180.dp) {
    if (points.isEmpty()) return
    val first = points.first().date
    val last = points.last().date
    val span = ChronoUnit.DAYS.between(first, last).coerceAtLeast(1).toFloat()
    val observed = points.count { it.observed }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(height).semantics {
                contentDescription = "Severity from ${first.format(MONTH_DAY)} to ${last.format(MONTH_DAY)}: $observed of ${points.size} days checked in, ${episodes.size} episodes."
            },
        ) {
            val left = 44f
            val bottomPad = 34f
            val w = size.width - left
            val h = size.height - bottomPad - 18f
            fun x(d: LocalDate) = left + ChronoUnit.DAYS.between(first, d) / span * w
            fun y(v: Int) = 8f + h - v / 10f * h
            // episodes
            episodes.forEach { e ->
                // LocalDate is Comparable<ChronoLocalDate>, so coerceAtLeast does not apply; clamp by hand.
                val s = LocalDate.parse(e.start).let { if (it.isBefore(first)) first else it }
                val en = (e.end?.let(LocalDate::parse) ?: last).let { if (it.isAfter(last)) last else it }
                if (en >= first && s <= last) drawRect(FT.DomainHeart.copy(alpha = 0.10f), Offset(x(s), 8f), Size((x(en) - x(s)).coerceAtLeast(2f), h))
            }
            // y axis: 0, 5, 10
            for (v in listOf(0, 5, 10)) {
                drawLine(FT.GlassBorder, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
                axisLabel(v.toString(), 4f, y(v) + 8f, FT.TextMuted)
            }
            // line, broken at unobserved days
            var path: Path? = null
            var prev: SeverityPoint? = null
            for (p in points) {
                val v = p.severity
                if (!p.observed || v == null) { path?.let { drawPath(it, FT.DomainHeart, style = Stroke(3f)) }; path = null; prev = null; continue }
                val o = Offset(x(p.date), y(v))
                if (path == null || prev == null || ChronoUnit.DAYS.between(prev.date, p.date) > 1) {
                    path?.let { drawPath(it, FT.DomainHeart, style = Stroke(3f)) }
                    path = Path().apply { moveTo(o.x, o.y) }
                } else path.lineTo(o.x, o.y)
                if (v == 0) {
                    if (p.recalled) drawCircle(FT.TextMuted, 3f, o, style = Stroke(1.5f)) else drawCircle(FT.TextSecondary, 3f, o)
                } else drawCircle(FT.DomainHeart, 3.5f, o)
                prev = p
            }
            path?.let { drawPath(it, FT.DomainHeart, style = Stroke(3f)) }
            // sessions under the axis
            sessionDays.filter { it >= first && it <= last }.forEach { d ->
                drawLine(FT.DomainTraining, Offset(x(d), 8f + h + 6f), Offset(x(d), 8f + h + 14f), 2f)
            }
            axisLabel(first.format(MONTH_DAY), left, size.height - 2f, FT.TextMuted)
            axisLabel(last.format(MONTH_DAY), size.width - 90f, size.height - 2f, FT.TextMuted)
        }
        Text("Line: severity 0-10 on checked-in days, broken where there was no check-in · dots on 0: symptom-free (hollow: \"nothing since\") · shaded: episodes · green ticks: sessions", style = FTType.Micro, color = FT.TextMuted)
    }
}

/** Days since each episode began against severity, one panel per episode. */
@Composable
fun RecoveryOverlay(s: ConcernStateDto) {
    val curves = recoveryCurves(s).filter { it.second.size >= 2 }
    if (curves.isEmpty()) {
        Text("Recovery curves appear once an episode has two or more rated check-ins.", style = FTType.BodySmall, color = FT.TextSecondary)
        return
    }
    val maxDay = curves.maxOf { c -> c.second.maxOf { it.first } }.coerceAtLeast(7)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "Recovery of ${curves.size} episodes, severity against days since each began." }) {
        val left = 44f
        val h = size.height - 40f
        val w = size.width - left
        for (v in listOf(0, 5, 10)) {
            val yy = 8f + h - v / 10f * h
            drawLine(FT.GlassBorder, Offset(left, yy), Offset(size.width, yy), 1f)
            axisLabel(v.toString(), 4f, yy + 8f, FT.TextMuted)
        }
        curves.forEachIndexed { i, (_, pts) ->
            val alpha = 0.35f + 0.65f * (i + 1) / curves.size
            val path = Path()
            pts.forEachIndexed { k, (d, v) ->
                val o = Offset(left + d / maxDay.toFloat() * w, 8f + h - v / 10f * h)
                if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, FT.DomainHeart.copy(alpha = alpha), style = Stroke(if (i == curves.lastIndex) 3.5f else 2f))
        }
        axisLabel("day 0", left, size.height - 4f, FT.TextMuted)
        axisLabel("day $maxDay", size.width - 90f, size.height - 4f, FT.TextMuted)
    }
    Text("Each line is one episode from its first day; the brightest is the latest.", style = FTType.Micro, color = FT.TextMuted)
}

/** Training and recovery on the same days as the concern (the CONTEXT lanes). */
@Composable
fun ContextLanes(lanes: ContextLanesDto, from: LocalDate) {
    val start = lanes.dates.indexOfFirst { it >= from.toString() }.takeIf { it >= 0 } ?: return
    val dates = lanes.dates.drop(start)
    if (dates.isEmpty()) return
    @Composable
    fun lane(title: String, values: List<Double?>, color: Color, unit: String, bars: Boolean) {
        val vals = values.drop(start)
        val max = vals.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = FTType.Micro, color = FT.TextSecondary)
            Text("max ${"%.0f".format(max)} $unit", style = FTType.Micro, color = FT.TextMuted)
        }
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val n = vals.size.coerceAtLeast(1)
            val bw = size.width / n
            if (bars) {
                vals.forEachIndexed { i, v -> if (v != null && v > 0) drawRect(color, Offset(i * bw, size.height * (1 - (v / max).toFloat())), Size((bw - 1f).coerceAtLeast(1f), size.height * (v / max).toFloat())) }
            } else {
                var prev: Offset? = null
                vals.forEachIndexed { i, v ->
                    if (v == null) { prev = null; return@forEachIndexed }
                    val o = Offset(i * bw + bw / 2, size.height * (1 - (v / max).toFloat()))
                    prev?.let { drawLine(color, it, o, 2f) }
                    prev = o
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lane("RUNNING, KM A DAY (TRAIL IN THE LANE BELOW)", lanes.runKm, FT.DomainTraining, "km", bars = true)
        lane("TRAIL KM", lanes.trailKm, FT.EmeraldHighlight, "km", bars = true)
        lane("7-DAY TRAINING LOAD", lanes.load7, FT.Info, "", bars = false)
        lane("SLEEP, H", lanes.sleepHours, FT.Category.Sleep.c500, "h", bars = false)
        val hrv = lanes.hrvDeviation.map { it?.let { d -> d + 50.0 } } // shifted so below-expected mornings stay visible
        lane("HRV AGAINST EXPECTED (MIDDLE = AS EXPECTED)", hrv, FT.DomainHeart, "", bars = false)
        Text("${LocalDate.parse(dates.first()).format(MONTH_DAY)} – ${LocalDate.parse(dates.last()).format(MONTH_DAY)}, the same days as the timeline above.", style = FTType.Micro, color = FT.TextMuted)
    }
}
