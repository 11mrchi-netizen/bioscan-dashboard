package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.ScorecardResult
import com.bioscan.fieldterminal.domain.ScorecardVerdict
import com.bioscan.fieldterminal.domain.score.AnsPoint
import com.bioscan.fieldterminal.domain.score.Bullet
import com.bioscan.fieldterminal.domain.score.DomainKey
import com.bioscan.fieldterminal.domain.score.InputScore
import com.bioscan.fieldterminal.domain.score.ScoreScale
import com.bioscan.fieldterminal.domain.score.ScoreSpecs
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.domain.score.bulletOf
import com.bioscan.fieldterminal.domain.score.formatInputValue
import com.bioscan.fieldterminal.domain.score.formatJudgedValue
import com.bioscan.fieldterminal.domain.score.trendOf
import com.bioscan.fieldterminal.ui.components.GaugePalette
import com.bioscan.fieldterminal.ui.components.ScoreRing
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Scoring system: the small graphs of the score screens (the tour's list, items 1-14). One file, so they share a look:
// faint glass track, the band colours, labels drawn next to what they name rather than in a legend.

/** A colour for a sub-score on the 0-150 scale. */
fun scoreColor(score: Double): Color = when {
    score >= 100 -> FT.EmeraldHighlight
    score >= 60 -> FT.Emerald
    score >= 40 -> FT.Warning
    else -> FT.Critical
}

private fun dayLabel(day: LocalDate): String = "${day.dayOfMonth} ${day.month.name.take(3)}"

// ---- 5. the 30-day strip, on the banded scale ----

/** One bar per morning on the 0-[cap] scale, with the bands shaded behind and the 40 / 60 / 100 lines labelled. */
@Composable
fun BandedStrip(points: List<Pair<LocalDate, Double?>>, cap: Double = ScoreScale.MAX) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(26.dp).height(110.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(cap.roundToInt().toString(), style = FTType.Micro, color = FT.TextMuted)
            Text("0", style = FTType.Micro, color = FT.TextMuted)
        }
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        var pick by androidx.compose.runtime.remember(points) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        Canvas(Modifier.weight(1f).height(110.dp).then(
            with(com.bioscan.fieldterminal.ui.components.Inspect) { Modifier.inspect(points) { px, w, isTap ->
                val i = (px / w * points.size).toInt().coerceIn(0, (points.size - 1).coerceAtLeast(0))
                pick = if (points.getOrNull(i)?.second == null) pick else if (isTap && i == pick) null else i
            } },
        )) {
            fun y(v: Double) = size.height * (1f - (v / cap).toFloat())
            // Bands: low, below normal, normal, primed and above (only up to the cap).
            val bands = listOf(0.0 to 40.0 to FT.Critical, 40.0 to 60.0 to FT.Warning, 60.0 to 100.0 to FT.Emerald, 100.0 to 150.0 to FT.EmeraldHighlight)
            for ((range, color) in bands) {
                val (lo, hi) = range
                if (lo >= cap) continue
                drawRect(color.copy(alpha = 0.06f), Offset(0f, y(min(hi, cap))), Size(size.width, y(lo) - y(min(hi, cap))))
            }
            for (line in listOf(40.0, 60.0, 100.0).filter { it < cap }) {
                drawLine(FT.TextMuted.copy(alpha = if (line == 100.0) 0.8f else 0.35f), Offset(0f, y(line)), Offset(size.width, y(line)),
                    strokeWidth = 1.5f, pathEffect = if (line == 100.0) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null)
            }
            val n = points.size.coerceAtLeast(1)
            val slot = size.width / n
            points.forEachIndexed { i, (_, v) ->
                if (v == null) return@forEachIndexed
                val top = y(min(v, cap))
                drawRect(scoreColor(v).copy(alpha = if (i == points.lastIndex || i == pick) 1f else 0.7f), Offset(i * slot + slot * 0.18f, top), Size(slot * 0.64f, size.height - top))
            }
            pick?.let { i ->
                val (d, v) = points[i]
                if (v == null) return@let
                val cx = i * slot + slot / 2
                com.bioscan.fieldterminal.ui.components.Inspect.run {
                    drawOverlay(measurer, cx, listOf(Offset(cx, y(min(v, cap)))), dayLabel(d), listOf(com.bioscan.fieldterminal.ui.components.InspectRow(null, v.roundToInt().toString(), scoreColor(v))))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 26.dp)) {
        Text(points.firstOrNull()?.first?.let(::dayLabel) ?: "", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text("100 = top of your normal", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(2f), textAlign = TextAlign.Center)
        Text(points.lastOrNull()?.first?.let(::dayLabel) ?: "", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

// ---- 6. a driver row as a bullet chart ----

/** Name, a bullet chart (normal band or target zone shaded, target ticked, today a dot), the value and the points. */
@Composable
fun BulletRow(input: InputScore, points: Double?, onClick: () -> Unit) {
    val bullet = bulletOf(input)
    Row(
        modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open ${input.spec.label}") { onClick() }.padding(vertical = 6.dp)
            .semantics { contentDescription = "${input.spec.label} ${formatJudgedValue(input)}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(input.spec.label, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.width(96.dp), maxLines = 2)
        if (bullet != null) BulletBar(bullet, scoreColor(input.score), Modifier.weight(1f)) else Box(Modifier.weight(1f))
        Text(formatJudgedValue(input), style = FTType.Value, color = FT.TextPrimary, modifier = Modifier.width(84.dp), textAlign = TextAlign.End, maxLines = 1)
        if (points != null) {
            Text("${if (points >= 0.5) "+" else ""}${points.roundToInt()}", style = FTType.Telemetry,
                color = when { points >= 1 -> FT.EmeraldHighlight; points <= -1 -> FT.Critical; else -> FT.TextMuted },
                modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
        }
        Text("›", style = FTType.Telemetry, color = FT.TextMuted)
    }
}

@Composable
fun BulletBar(b: Bullet, dot: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(16.dp)) {
        val cy = size.height / 2
        fun x(v: Double) = (size.width * b.fraction(v)).toFloat()
        drawLine(FT.GlassTrack, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 4f, cap = StrokeCap.Round)
        drawRoundRect(FT.Emerald.copy(alpha = 0.28f), Offset(x(b.zoneLo), cy - 6f), Size(max(2f, x(b.zoneHi) - x(b.zoneLo)), 12f), CornerRadius(4f))
        b.tick?.let { t -> drawLine(FT.TextSecondary, Offset(x(t), cy - 9f), Offset(x(t), cy + 9f), strokeWidth = 2f) }
        drawCircle(dot, radius = 6.5f, center = Offset(x(b.value), cy))
        drawCircle(FT.Base, radius = 2.5f, center = Offset(x(b.value), cy))
    }
}

// ---- 3. CHARGE's weights as one bar, defaults beneath ----

private val DOMAIN_TINTS = mapOf(
    DomainKey.RECOVERY to FT.Domain.Cardio, DomainKey.SLEEP to FT.Domain.Sleep, DomainKey.LOAD to FT.Domain.Load,
    DomainKey.FUEL to FT.Domain.Fuel, DomainKey.BALANCE to FT.Domain.Balance,
)

fun domainTint(key: DomainKey): Color = DOMAIN_TINTS.getValue(key)

@Composable
fun WeightsBars(weights: Map<DomainKey, Double>, defaults: Map<DomainKey, Double>, personal: Boolean) {
    @Composable
    fun bar(label: String, w: Map<DomainKey, Double>) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = FTType.Label, color = FT.TextMuted, modifier = Modifier.width(64.dp))
            Canvas(Modifier.weight(1f).height(14.dp)) {
                var x = 0f
                for (k in DomainKey.entries) {
                    val width = (w.getValue(k) * size.width).toFloat()
                    drawRect(domainTint(k), Offset(x, 0f), Size(max(0f, width - 2f), size.height))
                    x += width
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (personal) bar("YOURS", weights)
        bar("DEFAULT", defaults)
        for (row in DomainKey.entries.sortedByDescending { weights.getValue(it) }.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (k in row) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(8.dp)) { drawRect(domainTint(k)) }
                        Text("${k.label} ${(weights.getValue(k) * 100).roundToInt()} %", style = FTType.Micro, color = FT.TextSecondary)
                    }
                }
            }
        }
    }
}

// ---- 4. does it earn its place: a skill chart ----

/**
 * Forecast skill against having no predictor at all (the recent average): the score, its best single input, and the
 * no-predictor line at zero. The score's bar carries the 95 % interval of its difference from the best input.
 */
@Composable
fun SkillChart(result: ScorecardResult, scoreLabel: String) {
    val base = result.climatology?.meanScore
    val composite = result.composite?.meanScore
    val best = result.best?.meanScore
    if (base == null || composite == null || best == null || result.verdict == ScorecardVerdict.NOT_YET) return
    val rows = listOf(scoreLabel to composite - base, (result.best.label) to best - base)
    val span = max(rows.maxOf { abs(it.second) }, abs(composite - base + (result.hi ?: 0.0) - (result.difference ?: 0.0))).coerceAtLeast(1e-6)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { i, (label, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = FTType.Label, color = if (i == 0) FT.TextPrimary else FT.TextSecondary, modifier = Modifier.width(96.dp), maxLines = 1)
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    val zero = size.width * 0.3f
                    val scale = (size.width - zero) / span.toFloat()
                    drawLine(FT.TextMuted, Offset(zero, 0f), Offset(zero, size.height), strokeWidth = 1.5f)
                    val w = (v * scale).toFloat()
                    val color = if (i == 0) FT.Emerald else FT.TextSecondary.copy(alpha = 0.6f)
                    if (w >= 0) drawRect(color, Offset(zero, 2f), Size(w, size.height - 4f)) else drawRect(color, Offset(zero + w, 2f), Size(-w, size.height - 4f))
                    if (i == 0 && result.lo != null && result.hi != null) {
                        val bestX = zero + ((best - base) * scale).toFloat()
                        val lo = (bestX + (result.lo * scale).toFloat()).coerceIn(0f, size.width)
                        val hi = (bestX + (result.hi * scale).toFloat()).coerceIn(0f, size.width)
                        drawLine(FT.TextPrimary, Offset(lo, size.height / 2), Offset(hi, size.height / 2), strokeWidth = 2f)
                        drawLine(FT.TextPrimary, Offset(lo, 2f), Offset(lo, size.height - 2f), strokeWidth = 2f)
                        drawLine(FT.TextPrimary, Offset(hi, 2f), Offset(hi, size.height - 2f), strokeWidth = 2f)
                    }
                }
            }
        }
        Row {
            Text("", modifier = Modifier.width(104.dp))
            Text("│ no predictor   → better forecast", style = FTType.Micro, color = FT.TextMuted)
        }
    }
}

// ---- 7. a domain's groups as small rings ----

@Composable
fun GroupRings(groups: List<Pair<String, Double?>>, palette: GaugePalette = GaugePalette.Charge) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((label, score) in groups) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ScoreRing(score = score, trend = null, size = 52.dp, palette = palette, description = label)
                Text(label.uppercase(), style = FTType.Micro, color = FT.TextSecondary)
            }
        }
    }
}

// ---- 8. LOAD: the TSB gauge and the TSB line ----

/** TSB on a scale with the productive band shaded; the last 7 mornings as a fading trail, today as the dot. */
@Composable
fun TsbGauge(tsb: Series, day: LocalDate) {
    val lo = -50.0
    val hi = 30.0
    val trail = (6 downTo 0).mapNotNull { i -> tsb[day.minusDays(i.toLong())] }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            fun x(v: Double) = (size.width * ((v.coerceIn(lo, hi) - lo) / (hi - lo))).toFloat()
            val cy = size.height / 2
            drawLine(FT.GlassTrack, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 8f, cap = StrokeCap.Round)
            drawRoundRect(FT.Emerald.copy(alpha = 0.3f), Offset(x(ScoreSpecs.TSB_BAND_LO), cy - 8f), Size(x(ScoreSpecs.TSB_BAND_HI) - x(ScoreSpecs.TSB_BAND_LO), 16f), CornerRadius(6f))
            drawLine(FT.TextMuted, Offset(x(0.0), cy - 12f), Offset(x(0.0), cy + 12f), strokeWidth = 1.5f)
            trail.forEachIndexed { i, v ->
                val last = i == trail.lastIndex
                drawCircle(if (last) FT.TextPrimary else FT.TextSecondary.copy(alpha = 0.25f + 0.5f * i / max(1, trail.size)), radius = if (last) 8f else 4.5f, center = Offset(x(v), cy))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("HIGH LOAD", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
            Text("IN BAND", style = FTType.Micro, color = FT.Emerald, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("LOW LOAD", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

/** TSB over the last [days] mornings as a line inside its productive band. */
@Composable
fun TsbLine(tsb: Series, day: LocalDate, days: Int = 30) {
    val points = (days - 1 downTo 0).map { i -> day.minusDays(i.toLong()).let { d -> d to tsb[d] } }
    val values = points.mapNotNull { it.second } + listOf(ScoreSpecs.TSB_BAND_LO - 10, ScoreSpecs.TSB_BAND_HI + 10)
    val lo = values.min()
    val hi = values.max()
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(30.dp).height(110.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(hi.roundToInt().toString(), style = FTType.Micro, color = FT.TextMuted)
            Text(lo.roundToInt().toString(), style = FTType.Micro, color = FT.TextMuted)
        }
        Canvas(Modifier.weight(1f).height(110.dp)) {
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            fun x(i: Int) = size.width * i / (days - 1).coerceAtLeast(1)
            drawRect(FT.Emerald.copy(alpha = 0.12f), Offset(0f, y(ScoreSpecs.TSB_BAND_HI)), Size(size.width, y(ScoreSpecs.TSB_BAND_LO) - y(ScoreSpecs.TSB_BAND_HI)))
            drawLine(FT.TextMuted.copy(alpha = 0.5f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)
            val path = Path()
            var started = false
            points.forEachIndexed { i, (_, v) ->
                if (v == null) return@forEachIndexed
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            drawPath(path, FT.TextPrimary, style = Stroke(width = 3f))
            points.lastOrNull()?.second?.let { drawCircle(FT.EmeraldHighlight, 6f, Offset(x(days - 1), y(it))) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 30.dp)) {
        Text(points.first().first.let(::dayLabel), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text("shaded = productive band", style = FTType.Micro, color = FT.Emerald, modifier = Modifier.weight(2f), textAlign = TextAlign.Center)
        Text(points.last().first.let(::dayLabel), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

// ---- 10. BALANCE: the ANS map ----

/** HRV (up) against resting HR (right), both against their own baselines; 14 mornings, today ringed. */
@Composable
fun AnsMap(points: List<AnsPoint>, size: Dp = 200.dp) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size)) {
            Canvas(Modifier.size(size)) {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val r = this.size.width / 2
                fun at(p: AnsPoint) = Offset(c.x + (p.rhrZ.coerceIn(-3.0, 3.0) / 3.0 * r * 0.9).toFloat(), c.y - (p.hrvZ.coerceIn(-3.0, 3.0) / 3.0 * r * 0.9).toFloat())
                drawRect(FT.Emerald.copy(alpha = 0.10f), Offset(0f, 0f), Size(c.x, c.y)) // HRV up, RHR down: balanced
                drawRect(FT.Critical.copy(alpha = 0.08f), Offset(c.x, c.y), Size(c.x, c.y)) // HRV down, RHR up: sympathetic
                drawRect(FT.Warning.copy(alpha = 0.08f), Offset(0f, c.y), Size(c.x, c.y)) // both down: saturated
                drawLine(FT.TextMuted, Offset(0f, c.y), Offset(this.size.width, c.y), strokeWidth = 1.5f)
                drawLine(FT.TextMuted, Offset(c.x, 0f), Offset(c.x, this.size.height), strokeWidth = 1.5f)
                points.zipWithNext().forEach { (a, b) -> drawLine(FT.GlassBorder, at(a), at(b), strokeWidth = 1.5f) }
                points.forEachIndexed { i, p ->
                    val today = i == points.lastIndex
                    drawCircle(if (today) FT.TextPrimary else FT.TextSecondary.copy(alpha = 0.3f + 0.6f * i / max(1, points.size)), radius = if (today) 8f else 5f, center = at(p))
                    if (today) drawCircle(FT.EmeraldHighlight, radius = 14f, center = at(p), style = Stroke(width = 2.5f))
                }
            }
            Text("BALANCED", style = FTType.Micro, color = FT.Emerald, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            Text("UNUSUAL", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            Text("SATURATED", style = FTType.Micro, color = FT.Warning, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
            Text("SYMPATHETIC", style = FTType.Micro, color = FT.Critical, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
    }
    Text("↑ HRV above your normal · → resting HR above your normal · 14 mornings, today ringed", style = FTType.Micro, color = FT.TextMuted,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

// ---- 11. thumbnails ----

/** A small line of the last values (no axes), the last point marked. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color = FT.Emerald) {
    Canvas(modifier.width(84.dp).height(22.dp)) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max().let { if (it - lo < 1e-9) lo + 1 else it }
        fun at(i: Int) = Offset(size.width * i / (values.size - 1), (size.height * (1 - (values[i] - lo) / (hi - lo))).toFloat().coerceIn(2f, size.height - 2f))
        val path = Path()
        values.indices.forEach { i -> if (i == 0) path.moveTo(at(i).x, at(i).y) else path.lineTo(at(i).x, at(i).y) }
        drawPath(path, color, style = Stroke(width = 2.5f))
        drawCircle(FT.TextPrimary, 3.5f, at(values.lastIndex))
    }
}

// ---- 12. a metric's 60 days ----

/**
 * Sixty mornings of one input: the days joined by a line, the 7-day mean on top, the normal band shaded and labelled
 * at its edges on the left axis, date ticks under it, today marked.
 */
@Composable
fun MetricChart(series: Series, day: LocalDate, key: String, band: Pair<Double, Double>?, bandLabel: String = "your normal") {
    val from = day.minusDays(59)
    val points = (0..59).mapNotNull { i -> from.plusDays(i.toLong()).let { d -> series[d]?.let { i to it } } }
    if (points.isEmpty()) {
        Text("No readings in the last 60 days.", style = FTType.BodySmall, color = FT.TextMuted)
        return
    }
    val values = points.map { it.second } + listOfNotNull(band?.first, band?.second)
    val lo = values.min()
    val hi = values.max().let { if (it - lo < 1e-9) lo + 1 else it }
    val mean7 = (0..59).mapNotNull { i ->
        val window = (0..6).mapNotNull { k -> series[from.plusDays((i - k).toLong())] }
        if (window.size >= 4 && series[from.plusDays(i.toLong())] != null) i to window.average() else null
    }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(52.dp).height(140.dp)) {
            fun frac(v: Double) = (1 - (v - lo) / (hi - lo)).toFloat()
            val labels = listOfNotNull(hi, band?.second, band?.first, lo).distinct()
            for (v in labels) {
                val isBand = band != null && (v == band.first || v == band.second)
                Text(formatInputValue(key, v), style = FTType.Micro, color = if (isBand) FT.Emerald else FT.TextMuted,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = (128 * frac(v)).dp.coerceAtLeast(0.dp)))
            }
        }
        Canvas(Modifier.weight(1f).height(140.dp)) {
            fun x(i: Int) = size.width * i / 59f
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat().coerceIn(4f, size.height - 4f)
            band?.let { (a, b) -> drawRect(FT.Emerald.copy(alpha = 0.14f), Offset(0f, y(b)), Size(size.width, y(a) - y(b))) }
            for (week in 0..8) drawLine(FT.GlassTrack, Offset(x(59 - week * 7), 0f), Offset(x(59 - week * 7), size.height), strokeWidth = 1f)
            val path = Path()
            points.forEachIndexed { k, (i, v) -> if (k == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, FT.TextSecondary.copy(alpha = 0.5f), style = Stroke(width = 1.5f))
            val mean = Path()
            mean7.forEachIndexed { k, (i, v) -> if (k == 0) mean.moveTo(x(i), y(v)) else mean.lineTo(x(i), y(v)) }
            drawPath(mean, FT.Emerald, style = Stroke(width = 3f))
            points.forEach { (i, v) -> drawCircle(if (i == 59) FT.EmeraldHighlight else FT.TextSecondary, radius = if (i == 59) 7f else 3f, center = Offset(x(i), y(v))) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 52.dp)) {
        Text(dayLabel(from), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text("line = 7-day mean · shaded = $bandLabel", style = FTType.Micro, color = FT.Emerald, modifier = Modifier.weight(3f), textAlign = TextAlign.Center)
        Text(dayLabel(day), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}
