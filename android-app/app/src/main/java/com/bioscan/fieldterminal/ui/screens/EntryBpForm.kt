package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.NewBpReadingRow
import com.bioscan.fieldterminal.domain.bp.BpReading
import com.bioscan.fieldterminal.domain.bp.isCrisis
import com.bioscan.fieldterminal.domain.toAppIso
import com.bioscan.fieldterminal.ui.components.DateTimeField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDateTime
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R0: a home blood pressure sitting. The AHA protocol takes 2 readings 1 minute apart, so a new entry has two
// rows (the second optional and saved 1 minute after the first); editing a saved reading shows one.

private data class BpInput(val sys: String = "", val dia: String = "", val pulse: String = "") {
    val systolic: Int? get() = sys.trim().toIntOrNull()?.takeIf { it in 60..260 }
    val diastolic: Int? get() = dia.trim().toIntOrNull()?.takeIf { it in 30..160 }
    val pulseValue: Int? get() = pulse.trim().toIntOrNull()?.takeIf { it in 25..220 }
    val blank: Boolean get() = sys.isBlank() && dia.isBlank() && pulse.isBlank()
    val valid: Boolean get() = systolic != null && diastolic != null && systolic!! > diastolic!! && (pulse.isBlank() || pulseValue != null)
}

@Composable
private fun BpReadingFields(label: String, input: BpInput, onChange: (BpInput) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FormLabel(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { FieldTextField(input.sys, { onChange(input.copy(sys = it)) }, "SYS", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FieldTextField(input.dia, { onChange(input.copy(dia = it)) }, "DIA", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FieldTextField(input.pulse, { onChange(input.copy(pulse = it)) }, "PULSE", keyboardType = KeyboardType.Number) }
        }
    }
}

@Composable
internal fun BpForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initial: NewBpReadingRow? = null,
    onSave: (List<NewBpReadingRow>) -> Unit,
) {
    val editing = initial != null
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var first by remember { mutableStateOf(initial?.let { BpInput(it.systolic.toString(), it.diastolic.toString(), it.pulse?.toString() ?: "") } ?: BpInput()) }
    var second by remember { mutableStateOf(BpInput()) }
    var arm by remember { mutableStateOf(initial?.arm ?: "left") }
    val canSave = first.valid && (second.blank || second.valid)
    val crisis = listOf(first, second).filter { it.valid }.any { isCrisis(BpReading(dateTime, it.systolic!!, it.diastolic!!)) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        if (!editing) {
            Text(
                "Seated, back supported, feet flat, cuff on the bare upper arm at heart level, after 5 minutes' rest. " +
                    "Take 2 readings 1 minute apart, morning and evening.",
                style = FTType.BodySmall, color = FT.TextSecondary,
            )
        }
        BpReadingFields(if (editing) "READING (MMHG, PULSE OPTIONAL)" else "READING 1 (MMHG, PULSE OPTIONAL)", first) { first = it }
        if (!editing) BpReadingFields("READING 2, 1 MINUTE LATER (OPTIONAL)", second) { second = it }
        Column { FormLabel("ARM"); TextChipRow(listOf("left", "right"), arm, perRow = 2) { picked -> picked?.let { arm = it } } }
        if (crisis) {
            Text(
                "Above 180/120. Rest 5 minutes and measure again. If it stays this high, or with chest pain, shortness of breath, " +
                    "weakness or trouble speaking, get medical help now.",
                style = FTType.BodySmall, color = FT.Bad,
            )
        }
        SaveButton(saving, canSave) {
            val rows = listOfNotNull(first, second.takeIf { !it.blank }).mapIndexed { i, r ->
                NewBpReadingRow(
                    occurredAt = dateTime.plusMinutes(i.toLong()).toAppIso(),
                    systolic = r.systolic!!,
                    diastolic = r.diastolic!!,
                    pulse = r.pulseValue,
                    arm = arm,
                    position = initial?.position ?: "seated",
                    onBpMeds = initial?.onBpMeds,
                    note = initial?.note,
                )
            }
            onSave(rows)
        }
    }
}
