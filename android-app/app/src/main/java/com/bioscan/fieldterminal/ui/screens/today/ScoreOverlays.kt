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
import com.bioscan.fieldterminal.domain.ScorecardResult
import com.bioscan.fieldterminal.domain.ScorecardVerdict
import com.bioscan.fieldterminal.domain.score.AnsState
import com.bioscan.fieldterminal.domain.score.InputKind
import com.bioscan.fieldterminal.domain.score.ScoreKeys
import com.bioscan.fieldterminal.domain.score.ScoreSpecs
import com.bioscan.fieldterminal.domain.score.ansPoints
import com.bioscan.fieldterminal.domain.score.bandWord
import com.bioscan.fieldterminal.domain.score.bulletOf
import com.bioscan.fieldterminal.domain.score.formatJudgedValue
import com.bioscan.fieldterminal.domain.score.inSentence
import com.bioscan.fieldterminal.domain.score.metricStats
import com.bioscan.fieldterminal.domain.score.qualitySentence
import com.bioscan.fieldterminal.domain.score.relatedInputs
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

/** True to draw overlays in place instead of in a dialog (the screenshot tour). */
val LocalInlineOverlays = androidx.compose.runtime.staticCompositionLocalOf { false }

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
    val page: @Composable () -> Unit = {
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
    // The screenshot tour draws an overlay in place; the app shows it as a full-screen dialog.
    if (LocalInlineOverlays.current) page() else {
        Dialog(onDismissRequest = onBack ?: onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { page() }
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
        val words = listOfNotNull(charge.score?.let { bandWord(null, it) }, status).joinToString(" · ")
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

        OverlaySection("WHAT MOVED IT · vs your typical day (80)") {
            val max = charge.drivers.maxOfOrNull { abs(it.points) }?.coerceAtLeast(5.0) ?: 5.0
            if (charge.drivers.isEmpty()) Text("Not scored yet.", style = FTType.BodySmall, color = FT.TextMuted)
            for (d in charge.drivers) {
                DriverBar(label = d.key.label, points = d.points, max = max, onClick = { onOpen(ScoreOverlay.Domain(d.key)) })
            }
            val left = DomainKey.entries.filter { it !in charge.domainsUsed }
            if (left.isNotEmpty()) Text("Not in today's CHARGE: ${left.joinToString { it.label }} (not enough data)", style = FTType.Caption, color = FT.TextMuted)
        }

        OverlaySection("LAST 30 DAYS") { BandedStrip(view.chargeStrip) }

        OverlaySection("WEIGHTS") {
            WeightsBars(view.weights.weights, com.bioscan.fieldterminal.domain.score.CHARGE_WEIGHTS, view.weights.personal)
            Text(view.weights.sentence, style = FTType.Caption, color = FT.TextMuted)
        }
        OverlaySection("DOES IT EARN ITS PLACE?") {
            ScorecardBlock(view.quality?.charge, "CHARGE", isCharge = true)
            view.quality?.chargePlanDone?.let { ScorecardBlock(it, "CHARGE", isCharge = true) }
        }
        OverlaySection("DETAILS") {
            DetailLine("CAPS", charge.cap?.let { "⚠ ${it.reason} · capped at ${it.ceiling.roundToInt()}" } ?: "none")
            DetailLine("BASED ON", "${charge.domainsUsed.size} of ${DomainKey.entries.size} domains")
            if (charge.calibrating) DetailLine("CALIBRATING", "fewer than 28 earlier mornings to rank against: shown unranked")
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
            domain.score?.let { bandWord(key, it) } ?: "PARTIAL",
            if (key == DomainKey.LOAD) view.scores.loadDirection?.label else null,
            if (key == DomainKey.BALANCE) view.scores.ansState?.label else null,
            if (domain.calibrating) "CALIBRATING" else null,
        ).joinToString(" · ")
        RingHeader(domain.score, domain.change, if (domain.partial) RingState.PARTIAL else RingState.SCORED, false, words, domainSentence(domain), key.label)
        if (key == DomainKey.LOAD) view.series[ScoreKeys.TSB]?.let { TsbGauge(it, view.day) }
        if (key == DomainKey.BALANCE && view.scores.ansState == AnsState.SATURATED) {
            Text("SATURATED: HRV is high while the pattern looks like overreaching, so RECOVERY gets no points above 100 this morning.",
                style = FTType.Caption, color = FT.Warning)
        }
        val groups = domain.raw.groups
        if (groups.size > 1) GroupRings(groups.map { it.group.label to it.score })
        val points = inputPoints(domain.raw)
        for (g in groups) {
            OverlaySection(if (groups.size > 1) "WHAT MOVED IT · ${g.group.label.uppercase()}" else "WHAT MOVED IT") {
                val scored = g.inputs.sortedByDescending { abs(points[it.spec.key] ?: 0.0) }
                for (input in scored) BulletRow(input, points[input.spec.key]) { onOpen(ScoreOverlay.Input(key, input.spec.key)) }
                val missing = g.group.inputs.filter { spec -> scored.none { it.spec.key == spec.key } }
                if (missing.isNotEmpty()) Text("No data: ${missing.joinToString { inSentence(it.label) }}", style = FTType.Caption, color = FT.TextMuted)
            }
        }
        if (key == DomainKey.BALANCE) {
            val ans = ansPoints(view.series, view.day)
            if (ans.size >= 3) OverlaySection("NERVOUS SYSTEM MAP · 14 MORNINGS") { AnsMap(ans) }
        }
        val pictures = picturesFor(key)
        if (pictures.isNotEmpty()) {
            OverlaySection("THE FULL PICTURE") {
                for (picture in pictures) {
                    PictureTile(picture.title, view.pictureHeadline(picture), { PictureThumb(view, picture) }) {
                        val detail = picture.detail
                        if (detail != null) onOpenDetail(detail) else onOpen(ScoreOverlay.Picture(picture))
                    }
                }
            }
        }
        if (key == DomainKey.LOAD && view.series[ScoreKeys.TSB] != null) {
            OverlaySection("FATIGUE (TSB) · 30 DAYS") { TsbLine(view.series.getValue(ScoreKeys.TSB), view.day) }
        } else {
            OverlaySection("LAST 30 DAYS") { BandedStrip(view.domainStrips[key].orEmpty(), cap = if (key.upside) ScoreScale.MAX else 100.0) }
        }
        OverlaySection("DOES IT EARN ITS PLACE?") { ScorecardBlock(view.quality?.domains?.get(key), key.label, isCharge = false) }
        OverlaySection("WHAT IS ${key.label}?") { Text(whatIs(key), style = FTType.BodySmall, color = FT.TextSecondary) }
        Spacer(Modifier.height(24.dp))
    }
}

/** A full-picture tile: what it is, a small preview, and its one-line headline. */
@Composable
private fun PictureTile(title: String, headline: String, thumb: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open $title") { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = FTType.BodySmall, color = FT.TextPrimary)
            if (headline.isNotEmpty()) Text(headline, style = FTType.MonoCaption, color = FT.TextSecondary, maxLines = 2)
        }
        thumb()
        Text("›", style = FTType.Telemetry, color = FT.TextMuted)
    }
}

/** A score's scorecard: its verdict, a skill chart against its best single input, and one sentence. */
@Composable
private fun ScorecardBlock(result: ScorecardResult?, label: String, isCharge: Boolean) {
    if (result == null) {
        Text("Not scored yet.", style = FTType.BodySmall, color = FT.TextMuted)
        return
    }
    Text(
        "${com.bioscan.fieldterminal.domain.scorecardWord(result.verdict)} · against ${result.targetText}",
        style = FTType.Label,
        color = when (result.verdict) {
            ScorecardVerdict.EARNS_ITS_PLACE -> FT.Emerald
            ScorecardVerdict.NO_BETTER -> FT.Warning
            else -> FT.TextSecondary
        },
    )
    SkillChart(result, label)
    Text(qualitySentence(result, isCharge), style = FTType.BodySmall, color = FT.TextSecondary)
}

// ---- level 3: one metric ----

@Composable
fun InputOverlay(view: ScoresView, key: DomainKey, inputKey: String, onBack: () -> Unit, onClose: () -> Unit) {
    val domain: DomainScore = view.scores.domains.getValue(key)
    val input = domain.raw.inputs.firstOrNull { it.spec.key == inputKey }
    val spec = input?.spec ?: ScoreSpecs.ALL.getValue(key).groups.flatMap { it.inputs }.first { it.key == inputKey }
    val series = view.series[spec.key].orEmpty()
    val (log, minSd) = when (val k = spec.kind) {
        is InputKind.Baseline -> k.log to k.minSd
        is InputKind.Stability -> k.log to k.minSd
        else -> false to 0.0
    }
    FullScreenOverlay(title = spec.label.uppercase(), trailing = key.label, onClose = onClose, onBack = onBack) {
        if (input != null) {
            Text(formatJudgedValue(input), style = FTType.DisplayMetric, color = FT.TextPrimary)
            Text("${directionGlyph(input.direction)} ${directionWords(input.direction)}", style = FTType.LabelCaps, color = FT.TextSecondary)
            val points = inputPoints(domain.raw)[spec.key]
            val line = listOfNotNull(referenceLine(input), points?.let { "moved ${key.label} ${if (it >= 0) "+" else ""}${it.roundToInt()}" }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = FTType.BodySmall, color = FT.TextSecondary)
            bulletOf(input)?.let { BulletBar(it, scoreColor(input.score), Modifier.fillMaxWidth()) }
        } else {
            Text("No reading for this morning.", style = FTType.Body, color = FT.TextMuted)
        }
        // The normal band (or the target zone) in the metric's own units; log-scale metrics such as HRV are banded on
        // the log scale, so the shading matches the "your normal" range in the header.
        val values = (0..59).mapNotNull { series[view.day.minusDays(it.toLong())] }
        val band: Pair<Double, Double>? = when (val k = spec.kind) {
            is InputKind.Baseline, is InputKind.Stability -> baselineOf(series, view.day, 60, 14, log, minSd)?.let { (m, sd) ->
                if (log) kotlin.math.exp(m - sd) to kotlin.math.exp(m + sd) else (m - sd) to (m + sd)
            }
            is InputKind.AtLeast -> k.target to maxOf(k.target, values.maxOrNull() ?: k.target)
            is InputKind.AtMost -> minOf(0.0, values.minOrNull() ?: 0.0) to k.limit
            is InputKind.Band -> k.lo to k.hi
        }
        val personal = spec.kind is InputKind.Baseline || spec.kind is InputKind.Stability
        OverlaySection("60 DAYS") { MetricChart(series, view.day, spec.key, band, if (personal) "your normal" else "on target") }

        val stats = metricStats(series, view.day, log, minSd.coerceAtLeast(1e-6))
        OverlaySection("HOW IT SITS") {
            stats.sevenDay?.let { DetailLine("7-DAY LEVEL", formatInputValue(spec.key, it)) }
            stats.cvPct?.let { DetailLine("DAY TO DAY", "varies ${"%.1f".format(it)} % · ${if (it < 5) "steady" else if (it < 10) "some swing" else "swinging"}") }
            if (personal) {
                stats.rarerThan?.let { DetailLine("HOW RARE", "further from usual than ${(it * 100).roundToInt()} of your last 100 mornings") }
                stats.insideBand?.let { DetailLine("IN YOUR NORMAL", "$it of the last ${stats.withReading} mornings") }
            } else {
                // Target-type inputs are judged against the target, not against the person's usual.
                val month = (0..29).mapNotNull { d -> view.day.minusDays(d.toLong()).let { day -> com.bioscan.fieldterminal.domain.score.scoreInput(spec, series, day) } }
                if (month.isNotEmpty()) DetailLine("ON TARGET", "${month.count { it.direction == com.bioscan.fieldterminal.domain.score.InputDirection.ON_TARGET }} of the last ${month.size} mornings")
            }
            if (stats.sevenDay == null && stats.rarerThan == null) Text("Not enough readings yet.", style = FTType.BodySmall, color = FT.TextMuted)
        }

        val labels = ScoreSpecs.ALL.values.flatMap { it.groups }.flatMap { it.inputs }.associate { it.key to it.label }
        val others = view.scores.domains.values.flatMap { it.raw.inputs }
            .filter { it.spec.key != spec.key && it.direction != com.bioscan.fieldterminal.domain.score.InputDirection.NORMAL && it.direction != com.bioscan.fieldterminal.domain.score.InputDirection.ON_TARGET }
            .sortedBy { it.score }.take(4)
        OverlaySection("WHAT WENT WITH IT · THIS MORNING") {
            if (others.isEmpty()) Text("Everything else was close to usual.", style = FTType.BodySmall, color = FT.TextMuted)
            for (o in others) BulletRow(o, null) {}
        }
        val related = relatedInputs(view.series, spec.key, view.day, labels)
        OverlaySection("WHAT GOES WITH IT · 120 DAYS") {
            if (related.isEmpty()) Text("Nothing moves clearly with it yet.", style = FTType.BodySmall, color = FT.TextMuted)
            for (r in related) RelationRow(r)
            if (related.isNotEmpty()) Text("Moves together, not proof of cause. Lab › N=1 tests it.", style = FTType.Micro, color = FT.TextMuted)
        }
        OverlaySection("WHAT IS IT?") { Text(whatIsInput(spec.key), style = FTType.BodySmall, color = FT.TextSecondary) }
        Spacer(Modifier.height(24.dp))
    }
}

/** One relation as a bar from the centre: right when the two rise together, left when one rises as the other falls. */
@Composable
private fun RelationRow(r: com.bioscan.fieldterminal.domain.score.Relation) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(r.label, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.width(120.dp), maxLines = 1)
        Canvas(Modifier.weight(1f).height(12.dp)) {
            val mid = size.width / 2
            drawLine(FT.TextMuted, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.5f)
            val w = (abs(r.r) * mid).toFloat()
            val color = FT.Analysis
            if (r.r >= 0) drawRect(color, Offset(mid, 2f), Size(w, size.height - 4f)) else drawRect(color, Offset(mid - w, 2f), Size(w, size.height - 4f))
        }
        Text(if (r.r >= 0) "together" else "opposite", style = FTType.Micro, color = FT.TextSecondary, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
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
