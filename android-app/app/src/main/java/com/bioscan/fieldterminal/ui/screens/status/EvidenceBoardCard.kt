package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.EvidenceRows
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.RegisteredQuestion
import com.bioscan.fieldterminal.data.model.CaseCrossoverResultRow
import com.bioscan.fieldterminal.data.model.EventCurvePayload
import com.bioscan.fieldterminal.data.model.LagCurvePayload
import com.bioscan.fieldterminal.domain.EvidenceGroup
import com.bioscan.fieldterminal.domain.EvidenceRowView
import com.bioscan.fieldterminal.domain.Verdict
import com.bioscan.fieldterminal.domain.DailyChoice
import com.bioscan.fieldterminal.domain.canRegister
import com.bioscan.fieldterminal.domain.dailyChoiceFor
import com.bioscan.fieldterminal.domain.evidenceTally
import com.bioscan.fieldterminal.domain.verdictHistoryLine
import com.bioscan.fieldterminal.domain.verdictRuns
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.niceTicks
import com.bioscan.fieldterminal.ui.components.tickDecimals
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.abs

// Analysis review phase D (P3): the evidence board. Every question of the catalogue, drawn to scale and grouped by
// outcome: the dot is the estimate, the line its 95% interval, the grey block the no-effect zone, the ticks the smallest
// effect the data could detect. The verdict word and the rung sit beside each row, so nothing rests on colour. A row
// opens its detail: the sentence, what was adjusted for, the checks, and its event and lag curves when they exist.

private const val BOARD_INFO =
    "Each question was fixed in advance with a no-effect zone, the smallest change that would matter. CLEAR: the 95% " +
        "interval lies beyond it. NEGLIGIBLE: inside it, also an answer. UNCLEAR: it crosses an edge. NOT YET: too few " +
        "days. Every row is an association adjusted for weekday, trend and the day before, not a cause; only a " +
        "randomised self-experiment tests one."

private const val BOARD_DETAILS =
    "Reading a row: the dot is the estimate, the line its 95% interval, the grey block the no-effect zone, the ticks the " +
        "smallest effect the data could detect. Tap a row for its sentence, what was adjusted for, the checks and its curves.\n\n" +
        "No-effect zones: HRV 2%, resting heart rate 1 bpm, sleep 10 minutes. ASSOCIATED is the second rung of the evidence " +
        "ladder; a randomised self-experiment reaches TESTED."

@Composable
internal fun EvidenceBoardCard(groups: List<EvidenceGroup>, evidence: EvidenceRows, onTest: ((DailyChoice) -> Unit)? = null) {
    var open by remember { mutableStateOf<EvidenceRowView?>(null) }
    FTCard(title = "WHAT GOES WITH WHAT", info = BOARD_INFO, infoDetails = BOARD_DETAILS, methodology = "relationship_engine") {
        if (groups.isEmpty()) {
            Text("Not analysed yet. The nightly analysis publishes these.", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        Text(evidenceTally(groups), style = FTType.Caption, color = FT.TextMuted)
        // App restructure, fix 6: one line per question (label, its forest row, the verdict), grouped by outcome; the
        // sentences and numbers are one tap away.
        groups.forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(g.title, style = FTType.LabelCaps, color = FT.TextSecondary)
                g.rows.forEach { row -> EvidenceRow(row, g.scale) { open = row } }
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(0.38f))
                    Box(Modifier.weight(0.62f)) { AxisLabels(g.scale, g.unit) }
                }
            }
        }
    }
    open?.let { row ->
        EvidenceDetailSheet(
            row = row,
            curve = evidence.events.firstOrNull { it.key == row.id }?.payload,
            lag = evidence.lags.firstOrNull { it.key == row.id }?.payload,
            onDismiss = { open = null },
            onTest = onTest?.let { test -> dailyChoiceFor(row.id)?.let { choice -> { open = null; test(choice) } } },
        )
    }
}

@Composable
private fun EvidenceRow(row: EvidenceRowView, scale: Double, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .ftClickable(shape = shape, role = Role.Button, onClickLabel = "Open ${row.label}", onClick = onOpen)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(row.label, style = FTType.Caption, color = FT.TextPrimary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(0.38f))
        Box(Modifier.weight(0.62f)) {
            if (row.verdict == Verdict.NotYet) row.progress?.let { ProgressBar(it.fraction) } ?: Box(Modifier.height(22.dp)) else ForestRow(row, scale)
        }
        VerdictChip(row.verdict, rung = false)
    }
}

// The not-yet row's progress toward enough days, the same height as a forest row.
@Composable
private fun ProgressBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(FT.GlassBorder)) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(FT.Analysis))
        }
    }
}

@Composable
internal fun VerdictChip(verdict: Verdict, rung: Boolean = true) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    val (border, text, fill) = when (verdict) {
        Verdict.Clear -> Triple(FT.TextPrimary, FT.TextPrimary, FT.TextPrimary.copy(alpha = 0.10f))
        Verdict.Negligible -> Triple(FT.TextSecondary, FT.TextPrimary, Color.Transparent)
        Verdict.Unclear -> Triple(FT.TextMuted, FT.TextSecondary, Color.Transparent)
        Verdict.NotYet -> Triple(FT.GlassBorder, FT.TextMuted, Color.Transparent)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            verdict.word,
            style = FTType.Micro,
            color = text,
            modifier = Modifier.clip(shape).background(fill).border(1.dp, border, shape).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (rung) {
            Text(
                "ASSOCIATED",
                style = FTType.Micro,
                color = FT.TextMuted,
                modifier = Modifier.border(1.dp, FT.GlassBorder, shape).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(FT.GlassBorder)) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(FT.Analysis))
        }
        Text(text, style = FTType.Caption, color = FT.TextMuted)
    }
}

@Composable
private fun ForestRow(row: EvidenceRowView, scale: Double) {
    val description = row.sizeLine?.let { "${row.verdict.word}: $it" } ?: row.verdict.word
    Canvas(modifier = Modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = description }) {
        val pad = 8f
        val mid = size.width / 2f
        val half = size.width / 2f - pad
        fun x(v: Double) = (mid + (v / scale).toFloat().coerceIn(-1.05f, 1.05f) * half)
        val y = size.height / 2f
        row.zone?.let { z -> drawRect(FT.TextSecondary.copy(alpha = 0.16f), Offset(x(-z), 0f), Size(x(z) - x(-z), size.height)) }
        drawLine(FT.TextMuted, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.5f)
        row.mde?.let { m ->
            for (v in listOf(-m, m)) drawLine(FT.TextMuted, Offset(x(v), size.height - 6f), Offset(x(v), size.height), strokeWidth = 2f)
        }
        val lo = row.lo
        val hi = row.hi
        val est = row.estimate
        if (lo != null && hi != null) drawLine(FT.TextPrimary, Offset(x(lo), y), Offset(x(hi), y), strokeWidth = 3f)
        if (est != null) {
            drawCircle(FT.Surface, radius = 7f, center = Offset(x(est), y))
            drawCircle(FT.TextPrimary, radius = 5f, center = Offset(x(est), y))
        }
    }
}

@Composable
private fun AxisLabels(scale: Double, unit: String) {
    val fmt = { v: Double -> if (abs(scale) >= 10) "%.0f".format(v) else "%.1f".format(v) }
    Row(Modifier.fillMaxWidth()) {
        Text("-${fmt(scale)}", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text("0 $unit", style = FTType.Micro, color = FT.TextMuted)
        Text("+${fmt(scale)}", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceDetailSheet(row: EvidenceRowView, curve: EventCurvePayload?, lag: LagCurvePayload?, onDismiss: () -> Unit, onTest: (() -> Unit)? = null) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(row.label.uppercase(), style = FTType.SectionTitle, color = FT.TextPrimary)
            VerdictChip(row.verdict)
            Text(row.summary, style = FTType.Body, color = FT.TextPrimary)
            if (row.verdict != Verdict.NotYet) ForestRow(row, row.zone?.let { maxOf(it * 3, abs(row.lo ?: 0.0), abs(row.hi ?: 0.0)) * 1.1 } ?: 1.0)
            row.sizeLine?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            row.warning?.let { Text(it, style = FTType.BodySmall, color = FT.Warning) }
            row.registeredLine?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            row.verdictIntervalLine?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            if (row.adjustments.isNotEmpty()) Text("Adjusted for ${row.adjustments.joinToString(", ")}.", style = FTType.BodySmall, color = FT.TextSecondary)
            row.pooledLine?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            // Phase E: the question's verdicts over time, from the append-only ledger.
            var history by remember(row.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(row.id) {
                history = runCatching { verdictHistoryLine(verdictRuns(AnalysisRepository(SupabaseClientProvider.client).loadLedger(row.id))) }.getOrNull()
            }
            history?.let {
                Text("VERDICT HISTORY", style = FTType.LabelCaps, color = FT.TextSecondary)
                Text(it, style = FTType.Caption, color = FT.TextSecondary)
            }
            row.progress?.let { ProgressLine(it.fraction, it.text) }
            row.checks.forEach { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            if (curve != null && curve.state == "ready" && curve.days.isNotEmpty()) {
                Text("THE DAYS AROUND IT", style = FTType.LabelCaps, color = FT.TextSecondary)
                OffsetCurveChart(
                    points = curve.days.map { OffsetPoint(it.k, it.mean, it.lo, it.hi, it.chanceLo, it.chanceHi) },
                    unit = curve.unit,
                    xLabel = { k -> if (k == 0) "EVENT" else if (k > 0) "+$k" else "$k" },
                    marker = 0,
                )
                Text(curve.summary, style = FTType.Caption, color = FT.TextSecondary)
                Text("Line: the difference from comparable days without it, with its 95% interval · grey: chance band.", style = FTType.Micro, color = FT.TextMuted)
            } else if (curve != null) {
                Text(curve.summary, style = FTType.Caption, color = FT.TextMuted)
            }
            if (lag != null && lag.state == "ready" && lag.lags.isNotEmpty()) {
                Text("OVER THE WEEK AFTER, ${lag.perText.uppercase()}", style = FTType.LabelCaps, color = FT.TextSecondary)
                OffsetCurveChart(
                    points = lag.lags.map { OffsetPoint(it.lag, it.value, it.lo, it.hi, null, null) },
                    unit = lag.unit,
                    xLabel = { l -> if (l == 0) "SAME DAY" else "+$l" },
                    marker = null,
                )
                Text(lag.summary, style = FTType.Caption, color = FT.TextSecondary)
                lag.dose?.let { dose ->
                    Text("BY NUMBER OF DRINKS THE EVENING BEFORE", style = FTType.LabelCaps, color = FT.TextSecondary)
                    dose.forEach { d ->
                        Text(
                            "${d.group} drinks: ${"%+.1f".format(d.mean)}${lag.unit} from expected (95% interval ${"%+.1f".format(d.lo)} to ${"%+.1f".format(d.hi)}), ${d.n} mornings",
                            style = FTType.Caption,
                            color = FT.TextSecondary,
                        )
                    }
                }
            }
            row.notes.forEach { Text(it, style = FTType.Micro, color = FT.TextMuted) }
            // Phase F: a same-day choice can be tested -- the step from ASSOCIATED to TESTED.
            onTest?.let { test ->
                Text("TEST IT", style = FTType.LabelCaps, color = FT.TextSecondary)
                Text(
                    "This row is an association. To find out what the choice itself does for you, let the app draw the days: some with the rule, some without, in random order.",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
                FTButton(label = "SET UP A RANDOMISED TEST", emphasis = FTButtonEmphasis.Primary, onClick = test)
            }
        }
    }
}

data class OffsetPoint(val x: Int, val mean: Double?, val lo: Double?, val hi: Double?, val chanceLo: Double?, val chanceHi: Double?)

// Days around an event (or lags after an exposure) on a labelled axis: chance band (grey), 95% interval (light), the
// estimate as a line with dots, zero dashed, the event day marked.
@Composable
private fun OffsetCurveChart(points: List<OffsetPoint>, unit: String, xLabel: (Int) -> String, marker: Int?) {
    val measurer = rememberTextMeasurer()
    val values = points.flatMap { listOfNotNull(it.mean, it.lo, it.hi, it.chanceLo, it.chanceHi) } + 0.0
    val span = (values.maxOrNull() ?: 1.0) - (values.minOrNull() ?: -1.0)
    val lo = (values.minOrNull() ?: -1.0) - span * 0.08
    val hi = (values.maxOrNull() ?: 1.0) + span * 0.08
    val ticks = remember(lo, hi) { niceTicks(lo, hi) }
    val decimals = tickDecimals(ticks)
    val layouts = remember(ticks, measurer) { ticks.map { measurer.measure("%.${decimals}f".format(it), FTType.Micro.copy(color = FT.TextMuted)) } }
    val gutter = (layouts.maxOfOrNull { it.size.width } ?: 0) + 8f
    val xs = points.map { it.x }
    val x0 = xs.minOrNull() ?: 0
    val x1 = (xs.maxOrNull() ?: 1).coerceAtLeast(x0 + 1)
    Column {
        Text(unit.uppercase(), style = FTType.MonoCaption, color = FT.TextMuted)
        Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
            val padY = 8f
            fun px(x: Int) = gutter + 6f + (x - x0).toFloat() / (x1 - x0) * (size.width - gutter - 12f)
            fun py(v: Double) = padY + (1f - ((v - lo) / (hi - lo)).toFloat()) * (size.height - padY * 2)
            ticks.forEachIndexed { i, t ->
                drawLine(FT.GlassBorder, Offset(gutter, py(t)), Offset(size.width, py(t)), strokeWidth = 1f)
                drawText(layouts[i], topLeft = Offset(gutter - 6f - layouts[i].size.width, py(t) - layouts[i].size.height / 2f))
            }
            drawLine(FT.TextMuted, Offset(gutter, py(0.0)), Offset(size.width, py(0.0)), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
            marker?.let { m -> drawLine(FT.TextSecondary, Offset(px(m), 0f), Offset(px(m), size.height), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))) }
            fun band(sel: (OffsetPoint) -> Pair<Double?, Double?>, color: Color) {
                val pts = points.filter { sel(it).first != null && sel(it).second != null }
                if (pts.size < 2) return
                val path = Path().apply {
                    pts.forEachIndexed { i, p -> val y = py(sel(p).second as Double); if (i == 0) moveTo(px(p.x), y) else lineTo(px(p.x), y) }
                    pts.asReversed().forEach { p -> lineTo(px(p.x), py(sel(p).first as Double)) }
                    close()
                }
                drawPath(path, color)
            }
            band({ it.chanceLo to it.chanceHi }, FT.TextSecondary.copy(alpha = 0.16f))
            band({ it.lo to it.hi }, FT.Analysis.copy(alpha = 0.18f))
            val line = points.filter { it.mean != null }
            if (line.size >= 2) {
                val path = Path().apply { line.forEachIndexed { i, p -> if (i == 0) moveTo(px(p.x), py(p.mean as Double)) else lineTo(px(p.x), py(p.mean as Double)) } }
                drawPath(path, FT.Analysis, style = Stroke(width = 3f))
            }
            line.forEach { p ->
                drawCircle(FT.Surface, radius = 6f, center = Offset(px(p.x), py(p.mean as Double)))
                drawCircle(FT.Analysis, radius = 4f, center = Offset(px(p.x), py(p.mean as Double)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            points.forEach { p ->
                Text(xLabel(p.x), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

// Analysis review phase D (P5): what came before mornings outside the expected range on the unfavourable side,
// against reference days on the same weekday in the same month. Exploratory: a hint of what to test next.
// App restructure, fix 6: per outcome, one line per exposure -- how much more often it came the evening before, as an
// odds ratio on a log scale around 1× with its interval -- and the word; numbers and REGISTER are behind a tap.
@Composable
internal fun CaseCrossoverCard(
    rows: List<CaseCrossoverResultRow>,
    // Phase E: a hint can be registered as a question of its own, tested only on days after today.
    registered: List<RegisteredQuestion> = emptyList(),
    onRegister: ((exposure: String, outcome: String) -> Unit)? = null,
    registerError: String? = null,
) {
    if (rows.isEmpty()) return
    var open by remember { mutableStateOf<Pair<String, com.bioscan.fieldterminal.data.model.CaseCrossoverEntry>?>(null) }
    FTCard(
        title = "WHAT CAME BEFORE OFF MORNINGS",
        info = "Each off morning (outside its expected range on the unfavourable side) is compared with days on the same weekday in the same month, so habits and season cancel out. Each line is how much more often the exposure came the evening before (an odds ratio: right of 1× more often, left less often, with its 95% interval). Many exposures are screened at once: a hint of what to test, not an answer.",
        methodology = "case_crossover",
    ) {
        Text("EXPLORATORY · TAP A LINE FOR ITS NUMBERS", style = FTType.Micro, color = FT.TextMuted)
        registerError?.let { Text("Couldn't register: $it", style = FTType.Caption, color = FT.Critical) }
        if (registered.isNotEmpty()) Text("${registered.size} registered to test on new days", style = FTType.Caption, color = FT.TextSecondary)
        rows.sortedBy { listOf("hrv", "rhr", "sleep_hours").indexOf(it.key) }.forEach { r ->
            val p = r.payload
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.text.ifBlank { r.key }, style = FTType.LabelCaps, color = FT.TextSecondary)
                val ready = p.rows.filter { it.verdict != "not_yet" && it.oddsRatio != null }
                if (ready.isEmpty()) {
                    val best = p.rows.maxOfOrNull { it.cases } ?: 0
                    val need = p.rows.firstOrNull()?.need ?: 15
                    Text("Not yet: $best of $need off mornings", style = FTType.Caption, color = FT.TextMuted)
                    ProgressBar((best.toFloat() / need).coerceIn(0f, 1f))
                }
                val top = ready.maxOfOrNull { maxOf(abs(kotlin.math.ln(it.hi ?: 1.0)), abs(kotlin.math.ln(it.lo ?: 1.0))) }?.coerceIn(kotlin.math.ln(2.0), kotlin.math.ln(20.0)) ?: kotlin.math.ln(4.0)
                ready.sortedByDescending { abs(kotlin.math.ln(it.oddsRatio ?: 1.0)) }.forEach { e ->
                    val shape = RoundedCornerShape(FT.RadiusSmall)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).ftClickable(shape = shape, role = Role.Button, onClickLabel = "Open ${e.label}") { open = r.key to e }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(e.label, style = FTType.Caption, color = FT.TextPrimary, maxLines = 2, modifier = Modifier.weight(0.38f))
                        Box(Modifier.weight(0.62f)) { OddsRow(e, top) }
                        Text(crossoverWord(e.verdict), style = FTType.Micro, color = if (e.verdict == "no_clear_difference") FT.TextMuted else FT.TextPrimary)
                    }
                }
                if (ready.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(0.38f))
                        Row(Modifier.weight(0.62f)) {
                            Text("%.1f×".format(kotlin.math.exp(-top)), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
                            Text("1×", style = FTType.Micro, color = FT.TextMuted)
                            Text("%.0f×".format(kotlin.math.exp(top)), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
        }
    }
    open?.let { (outcome, e) ->
        val canReg = onRegister != null && canRegister(e.exposure, outcome, e.verdict, registered)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = null },
            containerColor = FT.Surface,
            title = { Text(e.label.uppercase(), style = FTType.SectionTitle, color = FT.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(crossoverWord(e.verdict), style = FTType.LabelCaps, color = FT.TextPrimary)
                    Text(
                        "It came the evening before ${"%.1f".format(e.oddsRatio)}× as often as on comparable days (95% interval " +
                            "${"%.1f".format(e.lo)} to ${"%.1f".format(e.hi)}), over ${e.cases} off mornings.",
                        style = FTType.BodySmall, color = FT.TextSecondary,
                    )
                    if (canReg) Text("Registering makes it a question of its own, tested only on days after today; its result appears under WHAT GOES WITH WHAT.", style = FTType.Caption, color = FT.TextMuted)
                }
            },
            confirmButton = {
                if (canReg) {
                    androidx.compose.material3.TextButton(onClick = { onRegister?.invoke(e.exposure, outcome); open = null }) { Text("REGISTER TO TEST", color = FT.DomainTraining) }
                } else {
                    androidx.compose.material3.TextButton(onClick = { open = null }) { Text("GOT IT", color = FT.DomainTraining) }
                }
            },
            dismissButton = if (canReg) ({ androidx.compose.material3.TextButton(onClick = { open = null }) { Text("CLOSE", color = FT.TextSecondary) } }) else null,
        )
    }
}

private fun crossoverWord(verdict: String) = when (verdict) {
    "more_often" -> "MORE OFTEN"
    "less_often" -> "LESS OFTEN"
    else -> "NO CLEAR DIFF."
}

// One exposure's odds ratio on a log scale around 1×: interval line, estimate dot.
@Composable
private fun OddsRow(e: com.bioscan.fieldterminal.data.model.CaseCrossoverEntry, top: Double) {
    val or = e.oddsRatio ?: return
    Canvas(modifier = Modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = "%.1f times as often".format(or) }) {
        val pad = 8f
        val mid = size.width / 2f
        val half = size.width / 2f - pad
        fun x(v: Double) = mid + (kotlin.math.ln(v.coerceAtLeast(1e-3)) / top).toFloat().coerceIn(-1.05f, 1.05f) * half
        val y = size.height / 2f
        drawLine(FT.TextMuted, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.5f)
        val lo = e.lo
        val hi = e.hi
        if (lo != null && hi != null) drawLine(FT.TextPrimary, Offset(x(lo), y), Offset(x(hi), y), strokeWidth = 3f)
        drawCircle(FT.Surface, radius = 7f, center = Offset(x(or), y))
        drawCircle(FT.TextPrimary, radius = 5f, center = Offset(x(or), y))
    }
}
