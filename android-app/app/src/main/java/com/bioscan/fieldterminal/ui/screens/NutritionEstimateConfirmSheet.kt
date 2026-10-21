package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.CronometerAlternative
import com.bioscan.fieldterminal.data.CronometerEnrichment
import com.bioscan.fieldterminal.data.CronometerItemInput
import com.bioscan.fieldterminal.data.CronometerItemResult
import com.bioscan.fieldterminal.data.MealItemSource
import com.bioscan.fieldterminal.data.NutritionCronometerLookupRepository
import com.bioscan.fieldterminal.data.NutritionMealEstimate
import com.bioscan.fieldterminal.data.NutritionMealSaveRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.calories
import com.bioscan.fieldterminal.data.counts
import com.bioscan.fieldterminal.data.usingDatabaseMatch
import com.bioscan.fieldterminal.data.withGrams
import com.bioscan.fieldterminal.data.withItems
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

// One whole-meal approximation to confirm (and correct, if visibly off) and save. Photo, text and barcode entries all
// land here. With Cronometer enrichment, each matched food is listed so a wrong match or portion is visible and
// fixable (pick another food, change grams, remove it) before anything is saved.
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

    // The user's item edits; reset whenever a new lookup result arrives.
    var current by remember(enrichment) { mutableStateOf(enrichment) }
    var swappingIndex by remember(enrichment) { mutableStateOf<Int?>(null) }
    var swapError by remember(enrichment) { mutableStateOf<String?>(null) }

    val effectiveCal = current?.calories ?: estimate.calories
    val effectivePro = current?.proteinG ?: estimate.proteinG
    val effectiveCarb = current?.carbsG ?: estimate.carbsG
    val effectiveFat = current?.fatG ?: estimate.fatG
    val effectiveFib = current?.fiberG ?: estimate.fiberG
    val effectiveSug = current?.sugarG ?: estimate.sugarG
    val effectiveSod = current?.sodiumMg ?: estimate.sodiumMg

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

    fun replaceItem(index: Int, item: CronometerItemResult?, extraUnits: Map<String, String> = emptyMap()) {
        val e = current ?: return
        val items = e.items.toMutableList()
        if (item == null) items.removeAt(index) else items[index] = item
        current = e.withItems(items, extraUnits)
    }

    fun pickAlternative(index: Int, alternative: CronometerAlternative) {
        val original = current?.items?.getOrNull(index) ?: return
        swappingIndex = index
        swapError = null
        scope.launch {
            try {
                val looked = NutritionCronometerLookupRepository(SupabaseClientProvider.client)
                    .enrichItems(listOf(CronometerItemInput(original.query, original.quantityG, alternative.foodId)))
                val picked = looked.items.firstOrNull() ?: throw IllegalStateException("no result")
                val previous = original.cronometerFoodId?.let { id ->
                    CronometerAlternative(id, original.cronometerFoodName ?: original.query, original.cronometerSource, null)
                }
                replaceItem(
                    index,
                    picked.copy(
                        memoryKey = original.memoryKey,
                        portionCount = original.portionCount,
                        portionUnit = original.portionUnit,
                        aiEstimate = original.aiEstimate,
                        usedEstimate = false,
                        disagreement = false,
                        alternatives = (listOfNotNull(previous) + original.alternatives).filter { it.foodId != alternative.foodId },
                        userEdited = true,
                    ),
                    looked.nutrientUnits,
                )
            } catch (e: Exception) {
                swapError = e.message ?: "lookup failed"
            } finally {
                swappingIndex = null
            }
        }
    }

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
            val e = current
            if (e != null) {
                Text("ENRICHED WITH CRONOMETER", style = FTType.LabelCaps, color = FT.Emerald)
                Text(
                    "Database-backed nutrition from ${e.primarySource ?: "Cronometer"} (${e.itemsIncluded} of ${e.items.size} items counted, " +
                        "confidence ${(e.confidence * 100).roundToInt()}%). Check each food and portion below.",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
                CrossCheckNote(e)
                e.items.forEachIndexed { index, item ->
                    MatchedItemRow(
                        index = index,
                        item = item,
                        swapping = swappingIndex == index,
                        onGrams = { grams -> replaceItem(index, item.withGrams(grams)) },
                        onPick = { alt -> pickAlternative(index, alt) },
                        onUseDatabase = { replaceItem(index, item.usingDatabaseMatch()) },
                        onRemove = if (e.items.size > 1) ({ replaceItem(index, null) }) else null,
                    )
                }
                swapError?.let { Text("Couldn't load that food ($it).", style = FTType.Caption, color = FT.Critical) }
            } else if (enriching) {
                Text("ENRICHING WITH CRONOMETER...", style = FTType.LabelCaps, color = FT.TextSecondary)
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

            AmberButton(label = if (saving) "SAVING..." else "SAVE", enabled = valid && !saving && swappingIndex == null) {
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
                                confidence = current?.confidence ?: estimate.confidence,
                            )
                            val scaledEnrichment = current?.let { e ->
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

// The independent AI estimate next to the database total, called out when they are far apart.
@Composable
private fun CrossCheckNote(e: CronometerEnrichment) {
    val ai = e.aiEstimateTotal ?: return
    val db = e.calories ?: return
    val far = abs(db - ai) > 100 && (db > ai * 1.5 || db < ai / 1.5)
    Text(
        if (far) "Database total ${db.roundToInt()} kcal vs AI estimate ~${ai.roundToInt()} kcal -- check the foods and portions below."
        else "AI cross-check: ~${ai.roundToInt()} kcal.",
        style = FTType.Caption,
        color = if (far) FT.Warning else FT.TextMuted,
    )
}

@Composable
private fun MatchedItemRow(
    index: Int,
    item: CronometerItemResult,
    swapping: Boolean,
    onGrams: (Double) -> Unit,
    onPick: (CronometerAlternative) -> Unit,
    onUseDatabase: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    var showAlternatives by remember(index, item.cronometerFoodId) { mutableStateOf(false) }
    // Keyed on the food, not the grams, so typing "18." is not reformatted under the cursor.
    var gramsText by remember(index, item.cronometerFoodId) { mutableStateOf(formatGrams(item.quantityG)) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(item.cronometerFoodName ?: "No database match", style = FTType.RowTitle, color = FT.TextPrimary)
        val kcal = item.calories()
        Text(
            "for \"${item.query}\" · ${item.servingUsed ?: "${formatGrams(item.quantityG)} g"}" +
                (kcal?.let { " · ${it.roundToInt()} kcal" } ?: "") +
                (item.cronometerSource?.let { " · $it" } ?: ""),
            style = FTType.Caption,
            color = FT.TextMuted,
        )
        statusLine(item)?.let { (text, warn) ->
            Text(text, style = FTType.CaptionStrong, color = if (warn) FT.Warning else FT.Emerald)
        }
        if (item.rejected.isNotEmpty()) {
            Text("Passed over: ${item.rejected.joinToString("; ")}", style = FTType.Caption, color = FT.TextMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.counts() && item.quantityG > 0) {
                FieldTextField(
                    gramsText,
                    { text ->
                        gramsText = text
                        text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let(onGrams)
                    },
                    "g",
                    modifier = Modifier.width(96.dp),
                    keyboardType = KeyboardType.Number,
                )
                Text("G", style = FTType.LabelCaps, color = FT.TextSecondary)
            }
            if (swapping) {
                Text("LOADING...", style = FTType.LabelCaps, color = FT.TextSecondary)
            } else if (item.alternatives.isNotEmpty()) {
                ActionLink(if (showAlternatives) "HIDE" else "CHANGE FOOD") { showAlternatives = !showAlternatives }
            }
            if (item.usedEstimate && item.cronometerFoodId != null) ActionLink("USE DATABASE", onUseDatabase)
            onRemove?.let { ActionLink("REMOVE", it) }
        }
        if (showAlternatives && !swapping) {
            item.alternatives.forEach { alt ->
                Text(
                    "${alt.name}" + (alt.kcalPer100g?.let { " · ${it.roundToInt()} kcal/100 g" } ?: "") + (alt.source?.let { " · $it" } ?: ""),
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            showAlternatives = false
                            onPick(alt)
                        }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

private fun statusLine(item: CronometerItemResult): Pair<String, Boolean>? = when {
    item.usedEstimate && item.aiEstimate != null ->
        "Using AI estimate (~${item.aiEstimate.calories.roundToInt()} kcal) -- the database match looked wrong" to true
    !item.counts() -> "Not counted -- no reliable database match" to true
    item.disagreement && item.aiEstimate != null ->
        "AI estimate is ~${item.aiEstimate.calories.roundToInt()} kcal -- check the portion" to true
    item.userEdited -> "Your edit" to false
    item.fromMemory -> "Your usual choice" to false
    else -> null
}

private fun formatGrams(g: Double): String = if (g == Math.floor(g)) g.toLong().toString() else "%.1f".format(g)

@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = FTType.LabelCaps,
        color = FT.Emerald,
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    )
}

private val sheetHeaderTitleStyle = FTType.SectionTitle
private val sheetActionLabelStyle = FTType.LabelCaps

@Composable
private fun FormFieldLabel(text: String) {
    Text(text, style = sheetActionLabelStyle, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

private fun LocalDateTime.toIsoWithOffset(): String = this.atOffset(java.time.ZoneOffset.UTC).toString()
