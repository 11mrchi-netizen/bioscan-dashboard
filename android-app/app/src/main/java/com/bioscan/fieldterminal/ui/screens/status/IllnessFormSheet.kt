package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.HealthEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.MedicationEntry
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class MedicationEntryDraft(val name: String = "", val dose: String = "", val frequency: String = "")

private val illnessSheetHeaderStyle = FTType.SectionTitle
private val illnessSheetLabelStyle = FTType.LabelCaps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IllnessFormSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { HealthEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()

    var date by remember { mutableStateOf(LocalDate.now()) }
    var name by remember { mutableStateOf("") }
    var symptoms by remember { mutableStateOf("") }
    var doctorSeen by remember { mutableStateOf(false) }
    val medications = remember { mutableStateListOf<MedicationEntryDraft>() }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("LOG ILLNESS", style = illnessSheetHeaderStyle, color = FT.Info)

            DateField("START DATE", date, { date = it })

            Column {
                IllnessFormFieldLabel("NAME")
                FieldTextField(name, { name = it }, "e.g. Flu, Sinusitis, Strep Throat")
            }

            Column {
                IllnessFormFieldLabel("SYMPTOMS (OPTIONAL)")
                FieldTextField(symptoms, { symptoms = it }, "e.g. Fever, sore throat, fatigue", singleLine = false)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IllnessFormFieldLabel("DOCTOR SEEN")
                Switch(
                    checked = doctorSeen,
                    onCheckedChange = { doctorSeen = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FT.Emerald,
                        checkedTrackColor = FT.Emerald.copy(alpha = 0.3f),
                        uncheckedThumbColor = FT.TextSecondary,
                        uncheckedTrackColor = FT.GlassFill,
                    ),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IllnessFormFieldLabel("MEDICATIONS")
                medications.forEachIndexed { i, entry ->
                    val entryShape = RoundedCornerShape(FT.RadiusCard)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(FT.BorderWidth, FT.GlassBorder, entryShape)
                            .background(FT.GlassFill, entryShape)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                IllnessFormFieldLabel("MEDICINE NAME")
                                FieldTextField(entry.name, { medications[i] = entry.copy(name = it) }, "e.g. Amoxicillin")
                            }
                            Text(
                                "REMOVE",
                                style = illnessSheetLabelStyle.copy(fontSize = 10.sp),
                                color = FT.Critical,
                                modifier = Modifier
                                    .padding(top = 18.dp)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        medications.removeAt(i)
                                    },
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                IllnessFormFieldLabel("DOSE")
                                FieldTextField(entry.dose, { medications[i] = entry.copy(dose = it) }, "e.g. 500mg")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                IllnessFormFieldLabel("FREQUENCY")
                                FieldTextField(entry.frequency, { medications[i] = entry.copy(frequency = it) }, "e.g. 3×/day")
                            }
                        }
                    }
                }
                Text(
                    "+ ADD MEDICATION",
                    style = illnessSheetLabelStyle,
                    color = FT.Info,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        medications.add(MedicationEntryDraft())
                    },
                )
            }

            Column {
                IllnessFormFieldLabel("NOTES (OPTIONAL)")
                FieldTextField(notes, { notes = it }, "Anything else worth noting", singleLine = false)
            }

            error?.let {
                Text("Couldn't save ($it).", style = FTType.Caption, color = FT.Critical)
            }

            val valid = name.isNotBlank()
            AmberButton(label = if (saving) "SAVING..." else "SAVE") {
                if (valid && !saving) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            repo.addIllness(
                                name = name.trim(),
                                symptoms = symptoms.trim().ifBlank { null },
                                startDate = date,
                                doctorSeen = doctorSeen,
                                medications = medications
                                    .filter { it.name.isNotBlank() }
                                    .map { MedicationEntry(it.name.trim(), it.dose.trim(), it.frequency.trim()) },
                                notes = notes.trim().ifBlank { null },
                            )
                            onSaved()
                        } catch (e: Exception) {
                            error = e.message ?: "Unknown error"
                        } finally {
                            saving = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun IllnessFormFieldLabel(text: String) {
    Text(text, style = illnessSheetLabelStyle, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}
