package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ExperimentResultRow
import com.bioscan.fieldterminal.data.ExperimentRow
import com.bioscan.fieldterminal.domain.ExperimentProtocol
import com.bioscan.fieldterminal.domain.designLabel
import com.bioscan.fieldterminal.domain.fourWordLabel
import com.bioscan.fieldterminal.domain.scheduleSpan
import com.bioscan.fieldterminal.domain.todayAssignment
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// Analysis Layer 3 / Phase 5, randomised in analysis review phase F. The person's registered experiments, what today asks
// for, and the latest published result of each. While an experiment runs the numbers are interim and carry no verdict; when
// the last period or day ends, a randomised design answers in four words against the zone set at registration (CLEAR
// BENEFIT, CLEAR HARM, NO MEANINGFUL EFFECT, UNCLEAR) -- the TESTED rung of the evidence ladder. Deleting is soft.

private const val EXPERIMENTS_INFO =
    "Randomised self-experiments: the order of the with- and without-periods (or days) was drawn at registration, so a " +
        "trend or a bad week can't line up with it. CLEAR BENEFIT or CLEAR HARM: the 95% interval lies beyond the " +
        "smallest change you said would matter. NO MEANINGFUL EFFECT: inside it. UNCLEAR: it crosses an edge."

@Composable
fun ExperimentsSection(
    experiments: List<ExperimentRow>,
    results: Map<String, ExperimentResultRow>,
    title: String = "EXPERIMENTS",
    today: LocalDate = LocalDate.now(),
    onDelete: ((ExperimentRow) -> Unit)? = null,
) {
    val shown = experiments.filter { it.status != "abandoned" }
    if (shown.isEmpty()) return
    FTCard(title = title, info = EXPERIMENTS_INFO, methodology = "supplement_experiment") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            shown.forEach { e -> ExperimentEntry(e, results[e.id], today, onDelete) }
        }
    }
}

// The Today page's line for each running randomised experiment: what today's draw asks for (never a blinded arm).
@Composable
fun TodaysDrawCard(experiments: List<ExperimentRow>, today: LocalDate) {
    val draws = experiments.filter { it.status != "abandoned" }.mapNotNull { e -> e.protocol?.let { todayAssignment(it, today) }?.let { e to it } }
    if (draws.isEmpty()) return
    FTCard(title = "TODAY'S DRAW", info = "What each running randomised experiment asks of today. The order was drawn when you registered; a day you don't follow still counts as drawn, so just log what you did.") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            draws.forEach { (e, a) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.name, style = FTType.Label, color = FT.TextSecondary)
                    Text(a.headline, style = FTType.BodySmall, color = FT.TextPrimary)
                    a.detail?.let { Text(it, style = FTType.Caption, color = FT.TextMuted) }
                }
            }
        }
    }
}

@Composable
internal fun ExperimentEntry(e: ExperimentRow, res: ExperimentResultRow?, today: LocalDate, onDelete: ((ExperimentRow) -> Unit)?) {
    var confirming by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val primary = res?.payload?.metrics?.firstOrNull()
    val protocol = e.protocol
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.name, style = FTType.Label, color = FT.TextPrimary, modifier = Modifier.weight(1f))
            if (res?.payload?.stage == "complete") primary?.verdict?.let(::fourWordLabel)?.let { FourWordChip(it) }
        }
        val span = protocol?.let(::scheduleSpan)
        Text(
            listOfNotNull(designLabel(e.design), span?.let { "${it.first} to ${it.second}" }, "status ${e.status}").joinToString(" · "),
            style = FTType.Caption,
            color = FT.TextMuted,
        )
        protocol?.let { todayAssignment(it, today) }?.let { a ->
            Text(a.headline, style = FTType.BodySmall, color = FT.TextPrimary)
            a.detail?.let { Text(it, style = FTType.Caption, color = FT.TextMuted) }
        }
        when {
            res == null -> Text("Not analysed yet. The nightly analysis publishes this.", style = FTType.BodySmall, color = FT.TextSecondary)
            primary == null -> Text("No usable data yet.", style = FTType.BodySmall, color = FT.TextSecondary)
            else -> {
                Text(primary.summary, style = FTType.BodySmall, color = FT.TextSecondary)
                val a = res.payload.adherence
                a.rate?.let {
                    val what = if (protocol?.design == "micro_randomized") "Rule kept on ${a.interventionLogged} of ${a.interventionDays} with-days with a record" else "Logged on ${a.interventionLogged} of ${a.interventionDays} with-period days"
                    Text(what, style = FTType.Caption, color = FT.TextMuted)
                }
                primary.notes.forEach { Text(it, style = FTType.Caption, color = FT.TextMuted) }
            }
        }
        if (protocol?.blinded == true && protocol.phases.isNotEmpty()) {
            FTButton(label = if (reveal) "HIDE THE SCHEDULE" else "SCHEDULE (FOR WHOEVER FILLS THE ORGANISER)") { reveal = !reveal }
            if (reveal) BlindedSchedule(protocol)
        }
        if (onDelete != null) {
            if (confirming) {
                Text("Delete this experiment? Its schedule and result leave the app; this can't be undone.", style = FTType.Caption, color = FT.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FTButton(label = "DELETE") { confirming = false; onDelete(e) }
                    FTButton(label = "KEEP") { confirming = false }
                }
            } else {
                FTButton(label = "DELETE…") { confirming = true }
            }
        }
    }
}

@Composable
private fun BlindedSchedule(protocol: ExperimentProtocol) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        protocol.phases.forEachIndexed { i, p ->
            Text("Period ${i + 1}: ${p.start} to ${p.end} · ${if (p.label == "B") "REAL" else "PLACEBO"}", style = FTType.Telemetry, color = FT.TextSecondary)
        }
    }
}

// The verdict's four words, bordered; the words carry the meaning, not a colour.
@Composable
private fun FourWordChip(text: String) {
    val strong = text.startsWith("CLEAR")
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Text(
        text,
        style = FTType.LabelCaps,
        color = if (strong) FT.TextPrimary else FT.TextSecondary,
        modifier = Modifier
            .border(FT.BorderWidth, if (strong) FT.TextPrimary else FT.TextMuted, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
