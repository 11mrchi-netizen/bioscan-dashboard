package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.PlannedMatch
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import com.bioscan.fieldterminal.domain.training.LoggedSet
import com.bioscan.fieldterminal.domain.training.PlanVerdict
import com.bioscan.fieldterminal.domain.training.PlannedLift
import com.bioscan.fieldterminal.domain.training.compareToPlan
import com.bioscan.fieldterminal.domain.training.generate.text
import com.bioscan.fieldterminal.domain.training.looseMovementKey
import com.bioscan.fieldterminal.domain.training.parsePlannedConditioning
import com.bioscan.fieldterminal.domain.training.parsePlannedLifts
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

private fun kg(x: Double) = if (x == Math.floor(x)) x.toInt().toString() else "%.1f".format(x)

private fun PlannedLift.targetText(): String = when (loadKind) {
    "pct_1rm", "fixed" -> targetKg?.let { "${kg(it)} kg" + (pct?.let { p -> " (${kg(p)}%)" } ?: "") }
    "added" -> targetKg?.let { "+${kg(it)} kg" + (pct?.let { p -> " (${kg(p)}%)" } ?: "") }
    "work_up_rm" -> "work up to a heavy set"
    else -> null
} ?: "no fixed load"

// DAV-346. What the training block planned for this workout, set against what was logged.
// Shown on the workout's own page; a workout can be linked to its plan when none is linked yet.
@Composable
fun PlannedSessionCard(match: PlannedMatch, logged: List<StrengthExerciseDto>, actualMinutes: Double?, onLink: () -> Unit) {
    val lifts = remember(match) { parsePlannedLifts(match.row.prescription) }
    val cond = remember(match) { parsePlannedConditioning(match.row.prescription) }
    val loggedByKey = remember(logged) { logged.associateBy { looseMovementKey(it.name) } }
    FTCard(title = "PLANNED") {
        Text(match.row.title, style = FTType.RowTitle, color = FT.TextPrimary)
        Text(
            listOfNotNull(match.blockName, "week ${match.row.weekIndex}", match.row.scheduledDate).joinToString(" · "),
            style = FTType.Caption, color = FT.TextSecondary,
        )
        lifts.forEach { p ->
            val sets = loggedByKey[looseMovementKey(p.exercise)]?.sets.orEmpty().map { LoggedSet(it.reps, it.weightKg) }
            val verdict = compareToPlan(p, sets)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.exercise, style = FTType.RowTitle, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                    Text(verdict.text, style = FTType.Label, color = verdictColor(verdict))
                }
                val scheme = listOfNotNull(p.sets?.text(), p.reps?.text()).joinToString(" x ")
                FTMetricRow("Planned", listOf(scheme, p.targetText()).filter { it.isNotEmpty() }.joinToString("  @  "))
                if (sets.isNotEmpty()) FTMetricRow("Logged", sets.joinToString(", ") { "${it.reps} x ${kg(it.kg)}" })
                p.technique?.let { Text("option: $it", style = FTType.Caption, color = FT.TextMuted) }
            }
        }
        cond?.let {
            FTMetricRow("Planned", it.label + (it.minutes?.let { m -> " · ${m.text()} min" } ?: ""))
            actualMinutes?.let { m -> FTMetricRow("Logged", "${m.toInt()} min") }
        }
        if (!match.linked) {
            Text("This workout is not linked to the plan yet.", style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            AmberButton("LINK TO THIS PLANNED SESSION", onClick = onLink)
        } else {
            Text("Linked to the plan.", style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun verdictColor(v: PlanVerdict) = when (v) {
    PlanVerdict.Done -> FT.Emerald
    PlanVerdict.Above -> FT.Info
    PlanVerdict.Below, PlanVerdict.Short -> FT.Warning
    PlanVerdict.NotLogged, PlanVerdict.Open -> FT.TextMuted
}
