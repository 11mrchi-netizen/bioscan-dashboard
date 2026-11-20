package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.PreventionData
import com.bioscan.fieldterminal.domain.risk.ALCOHOL_LOW_RISK_G_PER_WEEK
import com.bioscan.fieldterminal.domain.risk.CAFFEINE_SAFE_MG_PER_DAY
import com.bioscan.fieldterminal.domain.risk.ChecklistState
import com.bioscan.fieldterminal.domain.risk.HOT_DAY_C
import com.bioscan.fieldterminal.domain.risk.PREVENTIVE_LAYERS
import com.bioscan.fieldterminal.domain.risk.PreventiveState
import com.bioscan.fieldterminal.domain.risk.PreventiveStatus
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R4: Me › EXPOSURES › SUBSTANCES, SUN AND HEAT, HOME AND WATER, PREVENTIVE CARE. Exposures with their source
// and fix, and the layered preventive schedule with what is due. Nothing here is a daily metric, so nothing opens a history.

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

@Composable
private fun Row2(label: String, value: String, color: androidx.compose.ui.graphics.Color = FT.TextPrimary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = FTType.Caption, color = color)
    }
}

@Composable
internal fun SubstancesCard(p: PreventionData, smoking: String?) {
    val s = p.substances
    FTCard(title = "SUBSTANCES", info = "From logged drinks over the last 4 weeks: drinks not logged cannot be counted. Alcohol against the UK Chief Medical Officers' low-risk guideline (14 units, 112 g a week; WHO: no amount is without risk). Caffeine against EFSA's 400 mg a day for healthy adults; caffeine late in the day delays and lightens sleep.") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.alcoholGramsPerWeek == null) Text("No logged drinks in the last 4 weeks.", style = FTType.BodySmall, color = FT.TextSecondary)
            s.alcoholGramsPerWeek?.let { g ->
                val units = g / 8
                Row2("Alcohol", "${String.format(Locale.ENGLISH, "%.1f", units)} units a week", if (g > ALCOHOL_LOW_RISK_G_PER_WEEK) FT.Warn else FT.Ok)
            }
            s.caffeineMgPerDay?.let { c -> Row2("Caffeine", "${c.roundToInt()} mg a day", if (c > CAFFEINE_SAFE_MG_PER_DAY) FT.Warn else FT.Ok) }
            s.lateCaffeineDays?.let { d -> Row2("Caffeine at 14:00 or later", "$d of ${s.days} days", if (d > s.days / 2) FT.Warn else FT.TextPrimary) }
            Row2("Smoking or vaping", when (smoking) { "never" -> "never"; "former" -> "stopped"; "current" -> "now"; else -> "not answered" },
                when (smoking) { "current" -> FT.Warn; null -> FT.TextMuted; else -> FT.Ok })
        }
    }
}

@Composable
internal fun HeatCard(p: PreventionData, highUvSessions: Int?) {
    // The UV count comes from the air summary, which loads on its own: read it here, when both are on screen.
    val h = p.heat?.copy(sessionsHighUv = highUvSessions ?: 0) ?: return
    FTCard(title = "SUN AND HEAT", info = "Last 4 weeks. Hot days: a maximum of $HOT_DAY_C °C or more where you were (the phone's weather). High UV: an index of 6 or more during an outdoor session (WHO: extra protection needed). Heat raises heart rate and fluid loss at the same effort.") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row2("Hot days", "${h.hotDays} of ${h.days}")
            Row2("Sessions on hot days", "${h.sessionsOnHotDays}", if (h.sessionsOnHotDays > 0) FT.Warn else FT.TextPrimary)
            Row2("Outdoor sessions at UV 6 or more", "${h.sessionsHighUv}", if (h.sessionsHighUv > 0) FT.Warn else FT.TextPrimary)
            if (h.sessionsOnHotDays + h.sessionsHighUv > 0) {
                Text("Earlier sessions avoid the heat and the UV peak (11:00-15:00); the fuel plan's sweat rate covers fluid.", style = FTType.Caption, color = FT.TextSecondary)
            }
        }
    }
}

@Composable
internal fun HomeWaterCard(states: List<ChecklistState>, onEdit: () -> Unit) {
    val answered = states.count { it.answer != null }
    val flagged = states.filter { it.flagged }
    FTCard(title = "HOME AND WATER", info = "A checklist, not a score: no validated instrument scores these. Each flagged item shows why and the fix, with its source. Asked again after 12 months.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        answered == 0 -> "Not answered yet: ${states.size} short questions about cooking, plastics, damp, radon and water."
                        flagged.isEmpty() -> "$answered of ${states.size} answered; nothing flagged."
                        else -> "$answered of ${states.size} answered; ${flagged.size} to look at."
                    },
                    style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f),
                )
                FTButton(if (answered == 0) "ANSWER" else "EDIT", emphasis = if (answered == 0 || states.any { it.stale }) FTButtonEmphasis.Primary else FTButtonEmphasis.Tertiary, onClick = onEdit)
            }
            flagged.forEach { st ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(st.item.words(st.answer?.value) ?: st.item.question, style = FTType.RowTitle, color = FT.Warn)
                    Text(st.item.why, style = FTType.BodySmall, color = FT.TextSecondary)
                    Text(st.item.fix, style = FTType.BodySmall, color = FT.TextPrimary)
                    Text(st.item.source, style = FTType.Caption, color = FT.TextMuted)
                }
            }
        }
    }
}

@Composable
internal fun PreventiveCard(statuses: List<PreventiveStatus>, onRecord: (PreventiveStatus) -> Unit) {
    val due = statuses.count { it.state == PreventiveState.DUE }
    FTCard(title = "PREVENTIVE CARE", info = "A layered schedule (owner decision): USPSTF A and B recommendations and the CDC adult vaccines first, then European cardiovascular prevention, then what depends on where you are from, then optional items to discuss with a doctor. Dates come from what you record here or from your data (BP readings, lab draws). A reminder of what guidelines say, not medical advice.") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (due == 0) "Nothing due." else "$due due now.", style = FTType.CaptionStrong, color = if (due == 0) FT.Ok else FT.Warn)
            PREVENTIVE_LAYERS.forEach { (layer, title) ->
                val rows = statuses.filter { it.item.layer == layer }
                if (rows.isEmpty()) return@forEach
                Text(title, style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
                rows.forEach { s -> PreventiveRow(s, onRecord) }
            }
        }
    }
}

@Composable
private fun PreventiveRow(s: PreventiveStatus, onRecord: (PreventiveStatus) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(s.item.name, style = FTType.Body, color = FT.TextPrimary)
            val line = when (s.state) {
                PreventiveState.DUE -> if (s.last == null) "due · never recorded" else "due · last ${s.last.format(DAY)}"
                PreventiveState.UPCOMING -> "next ${s.next!!.format(DAY)} · last ${s.last!!.format(DAY)}${s.lastFrom?.let { " ($it)" } ?: ""}"
                PreventiveState.DONE -> "done ${s.last!!.format(DAY)}${s.lastFrom?.let { " ($it)" } ?: ""}"
                PreventiveState.NOT_APPLICABLE -> "marked as not applicable"
                PreventiveState.CHECK -> "check: ${s.check}"
            }
            Text(line, style = FTType.MonoCaption, color = when (s.state) { PreventiveState.DUE -> FT.Warn; PreventiveState.CHECK -> FT.TextSecondary; else -> FT.TextSecondary })
        }
        FTButton(if (s.state == PreventiveState.DUE || s.state == PreventiveState.CHECK) "RECORD" else "EDIT",
            emphasis = if (s.state == PreventiveState.DUE) FTButtonEmphasis.Primary else FTButtonEmphasis.Tertiary) { onRecord(s) }
    }
}
