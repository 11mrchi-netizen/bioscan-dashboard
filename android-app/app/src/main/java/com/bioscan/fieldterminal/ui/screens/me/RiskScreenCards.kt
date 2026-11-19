package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.RiskScreens
import com.bioscan.fieldterminal.domain.risk.FactorState
import com.bioscan.fieldterminal.domain.risk.PreventOutcome
import com.bioscan.fieldterminal.domain.risk.PreventOutcomeOrWhy
import com.bioscan.fieldterminal.domain.risk.preventCategory
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import java.util.Locale
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R3: Me › EXPOSURES › HEART, SUGAR AND BRAIN. PREVENT (10- and 30-year cardiovascular risk), Life's
// Essential 8, FINDRISC and the Lancet dementia factors, each saying what it read and what is still missing. These are
// worked out now from the latest inputs, not daily series, so nothing here opens a history.

internal fun pct(x: Double): String = String.format(Locale.ENGLISH, if (x < 0.1) "%.1f %%" else "%.0f %%", x * 100)

@Composable
private fun Line(label: String, value: String, color: androidx.compose.ui.graphics.Color = FT.TextPrimary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = FTType.Caption, color = color)
    }
}

@Composable
internal fun RiskScreenCards(s: RiskScreens, onEditBackground: () -> Unit) {
    FTCard(title = "HEALTH BACKGROUND", info = "Smoking, medicines, diabetes, family history, hearing, vision, head injury and education: what the screens below need and no sensor or lab gives. The assistant cannot read it.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (s.backgroundAnswered) "Answered. Edit it when something changes (a new medicine, stopping smoking)." else "Not answered yet: the screens below need it.",
                style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f),
            )
            FTButton(if (s.backgroundAnswered) "EDIT" else "ANSWER", emphasis = if (s.backgroundAnswered) FTButtonEmphasis.Tertiary else FTButtonEmphasis.Primary, onClick = onEditBackground)
        }
    }

    FTCard(title = "HEART · PREVENT", info = "AHA PREVENT equations (Khan et al., Circulation 2024), base model: the chance of a heart attack, stroke or heart failure in the next 10 years (and 30, up to age 59). Valid from 30 to 79. Low under 5 %, borderline to 7.5 %, intermediate to 20 %, high from 20 %. A population model: it describes people like you on average.") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val p = s.prevent) {
                is PreventOutcomeOrWhy.Ok -> {
                    val total = p.result.tenYear.getValue(PreventOutcome.TOTAL_CVD)
                    val cat = preventCategory(total)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pct(total), style = FTType.MetricMedium, color = FT.TextPrimary)
                        Text("in 10 years · ${cat.label}", style = FTType.Caption, color = stateColor(cat.state))
                    }
                    PreventOutcome.entries.drop(1).forEach { o -> Line(o.label, pct(p.result.tenYear.getValue(o))) }
                    p.result.thirtyYear?.let { Line("Any of these in 30 years", pct(it.getValue(PreventOutcome.TOTAL_CVD))) }
                    Text(
                        listOfNotNull(s.inputs.sbp?.note?.let { "BP: $it" }, s.inputs.totalCholMgDl?.note?.let { "lipids $it" }).joinToString(" · "),
                        style = FTType.MonoCaption, color = FT.TextMuted,
                    )
                }
                is PreventOutcomeOrWhy.NotApplicable -> Text("Not worked out: ${p.reasons.joinToString("; ")}.", style = FTType.BodySmall, color = FT.TextSecondary)
                null -> Text("Not worked out.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
        }
    }

    val le8 = s.le8
    FTCard(title = "HEART · LIFE'S ESSENTIAL 8", info = "AHA Life's Essential 8 (Lloyd-Jones et al., Circulation 2022): eight measures scored 0-100 each, the score their mean. High from 80, moderate 50-79, low under 50. Diet needs a questionnaire that comes later, so the score here is over the measures assessed.") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            le8.score?.let { sc ->
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$sc", style = FTType.MetricMedium, color = FT.TextPrimary)
                    Text("/ 100 · ${le8.band} · ${le8.assessed.size} of 8 measures", style = FTType.Caption, color = stateColor(le8.state))
                }
            }
            le8.metrics.forEach { m ->
                Line(m.metric.label, m.points?.let { "$it · ${m.reading ?: ""}".trimEnd(' ', '·') } ?: "needs ${m.missing}",
                    if (m.points == null) FT.TextMuted else stateColor(if (m.points >= 80) "ok" else if (m.points >= 50) "warn" else "bad"))
            }
        }
    }

    val fr = s.findrisc
    FTCard(title = "SUGAR · FINDRISC", info = "Finnish Diabetes Risk Score (Lindström & Tuomilehto, Diabetes Care 2003): the chance of type 2 diabetes in 10 years from eight questions, 0-26 points. Under 7 low, 7-11 slightly elevated, 12-14 moderate, 15-20 high, over 20 very high.") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${fr.score}", style = FTType.MetricMedium, color = FT.TextPrimary)
                Text(
                    if (fr.complete) "/ 26 · ${fr.band} · ${fr.tenYearRisk} in 10 years" else "/ 26 so far · could be up to ${fr.maxPossible}",
                    style = FTType.Caption, color = if (fr.complete) stateColor(fr.state) else FT.TextSecondary,
                )
            }
            fr.items.forEach { it2 -> Line(it2.label, it2.points?.let { p -> "$p · ${it2.reading ?: ""}".trimEnd(' ', '·') } ?: "not answered", if (it2.points == null) FT.TextMuted else FT.TextPrimary) }
        }
    }

    val present = s.brain.count { it.state == FactorState.PRESENT }
    val unknown = s.brain.count { it.state == FactorState.UNKNOWN }
    FTCard(title = "BRAIN · DEMENTIA FACTORS", info = "The 14 modifiable factors of the 2024 Lancet Commission (Livingston et al.), which together account for about 45 % of dementia worldwide. Each is listed as present, absent or unknown with where it came from; there is no combined number, since the factors are not independent.") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$present present · ${s.brain.size - present - unknown} absent · $unknown unknown", style = FTType.CaptionStrong, color = if (present > 0) FT.Warn else FT.TextPrimary)
            s.brain.forEach { f ->
                Line(
                    f.label,
                    when (f.state) {
                        FactorState.UNKNOWN -> "needs ${f.needs}"
                        else -> listOfNotNull(f.state.word, f.reading).joinToString(" · ")
                    },
                    when (f.state) { FactorState.PRESENT -> FT.Warn; FactorState.ABSENT -> FT.Ok; FactorState.UNKNOWN -> FT.TextMuted },
                )
            }
        }
    }
}
