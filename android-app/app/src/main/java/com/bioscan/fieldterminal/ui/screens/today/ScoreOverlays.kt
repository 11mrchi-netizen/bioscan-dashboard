package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bioscan.fieldterminal.data.ScoresView
import com.bioscan.fieldterminal.domain.score.AnsState
import com.bioscan.fieldterminal.domain.score.ChargeStatus
import com.bioscan.fieldterminal.domain.score.DomainKey
import com.bioscan.fieldterminal.domain.score.DomainScore
import com.bioscan.fieldterminal.domain.score.InputScore
import com.bioscan.fieldterminal.domain.score.LoadDirection
import com.bioscan.fieldterminal.domain.score.ScoreScale
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.domain.score.baselineOf
import com.bioscan.fieldterminal.domain.score.chargeRankLine
import com.bioscan.fieldterminal.domain.score.directionGlyph
import com.bioscan.fieldterminal.domain.score.directionWords
import com.bioscan.fieldterminal.domain.score.domainSentence
import com.bioscan.fieldterminal.domain.score.driverSummary
import com.bioscan.fieldterminal.domain.score.formatInputValue
import com.bioscan.fieldterminal.domain.score.inputPoints
import com.bioscan.fieldterminal.domain.score.referenceLine
import com.bioscan.fieldterminal.domain.score.trendOf
import com.bioscan.fieldterminal.domain.score.weekday
import com.bioscan.fieldterminal.domain.score.whatIs
import com.bioscan.fieldterminal.domain.score.whatIsInput
import com.bioscan.fieldterminal.ui.components.RingState
import com.bioscan.fieldterminal.ui.components.ScoreRing
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

// Scoring system (docs/scoring-system/02-page-mockups.md §0, §2-§4): the full-screen overlays a tap opens. Level 2 is
// CHARGE or a domain, everything expanded; level 3 is one metric. ‹ goes back a level, ✕ closes them all.

sealed interface ScoreOverlay {
    data object Charge : ScoreOverlay
    data class Domain(val key: DomainKey) : ScoreOverlay
    data class Input(val key: DomainKey, val inputKey: String) : ScoreOverlay
    data class Picture(val picture: FullPicture) : ScoreOverlay
}


@Composable
fun FullScreenOverlay(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onBack ?: onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(modifier = Modifier.fillMaxSize().background(FT.Base).statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Text("‹", style = FTType.SectionTitle, color = FT.TextSecondary,
                        modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Back") { onBack() }.padding(12.dp))
                }
                Text(title, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (trailing != null) Text(trailing, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(end = 8.dp))
                Text("✕", style = FTType.RowTitle, color = FT.TextSecondary,
                    modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Close") { onClose() }.padding(12.dp))
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
fun OverlaySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule))
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = FTType.LabelCaps, color = FT.TextMuted)
        content()
    }
}

private fun dayLabel(day: LocalDate): String = "${weekday(day).take(3).uppercase()} ${day.dayOfMonth} ${day.month.name.take(3)}"

@Composable
private fun RingHeader(score: Double?, change: Double?, state: RingState, capped: Boolean, words: String, sentence: String?, description: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScoreRing(score = score, trend = trendOf(change), size = 168.dp, state = state, capped = capped, description = description)
        Text(words, style = FTType.LabelCaps, color = FT.TextSecondary)
        if (sentence != null) Text(sentence, style = FTType.Body, color = FT.TextPrimary, textAlign = TextAlign.Center)
    }
}

// ---- level 2: CHARGE ----

@Composable
fun ChargeOverlay(view: ScoresView, onOpen: (ScoreOverlay) -> Unit, onClose: () -> Unit) {
    val charge = view.scores.charge
    FullScreenOverlay(title = "CHARGE", trailing = dayLabel(view.day), onClose = onClose) {
        val status = if (view.status == ChargeStatus.FINAL) "FINAL ✓" else "PROVISIONAL"
        val words = listOfNotNull(charge.band?.label, status).joinToString(" · ")
        val sentence = listOfNotNull(
            "How you should feel after ${weekday(view.day.minusDays(1))}.",
            chargeRankLine(charge),
        ).joinToString(" ")
        RingHeader(
            charge.score, charge.change,
            if (view.status == ChargeStatus.PROVISIONAL) RingState.PROVISIONAL else if (charge.score == null) RingState.PARTIAL else RingState.SCORED,
            charge.cap != null, words, sentence, "Charge",
        )
        if (view.status == ChargeStatus.PROVISIONAL) {
            Text("Final when you close ${weekday(view.day.minusDays(1))}, or at 12:00.", style = FTType.BodySmall, color = FT.TextSecondary)
        }
        driverSummary(charge)?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }

        OverlaySection("WHAT MOVED IT · vs your typical day (80)") {
            val max = charge.drivers.maxOfOrNull { abs(it.points) }?.coerceAtLeast(5.0) ?: 5.0
            if (charge.drivers.isEmpty()) Text("Not scored yet.", style = FTType.BodySmall, color = FT.TextMuted)
            for (d in charge.drivers) {
                DriverBar(label = d.key.label, points = d.points, max = max, onClick = { onOpen(ScoreOverlay.Domain(d.key)) })
            }
            val left = DomainKey.entries.filter { it !in charge.domainsUsed }
            if (left.isNotEmpty()) Text("Not in today's CHARGE: ${left.joinToString { it.label }} (not enough data)", style = FTType.Caption, color = FT.TextMuted)
        }

        OverlaySection("LAST 30 DAYS") { ScoreStrip(view.chargeStrip) }

        OverlaySection("DETAILS") {
            DetailLine("CAPS", charge.cap?.let { "⚠ ${it.reason} · capped at ${it.ceiling.roundToInt()}" } ?: "none")
            DetailLine("BASED ON", "${charge.domainsUsed.size} of ${DomainKey.entries.size} domains")
            DetailLine("WEIGHTS", weightsLine(view.weights))
            if (charge.calibrating) DetailLine("CALIBRATING", "fewer than 28 earlier mornings to rank against: shown unranked")
        }
        OverlaySection("DOES IT EARN ITS PLACE?") {
            ScorecardLine(view.quality?.charge)
            view.quality?.chargePlanDone?.let { ScorecardLine(it) }
            Text(view.weights.sentence, style = FTType.Caption, color = FT.TextMuted)
        }
        OverlaySection("WHAT IS CHARGE?") { Text(whatIs(null), style = FTType.BodySmall, color = FT.TextSecondary) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = FTType.Label, color = FT.TextMuted, modifier = Modifier.width(96.dp))
        Text(value, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DriverBar(label: String, points: Double, max: Double, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open $label") { onClick() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = FTType.Label, color = FT.TextPrimary, modifier = Modifier.width(80.dp))
        Canvas(modifier = Modifier.weight(1f).height(10.dp)) {
            val mid = size.width / 2
            drawLine(FT.GlassTrack, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 2f)
            val w = (abs(points) / max).toFloat().coerceAtMost(1f) * mid
            val color = if (points >= 0) FT.Emerald else FT.Critical
            if (points >= 0) drawRect(color.copy(alpha = 0.7f), Offset(mid, 0f), Size(w, size.height))
            else drawRect(color.copy(alpha = 0.7f), Offset(mid - w, 0f), Size(w, size.height))
        }
        Text("${if (points >= 0) "+" else ""}${points.roundToInt()}", style = FTType.Telemetry, color = FT.TextPrimary,
            modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
        Text("›", style = FTType.Telemetry, color = FT.TextMuted)
    }
}

/** A 30-day strip of a score: one bar per morning on the 0-150 scale, with the top of normal marked. */
@Composable
fun ScoreStrip(points: List<Pair<LocalDate, Double?>>, cap: Double = ScoreScale.MAX) {
    Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        val n = points.size.coerceAtLeast(1)
        val slot = size.width / n
        fun y(v: Double) = size.height * (1f - (v / cap).toFloat())
        drawLine(FT.TextMuted, Offset(0f, y(100.0)), Offset(size.width, y(100.0)), strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        drawLine(FT.GlassTrack, Offset(0f, y(60.0)), Offset(size.width, y(60.0)), strokeWidth = 1f)
        points.forEachIndexed { i, (_, v) ->
            if (v == null) return@forEachIndexed
            val color = when {
                v >= 100 -> FT.EmeraldHighlight
                v >= 60 -> FT.Emerald
                v >= 40 -> FT.Warning
                else -> FT.Critical
            }
            val top = y(v)
            drawRect(color.copy(alpha = if (i == points.lastIndex) 1f else 0.6f), Offset(i * slot + slot * 0.15f, top), Size(slot * 0.7f, size.height - top))
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(points.firstOrNull()?.first?.let(::dayLabel) ?: "", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text(points.lastOrNull()?.first?.let(::dayLabel) ?: "", style = FTType.Micro, color = FT.TextMuted)
    }
}

// ---- level 2: a domain ----

@Composable
fun DomainOverlay(
    view: ScoresView,
    key: DomainKey,
    onOpen: (ScoreOverlay) -> Unit,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    onOpenDetail: (DetailPage) -> Unit = {},
) {
    val domain = view.scores.domains.getValue(key)
    val about = if (key == DomainKey.SLEEP || key == DomainKey.RECOVERY) "${weekday(view.day.minusDays(1)).uppercase().take(3)} NIGHT"
    else weekday(view.day.minusDays(1)).uppercase()
    FullScreenOverlay(title = key.label, trailing = about, onClose = onClose, onBack = onBack) {
        val words = listOfNotNull(
            domain.band?.label ?: "PARTIAL",
            if (key == DomainKey.LOAD) view.scores.loadDirection?.label else null,
            if (key == DomainKey.BALANCE) view.scores.ansState?.let { "◉ ${it.label}" } else null,
            if (domain.calibrating) "CALIBRATING" else null,
        ).joinToString(" · ")
        RingHeader(domain.score, domain.change, if (domain.partial) RingState.PARTIAL else RingState.SCORED, false, words, domainSentence(domain), key.label)
        if (key == DomainKey.LOAD) view.scores.loadDirection?.let { LoadDirectionStrip(it) }
        if (key == DomainKey.BALANCE && view.scores.ansState == AnsState.SATURATED) {
            Text("SATURATED: HRV is high while the pattern looks like overreaching, so RECOVERY gets no points above 100 this morning.",
                style = FTType.Caption, color = FT.Warning)
        }
        val groups = domain.raw.groups
        if (groups.size > 1) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (g in groups) Text("${g.group.label.uppercase()} ${g.score?.roundToInt() ?: "—"}", style = FTType.Telemetry, color = FT.TextSecondary)
            }
        }
        val points = inputPoints(domain.raw)
        for (g in groups) {
            OverlaySection(if (groups.size > 1) "WHAT MOVED IT · ${g.group.label.uppercase()}" else "WHAT MOVED IT") {
                val scored = g.inputs.sortedByDescending { abs(points[it.spec.key] ?: 0.0) }
                for (input in scored) InputTile(input, points[input.spec.key]) { onOpen(ScoreOverlay.Input(key, input.spec.key)) }
                val missing = g.group.inputs.filter { spec -> scored.none { it.spec.key == spec.key } }
                if (missing.isNotEmpty()) Text("No data: ${missing.joinToString { it.label.lowercase() }}", style = FTType.Caption, color = FT.TextMuted)
            }
        }
        val pictures = picturesFor(key)
        if (pictures.isNotEmpty()) {
            OverlaySection("THE FULL PICTURE") {
                for (picture in pictures) {
                    PictureTile(picture.title, view.pictureHeadline(picture)) {
                        val detail = picture.detail
                        if (detail != null) onOpenDetail(detail) else onOpen(ScoreOverlay.Picture(picture))
                    }
                }
            }
        }
        OverlaySection("LAST 30 DAYS") { ScoreStrip(view.domainStrips[key].orEmpty(), cap = if (key.upside) ScoreScale.MAX else 100.0) }
        OverlaySection("DOES IT EARN ITS PLACE?") { ScorecardLine(view.quality?.domains?.get(key)) }
        OverlaySection("WHAT IS ${key.label}?") { Text(whatIs(key), style = FTType.BodySmall, color = FT.TextSecondary) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoadDirectionStrip(direction: LoadDirection) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (d in listOf(LoadDirection.LOW, LoadDirection.IN_BAND, LoadDirection.HIGH)) {
            Text(
                d.label.removeSuffix(" SIDE"),
                style = FTType.Label,
                color = if (d == direction) FT.TextPrimary else FT.TextMuted,
                modifier = Modifier.background(if (d == direction) FT.GlassStrongFill else Color.Transparent, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** A headline tile: name, value, direction, and on level 2 the points it moved its domain. */
@Composable
fun InputTile(input: InputScore, points: Double?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open ${input.spec.label}") { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(input.spec.label, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f))
        Text(formatInputValue(input.spec.key, input.value), style = FTType.Value, color = FT.TextPrimary)
        Text(directionGlyph(input.direction), style = FTType.Telemetry, color = FT.TextSecondary, modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
        if (points != null) {
            Text("${if (points >= 0.5) "+" else ""}${points.roundToInt()}", style = FTType.Telemetry,
                color = when { points >= 1 -> FT.EmeraldHighlight; points <= -1 -> FT.Critical; else -> FT.TextMuted },
                modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
        }
        Text("›", style = FTType.Telemetry, color = FT.TextMuted)
    }
}

/** A full-picture tile: what it is and its one-line headline. */
@Composable
private fun PictureTile(title: String, headline: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open $title") { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f))
        Text(headline, style = FTType.MonoCaption, color = FT.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text("›", style = FTType.Telemetry, color = FT.TextMuted)
    }
}

/** The weights in use, one line: "personal (143 mornings): RECOVERY 41 % · SLEEP 22 % · …". */
private fun weightsLine(w: com.bioscan.fieldterminal.domain.score.ChargeWeights): String {
    val shares = DomainKey.entries.sortedByDescending { w.weights.getValue(it) }
        .joinToString(" · ") { "${it.label} ${(w.weights.getValue(it) * 100).roundToInt()} %" }
    return if (w.personal) "personal (${w.days} mornings): $shares" else "default: $shares · personal after ${w.need} scored mornings (you have ${w.days})"
}

/** A score's scorecard: its verdict word and sentence, never hiding the score (unlike the old composites). */
@Composable
private fun ScorecardLine(result: com.bioscan.fieldterminal.domain.ScorecardResult?) {
    if (result == null) {
        Text("Not scored yet.", style = FTType.BodySmall, color = FT.TextMuted)
        return
    }
    Text(
        "${com.bioscan.fieldterminal.domain.scorecardWord(result.verdict)} · against ${result.targetText}",
        style = FTType.Label,
        color = when (result.verdict) {
            com.bioscan.fieldterminal.domain.ScorecardVerdict.EARNS_ITS_PLACE -> FT.Emerald
            com.bioscan.fieldterminal.domain.ScorecardVerdict.NO_BETTER -> FT.Warning
            else -> FT.TextSecondary
        },
    )
    Text(result.sentence, style = FTType.BodySmall, color = FT.TextSecondary)
}

// ---- level 3: one metric ----

@Composable
fun InputOverlay(view: ScoresView, key: DomainKey, inputKey: String, onBack: () -> Unit, onClose: () -> Unit) {
    val domain: DomainScore = view.scores.domains.getValue(key)
    val input = domain.raw.inputs.firstOrNull { it.spec.key == inputKey }
    val spec = input?.spec ?: com.bioscan.fieldterminal.domain.score.ScoreSpecs.ALL.getValue(key).groups.flatMap { it.inputs }.first { it.key == inputKey }
    FullScreenOverlay(title = spec.label.uppercase(), trailing = key.label, onClose = onClose, onBack = onBack) {
        if (input != null) {
            Text(formatInputValue(spec.key, input.value), style = FTType.DisplayMetric, color = FT.TextPrimary)
            Text("${directionGlyph(input.direction)} ${directionWords(input.direction)}", style = FTType.LabelCaps, color = FT.TextSecondary)
            val points = inputPoints(domain.raw)[spec.key]
            val line = listOfNotNull(referenceLine(input), points?.let { "moved ${key.label} ${if (it >= 0) "+" else ""}${it.roundToInt()}" }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            Text("No reading for this morning.", style = FTType.Body, color = FT.TextMuted)
        }
        OverlaySection("60 DAYS") { SeriesChart(view.series[spec.key].orEmpty(), view.day, spec.key) }
        OverlaySection("WHAT IS IT?") { Text(whatIsInput(spec.key), style = FTType.BodySmall, color = FT.TextSecondary) }
        Spacer(Modifier.height(24.dp))
    }
}

/** Sixty mornings of one input as dots, with the person's normal band (median ± robust spread) as of today. */
@Composable
private fun SeriesChart(series: Series, day: LocalDate, key: String) {
    val from = day.minusDays(59)
    val points = (0..59).mapNotNull { i -> from.plusDays(i.toLong()).let { d -> series[d]?.let { i to it } } }
    if (points.isEmpty()) {
        Text("No readings in the last 60 days.", style = FTType.BodySmall, color = FT.TextMuted)
        return
    }
    val band = baselineOf(series, day, 60, 14, log = false, minSd = 0.0)
    val values = points.map { it.second } + listOfNotNull(band?.let { it.first - it.second }, band?.let { it.first + it.second })
    val lo = values.min()
    val hi = values.max().let { if (it - lo < 1e-9) lo + 1 else it }
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        fun x(i: Int) = size.width * i / 59f
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat().coerceIn(4f, size.height - 4f)
        band?.let { (m, s) -> drawRect(FT.GlassStrongFill, Offset(0f, y(m + s)), Size(size.width, y(m - s) - y(m + s))) }
        for ((i, v) in points) drawCircle(if (i == 59) FT.EmeraldHighlight else FT.Emerald.copy(alpha = 0.75f), radius = if (i == 59) 6f else 4f, center = Offset(x(i), y(v)))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(formatInputValue(key, lo), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        if (band != null) Text("shaded = your normal", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(formatInputValue(key, hi), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/**
 * Shows the top of [stack]: each overlay opened from another stacks on it, so ‹ returns to the one below and ✕ closes
 * them all.
 */
@Composable
fun ScoreOverlayHost(view: ScoresView, stack: List<ScoreOverlay>, onStack: (List<ScoreOverlay>) -> Unit, onOpenDetail: (DetailPage) -> Unit = {}) {
    val top = stack.lastOrNull() ?: return
    val push: (ScoreOverlay) -> Unit = { onStack(stack + it) }
    val close = { onStack(emptyList()) }
    val back: (() -> Unit)? = if (stack.size > 1) ({ onStack(stack.dropLast(1)) }) else null
    when (top) {
        ScoreOverlay.Charge -> ChargeOverlay(view, onOpen = push, onClose = close)
        // A detail page is a pushed page: the overlays close so back from it returns to TODAY.
        is ScoreOverlay.Domain -> DomainOverlay(view, top.key, onOpen = push, onClose = close, onBack = back, onOpenDetail = { d -> close(); onOpenDetail(d) })
        is ScoreOverlay.Input -> InputOverlay(view, top.key, top.inputKey, onBack = back ?: close, onClose = close)
        is ScoreOverlay.Picture -> PictureOverlay(view, top.picture, onBack = back ?: close, onClose = close)
    }
}
