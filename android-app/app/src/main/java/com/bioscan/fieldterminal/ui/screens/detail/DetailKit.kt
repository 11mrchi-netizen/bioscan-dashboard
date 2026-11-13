package com.bioscan.fieldterminal.ui.screens.detail

import com.bioscan.fieldterminal.data.catching
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
import com.bioscan.fieldterminal.ui.components.InspectRow
import com.bioscan.fieldterminal.ui.components.chartInspect
import com.bioscan.fieldterminal.ui.components.drawInspectOverlay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import com.bioscan.fieldterminal.ui.components.localTrendAt
import com.bioscan.fieldterminal.ui.components.smoothedPath
import com.bioscan.fieldterminal.ui.components.splitAtGaps
import com.bioscan.fieldterminal.ui.components.trendCurve
import com.bioscan.fieldterminal.domain.DayRange
import com.bioscan.fieldterminal.domain.MetricReading
import com.bioscan.fieldterminal.domain.readMetric
import com.bioscan.fieldterminal.domain.score.InputDirection
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.BarLayout
import com.bioscan.fieldterminal.ui.components.FTBarSpec
import com.bioscan.fieldterminal.ui.components.barAlpha
import com.bioscan.fieldterminal.ui.components.barLayout
import com.bioscan.fieldterminal.ui.components.drawBarStub
import com.bioscan.fieldterminal.ui.components.drawRangeCapsule
import com.bioscan.fieldterminal.ui.components.drawStackedBar
import com.bioscan.fieldterminal.ui.components.ftDomainWash
import com.bioscan.fieldterminal.ui.components.ftPlatinumWash
import com.bioscan.fieldterminal.ui.components.ftGlass
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

// The standard ranges (FTRange: 28D, 90D, 1Y); ALL is not offered here because these pages read a bounded window.
enum class ChartRange(val days: Int, val label: String) { M1(28, "28D"), M3(90, "90D"), Y1(365, "1Y") }

/** The chart window the page's range chips picked. */
val LocalRangeDays = compositionLocalOf { ChartRange.M3.days }

/** Regimen changes (domain/Regimen.kt) every TrendChart on the page draws as dashed lines; empty to hide them. */
val LocalRegimenMarkers = compositionLocalOf { emptyList<com.bioscan.fieldterminal.domain.RegimenMarker>() }

@Composable
fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    eyebrow: String? = null,
    ranged: Boolean = true,
    defaultRange: ChartRange = ChartRange.M3,
    // v1: the page's domain hue, drawn as the background wash (FT.Domain.*). Null keeps the plain ground.
    domain: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Kept per page across navigation (RangeMemory): the page opens again on the window it was last read in.
    var range by com.bioscan.fieldterminal.ui.components.rememberRange("detail:$title", defaultRange)
    // Regimen changes on the charts: loaded once per page (the roster history is cached), shown unless switched off.
    var showRegimen by rememberSaveable { mutableStateOf(true) }
    var markers by remember { mutableStateOf(emptyList<com.bioscan.fieldterminal.domain.RegimenMarker>()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        markers = catching { loadRegimenMarkers() }.getOrDefault(emptyList())
    }
    Column(modifier = Modifier.fillMaxSize().background(FT.Base).then(if (domain != null) Modifier.ftDomainWash(domain) else Modifier.ftPlatinumWash())) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            com.bioscan.fieldterminal.ui.components.FTIconButton(com.bioscan.fieldterminal.ui.components.FTIcons.Back, "Back", onBack)
            Text(title, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (eyebrow != null) Text(eyebrow, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(end = 12.dp))
        }
        if (ranged) {
            com.bioscan.fieldterminal.ui.components.FTChipRow(
                ChartRange.entries, range, { it.label }, { range = it },
                trailing = if (markers.isEmpty()) null else ({
                    // A toggle, not a range: set apart by a gap.
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    com.bioscan.fieldterminal.ui.components.FTChip("REGIMEN", showRegimen, { showRegimen = !showRegimen }, role = Role.Switch)
                }),
            )
        }
        CompositionLocalProvider(LocalRangeDays provides range.days, LocalRegimenMarkers provides if (showRegimen) markers else emptyList()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

// `.ft-chip`: a mono pill; the selected one is platinum with dark ink.
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) = com.bioscan.fieldterminal.ui.components.FTChip(label, selected, onClick)

/**
 * A page's analytical card: since the polish pass an FTCard (Supporting by default, [emphasis] for a page's lead card),
 * so every card has one header, one help affordance ("?", the info in the shared dialog) and one set of paddings.
 * A [trailing] note ending in "›" draws the chevron icon instead of the glyph.
 */
@Composable
fun Section(
    title: String,
    info: String? = null,
    trailing: String? = null,
    emphasis: com.bioscan.fieldterminal.ui.components.FTCardEmphasis = com.bioscan.fieldterminal.ui.components.FTCardEmphasis.Supporting,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevron = trailing?.trimEnd()?.endsWith("›") == true
    com.bioscan.fieldterminal.ui.components.FTCard(
        title = title,
        info = info,
        emphasis = emphasis,
        onClick = onClick,
        trailing = trailing?.trimEnd()?.removeSuffix("›")?.trimEnd()?.ifBlank { null },
        trailingChevron = chevron,
        content = content,
    )
}

/** A row that opens another page. */
@Composable
fun LinkRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .ftGlass(RoundedCornerShape(FT.RadiusModule))
            .ftClickable(shape = RoundedCornerShape(FT.RadiusModule), role = Role.Button, onClickLabel = title) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = FTType.RowTitle, color = FT.TextPrimary)
            if (subtitle != null) Text(subtitle, style = FTType.Caption, color = FT.TextMuted)
        }
        com.bioscan.fieldterminal.ui.components.FTChevron()
    }
}

@Composable
fun Loading(guard: LoadGuard) {
    // A page-shaped placeholder instead of a spinner (ui/components/Skeleton.kt).
    if (guard.error != null) LoadErrorCard(guard) else com.bioscan.fieldterminal.ui.components.SkeletonSections()
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
    // Says which days the comparison reads, so it is not taken for the chart's range: the normal is the 60 days before
    // the latest reading (domain/MetricReading.kt), the change the 7 days before it.
    val band = reading.normal?.let { " (${format(it.first)}–${format(it.second)} over the 60 days before)" } ?: ""
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
    color: Color = FT.Metric.WeightEma, // neutral ink when a caller names no metric hue
    betterHigher: Boolean? = null,
    target: Pair<Double, Double>? = null,
    targetLabel: String? = null,
    today: LocalDate = LocalDate.now(),
    // When set, the chart shows these series each in their own colour (with a legend) instead of the single trend line;
    // the headline still reads the main [series]. Used for body fat measured several ways (scale, tape, caliper).
    overlay: List<com.bioscan.fieldterminal.ui.components.TrendSeries>? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val reading = remember(series, today) { readMetric(series, today, log = log, minSd = minSd) }
    Section(title, info = info) {
        Headline(reading, format, unit, today, betterHigher)
        if (overlay != null) {
            com.bioscan.fieldterminal.ui.components.DateTrendLine(series = overlay, legend = true, valueFormat = format)
        } else {
            TrendChart(series, today, LocalRangeDays.current, reading.normal, format, color, target = target, targetLabel = targetLabel)
        }
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
    color: Color = FT.Metric.WeightEma,
    betterHigher: Boolean? = null,
    flag: String? = null,
    note: String? = null,
    today: LocalDate = LocalDate.now(),
) {
    var open by remember { mutableStateOf(false) }
    val reading = remember(series, today) { readMetric(series, today, log = log, minSd = minSd) }
    val spark = remember(series, today) { (29 downTo 0).map { series[today.minusDays(it.toLong())] } }
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
        com.bioscan.fieldterminal.ui.screens.today.SlotSparkline(spark, color = color)
        com.bioscan.fieldterminal.ui.components.FTChevron(tint = FT.TextMuted)
    }
    if (open) MetricHistoryOverlay(HistorySpec(label, series, unit, format, color, log, minSd, betterHigher, info), today) { open = false }
}

/** A metric as a tile: [MetricTiles] draws them two to a row, each opening its own trend full screen. */
data class TileSpec(
    val label: String,
    val series: Series,
    val unit: String,
    val format: (Double) -> String,
    val info: String? = null,
    val minSd: Double = 0.0,
    val color: Color = FT.Metric.WeightEma,
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
    // The 30 days up to the latest reading, each in its place.
    val spark = remember(t.series) { t.series.keys.maxOrNull()?.let { end -> (29 downTo 0).map { t.series[end.minusDays(it.toLong())] } }.orEmpty() }
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        modifier.background(FT.Glass, shape).ftClickable(shape = shape, role = Role.Button, onClickLabel = "Open ${t.label}") { open = true }.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(t.label.uppercase(), style = FTType.Micro, color = FT.TextMuted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reading.latest?.let(t.format) ?: "—", style = FTType.MetricMedium, color = FT.TextPrimary)
            Text(t.unit, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text("${positionGlyph(reading.position)} ${positionWords(reading.position)}", style = FTType.Caption, color = FT.TextMuted)
        com.bioscan.fieldterminal.ui.screens.today.SlotSparkline(spark, color = t.color)
    }
    if (open) MetricHistoryOverlay(HistorySpec(t.label, t.series, t.unit, t.format, t.color, minSd = t.minSd, betterHigher = t.betterHigher, info = t.info), today) { open = false }
}

/** One small number tile; with [history] it opens that metric's history page. */
data class Stat(val label: String, val value: String, val sub: String? = null, val history: HistorySpec? = null)

/** Small number tiles, two to a row (static summaries). */
@Composable
fun StatTiles(tiles: List<Triple<String, String, String?>>) = StatTileGrid(tiles.map { (l, v, s) -> Stat(l, v, s) })

/** Small number tiles, two to a row; a tile with a history is tappable (marked ›) and opens it. */
@Composable
fun StatTileGrid(tiles: List<Stat>, today: LocalDate = LocalDate.now()) {
    var open by remember { mutableStateOf<HistorySpec?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) {
                    val shape = RoundedCornerShape(FT.RadiusModule)
                    Column(
                        Modifier.weight(1f).background(FT.Glass, shape)
                            .then(if (t.history != null) Modifier.ftClickable(shape = shape, role = Role.Button, onClickLabel = "Open ${t.label}") { open = t.history } else Modifier)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            t.history?.let { com.bioscan.fieldterminal.ui.components.FTMetricDot(it.color, Modifier.padding(end = 6.dp), size = 6.dp) }
                            Text(t.label, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
                            if (t.history != null) com.bioscan.fieldterminal.ui.components.FTChevron(tint = FT.TextMuted)
                        }
                        Text(t.value, style = FTType.MetricMedium, color = FT.TextPrimary)
                        if (t.sub != null) Text(t.sub, style = FTType.Caption, color = FT.TextMuted)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
    open?.let { h -> MetricHistoryOverlay(h, today) { open = null } }
}

// ---- a metric's own history page ----

/** What a metric's history page needs: its series by day, how to print it, and its fixed metric hue. */
data class HistorySpec(
    val label: String,
    val series: Series,
    val unit: String,
    val format: (Double) -> String,
    val color: Color = FT.Metric.WeightEma,
    val log: Boolean = false,
    val minSd: Double = 0.0,
    val betterHigher: Boolean? = null,
    val info: String? = null,
)

/**
 * The page every metric opens to (rows, tiles, night tiles, "what went with it"): the latest value and where it sits,
 * its evolution over 30 D · 90 D · 1 Y · ALL with the normal band, and the numbers of the window (average, lowest,
 * highest, readings). Full screen, ‹ / ✕ back to where it was opened.
 */
@Composable
fun MetricHistoryOverlay(spec: HistorySpec, today: LocalDate = LocalDate.now(), onBack: (() -> Unit)? = null, onClose: () -> Unit) {
    val first = remember(spec.series) { spec.series.keys.minOrNull() }
    val allDays = first?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt() + 1 }?.coerceAtLeast(30) ?: 365
    var days by rememberSaveable { mutableStateOf(ChartRange.M3.days) }
    val reading = remember(spec.series, today) { readMetric(spec.series, today, log = spec.log, minSd = spec.minSd) }
    FullScreenOverlay(title = spec.label.uppercase(), onClose = onClose, onBack = onBack) {
        Headline(reading, spec.format, spec.unit, today, spec.betterHigher)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in ChartRange.entries) Chip(r.label, days == r.days) { days = r.days }
            if (allDays > ChartRange.Y1.days) Chip("ALL", days == allDays) { days = allDays }
        }
        CompositionLocalProvider(LocalRangeDays provides days) {
            Section("$days DAYS") { TrendChart(spec.series, today, days, reading.normal, spec.format, spec.color) }
        }
        val window = remember(spec.series, days, today) {
            spec.series.filterKeys { !it.isBefore(today.minusDays((days - 1).toLong())) && !it.isAfter(today) }
        }
        Section("IN THESE $days DAYS") {
            if (window.isEmpty()) EmptyLine("No readings in this range.") else {
                val lo = window.minBy { it.value }
                val hi = window.maxBy { it.value }
                StatTiles(
                    listOf(
                        Triple("AVERAGE", "${spec.format(window.values.average())} ${spec.unit}".trim(), null),
                        Triple("READINGS", "${window.size}", "of $days days"),
                        Triple("LOWEST", "${spec.format(lo.value)} ${spec.unit}".trim(), shortDate(lo.key)),
                        Triple("HIGHEST", "${spec.format(hi.value)} ${spec.unit}".trim(), shortDate(hi.key)),
                    ),
                )
            }
        }
        if (spec.info != null) Section("WHAT IS IT?") { Text(spec.info, style = FTType.BodySmall, color = FT.TextSecondary) }
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) = com.bioscan.fieldterminal.ui.components.FTLegend(items)

/** One date format for every chart and label (ChartDates): "14 Aug". */
fun shortDate(d: LocalDate): String = com.bioscan.fieldterminal.ui.components.ChartDates.short(d)

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
    color: Color = FT.Metric.WeightEma,
    target: Pair<Double, Double>? = null,
    targetLabel: String? = null,
    height: Int = 140,
    bandLabel: String = "your normal (60 days before)",
) {
    val from = day.minusDays((days - 1).toLong())
    val points = remember(series, day, days) { (0 until days).mapNotNull { i -> series[from.plusDays(i.toLong())]?.let { i to it } } }
    if (points.size < 2) {
        EmptyLine("Not enough readings in the last $days days.")
        return
    }
    // The trend: a local linear fit over about [span] days (a Gaussian of span/1.6 days), drawn through runs of readings
    // and broken where readings stop for a while (ChartPaths.kt). The readings themselves are the faint dots.
    val span = when {
        days > 400 -> 30
        days > 120 -> 14
        else -> 7
    }
    val bandwidth = span / 1.6
    val runs = remember(points, span) {
        splitAtGaps(points, { it.first.toDouble() }, maxGap = maxOf(3.0 * span, 21.0)).map { run ->
            trendCurve(run.map { it.first.toDouble() }, run.map { it.second }, bandwidth)
        }
    }
    val mean = remember(points, span) {
        val xs = points.map { it.first.toDouble() }
        val ys = points.map { it.second }
        points.map { (i, _) -> i to localTrendAt(xs, ys, i.toDouble(), bandwidth) }
    }
    val markers = LocalRegimenMarkers.current
    val inWindow = remember(markers, from, day) { markers.filter { !it.date.isBefore(from) && !it.date.isAfter(day) } }
    val values = points.map { it.second } + listOfNotNull(band?.first, band?.second, target?.first, target?.second)
    val lo = values.min()
    val hi = values.max().let { if (it - lo < 1e-9) lo + 1 else it }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(48.dp).height(height.dp)) {
            fun frac(v: Double) = (1 - (v - lo) / (hi - lo)).toFloat()
            // At least three labelled values (chart rules): the top, the bottom, the middle, and the normal band's edges.
            val labels = listOfNotNull(hi, band?.second, (hi + lo) / 2, band?.first, lo).distinct()
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
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        var pick by rememberPick(Triple(series, day, days))
        val byIndex = remember(points) { points.toMap() }
        val meanByIndex = remember(mean) { mean.toMap() }
        Canvas(Modifier.weight(1f).height(height.dp).chartInspect(Triple(points, days, lo)) { px, w, isTap ->
            val i = nearestIndex(px, w, days, points.map { it.first })
            pick = if (isTap && i == pick) null else i
        }) {
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
            val dot = if (points.size > 120) 2f else 3f
            points.forEach { (i, v) -> drawCircle(FT.TextSecondary.copy(alpha = 0.55f), radius = dot, center = Offset(x(i), y(v))) }
            fun xf(d: Double) = (size.width * d / (days - 1).coerceAtLeast(1)).toFloat()
            for (run in runs) {
                if (run.size < 2) continue
                drawPath(
                    smoothedPath(run.map { (d, v) -> Offset(xf(d), y(v)) }), color,
                    style = Stroke(width = 3.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                )
            }
            points.last().let { (i, v) -> drawCircle(FT.EmeraldHighlight, radius = 6f, center = Offset(x(i), y(v))) }
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            for (mk in inWindow) {
                val i = java.time.temporal.ChronoUnit.DAYS.between(from, mk.date).toInt()
                drawLine(FT.Warning.copy(alpha = 0.8f), Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1.5f, pathEffect = dash)
                drawCircle(FT.Warning, radius = 4f, center = Offset(x(i), 4f))
            }
            pick?.let { i ->
                val v = byIndex[i] ?: return@let
                val rows = listOfNotNull(
                    InspectRow(null, format(v), FT.TextPrimary),
                    meanByIndex[i]?.let { InspectRow("trend", format(it), color) },
                    band?.let { (a, b) -> InspectRow(bandLabel, "${format(a)}–${format(b)}", color) },
                )
                drawInspectOverlay(measurer, x(i), listOf(Offset(x(i), y(v))), shortDate(from.plusDays(i.toLong())), rows)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
        Text(shortDate(from), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        val key = listOfNotNull(
            "line = trend",
            band?.let { "shaded = $bandLabel" },
            targetLabel?.let { "dashed = $it" },
        ).joinToString(" · ")
        Text(key, style = FTType.Micro, color = color, modifier = Modifier.weight(3f), textAlign = TextAlign.Center)
        Text(shortDate(day), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    if (inWindow.isNotEmpty()) {
        // The newest changes in words; the dashed lines mark when, not that they caused anything.
        Column(Modifier.fillMaxWidth().padding(start = 48.dp, top = 2.dp)) {
            inWindow.takeLast(3).reversed().forEach { mk ->
                Text("┆ ${shortDate(mk.date)} ${mk.label}", style = FTType.Micro, color = FT.Warning, maxLines = 2)
            }
            if (inWindow.size > 3) Text("+${inWindow.size - 3} earlier regimen changes in this range", style = FTType.Micro, color = FT.TextMuted)
        }
    }
}

/** The day index (0 until [days]) under a touch at [x] of [width], snapped to the nearest index that has a value. */
internal fun nearestIndex(x: Float, width: Float, days: Int, has: List<Int>): Int? {
    if (has.isEmpty()) return null
    val raw = (x / width.coerceAtLeast(1f) * (days - 1).coerceAtLeast(1)).roundToInt()
    return has.minByOrNull { kotlin.math.abs(it - raw) }
}

/** Touch handling shared by the kit's charts: a drag moves the cursor, releasing keeps it, a tap on it clears it. */
@Composable
internal fun rememberPick(key: Any?): androidx.compose.runtime.MutableState<Int?> = remember(key) { mutableStateOf<Int?>(null) }

/** All regimen changes as chart markers, from the roster history (cached). */
internal suspend fun loadRegimenMarkers(): List<com.bioscan.fieldterminal.domain.RegimenMarker> =
    com.bioscan.fieldterminal.domain.regimenMarkers(
        com.bioscan.fieldterminal.data.SupplementsRepository(com.bioscan.fieldterminal.data.SupabaseClientProvider.client).loadRegimenChanges(),
    )

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
            Text(format((hi + lo) / 2), style = FTType.Micro, color = FT.TextMuted)
            Text(format(lo), style = FTType.Micro, color = FT.TextMuted)
        }
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        var pick by rememberPick(Triple(lines, day, days))
        val withValue = remember(lines, day, days) { (0 until days).filter { i -> lines.any { it.series[from.plusDays(i.toLong())] != null } } }
        Canvas(Modifier.weight(1f).height(height.dp).chartInspect(Triple(lines, day, days)) { px, w, isTap ->
            val i = nearestIndex(px, w, days, withValue)
            pick = if (isTap && i == pick) null else i
        }) {
            fun x(i: Int) = size.width * i / (days - 1).coerceAtLeast(1).toFloat()
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            if (zeroLine) drawLine(FT.TextMuted, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)
            for (l in lines) {
                val pts = (0 until days).mapNotNull { i -> l.series[from.plusDays(i.toLong())]?.let { Offset(x(i), y(it)) } }
                drawPath(smoothedPath(pts), l.color, style = Stroke(width = 2.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round, pathEffect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
            }
            pick?.let { i ->
                val d = from.plusDays(i.toLong())
                val here = lines.mapNotNull { l -> l.series[d]?.let { l to it } }
                drawInspectOverlay(measurer, x(i), here.map { (_, v) -> Offset(x(i), y(v)) }, shortDate(d),
                    here.map { (l, v) -> InspectRow(l.label, format(v), l.color) })
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
    // The top line names each series in its own colour, so no legend is drawn under the chart. With nothing picked it
    // reads the whole range ([rangeName]); a second tap on the picked column goes back to it.
    rangeName: String? = null,
    // The last column is still filling (this week, today): drawn faded and read as "so far", so it is not taken for a drop.
    lastPartial: Boolean = false,
) {
    if (columns.isEmpty() || columns.all { it.second.sum() <= 0.0 }) {
        EmptyLine("Nothing logged in this range.")
        return
    }
    var selected by remember(columns) { mutableStateOf<Int?>(null) }
    val shown = (selected ?: columns.lastIndex).coerceIn(0, columns.lastIndex)
    if (rangeName != null) {
        val pick = selected
        val (name, parts) = if (pick == null) rangeName to labels.indices.map { i -> columns.sumOf { it.second.getOrElse(i) { 0.0 } } } else columns[pick]
        val line = androidx.compose.ui.text.buildAnnotatedString {
            if (pick == null) {
                withStyle(androidx.compose.ui.text.SpanStyle(color = FT.TextPrimary)) { append("TOTAL ${format(parts.sum())} $unit") }
            } else {
                withStyle(androidx.compose.ui.text.SpanStyle(color = FT.TextPrimary)) { append(name.uppercase() + if (lastPartial && pick == columns.lastIndex) " (SO FAR)" else "") }
                append(" · total ${format(parts.sum())} $unit")
            }
            labels.indices.filter { parts.getOrElse(it) { 0.0 } > 0.0 }.forEach { i ->
                append("   ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = colors.getOrElse(i) { FT.TextSecondary })) {
                    append("${labels[i]} ${format(parts[i])}")
                }
            }
        }
        Text(line, style = FTType.Caption, color = FT.TextSecondary)
    } else {
        val (name, parts) = columns[shown]
        Text(
            "$name" + (if (lastPartial && shown == columns.lastIndex) " (so far)" else "") + " · " +
                labels.indices.joinToString(" · ") { "${labels[it]} ${format(parts.getOrElse(it) { 0.0 })}" } + " · total ${format(parts.sum())} $unit",
            style = FTType.Caption, color = FT.TextSecondary,
        )
    }
    val top = columns.maxOf { it.second.sum() }.coerceAtLeast(1e-9)
    Row(Modifier.fillMaxWidth()) {
        com.bioscan.fieldterminal.ui.components.BarYAxis(top, format, height.dp)
        Canvas(
            Modifier.weight(1f).height(height.dp).chartInspect(columns) { px, w, isTap ->
                val i = ((px / w) * columns.size).toInt().coerceIn(0, columns.lastIndex)
                selected = if (rangeName != null && isTap && i == selected) null else i
            },
        ) {
            val layout = barLayout(columns.size)
            val usable = size.height - FTBarSpec.TopPad.toPx()
            columns.forEachIndexed { i, (_, values) ->
                if (values.sum() <= 0.0) drawBarStub(layout.left(i), layout.barWidth)
                else drawStackedBar(values, colors, layout.left(i), layout.barWidth, { v -> (v / top).toFloat() * usable },
                    alpha = barAlpha(i, selected, projected = lastPartial && i == columns.lastIndex && selected != i),
                    cap = if (rangeName != null) i == selected else i == shown)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Text(columns.first().first, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text(unit + if (lastPartial) " · faded = so far" else "", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(2.2f), textAlign = TextAlign.Center, maxLines = 1)
        Text(columns.last().first, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    if (rangeName == null) Legend(labels.zip(colors))
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
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        var pick by rememberPick(Triple(shown, day, days))
        val byIndex = remember(shown) { shown.associate { java.time.temporal.ChronoUnit.DAYS.between(from, it.first).toInt() to it } }
        Canvas(Modifier.weight(1f).height(120.dp).chartInspect(Triple(shown, day, days)) { px, w, isTap ->
            val i = nearestIndex(px, w, days, byIndex.keys.sorted())
            pick = if (isTap && i == pick) null else i
        }) {
            fun x(d: LocalDate) = size.width * java.time.temporal.ChronoUnit.DAYS.between(from, d) / (days - 1).coerceAtLeast(1).toFloat()
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            val w = BarLayout(size.width, days, FTBarSpec.gapFor(days).toPx()).barWidth.coerceIn(3.dp.toPx(), 10.dp.toPx())
            // §1.10 range bars: a capsule from the day's low to its high; the selected (or today's) at full strength.
            for ((d, r) in shown) {
                val i = java.time.temporal.ChronoUnit.DAYS.between(from, d).toInt()
                drawRangeCapsule(color, x(d), w, y(r.high), y(r.low), alpha = if (pick == null) (if (d == day) 1f else 0.85f) else barAlpha(i, pick))
            }
            pick?.let { i ->
                val (d, r) = byIndex[i] ?: return@let
                drawInspectOverlay(measurer, x(d), listOf(Offset(x(d), y(r.high)), Offset(x(d), y(r.low))), shortDate(d),
                    listOf(InspectRow("high", "${r.high.roundToInt()} $unit", color), InspectRow("low", "${r.low.roundToInt()} $unit", color)))
            }
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
fun ShareBars(rows: List<Triple<String, Double, String>>, max: Double, color: Color = FT.Metric.WeightEma) {
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
