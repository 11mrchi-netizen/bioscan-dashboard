package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.risk.INSTRUMENT_DOMAINS
import com.bioscan.fieldterminal.domain.risk.InstrumentSummary
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTChevron
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R2: Me › EXPOSURES › MIND, CONNECTION and WORK. Each questionnaire's latest score (its hue), the band in its
// state colour, the score before it, and when it is due; a row with results opens its history, TAKE opens the questionnaire.

/** Each questionnaire's one hue (design/tokens --m-*), on the phone and in the web document. */
fun instrumentInk(id: String): Color = when (id) {
    "phq9" -> FT.Metric.Phq9
    "gad7" -> FT.Metric.Gad7
    "who5" -> FT.Metric.Who5
    "ucla3" -> FT.Metric.Loneliness
    "cbi_work" -> FT.Metric.Burnout
    "work_hours" -> FT.Metric.WorkHours
    else -> FT.TextPrimary
}

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private val DOMAIN_INFO = mapOf(
    "mind" to "Low mood (PHQ-9), anxiety (GAD-7) and wellbeing (WHO-5): validated screens, every 4 weeks. A screen, not a diagnosis.",
    "connection" to "Loneliness (the 3-item UCLA scale), every 4 weeks. Loneliness and isolation go with higher mortality and dementia risk.",
    "work" to "Burnout at work (the Copenhagen Burnout Inventory's work scale) and working hours, every 8 weeks. 55 h a week or more raises stroke and heart disease risk (WHO/ILO 2021).",
)

@Composable
internal fun QuestionnaireCards(summaries: List<InstrumentSummary>, today: LocalDate, onTake: (String) -> Unit, onOpen: (InstrumentSummary) -> Unit) {
    INSTRUMENT_DOMAINS.forEach { (domain, title) ->
        val rows = summaries.filter { it.instrument.domain == domain }
        if (rows.isEmpty()) return@forEach
        FTCard(title = title, info = DOMAIN_INFO[domain]) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { s -> QuestionnaireRow(s, today, onTake, onOpen) }
            }
        }
    }
}

@Composable
private fun QuestionnaireRow(s: InstrumentSummary, today: LocalDate, onTake: (String) -> Unit, onOpen: (InstrumentSummary) -> Unit) {
    val inst = s.instrument
    val ink = instrumentInk(inst.id)
    val latest = s.latest
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.weight(1f).then(if (latest != null) Modifier.ftClickable(onClickLabel = "Open its history") { onOpen(s) } else Modifier).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).background(ink, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f)) {
                Text("${inst.name.uppercase()} · ${inst.short}", style = FTType.LabelCaps, color = FT.TextPrimary, maxLines = 1)
                val when_ = when {
                    latest == null -> "NOT TAKEN YET"
                    s.due -> "${latest.date.format(DAY).uppercase()} · DUE NOW"
                    else -> "${latest.date.format(DAY).uppercase()} · NEXT ${s.dueOn!!.format(DAY).uppercase()}"
                }
                Text(when_, style = FTType.MonoCaption, color = if (s.due) FT.TextPrimary else FT.TextSecondary)
                latest?.band?.let { Text(it, style = FTType.Caption, color = stateColor(s.state)) }
            }
            if (latest != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtScore(latest.score), style = FTType.Telemetry, color = ink)
                    s.previous?.let { p ->
                        val arrow = when { latest.score > p -> "↑"; latest.score < p -> "↓"; else -> "=" }
                        Text("$arrow from ${fmtScore(p)}", style = FTType.MonoCaption, color = FT.TextSecondary)
                    }
                }
                FTChevron(tint = FT.TextSecondary)
            }
        }
        FTButton(if (latest == null || s.due) "TAKE" else "AGAIN", emphasis = if (s.due) FTButtonEmphasis.Primary else FTButtonEmphasis.Tertiary) { onTake(inst.id) }
    }
}
