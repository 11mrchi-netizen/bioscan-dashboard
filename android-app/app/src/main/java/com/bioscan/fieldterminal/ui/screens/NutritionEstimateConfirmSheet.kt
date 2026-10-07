package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.CronometerEnrichment
import com.bioscan.fieldterminal.data.MealItemSource
import com.bioscan.fieldterminal.data.NutritionMealEstimate
import com.bioscan.fieldterminal.data.NutritionMealSaveRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDateTime

// User request (2026-09-30): replaces NutritionCandidateReviewSheet for the
// photo-estimation path only -- one whole-meal approximation to confirm
// (and correct, if visibly off) and save, not N candidates each requiring a
// database-search match. Text-description/barcode logging still use the
// review sheet unchanged.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionEstimateConfirmSheet(
    estimate: NutritionMealEstimate,
    mealDateTime: LocalDateTime,
    aiEstimateId: Long?,
    enrichment: CronometerEnrichment? = null,
    enriching: Boolean = false,
    source: MealItemSource = MealItemSource.AiImage,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val effectiveCal = enrichment?.calories ?: estimate.calories
    val effectivePro = enrichment?.proteinG ?: estimate.proteinG
    val effectiveCarb = enrichment?.carbsG ?: estimate.carbsG
    val effectiveFat = enrichment?.fatG ?: estimate.fatG
    val effectiveFib = enrichment?.fiberG ?: estimate.fiberG
    val effectiveSug = enrichment?.sugarG ?: estimate.sugarG
    val effectiveSod = enrichment?.sodiumMg ?: estimate.sodiumMg

    var description by remember { mutableStateOf(estimate.description) }
    var calories by remember(effectiveCal) { mutableStateOf(effectiveCal.toString()) }
    var protein by remember(effectivePro) { mutableStateOf(effectivePro.toString()) }
    var carbs by remember(effectiveCarb) { mutableStateOf(effectiveCarb.toString()) }
    var fat by remember(effectiveFat) { mutableStateOf(effectiveFat.toString()) }
    var fiber by remember(effectiveFib) { mutableStateOf(effectiveFib?.toString() ?: "") }
    var sugar by remember(effectiveSug) { mutableStateOf(effectiveSug?.toString() ?: "") }
    var sodium by remember(effectiveSod) { mutableStateOf(effectiveSod?.toString() ?: "") }
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
            val headerTitle = when (source) {
                MealItemSource.AiText -> "TEXT LOOKUP"
                MealItemSource.Barcode -> "BARCODE LOOKUP"
                else -> "MEAL ESTIMATE"
            }
            Text(headerTitle, style = sheetHeaderTitleStyle, color = FT.Emerald)
            if (enrichment != null) {
                Text(
                    "ENRICHED WITH CRONOMETER",
                    style = FTType.LabelCaps,
                    color = FT.Emerald,
                )
                Text(
                    "Database-backed nutrition from ${enrichment.primarySource ?: "Cronometer"} (${enrichment.itemsIncluded} items matched). Edit anything that looks off.",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            } else if (enriching) {
                Text(
                    "ENRICHING WITH CRONOMETER...",
                    style = FTType.LabelCaps,
                    color = FT.TextSecondary,
                )
                Text(
                    "Looking up foods in database for more accurate nutrition. Gemini estimate shown below.",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            } else {
                Text(
                    "Approximate whole-meal estimate (confidence ${(estimate.confidence * 100).toInt()}%) -- edit anything that looks off before saving.",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            }

            Column { FormFieldLabel("DESCRIPTION"); FieldTextField(description, { description = it }, "Meal description") }
            Column { FormFieldLabel("CALORIES"); FieldTextField(calories, { calories = it }, "0", keyboardType = KeyboardType.Number) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { FormFieldLabel("PROTEIN G"); FieldTextField(protein, { protein = it }, "0", keyboardType = KeyboardType.Number) }
                Column(Modifier.weight(1f)) { FormFieldLabel("CARBS G"); FieldTextField(carbs, { carbs = it }, "0", keyboardType = KeyboardType.Number) }
                Column(Modifier.weight(1f)) { FormFieldLabel("FAT G"); FieldTextField(fat, { fat = it }, "0", keyboardType = KeyboardType.Number) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { FormFieldLabel("FIBER G"); FieldTextField(fiber, { fiber = it }, "0", keyboardType = KeyboardType.Number) }
                Column(Modifier.weight(1f)) { FormFieldLabel("SUGAR G"); FieldTextField(sugar, { sugar = it }, "0", keyboardType = KeyboardType.Number) }
                Column(Modifier.weight(1f)) { FormFieldLabel("SODIUM MG"); FieldTextField(sodium, { sodium = it }, "0", keyboardType = KeyboardType.Number) }
            }

            error?.let {
                Text("Couldn't save ($it).", style = FTType.Caption, color = FT.Critical)
            }

            val caloriesValue = calories.toDoubleOrNull()
            val proteinValue = protein.toDoubleOrNull()
            val carbsValue = carbs.toDoubleOrNull()
            val fatValue = fat.toDoubleOrNull()
            val valid = description.isNotBlank() && caloriesValue != null && proteinValue != null && carbsValue != null && fatValue != null

            AmberButton(label = if (saving) "SAVING..." else "SAVE", enabled = valid && !saving) {
                if (valid && !saving) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val finalEstimate = NutritionMealEstimate(
                                description = description.trim(),
                                calories = caloriesValue!!,
                                proteinG = proteinValue!!,
                                carbsG = carbsValue!!,
                                fatG = fatValue!!,
                                fiberG = fiber.toDoubleOrNull(),
                                sugarG = sugar.toDoubleOrNull(),
                                sodiumMg = sodium.toDoubleOrNull(),
                                confidence = estimate.confidence,
                            )
                            val scaledEnrichment = enrichment?.let { e ->
                                val croCal = e.calories
                                if (croCal != null && croCal > 0) {
                                    val scale = caloriesValue!! / croCal
                                    e.copy(
                                        calories = caloriesValue,
                                        proteinG = proteinValue,
                                        carbsG = carbsValue,
                                        fatG = fatValue,
                                        fiberG = fiber.toDoubleOrNull(),
                                        sugarG = sugar.toDoubleOrNull(),
                                        sodiumMg = sodium.toDoubleOrNull(),
                                        allNutrients = e.allNutrients.mapValues { (key, v) ->
                                            when (key) {
                                                "calories" -> caloriesValue!!
                                                "protein" -> proteinValue!!
                                                "carbs" -> carbsValue!!
                                                "fat" -> fatValue!!
                                                "fiber" -> fiber.toDoubleOrNull() ?: (v * scale)
                                                "sugar" -> sugar.toDoubleOrNull() ?: (v * scale)
                                                "sodium" -> sodium.toDoubleOrNull() ?: (v * scale)
                                                else -> Math.round(v * scale * 100.0) / 100.0
                                            }
                                        },
                                    )
                                } else e
                            }
                            NutritionMealSaveRepository(SupabaseClientProvider.client)
                                .saveEstimatedMeal(mealDateTime.toIsoWithOffset(), finalEstimate, aiEstimateId, scaledEnrichment, source)
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

private fun LocalDateTime.toIsoWithOffset(): String = this.atOffset(java.time.ZoneOffset.UTC).toString()
