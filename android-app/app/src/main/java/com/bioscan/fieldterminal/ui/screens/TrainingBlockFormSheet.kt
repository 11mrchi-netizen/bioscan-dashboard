package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.domain.FocusQuality
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDate

// User-requested editability for the training block once training_cycles
// started getting real rows (manual entry here + a separate Notion
// historical import). `cycle == null` is add mode; otherwise edit. Only a
// single stated focus (role=Primary) is editable here -- see
// TrainingCyclesRepository's own comment on why DAV-291's full
// concurrent-multi-focus shape isn't editable through this form yet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingBlockFormSheet(cycle: TrainingCycle?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { TrainingCyclesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()

    var focusText by remember { mutableStateOf(cycle?.focus?.firstOrNull()?.quality?.name?.replace(Regex("(?<=.)(?=\\p{Upper})"), " ") ?: "") }
    var startDate by remember { mutableStateOf(cycle?.startDate ?: LocalDate.now()) }
    var ongoing by remember { mutableStateOf(cycle?.endDate == null) }
    var endDate by remember { mutableStateOf(cycle?.endDate ?: LocalDate.now()) }
    var goalMetric by remember { mutableStateOf(cycle?.goalMetric ?: "") }
    var startingValue by remember { mutableStateOf(cycle?.startingValue?.toString() ?: "") }
    var targetValue by remember { mutableStateOf(cycle?.targetValue?.toString() ?: "") }
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
            Text(if (cycle == null) "ADD TRAINING BLOCK" else "EDIT TRAINING BLOCK", style = sheetHeaderTitleStyle, color = FT.Emerald)

            Column { FormFieldLabel("FOCUS (OPTIONAL)"); FieldTextField(focusText, { focusText = it }, "e.g. Aerobic Base, Strength") }
            DateField("START DATE", startDate, { startDate = it })
            ToggleRow("ONGOING (NO END DATE)", ongoing) { ongoing = !ongoing }
            if (!ongoing) {
                DateField("END DATE", endDate, { endDate = it })
            }
            Column { FormFieldLabel("GOAL METRIC (OPTIONAL)"); FieldTextField(goalMetric, { goalMetric = it }, "e.g. weekly_mileage_km") }
            Column { FormFieldLabel("STARTING VALUE"); FieldTextField(startingValue, { startingValue = it }, "e.g. 30", keyboardType = KeyboardType.Decimal) }
            Column { FormFieldLabel("TARGET VALUE"); FieldTextField(targetValue, { targetValue = it }, "e.g. 60", keyboardType = KeyboardType.Decimal) }

            error?.let {
                Text(it, style = FTType.Caption, color = FT.Critical)
            }

            val hasGoal = goalMetric.isNotBlank()
            val startingNum = startingValue.toDoubleOrNull()
            val targetNum = targetValue.toDoubleOrNull()
            val goalValid = !hasGoal || (startingNum != null && targetNum != null)
            val focusQuality = focusText.takeIf { it.isNotBlank() }?.let { FocusQuality.fromLabel(it) }
            val focusValid = focusText.isBlank() || focusQuality != null

            AmberButton(label = if (saving) "SAVING..." else "SAVE") {
                if (!goalValid) {
                    error = "Set both a starting and target value, or clear the goal metric."
                } else if (!focusValid) {
                    error = "Didn't recognize that focus -- try e.g. \"Aerobic Base\" or \"Strength\"."
                } else if (!saving) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val resolvedEnd = if (ongoing) null else endDate
                            val resolvedGoal = goalMetric.trim().ifBlank { null }
                            if (cycle == null) {
                                repo.insertCycle(startDate, resolvedEnd, focusQuality, resolvedGoal, startingNum, targetNum)
                            } else {
                                repo.updateCycle(cycle.id, startDate, resolvedEnd, focusQuality, resolvedGoal, startingNum, targetNum)
                            }
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

private val sheetHeaderTitleStyle = FTType.SectionTitle
private val sheetActionLabelStyle = FTType.LabelCaps

@Composable
private fun FormFieldLabel(text: String) {
    Text(text, style = sheetActionLabelStyle, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, if (checked) FT.Emerald else FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .background(if (checked) FT.Emerald.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(FT.RadiusModule))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.size(18.dp).border(1.dp, if (checked) FT.Emerald else FT.GlassBorder)
                .background(if (checked) FT.Emerald else androidx.compose.ui.graphics.Color.Transparent),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) Text("✓", style = TextStyle(fontSize = 12.sp), color = FT.Base)
        }
        Text(label, style = sheetActionLabelStyle, color = FT.TextPrimary)
    }
}
