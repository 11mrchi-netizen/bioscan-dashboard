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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ExperimentResultRow
import com.bioscan.fieldterminal.data.ExperimentRow
import com.bioscan.fieldterminal.data.model.TypicalityResultRow
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.ScoreRule
import com.bioscan.fieldterminal.domain.ScorecardResult
import com.bioscan.fieldterminal.domain.ScorecardVerdict
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.TypicalityView
import com.bioscan.fieldterminal.domain.WhyContext
import com.bioscan.fieldterminal.domain.fourWordLabel
import com.bioscan.fieldterminal.domain.scheduleSpan
import com.bioscan.fieldterminal.domain.scorecardWord
import com.bioscan.fieldterminal.domain.todayAssignment
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.InfoDialog
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// App restructure, fix 6: Lab › N=1's cards, graphs first. Each first view is a title, one line of rows and the n; the
// explanations sit behind the card's "?" or a tap on a row.

private const val EXPERIMENTS_N1_INFO =
    "Randomised self-experiments: the order of the with- and without-days (or periods) was drawn at registration, so a " +
        "trend or a bad week can't line up with it. The bar is how far through its schedule each one is; the word on the " +
        "right is its result once it ends (CLEAR BENEFIT, CLEAR HARM, NO MEANINGFUL EFFECT, UNCLEAR). Tap one for its design, " +
        "today's assignment, the interim numbers and DELETE."

// Today's draws as chips on top, then one row per experiment: name, progress through its schedule, result or RUNNING.
@Composable
fun N1ExperimentsCard(
    experiments: List<ExperimentRow>,
    results: Map<String, ExperimentResultRow>,
    title: String = "EXPERIMENTS",
    today: LocalDate = LocalDate.now(),
    onDelete: ((ExperimentRow) -> Unit)? = null,
) {
    val shown = experiments.filter { it.status != "abandoned" }
    if (shown.isEmpty()) return
    var open by remember { mutableStateOf<String?>(null) }
    FTCard(title = title, info = EXPERIMENTS_N1_INFO, methodology = "supplement_experiment") {
        val draws = shown.mapNotNull { e -> e.protocol?.let { todayAssignment(it, today) }?.let { e to it } }
        if (draws.isNotEmpty()) {
            Text("TODAY'S DRAW", style = FTType.LabelCaps, color = FT.TextMuted)
            draws.forEach { (e, a) -> DrawChip("${e.name}: ${a.headline}") }
        }
        shown.forEach { e ->
            val res = results[e.id]
            val span = e.protocol?.let(::scheduleSpan)
            val progress = span?.let { (from, to) ->
                runCatching {
                    val start = LocalDate.parse(from)
                    val end = LocalDate.parse(to)
                    val total = ChronoUnit.DAYS.between(start, end).toFloat() + 1f
                    (ChronoUnit.DAYS.between(start, today).toFloat() + 1f).coerceIn(0f, total) / total
                }.getOrNull()
            }
            val word = res?.takeIf { it.payload.stage == "complete" }?.payload?.metrics?.firstOrNull()?.verdict?.let(::fourWordLabel)
                ?: if ((progress ?: 0f) >= 1f) "ANALYSING" else "RUNNING"
            val shape = RoundedCornerShape(FT.RadiusSmall)
            Column(
                Modifier.fillMaxWidth().clip(shape).ftClickable(shape = shape, role = Role.Button, onClickLabel = "Open ${e.name}") { open = if (open == e.id) null else e.id },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.name, style = FTType.Label, color = FT.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(word, style = FTType.Micro, color = if (word.startsWith("CLEAR")) FT.TextPrimary else FT.TextSecondary)
                    Text(if (open == e.id) "▴" else "▾", style = FTType.Label, color = FT.TextMuted)
                }
                progress?.let { f ->
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(FT.GlassBorder)) {
                        Box(Modifier.fillMaxWidth(f).height(6.dp).background(FT.Analysis))
                    }
                }
            }
            if (open == e.id) ExperimentEntry(e, res, today, onDelete)
        }
    }
}

@Composable
private fun DrawChip(text: String) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Text(
        text,
        style = FTType.Caption,
        color = FT.TextPrimary,
        modifier = Modifier.fillMaxWidth().background(FT.Analysis.copy(alpha = 0.12f), shape).border(FT.BorderWidth, FT.Analysis.copy(alpha = 0.4f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private const val SCORECARDS_N1_INFO =
    "Each summary score is tested on your own history: every morning it forecasts the outcome it is meant to help " +
        "predict, and so does each of its inputs. The line is how much better (right) or worse (left) than its best single " +
        "input it forecast, with its 95% interval; the grey block is a difference too small to matter. KEEPS: better than " +
        "its best input. INPUTS ONLY: no better, so the app shows its inputs instead. UNCLEAR: not settled. Tap a row for " +
        "its sentence."

private const val SCORECARDS_N1_DETAILS =
    "Forecasts use only the days before (rolling origin) and are scored with the log score, or the Brier score for a " +
        "yes-or-no outcome. CONDITION is retired (the expected band on THIS MORNING answers the same question); the " +
        "HEALTHSPAN grade is kept as a summary of its factors, since the outcomes it is about are years away."

// One row per composite: the difference from its best input as a forest row around zero, and KEEPS / INPUTS ONLY.
@Composable
fun N1ScorecardsCard(bundle: ScorecardBundle?, typicality: Pair<String, String>? = null) {
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }
    FTCard(title = "DO THE SUMMARIES EARN THEIR PLACE?", info = SCORECARDS_N1_INFO, infoDetails = SCORECARDS_N1_DETAILS) {
        if (bundle == null) {
            Text("Working out the scorecards from your history…", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        listOf(
            "TODAY'S RECOVERY" to bundle.recovery,
            "READY FOR" to bundle.readyFor,
            "SLEEP INDEX · ENERGY" to bundle.sleepEnergy,
            "SLEEP INDEX · HRV" to bundle.sleepHrv,
        ).forEach { (title, result) ->
            val shape = RoundedCornerShape(FT.RadiusSmall)
            Row(
                Modifier.fillMaxWidth().clip(shape).ftClickable(shape = shape, role = Role.Button, onClickLabel = "Explain $title") {
                    open = title to (result?.let { r -> r.sentence + (r.best?.let { "\n\nBest single input: ${it.label.lowercase()}." } ?: "") } ?: "Not scored yet.")
                }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = FTType.Caption, color = FT.TextPrimary, maxLines = 2, modifier = Modifier.weight(0.36f))
                Box(Modifier.weight(0.44f)) { result?.let { DifferenceRow(it) } }
                Text(keepsWord(result), style = FTType.Micro, color = if (result?.verdict == ScorecardVerdict.EARNS_ITS_PLACE) FT.TextPrimary else FT.TextSecondary, modifier = Modifier.weight(0.2f))
            }
        }
        typicality?.let { (word, sentence) ->
            Row(
                Modifier.fillMaxWidth().ftClickable(role = Role.Button) { open = "IS TODAY TYPICAL?" to sentence },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("IS TODAY TYPICAL?", style = FTType.Caption, color = FT.TextPrimary, modifier = Modifier.weight(0.8f))
                Text(word, style = FTType.Micro, color = FT.TextSecondary, modifier = Modifier.weight(0.2f))
            }
        }
    }
    open?.let { (title, body) -> InfoDialog(title, body, details = null) { open = null } }
}

private fun keepsWord(r: ScorecardResult?): String = when (r?.verdict) {
    ScorecardVerdict.EARNS_ITS_PLACE -> "KEEPS"
    ScorecardVerdict.NO_BETTER -> "INPUTS ONLY"
    ScorecardVerdict.UNCLEAR -> "UNCLEAR"
    else -> "NOT YET"
}

// The composite minus its best input (mean daily score, higher is better) with its interval, around zero, with the
// negligible band shaded. The axis is fixed per rule so rows compare at a glance.
@Composable
private fun DifferenceRow(r: ScorecardResult) {
    val d = r.difference ?: return Box(Modifier.height(22.dp))
    val negligible = if (r.rule == ScoreRule.BRIER) com.bioscan.fieldterminal.domain.ScorecardRules.NEGLIGIBLE_BRIER else com.bioscan.fieldterminal.domain.ScorecardRules.NEGLIGIBLE_LOG
    val scale = maxOf(negligible * 5, abs(r.lo ?: d), abs(r.hi ?: d), abs(d)) * 1.1
    Canvas(Modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = "${scorecardWord(r.verdict)}, difference %+.3f".format(d) }) {
        val mid = size.width / 2f
        val half = size.width / 2f - 6f
        fun x(v: Double) = mid + (v / scale).toFloat().coerceIn(-1f, 1f) * half
        val y = size.height / 2f
        drawRect(FT.TextSecondary.copy(alpha = 0.16f), Offset(x(-negligible), 0f), Size(x(negligible) - x(-negligible), size.height))
        drawLine(FT.TextMuted, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.5f)
        val lo = r.lo
        val hi = r.hi
        if (lo != null && hi != null) drawLine(FT.TextPrimary, Offset(x(lo), y), Offset(x(hi), y), strokeWidth = 3f)
        drawCircle(FT.Surface, radius = 7f, center = Offset(x(d), y))
        drawCircle(FT.TextPrimary, radius = 5f, center = Offset(x(d), y))
    }
}

private val TYPICALITY_SIGNALS = mapOf("hrv" to "HRV", "rhr" to "RESTING HR", "sleep" to "SLEEP", "deep_rem" to "DEEP + REM", "steps" to "STEPS")

// Fix 6 on Today: IS TODAY TYPICAL? as its pill and a strip of how far each signal that stood out sits from your usual
// (in standard deviations, the typical band shaded); the sentences are in the pill's pop-up.
@Composable
fun TypicalityCompactCard(view: TypicalityView, row: TypicalityResultRow?) {
    var open by remember { mutableStateOf(false) }
    val today = row?.payload?.today
    val word = when {
        row == null -> StateWord(MetricState.Unavailable, "NOT ANALYSED YET")
        row.state == "building" -> StateWord(MetricState.Building, "BUILDING")
        today == null || today.state == "insufficient" -> StateWord(MetricState.Neutral, "NOT ENOUGH SIGNALS")
        today.excluded -> StateWord(MetricState.Neutral, "ILLNESS: NOT JUDGED")
        today.state == "typical" -> StateWord(MetricState.Optimal, "TYPICAL FOR YOU")
        today.state == "elevated" -> StateWord(MetricState.Neutral, "DIFFERENT FROM USUAL")
        else -> StateWord(MetricState.Warning, "UNUSUAL FOR YOU")
    }
    val popup = (listOf(view.headline) + view.lines + listOfNotNull(view.scorecard?.let { (w, s) -> "Scorecard: $w. $s" })).joinToString("\n\n")
    FTCard(
        title = "IS TODAY TYPICAL?",
        info = "Compares today with your own recent typical days across several signals together. It is experimental: on real history it flags more ordinary days than its nominal share, so it is a prompt to look, not a finding, and it does not feed Today's Recovery.",
        methodology = "typicality",
        why = WhyContext("IS TODAY TYPICAL?", words = listOf("" to (word.word ?: "")), shown = mapOf("pop-up" to popup)),
    ) {
        FTStatePill(word, onClick = { open = true })
        val drivers = today?.drivers.orEmpty()
        if (drivers.isNotEmpty()) {
            drivers.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(TYPICALITY_SIGNALS[d.signal] ?: d.signal.uppercase(), style = FTType.Micro, color = FT.TextSecondary, modifier = Modifier.weight(0.3f))
                    Box(Modifier.weight(0.7f)) { ZRow(d.z) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(0.3f))
                Row(Modifier.weight(0.7f)) {
                    Text("−3 SD", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
                    Text("USUAL", style = FTType.Micro, color = FT.TextMuted)
                    Text("+3 SD", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
    if (open) InfoDialog("IS TODAY TYPICAL?", popup, details = null) { open = false }
}

@Composable
private fun ZRow(z: Double) {
    Canvas(Modifier.fillMaxWidth().height(18.dp).semantics { contentDescription = "%+.1f standard deviations".format(z) }) {
        val mid = size.width / 2f
        val half = size.width / 2f - 6f
        fun x(v: Double) = mid + (v / 3.0).toFloat().coerceIn(-1f, 1f) * half
        val y = size.height / 2f
        drawRect(FT.TextSecondary.copy(alpha = 0.16f), Offset(x(-2.0), 0f), Size(x(2.0) - x(-2.0), size.height))
        drawLine(FT.TextMuted, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.5f)
        drawLine(FT.TextPrimary, Offset(mid, y), Offset(x(z), y), strokeWidth = 3f)
        drawCircle(FT.Surface, radius = 6f, center = Offset(x(z), y))
        drawCircle(FT.TextPrimary, radius = 4f, center = Offset(x(z), y))
    }
}
