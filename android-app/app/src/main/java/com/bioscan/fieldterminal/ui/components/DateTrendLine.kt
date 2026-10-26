package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// DAV-76. Shares DotPlot.kt's own Canvas + bounding-box + calendar-date
// xFor/yFor technique, but connects points with a line -- unlike bloodwork's
// ~1 draw/year (where a line would falsely imply continuity DotPlot's own
// header comment explains), a daily weight EMA or daily nutrition series is
// genuinely continuous enough for one. An optional reference band (e.g. the
// AMDR protein range) reuses DotPlot's exact band-drawing rectangle.
//
// Analysis review phase C (contract "Chart rules", ChartRules.kt): every chart drawn here has a y-axis with at least
// three labelled values; a caller can fix the scale to the person's usual range (`scale`), break a daily series at
// missing days (`TrendSeries.maxGapDays`, the gap is marked on the axis), draw a per-day expected band (`band`), ring
// flagged points (`marks`) and date illness, device changes and the like (`annotations`).
private val TREND_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val INSPECT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

// `label` names the series in the tap-to-inspect box; null = value only.
// `highlightLast` draws a permanent state-colored dot on the series' latest
// point (BodyConsole.kt's "engine pin" two-circle motif, at chart scale) so
// the most recent reading reads at a glance, without needing to tap first.
// `dashed` (UX review P2): a second series in the same hue is told apart by pattern as well as lightness, so the
// difference never rests on colour alone.
data class TrendSeries(
    val points: List<Pair<LocalDate, Double>>,
    val color: Color,
    val label: String? = null,
    val highlightLast: Color? = null,
    val dashed: Boolean = false,
    // Chart rule: a daily series breaks its line where more than this many days in a row are missing, and the gap is
    // marked on the axis. Null keeps a session-based series (a run every few days) connected.
    val maxGapDays: Int? = null,
    // Readings as dots, no line: the expected-band chart, where each morning is judged against its own band.
    val dotsOnly: Boolean = false,
)

// A per-day range behind the series: the expected band (80%) with its centre.
data class TrendBandDay(val date: LocalDate, val low: Double, val high: Double, val center: Double? = null)

// A ringed point (a flagged morning). The ring is the signal, so it never rests on colour alone.
data class TrendMark(val date: LocalDate, val value: Double, val color: Color)

// A dated note: a dashed line for one day (a new device), a shaded span for several (a logged illness).
data class TrendAnnotation(val start: LocalDate, val end: LocalDate = start, val label: String)

private fun defaultTrendFormat(v: Double): String = if (kotlin.math.abs(v) >= 100) "%.0f".format(v) else "%.1f".format(v)

@Composable
fun DateTrendLine(
    points: List<Pair<LocalDate, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
    refLow: Double? = null,
    refHigh: Double? = null,
    highlightColor: Color? = null,
    valueFormat: (Double) -> String = ::defaultTrendFormat,
    unit: String? = null,
    scale: ClosedFloatingPointRange<Double>? = null,
    maxGapDays: Int? = null,
) = DateTrendLine(
    series = listOf(TrendSeries(points, color, highlightLast = highlightColor, maxGapDays = maxGapDays)),
    refLow = refLow,
    refHigh = refHigh,
    modifier = modifier,
    valueFormat = valueFormat,
    unit = unit,
    scale = scale,
)

// DAV-80: multi-series overload -- raw/7-day-avg/28-day-avg VO2max all share
// one y-axis (same unit, just different smoothing), unlike weight vs.
// nutrition's genuinely different units, which stay on separate stacked
// charts instead of one dual-axis plot. All series share one bounding box
// so they read as directly comparable, not independently scaled.
//
// 25/9 rework: tap or horizontally drag to inspect -- shows the date and each
// series' value in an overlaid box (tap the same point again to dismiss).
@Composable
fun DateTrendLine(
    series: List<TrendSeries>,
    modifier: Modifier = Modifier,
    refLow: Double? = null,
    refHigh: Double? = null,
    valueFormat: (Double) -> String = ::defaultTrendFormat,
    // Chart frame: the unit every value on this chart is in, shown as a header
    // so a trend is never an unlabeled squiggle (contract section 10).
    unit: String? = null,
    // A key under the chart (line sample + label per labelled series), for charts whose series a reader must tell apart.
    legend: Boolean = false,
    // The person's usual range for this metric (personalScale): the axis stays put from day to day and widens only when
    // the data leave it, so ordinary variation does not fill the chart.
    scale: ClosedFloatingPointRange<Double>? = null,
    band: List<TrendBandDay> = emptyList(),
    marks: List<TrendMark> = emptyList(),
    annotations: List<TrendAnnotation> = emptyList(),
    // Legend words for the band and the marks (shown when `legend` is on).
    bandLabel: String? = null,
    markLabel: String? = null,
    chartHeight: Dp = 90.dp,
    // Running page: two stacked panels (cadence over stride) share one date span and one label gutter, so a date sits
    // at the same x in both.
    dateRange: ClosedRange<LocalDate>? = null,
    minGutter: Dp = 0.dp,
) {
    val sortedSeries = series
        .map { it.copy(points = it.points.sortedBy { p -> p.first }) }
        .filter { it.points.size >= if (it.dotsOnly || it.maxGapDays != null) 1 else 2 }
    val sortedBand = band.sortedBy { it.date }
    if (sortedSeries.isEmpty() && sortedBand.isEmpty()) return
    val allPoints = sortedSeries.flatMap { it.points }

    val padX = 12f
    val padY = 10f
    val dates = allPoints.map { it.first } + sortedBand.map { it.date } + marks.map { it.date }
    val dayMin = dateRange?.start?.let { minOf(it, dates.min()) } ?: dates.min()
    val dayMax = dateRange?.endInclusive?.let { maxOf(it, dates.max()) } ?: dates.max()
    val daySpan = ChronoUnit.DAYS.between(dayMin, dayMax).toFloat().let { if (it < 1f) 1f else it }

    val values = allPoints.map { it.second } + sortedBand.flatMap { listOf(it.low, it.high) } + marks.map { it.value }
    var yMin = values.min()
    var yMax = values.max()
    if (refLow != null) yMin = minOf(yMin, refLow)
    if (refHigh != null) yMax = maxOf(yMax, refHigh)
    if (scale != null) {
        yMin = minOf(yMin, scale.start)
        yMax = maxOf(yMax, scale.endInclusive)
    }
    if (yMax - yMin < 0.001) {
        yMin -= 1.0
        yMax += 1.0
    }
    val yPad = (yMax - yMin) * 0.075
    val yLo = yMin - yPad
    val yHi = yMax + yPad

    // Chart rule: at least three labelled values on the y-axis, in a gutter of their own.
    val measurer = rememberTextMeasurer()
    val ticks = remember(yLo, yHi) { niceTicks(yLo, yHi) }
    val tickLayouts = remember(ticks, measurer) {
        val decimals = tickDecimals(ticks)
        ticks.map { measurer.measure("%.${decimals}f".format(it), FTType.Micro.copy(color = FT.TextMuted)) }
    }
    val gutter = maxOf((tickLayouts.maxOfOrNull { it.size.width } ?: 0) + 6f, with(LocalDensity.current) { minGutter.toPx() })
    val gutterDp = with(LocalDensity.current) { gutter.toDp() }

    fun xFor(day: LocalDate, width: Float): Float =
        gutter + padX + ChronoUnit.DAYS.between(dayMin, day).toFloat() / daySpan * (width - gutter - padX * 2)
    fun yFor(value: Double, height: Float): Float =
        padY + (1f - ((value - yLo) / (yHi - yLo)).toFloat()) * (height - padY * 2)

    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    // Series/points change when the caller switches timeframe -- a stale
    // selection outside the new range would draw off-canvas.
    val activeDay = selectedDay?.takeIf { it in dayMin..dayMax }
    val inspectDays = remember(dates) { dates.distinct() }

    Column(modifier = modifier.fillMaxWidth()) {
        unit?.let {
            Text(it.uppercase(), style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .chartInspect(Triple(sortedSeries, sortedBand, gutter)) { x, width, isTap ->
                    val nearest = inspectDays.minBy { kotlin.math.abs(xFor(it, width) - x) }
                    selectedDay = if (isTap && selectedDay == nearest) null else nearest
                },
        ) {
            val plotLeft = gutter
            // Grid and y labels.
            ticks.forEachIndexed { i, t ->
                val y = yFor(t, size.height)
                drawLine(FT.GlassBorder, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = 1f)
                val layout = tickLayouts[i]
                drawText(layout, topLeft = Offset(gutter - 6f - layout.size.width, y - layout.size.height / 2f))
            }

            // Annotations: a span for several days, a dashed line for one.
            annotations.filter { !it.end.isBefore(dayMin) && !it.start.isAfter(dayMax) }.forEach { a ->
                val from = maxOf(a.start, dayMin)
                val to = minOf(a.end, dayMax)
                val x0 = xFor(from, size.width)
                val x1 = xFor(to, size.width)
                if (to.isAfter(from)) {
                    drawRect(FT.TextSecondary.copy(alpha = 0.10f), topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height))
                } else {
                    drawLine(FT.TextSecondary.copy(alpha = 0.7f), Offset(x0, 0f), Offset(x0, size.height), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                }
                val label = measurer.measure(a.label.uppercase(), FTType.Micro.copy(color = FT.TextSecondary))
                val lx = (x0 + 4f).coerceAtMost(size.width - label.size.width.toFloat())
                drawText(label, topLeft = Offset(lx, 0f))
            }

            if (refLow != null && refHigh != null) {
                drawRect(
                    color = FT.TextSecondary.copy(alpha = 0.14f),
                    topLeft = Offset(plotLeft, yFor(refHigh, size.height)),
                    size = Size(size.width - plotLeft, yFor(refLow, size.height) - yFor(refHigh, size.height)),
                )
            }

            // The expected band: one filled shape per run of consecutive days, its centre dashed.
            trendSegments(sortedBand.map { it.date to it }, maxGapDays = 1).forEach { run ->
                val days = run.map { it.second }
                if (days.size == 1) {
                    val d = days.first()
                    val x = xFor(d.date, size.width)
                    drawLine(FT.TextSecondary.copy(alpha = 0.35f), Offset(x, yFor(d.high, size.height)), Offset(x, yFor(d.low, size.height)), strokeWidth = 8f)
                } else {
                    val shape = Path().apply {
                        days.forEachIndexed { i, d -> if (i == 0) moveTo(xFor(d.date, size.width), yFor(d.high, size.height)) else lineTo(xFor(d.date, size.width), yFor(d.high, size.height)) }
                        days.asReversed().forEach { d -> lineTo(xFor(d.date, size.width), yFor(d.low, size.height)) }
                        close()
                    }
                    drawPath(shape, FT.TextSecondary.copy(alpha = 0.16f))
                    val centre = days.filter { it.center != null }
                    if (centre.size >= 2) {
                        val line = Path().apply {
                            centre.forEachIndexed { i, d -> if (i == 0) moveTo(xFor(d.date, size.width), yFor(d.center!!, size.height)) else lineTo(xFor(d.date, size.width), yFor(d.center!!, size.height)) }
                        }
                        drawPath(line, FT.TextSecondary.copy(alpha = 0.6f), style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                    }
                }
            }

            sortedSeries.forEach { s ->
                if (s.dotsOnly) {
                    s.points.forEach { (day, value) ->
                        val c = Offset(xFor(day, size.width), yFor(value, size.height))
                        drawCircle(FT.Surface, radius = 6.5f, center = c)
                        drawCircle(s.color, radius = 4.5f, center = c)
                    }
                } else {
                    val segments = trendSegments(s.points, s.maxGapDays)
                    segments.forEach { seg ->
                        if (seg.size == 1) {
                            drawCircle(s.color, radius = 3.5f, center = Offset(xFor(seg[0].first, size.width), yFor(seg[0].second, size.height)))
                        } else {
                            val line = Path().apply {
                                seg.forEachIndexed { i, (day, value) ->
                                    val x = xFor(day, size.width)
                                    val y = yFor(value, size.height)
                                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                                }
                            }
                            drawPath(
                                line,
                                color = s.color,
                                style = Stroke(
                                    width = 4f,
                                    cap = if (s.dashed) StrokeCap.Butt else StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                    pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 9f)) else null,
                                ),
                            )
                        }
                    }
                    // Missing days stay visible: a dashed stretch on the baseline under each gap.
                    segments.zipWithNext().forEach { (before, after) ->
                        val x0 = xFor(before.last().first, size.width) + 6f
                        val x1 = xFor(after.first().first, size.width) - 6f
                        if (x1 > x0) {
                            drawLine(FT.TextMuted, Offset(x0, size.height - 2f), Offset(x1, size.height - 2f), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f)))
                        }
                    }
                }

                s.highlightLast?.let { highlight ->
                    val (lastDay, lastValue) = s.points.last()
                    val center = Offset(xFor(lastDay, size.width), yFor(lastValue, size.height))
                    drawCircle(highlight.copy(alpha = 0.18f), radius = 9f, center = center)
                    drawCircle(highlight, radius = 4.5f, center = center)
                }
            }

            marks.forEach { m ->
                val c = Offset(xFor(m.date, size.width), yFor(m.value, size.height))
                drawCircle(FT.Surface, radius = 10f, center = c, style = Stroke(width = 4.5f))
                drawCircle(m.color, radius = 10f, center = c, style = Stroke(width = 2.5f))
            }

            activeDay?.let { day ->
                val hits = sortedSeries.mapNotNull { s -> s.points.firstOrNull { it.first == day }?.let { s to it.second } }
                val bandDay = sortedBand.firstOrNull { it.date == day }
                if (hits.isNotEmpty() || bandDay != null) {
                    val x = xFor(day, size.width)
                    drawInspectOverlay(
                        measurer = measurer,
                        x = x,
                        dots = hits.map { (_, v) -> Offset(x, yFor(v, size.height)) },
                        header = day.format(INSPECT_DATE_FORMAT),
                        rows = hits.map { (s, v) -> InspectRow(s.label, valueFormat(v), s.color) } +
                            listOfNotNull(bandDay?.let { InspectRow(bandLabel ?: "BAND", valueFormat(it.low) + "–" + valueFormat(it.high), FT.TextSecondary) }),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = gutterDp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dayMin.format(TREND_DATE_FORMAT), style = FTType.MonoCaption, color = FT.TextSecondary)
            Text(dayMax.format(TREND_DATE_FORMAT), style = FTType.MonoCaption, color = FT.TextSecondary)
        }
        if (legend) {
            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                sortedSeries.filter { it.label != null }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(modifier = Modifier.width(22.dp).height(8.dp)) {
                            if (s.dotsOnly) {
                                drawCircle(s.color, radius = 4.5f, center = Offset(size.width / 2f, size.height / 2f))
                            } else {
                                drawLine(
                                    color = s.color,
                                    start = Offset(0f, size.height / 2f),
                                    end = Offset(size.width, size.height / 2f),
                                    strokeWidth = 4f,
                                    pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 9f)) else null,
                                )
                            }
                        }
                        Text(s.label!!, style = FTType.MonoCaption, color = FT.TextSecondary)
                    }
                }
                if (bandLabel != null && sortedBand.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(modifier = Modifier.width(22.dp).height(10.dp)) { drawRect(FT.TextSecondary.copy(alpha = 0.3f)) }
                        Text(bandLabel, style = FTType.MonoCaption, color = FT.TextSecondary)
                    }
                }
                if (markLabel != null && marks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(modifier = Modifier.width(14.dp).height(14.dp)) {
                            drawCircle(marks.first().color, radius = size.minDimension / 2f - 1.5f, style = Stroke(width = 2.5f))
                        }
                        Text(markLabel, style = FTType.MonoCaption, color = FT.TextSecondary)
                    }
                }
            }
        }
    }
}
