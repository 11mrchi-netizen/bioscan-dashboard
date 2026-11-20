package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.InstrumentsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.risk.InstrumentScore
import com.bioscan.fieldterminal.domain.risk.score
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.FlowEssentials
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import java.util.Locale
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R2: one questionnaire (PHQ-9, GAD-7, WHO-5, UCLA-3, the burnout inventory's work scale, working hours), as
// published, on one page; SAVE scores it with instruments.json and shows the result in place. A PHQ-9 answer of thoughts of
// being better off dead or of self-harm shows a plain line, whatever the total (owner decision: no crisis line, no
// notification to anyone).

internal fun fmtScore(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ENGLISH, "%.1f", v)

internal fun stateColor(state: String?) = when (state) {
    "ok" -> FT.Ok
    "warn" -> FT.Warn
    "bad" -> FT.Bad
    else -> FT.TextSecondary
}

@Composable
fun QuestionnaireSheet(instrumentId: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val defs = remember { InstrumentsRepository.definitions(context) }
    val inst = remember(instrumentId) { defs.instrument(instrumentId) } ?: run { onDismiss(); return }
    val repo = remember { InstrumentsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val answers = remember(instrumentId) { mutableStateListOf<Double?>().apply { repeat(maxOf(1, inst.items.size)) { add(null) } } }
    var hours by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<InstrumentScore?>(null) }

    val values: List<Double>? = if (inst.number != null) hours.replace(',', '.').toDoubleOrNull()?.let { listOf(it) }
    else answers.takeIf { a -> a.all { it != null } }?.map { it!! }
    val scored = values?.let { defs.score(inst, it) }

    FTFlow(
        title = inst.name.uppercase(), pages = listOf(FlowPage("QUESTIONS")), state = rememberFlowState(), onClose = onDismiss,
        canSave = scored != null && !saving && result == null, saving = saving, wash = FT.DomainUser,
        subtitle = "${inst.short} · ${if (inst.items.isEmpty()) "ONE QUESTION" else "${inst.items.size} QUESTIONS"}",
        error = error?.let { "Couldn't save: $it" },
        onSave = {
            val v = values ?: return@FTFlow
            val s = scored ?: return@FTFlow
            saving = true
            error = null
            scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }, onDone = { saving = false }) {
                repo.save(defs, inst, v, s)
                result = s
                onSaved()
            }
        },
        bottomBar = if (result != null) ({ _ ->
            Row(Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
                FTButton("DONE", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }) else null,
    ) {
        val r = result
        if (r != null) {
            Result(inst.unit, r, inst.source)
        } else {
            FlowEssentials {
                FlowQuestion(inst.prompt)
                if (inst.number != null) {
                    FieldTextField(hours, { hours = it }, "e.g. 45", keyboardType = KeyboardType.Decimal)
                    Text(inst.number.unit, style = FTType.Caption, color = FT.TextSecondary)
                }
            }
            inst.items.forEachIndexed { i, item ->
                val opts = defs.optionsOf(item)
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${i + 1}. ${item.text}", style = FTType.Body, color = FT.TextPrimary)
                    TextChipRow(opts.map { it.label }, opts.firstOrNull { it.value == answers[i] }?.label, perRow = if (opts.size > 3) 2 else 3) { picked ->
                        answers[i] = opts.firstOrNull { it.label == picked }?.value
                    }
                }
            }
            Text(inst.source, style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun Result(unit: String, r: InstrumentScore, source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fmtScore(r.score), style = FTType.DisplayMetric, color = FT.TextPrimary)
            Text(unit, style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.padding(top = 22.dp))
        }
        r.band?.let { Text(it.label, style = FTType.RowTitle, color = stateColor(it.state)) }
        r.flags.forEach { f ->
            Text(
                f.text, style = FTType.Body, color = FT.TextPrimary,
                modifier = Modifier.fillMaxWidth().background(FT.GlassFill, RoundedCornerShape(FT.RadiusSmall)).padding(14.dp),
            )
        }
        Text("Saved. It shows under Me › EXPOSURES, and is due again in a few weeks.", style = FTType.BodySmall, color = FT.TextSecondary)
        Text(source, style = FTType.Caption, color = FT.TextMuted)
    }
}
