package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrainingBlockFormSheet(cycle: TrainingCycle?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { TrainingCyclesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()

    var templateName by remember { mutableStateOf(cycle?.tbTemplate ?: "") }
    var startDate by remember { mutableStateOf(cycle?.startDate ?: LocalDate.now()) }
    val defaultWeeks = cycle?.endDate
        ?.let { ChronoUnit.WEEKS.between(cycle.startDate, it).toInt().coerceAtLeast(1) }
        ?: 6
    var weeks by remember { mutableStateOf(defaultWeeks.toString()) }
    var primaryFocus by remember { mutableStateOf(cycle?.tbPrimary?.let { TbFocus.fromKey(it) }) }
    var secondaryFocus by remember { mutableStateOf(cycle?.tbSecondary?.let { TbFocus.fromKey(it) }) }
    var strengthDays by remember { mutableIntStateOf(cycle?.tbStrengthDays ?: 3) }
    var conditioningDays by remember { mutableIntStateOf(cycle?.tbConditioningDays ?: 2) }
    var goalMetric by remember { mutableStateOf(cycle?.goalMetric ?: "") }
    var startingValue by remember { mutableStateOf(cycle?.startingValue?.toString() ?: "") }
    var targetValue by remember { mutableStateOf(cycle?.targetValue?.toString() ?: "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val weeksInt = weeks.toIntOrNull()?.coerceIn(1, 52) ?: 1
    val endDate = startDate.plusWeeks(weeksInt.toLong())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                if (cycle == null) "ADD TRAINING BLOCK" else "EDIT TRAINING BLOCK",
                style = sheetHeaderStyle,
                color = FT.Emerald,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel("TEMPLATE NAME")
                FieldTextField(templateName, { templateName = it }, "e.g. Operator, Base Building")
            }

            DateField("START DATE", startDate, { startDate = it })

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel("DURATION (WEEKS)")
                FieldTextField(
                    weeks,
                    { weeks = it.filter { c -> c.isDigit() } },
                    "e.g. 6",
                    keyboardType = KeyboardType.Number,
                )
            }
            Text(
                "Ends $endDate",
                style = TextStyle(fontFamily = RobotoMono, fontSize = 11.5.sp),
                color = FT.TextMuted,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormLabel("PRIMARY FOCUS *")
                FocusChipGrid(
                    selected = primaryFocus,
                    exclude = secondaryFocus,
                    onSelect = { primaryFocus = if (primaryFocus == it) null else it },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormLabel("SECONDARY FOCUS (OPTIONAL)")
                FocusChipGrid(
                    selected = secondaryFocus,
                    exclude = primaryFocus,
                    onSelect = { secondaryFocus = if (secondaryFocus == it) null else it },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormLabel("STR DAYS/WEEK")
                    DaysStepper(strengthDays, { if (strengthDays > 0) strengthDays-- }, { if (strengthDays < 7) strengthDays++ })
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormLabel("COND DAYS/WEEK")
                    DaysStepper(conditioningDays, { if (conditioningDays > 0) conditioningDays-- }, { if (conditioningDays < 7) conditioningDays++ })
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel("GOAL METRIC (OPTIONAL)")
                FieldTextField(goalMetric, { goalMetric = it }, "e.g. weekly_mileage_km")
            }
            if (goalMetric.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FormLabel("STARTING VALUE")
                        FieldTextField(startingValue, { startingValue = it }, "e.g. 30", keyboardType = KeyboardType.Decimal)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FormLabel("TARGET VALUE")
                        FieldTextField(targetValue, { targetValue = it }, "e.g. 60", keyboardType = KeyboardType.Decimal)
                    }
                }
            }

            error?.let {
                Text(it, style = FTType.Caption, color = FT.Critical)
            }

            val hasGoal = goalMetric.isNotBlank()
            val startingNum = startingValue.toDoubleOrNull()
            val targetNum = targetValue.toDoubleOrNull()
            val goalValid = !hasGoal || (startingNum != null && targetNum != null)

            AmberButton(label = if (saving) "SAVING..." else "SAVE") {
                if (primaryFocus == null) {
                    error = "Select a primary focus."
                } else if (!goalValid) {
                    error = "Set both a starting and target value, or clear the goal metric."
                } else if (!saving) {
                    saving = true
                    error = null
                    val notesJson = buildJsonObject {
                        putJsonObject("notion_import_focus") {
                            put("source", "user")
                            templateName.trim().takeIf { it.isNotBlank() }?.let { put("template", it) }
                            put("primary", primaryFocus!!.key)
                            secondaryFocus?.let { put("secondary", it.key) }
                            put("strength_days", strengthDays)
                            put("conditioning_days", conditioningDays)
                        }
                    }.toString()
                    val resolvedGoal = goalMetric.trim().ifBlank { null }
                    scope.launch {
                        try {
                            if (cycle == null) {
                                repo.insertTbCycle(startDate, endDate, notesJson, resolvedGoal, startingNum, targetNum)
                            } else {
                                repo.updateTbCycle(cycle.id, startDate, endDate, notesJson, resolvedGoal, startingNum, targetNum)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusChipGrid(selected: TbFocus?, exclude: TbFocus?, onSelect: (TbFocus) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TbFocus.entries.forEach { focus ->
            val isSelected = selected == focus
            val isExcluded = exclude == focus
            val borderColor = if (isSelected) FT.Emerald else FT.GlassBorder
            val bgColor = if (isSelected) FT.Emerald.copy(alpha = 0.12f) else Color.Transparent
            val textColor = when {
                isExcluded -> FT.TextMuted.copy(alpha = 0.35f)
                isSelected -> FT.Emerald
                else -> FT.TextMuted
            }
            Text(
                text = focus.label,
                style = chipLabelStyle,
                color = textColor,
                modifier = Modifier
                    .border(FT.BorderWidth, borderColor, RoundedCornerShape(FT.RadiusSmall))
                    .background(bgColor, RoundedCornerShape(FT.RadiusSmall))
                    .then(
                        if (!isExcluded) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(focus) } else Modifier,
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun DaysStepper(value: Int, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "−",
            style = stepperButtonStyle,
            color = if (value > 0) FT.TextPrimary else FT.TextMuted,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDecrement,
            ),
        )
        Text(value.toString(), style = stepperValueStyle, color = FT.TextPrimary)
        Text(
            "+",
            style = stepperButtonStyle,
            color = if (value < 7) FT.TextPrimary else FT.TextMuted,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onIncrement,
            ),
        )
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, style = formLabelStyle, color = FT.TextSecondary)
}

private val sheetHeaderStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private val formLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)
private val chipLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
private val stepperButtonStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
private val stepperValueStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 18.sp)

private enum class TbFocus(val label: String, val key: String) {
    MaxStrength("Max Strength", "max_strength"),
    StrengthMaintenance("Str Maintenance", "strength_maintenance"),
    Hypertrophy("Hypertrophy", "hypertrophy"),
    WorkCapacity("Work Capacity", "work_capacity"),
    AerobicBase("Aerobic Base", "aerobic_base"),
    Vo2Max("VO2max", "vo2max"),
    Threshold("Threshold", "threshold"),
    Endurance("Endurance", "endurance"),
    SpeedPower("Speed & Power", "speed_power"),
    Deload("Deload", "deload"),
    ;
    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}
