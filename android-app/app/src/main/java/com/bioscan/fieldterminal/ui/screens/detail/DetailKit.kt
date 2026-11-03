package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.DayRange
import com.bioscan.fieldterminal.domain.MetricReading
import com.bioscan.fieldterminal.domain.readMetric
import com.bioscan.fieldterminal.domain.score.InputDirection
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.today.FullScreenOverlay
import com.bioscan.fieldterminal.ui.screens.today.Sparkline
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// The detail pages' shared kit (Sleep, Cardio, Load, Nutrition...), in the look of the score overlays: a plain page with
// a back arrow, glass sections with a caps title, the number first and its chart under it, and every explanation one tap
// away behind the section's ⓘ (never a collapsed card). Charts share one range (30 D · 90 D · 1 Y) chosen at the top.

enum class ChartRange(val days: Int, val label: String) { M1(30, "30 D"), M3(90, "90 D"), Y1(365, "1 Y") }

/** The chart window the page's range chips picked. */
val LocalRangeDays = compositionLocalOf { ChartRange.M3.days }

@Composable
fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    eyebrow: String? = null,
    ranged: Boolean = true,
    defaultRange: ChartRange = ChartRange.M3,
    content: @Composable ColumnScope.() -> Unit,
) {
    var range by rememberSaveable { mutableStateOf(defaultRange) }
    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = FTType.SectionTitle, color = FT.TextSecondary,
                modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Back") { onBack() }.padding(12.dp))
            Text(title, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (eyebrow != null) Text(eyebrow, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(end = 12.dp))
        }
        if (ranged) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in ChartRange.entries) Chip(r.label, r == range) { range = r }
            }
        }
        CompositionLocalProvider(LocalRangeDays provides range.days) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = FTType.Label,
        color = if (selected) FT.Emerald else FT.TextSecondary,
        modifier = Modifier
            .border(FT.BorderWidth, if (selected) FT.Emerald.copy(alpha = 0.7f) else FT.GlassBorder, shape)
            .background(if (selected) FT.Emerald.copy(alpha = 0.12f) else FT.GlassFill, shape)
            .ftClickable(shape = shape, role = Role.Tab, onClickLabel = label) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A glass section: caps title, an ⓘ that opens [info] full screen, then the content. */
@Composable
fun Section(title: String, info: String? = null, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule))
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(end = 6.dp))
            if (info != null) {
                Text("ⓘ", style = FTType.RowTitle, color = FT.TextSecondary,
                    modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "What is $title") { open = true }.padding(horizontal = 6.dp))
            }
        }
        content()
    }
    if (open && info != null) InfoOverlay(title, info) { open = false }
}

@Composable
fun InfoOverlay(title: String, body: String, onClose: () -> Unit) {
    FullScreenOverlay(title = title.uppercase(), onClose = onClose) {
        Text(body, style = FTType.Body, color = FT.TextSecondary)
    }
}

/** A row that opens another page. */
@Composable
fun LinkRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule))
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .ftClickable(shape = RoundedCornerShape(FT.RadiusModule), role = Role.Button, onClickLabel = title) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = FTType.RowTitle, color = FT.TextPrimary)
            if (subtitle != null) Text(subtitle, style = FTType.Caption, color = FT.TextMuted)
        }
        Text("›", style = FTType.SectionTitle, color = FT.TextMuted)
    }
}

@Composable
fun Loading(guard: LoadGuard) {
    if (guard.error != null) LoadErrorCard(guard) else {
        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.Emerald)
        }
    }
}

@Composable
fun EmptyLine(text: String) = Text(text, style = FTType.BodySmall, color = FT.TextMuted)

// ---- the number and its words ----

fun positionGlyph(p: InputDirection?): String = when (p) {
    InputDirection.ABOVE -> "▲"
    InputDirection.BELOW -> "▼"
    InputDirection.NORMAL -> "●"
    else -> "·"
}

fun positionWords(p: InputDirection?): String = when (p) {
    InputDirection.ABOVE -> "above your normal"
    InputDirection.BELOW -> "below your normal"
    InputDirection.NORMAL -> "within your normal"
    else -> "no normal yet"
}

private fun whenWords(date: LocalDate?, today: LocalDate): String? = when (date) {
    null, today -> null
    today.minusDays(1) -> "yesterday"
    else -> "${date.dayOfMonth} ${date.month.name.take(3).lowercase()}"
}

/** The latest value large, then where it sits against the normal band and how it moved against the week before. */
@Composable
fun Headline(reading: MetricReading, format: (Double) -> String, unit: String, today: LocalDate = LocalDate.now(), betterHigher: Boolean? = null) {
    val latest = reading.latest
    if (latest == null) {
        EmptyLine("No reading in the last week.")
        return
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(format(latest), style = FTType.MetricMedium, color = FT.TextPrimary)
        Text(unit, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        whenWords(reading.latestDate, today)?.let { Text("· $it", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp)) }
    }
    val tint = when {
        reading.position == null || reading.position == InputDirection.NORMAL || betterHigher == null -> FT.TextSecondary
        (reading.position == InputDirection.ABOVE) == betterHigher -> FT.Emerald
        else -> FT.Warning
    }
    val band = reading.normal?.let { " (${format(it.first)}–${format(it.second)})" } ?: ""
    val change = reading.change?.let { c -> " · ${if (c >= 0) "+" else "−"}${format(abs(c))} vs the week before" } ?: ""
    Text("${positionGlyph(reading.position)} ${positionWords(reading.position)}$band$change", style = FTType.Caption, color = tint)
}

/**
 * A metric's own section: headline, then its line over the page's range with the normal band shaded. [target] draws a
 * fixed reference range (sleep's 7-9 h) as dashed lines.
 */
@Composable
fun MetricPanel(
    title: String,
    series: Series,
    unit: String,
    format: (Double) -> String,
    info: String? = null,
    log: Boolean = false,
    minSd: Double = 0.0,
    color: Color = FT.Emerald,
    betterHigher: Boolean? = null,
    target: Pair<Double, Double>? = null,
    targetLabel: String? = null,
    today: LocalDate = LocalDate.now(),
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val reading = remember(series, today) { readMetric(series, today, log = log, minSd = minSd) }
    Section(title, info = info) {
        Headline(reading, format, unit, today, betterHigher)
        TrendChart(series, today, LocalRangeDays.current, reading.normal, format, color, target = target, targetLabel = targetLabel)
        extra()
    }
}

/** One metric as a line: name, latest value, where it sits, a month's sparkline. Tap for its own page. */
@Composable
fun MetricRow(
    label: String,
    series: Series,
    unit: String,
    format: (Double) -> String,
    info: String? = null,
    log: Boolean = false,
    minSd: Double = 0.0,
    color: Color = FT.Emerald,
    betterHigher: Boolean? = null,
    flag: String? = null,
    note: String? = null,
    today: LocalDate = LocalDate.now(),
) {
    var open by remember { mutableStateOf(false) }
    val reading = remember(series, today) { readMetric(series, today, log = log, minSd = minSd) }
    val spark = remember(series, today) { (29 downTo 0).mapNotNull { series[today.minusDays(it.toLong())] } }
    Row(
        modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open $label") { open = true }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = FTType.RowTitle, color = FT.TextPrimary)
            Text(flag ?: note ?: positionWords(reading.position), style = FTType.Caption, color = if (flag != null) FT.Warning else FT.TextMuted)
        }
        Text(reading.latest?.let { "${format(it)} $unit".trim() } ?: "—", style = FTType.Telemetry, color = FT.TextPrimary)
        Text(positionGlyph(reading.position), style = FTType.Telemetry, color = FT.TextSecondary)
        Sparkline(spark, color = color)
        Text("›", style = FTType.RowTitle, color = FT.TextMuted)
    }
    if (open) {
        val days = LocalRangeDays.current
        FullScreenOverlay(title = label.uppercase(), onClose = { open = false }) {
            Headline(reading, format, unit, today, betterHigher)
            Section("${days} DAYS") { TrendChart(series, today, days, reading.normal, format, color) }
            if (info != null) Section("WHAT IS IT?") { Text(info, style = FTType.BodySmall, color = FT.TextSecondary) }
        }
    }
}

/** A metric as a tile: [MetricTiles] draws them two to a row, each opening its own trend full screen. */
data class TileSpec(
    val label: String,
    val series: Series,
    val unit: String,
    val format: (Double) -> String,
    val info: String? = null,
    val minSd: Double = 0.0,
    val color: Color = FT.Emerald,
    val betterHigher: Boolean? = null,
)

@Composable
fun MetricTiles(tiles: List<TileSpec>, today: LocalDate = LocalDate.now()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) MetricTile(t, today, Modifier.weight(1f))
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(t: TileSpec, today: LocalDate, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val reading = remember(t.series, today) { readMetric(t.series, today, minSd = t.minSd, maxAgeDays = 60) }
    val spark = remember(t.series) { t.series.entries.sortedBy { it.key }.takeLast(30).map { it.value } }
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        modifier.background(FT.GlassFill, shape).ftClickable(shape = shape, role = Role.Button, onClickLabel = "Open ${t.label}") { open = true }.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(t.label.uppercase(), style = FTType.Micro, color = FT.TextMuted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reading.latest?.let(t.format) ?: "—", style = FTType.MetricMedium, color = FT.TextPrimary)
            Text(t.unit, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text("${positionGlyph(reading.position)} ${positionWords(reading.position)}", style = FTType.Caption, color = FT.TextMuted)
        Sparkline(spark, color = t.color)
    }
    if (open) {
        val days = LocalRangeDays.current
        FullScreenOverlay(title = t.label.uppercase(), onClose = { open = false }) {
            Headline(reading, t.format, t.unit, today, t.betterHigher)
            Section("$days DAYS") { TrendChart(t.series, today, days, reading.normal, t.format, t.color) }
            if (t.info != null) Section("WHAT IS IT?") { Text(t.info, style = FTType.BodySmall, color = FT.TextSecondary) }
        }
    }
}

/** Small number tiles, two to a row. */
@Composable
fun StatTiles(tiles: List<Triple<String, String, String?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value, sub) in row) {
                    Column(
                        Modifier.weight(1f).background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(label, style = FTType.Micro, color = FT.TextMuted)
                        Text(value, style = FTType.MetricMedium, color = FT.TextPrimary)
                        if (sub != null) Text(sub, style = FTType.Caption, color = FT.TextMuted)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((label, color) in items) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(9.dp).background(color, CircleShape))
                Text(label, style = FTType.Caption, color = FT.TextSecondary)
            }
        }
    }
}

fun shortDate(d: LocalDate): String = "${d.dayOfMonth} ${d.month.name.take(3)}"

// ---- charts ----

/**
 * A daily line over [days] ending on [day]: faint daily points, the 7-day (14-day past 120 days) mean bold, the normal
 * band shaded, today marked. Axis labels at the band's edges and the extremes.
 */
@Composable
fun TrendChart(
    series: Series,
    day: LocalDate,
    days: Int,
    band: Pair<Double, Double>?,
    format: (Double) -> String,
    color: Color = FT.Emerald,
    target: Pair<Double, Double>? = null,
    targetLabel: String? = null,
    height: Int = 140,
    bandLabel: String = "your normal",
) {
    val from = day.minusDays((days - 1).toLong())
    val points = remember(series, day, days) { (0 until days).mapNotNull { i -> series[from.plusDays(i.toLong())]?.let { i to it } } }
    if (points.size < 2) {
        EmptyLine("Not enough readings in the last $days days.")
        return
    }
    val span = if (days > 120) 14 else 7
    val mean = remember(points, span) {
        val byIndex = points.toMap()
        points.mapNotNull { (i, _) ->
            val w = (0 until span).mapNotNull { byIndex[i - it] }
            if (w.size >= span / 2) i to w.average() else null
        }
    }
    val values = points.map { it.second } + listOfNotNull(band?.first, band?.second, target?.first, target?.second)
    val lo = values.min()
    val hi = values.max().let { if (it - lo < 1e-9) lo + 1 else it }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(48.dp).height(height.dp)) {
            fun frac(v: Double) = (1 - (v - lo) / (hi - lo)).toFloat()
            val labels = listOfNotNull(hi, band?.second, band?.first, lo).distinct()
            var lastTop = -100f
            for (v in labels.sortedByDescending { it }) {
                val top = (height - 12) * frac(v)
                if (top - lastTop < 11f) continue
                lastTop = top
                val isBand = band != null && (v == band.first || v == band.second)
                Text(format(v), style = FTType.Micro, color = if (isBand) color else FT.TextMuted,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = top.dp.coerceAtLeast(0.dp)))
            }
        }
        Canvas(Modifier.weight(1f).height(height.dp)) {
            fun x(i: Int) = size.width * i / (days - 1).coerceAtLeast(1).toFloat()
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat().coerceIn(3f, size.height - 3f)
            band?.let { (a, b) -> drawRect(color.copy(alpha = 0.14f), Offset(0f, y(b)), Size(size.width, y(a) - y(b))) }
            target?.let { (a, b) ->
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                for (v in listOf(a, b)) drawLine(FT.TextSecondary.copy(alpha = 0.6f), Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1.5f, pathEffect = dash)
            }
            val step = if (days > 120) 30 else 7
            var g = days - 1
            while (g >= 0) { drawLine(FT.GlassTrack, Offset(x(g), 0f), Offset(x(g), size.height), strokeWidth = 1f); g -= step }
            val raw = Path()
            points.forEachIndexed { k, (i, v) -> if (k == 0) raw.moveTo(x(i), y(v)) else raw.lineTo(x(i), y(v)) }
            drawPath(raw, FT.TextSecondary.copy(alpha = 0.4f), style = Stroke(width = 1.2f))
            val m = Path()
            mean.forEachIndexed { k, (i, v) -> if (k == 0) m.moveTo(x(i), y(v)) else m.lineTo(x(i), y(v)) }
            drawPath(m, color, style = Stroke(width = 3f))
            if (days <= 90) points.forEach { (i, v) -> drawCircle(FT.TextSecondary, radius = 2.5f, center = Offset(x(i), y(v))) }
            points.last().let { (i, v) -> drawCircle(FT.EmeraldHighlight, radius = 6f, center = Offset(x(i), y(v))) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
        Text(shortDate(from), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        val key = listOfNotNull(
            "line = ${span}-day mean",
            band?.let { "shaded = $bandLabel" },
            targetLabel?.let { "dashed = $it" },
        ).joinToString(" · ")
        Text(key, style = FTType.Micro, color = color, modifier = Modifier.weight(3f), textAlign = TextAlign.Center)
        Text(shortDate(day), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/** Several lines on one axis (fitness and fatigue), told apart by colour and dash, labelled in a legend. */
data class ChartLine(val label: String, val series: Series, val color: Color, val dashed: Boolean = false)

@Composable
fun MultiLineChart(lines: List<ChartLine>, day: LocalDate, days: Int, format: (Double) -> String, zeroLine: Boolean = false, height: Int = 140) {
    val from = day.minusDays((days - 1).toLong())
    val all = lines.flatMap { l -> (0 until days).mapNotNull { l.series[from.plusDays(it.toLong())] } } + if (zeroLine) listOf(0.0) else emptyList()
    if (all.size < 2) {
        EmptyLine("Not enough history in the last $days days.")
        return
    }
    val lo = all.min()
    val hi = all.max().let { if (it - lo < 1e-9) lo + 1 else it }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(40.dp).height(height.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(format(hi), style = FTType.Micro, color = FT.TextMuted)
            Text(format(lo), style = FTType.Micro, color = FT.TextMuted)
        }
        Canvas(Modifier.weight(1f).height(height.dp)) {
            fun x(i: Int) = size.width * i / (days - 1).coerceAtLeast(1).toFloat()
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            if (zeroLine) drawLine(FT.TextMuted, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)
            for (l in lines) {
                val p = Path()
                var started = false
                for (i in 0 until days) {
                    val v = l.series[from.plusDays(i.toLong())] ?: continue
                    if (!started) { p.moveTo(x(i), y(v)); started = true } else p.lineTo(x(i), y(v))
                }
                drawPath(p, l.color, style = Stroke(width = 2.5f, pathEffect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Text(shortDate(from), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text(shortDate(day), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    Legend(lines.map { it.label to it.color })
}

/**
 * Columns split into stacked parts (sleep stages, macros, weekly counts). Tap a column to read it in the line above.
 * [columns] are oldest first.
 */
@Composable
fun StackedBars(
    columns: List<Pair<String, List<Double>>>,
    colors: List<Color>,
    labels: List<String>,
    format: (Double) -> String,
    unit: String,
    height: Int = 120,
) {
    if (columns.isEmpty() || columns.all { it.second.sum() <= 0.0 }) {
        EmptyLine("Nothing logged in this range.")
        return
    }
    var selected by remember(columns) { mutableStateOf(columns.lastIndex) }
    val (name, parts) = columns[selected.coerceIn(0, columns.lastIndex)]
    Text(
        "$name · " + labels.indices.joinToString(" · ") { "${labels[it]} ${format(parts.getOrElse(it) { 0.0 })}" } + " · total ${format(parts.sum())} $unit",
        style = FTType.Caption, color = FT.TextSecondary,
    )
    val top = columns.maxOf { it.second.sum() }.coerceAtLeast(1e-9)
    Canvas(
        Modifier.fillMaxWidth().height(height.dp).pointerInput(columns) {
            detectTapGestures { o -> selected = ((o.x / size.width) * columns.size).toInt().coerceIn(0, columns.lastIndex) }
        },
    ) {
        val slot = size.width / columns.size
        val w = (slot * 0.7f).coerceIn(2f, 28.dp.toPx())
        columns.forEachIndexed { i, (_, values) ->
            var y = size.height
            val alpha = if (i == selected) 1f else 0.6f
            values.forEachIndexed { k, v ->
                val h = (v / top).toFloat() * size.height
                if (h > 0.5f) {
                    drawRoundRect(colors[k % colors.size].copy(alpha = alpha), Offset(i * slot + (slot - w) / 2, y - h), Size(w, max(1f, h - 1.5f)), CornerRadius(2f))
                    y -= h
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(columns.first().first, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text("0–${format(top)} $unit", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(columns.last().first, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    Legend(labels.zip(colors))
}

/** Each day's low-to-high span as a vertical bar (heart rate). */
@Composable
fun RangeBars(ranges: List<Pair<LocalDate, DayRange>>, day: LocalDate, days: Int, unit: String, color: Color = FT.DomainHeart) {
    val from = day.minusDays((days - 1).toLong())
    val shown = ranges.filter { !it.first.isBefore(from) && !it.first.isAfter(day) }
    if (shown.isEmpty()) {
        EmptyLine("No heart-rate range in the last $days days.")
        return
    }
    val lo = shown.minOf { it.second.low }
    val hi = shown.maxOf { it.second.high }.let { if (it - lo < 1e-9) lo + 1 else it }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(36.dp).height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(hi.roundToInt().toString(), style = FTType.Micro, color = FT.TextMuted)
            Text(lo.roundToInt().toString(), style = FTType.Micro, color = FT.TextMuted)
        }
        Canvas(Modifier.weight(1f).height(120.dp)) {
            fun x(d: LocalDate) = size.width * java.time.temporal.ChronoUnit.DAYS.between(from, d) / (days - 1).coerceAtLeast(1).toFloat()
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            val w = (size.width / days * 0.6f).coerceIn(1.5f, 10f)
            for ((d, r) in shown) drawRoundRect(color.copy(alpha = if (d == day) 1f else 0.6f), Offset(x(d) - w / 2, y(r.high)), Size(w, max(2f, y(r.low) - y(r.high))), CornerRadius(w / 2))
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
        Text(shortDate(from), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text(unit, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(shortDate(day), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/** Horizontal share bars, one per labelled part, each with its value at the end (component scores, distributions). */
@Composable
fun ShareBars(rows: List<Triple<String, Double, String>>, max: Double, color: Color = FT.Emerald) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, value, text) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.width(110.dp))
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    drawRoundRect(FT.GlassTrack, Offset.Zero, size, CornerRadius(5f))
                    val f = (value / max.coerceAtLeast(1e-9)).toFloat().coerceIn(0f, 1f)
                    drawRoundRect(color, Offset.Zero, Size(size.width * f, size.height), CornerRadius(5f))
                }
                Text(text, style = FTType.Telemetry, color = FT.TextPrimary, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
            }
        }
    }
}

/** Formatting helpers shared by the pages. */
object Fmt {
    val int: (Double) -> String = { it.roundToInt().toString() }
    val one: (Double) -> String = { "%.1f".format(it) }
    val hm: (Double) -> String = { h -> val m = (h * 60).roundToInt(); "${m / 60}h${"%02d".format(m % 60)}" }
    val kSteps: (Double) -> String = { if (it >= 1000) "%.1fk".format(it / 1000) else it.roundToInt().toString() }
    val signed: (Double) -> String = { (if (it >= 0) "+" else "") + it.roundToInt() }
}
