package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.bioscan.fieldterminal.data.CronometerItemResult
import com.bioscan.fieldterminal.data.FoodMeasure
import com.bioscan.fieldterminal.data.FoodSuggestion
import com.bioscan.fieldterminal.data.FoodSuggestionRef
import com.bioscan.fieldterminal.data.MealItemSource
import com.bioscan.fieldterminal.data.NutritionCronometerLookupRepository
import com.bioscan.fieldterminal.data.NutritionMealEstimate
import com.bioscan.fieldterminal.data.NutritionMealSaveRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.calories
import com.bioscan.fieldterminal.data.counts
import com.bioscan.fieldterminal.data.emptyEnrichment
import com.bioscan.fieldterminal.data.formatAmount
import com.bioscan.fieldterminal.data.usingDatabaseMatch
import com.bioscan.fieldterminal.data.withGrams
import com.bioscan.fieldterminal.data.withItems
import com.bioscan.fieldterminal.data.withMeasure
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ADDING = -1

// One meal to confirm (and correct) before saving. Photo, text and barcode entries all land here. Each food is listed
// with its portion; any of them can be replaced by typing another food (with suggestions), new ingredients can be added
// (also to a photo estimate that had no database match), and portions can be set by serving, count or grams.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionEstimateConfirmSheet(
    estimate: NutritionMealEstimate,
    mealDateTime: LocalDateTime,
    // Which planned meal it was (meals.meal_slot), chosen on the food form.
    mealSlot: String? = null,
    aiEstimateId: Long?,
    enrichment: CronometerEnrichment? = null,
    enriching: Boolean = false,
    source: MealItemSource = MealItemSource.AiImage,
    // Photo logging v3: rerun the estimate on the same photos with a tapped answer, or with what the meal really is.
    rerunning: Boolean = false,
    // Every answer picked, sent together (one rerun for all the questions, not one per tap).
    onAnswer: ((List<com.bioscan.fieldterminal.data.PhotoAnswer>) -> Unit)? = null,
    // What the meal really is, with any answers picked so far.
    onCorrect: ((String, List<com.bioscan.fieldterminal.data.PhotoAnswer>) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val lookupRepo = remember { NutritionCronometerLookupRepository(SupabaseClientProvider.client) }

    // The user's item edits; reset whenever a new lookup result arrives.
    var current by remember(enrichment) { mutableStateOf(enrichment) }
    // Which item has its search field open (ADDING for a new ingredient), and which one is loading a picked food.
    var editing by remember(enrichment) { mutableStateOf<Int?>(null) }
    var busy by remember(enrichment) { mutableStateOf<Int?>(null) }
    var busyName by remember(enrichment) { mutableStateOf("") }
    var pickError by remember(enrichment) { mutableStateOf<String?>(null) }

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

    // Loads the picked food and puts it in place of item `target`, or adds it (target == ADDING). A replaced item keeps
    // its grams and its memory key, so the pick is remembered for what the user originally wrote.
    fun pickSuggestion(target: Int, typed: String, suggestion: FoodSuggestion) {
        val original = current?.items?.getOrNull(target)
        editing = null
        busy = target
        busyName = suggestion.name
        pickError = null
        scope.launch {
            try {
                val grams = original?.quantityG?.takeIf { it > 0 } ?: suggestion.defaultGrams
                val looked = lookupRepo.resolve(original?.query ?: typed, suggestion, grams)
                val picked = looked.items.firstOrNull() ?: throw IllegalStateException("no result")
                val base = current ?: emptyEnrichment()
                val items = base.items.toMutableList()
                if (original != null) {
                    val previous = original.cronometerFoodId?.let { id ->
                        CronometerAlternative(id, original.cronometerFoodName ?: original.query, original.cronometerSource, null)
                    }
                    items[target] = picked.copy(
                        memoryKey = original.memoryKey,
                        portionCount = original.portionCount,
                        portionUnit = original.portionUnit,
                        aiEstimate = original.aiEstimate,
                        usedEstimate = false,
                        disagreement = false,
                        alternatives = (listOfNotNull(previous) + original.alternatives + picked.alternatives)
                            .distinctBy { it.foodId }
                            .filter { it.foodId != picked.cronometerFoodId },
                        userEdited = true,
                    )
                } else {
                    items += picked.copy(userEdited = true)
                }
                current = base.withItems(items, looked.nutrientUnits)
            } catch (e: Exception) {
                pickError = e.message ?: "lookup failed"
            } finally {
                busy = null
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
            if (source == MealItemSource.AiImage) PhotoEstimatePanel(estimate, rerunning, onAnswer, onCorrect)
            val e = current
            if (e != null) {
                Text("FOODS", style = FTType.LabelCaps, color = FT.Emerald)
                Text(
                    "From ${sourceLabel(e.primarySource) ?: "the food database"} (${e.itemsIncluded} of ${e.items.size} items counted, " +
                        "confidence ${(e.confidence * 100).roundToInt()}%). Check each food and portion; replace or add anything that's off.",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
                if (enrichment == null) {
                    Text("Totals now come from the foods below, not the photo estimate.", style = FTType.Caption, color = FT.TextMuted)
                }
                CrossCheckNote(e)
                e.items.forEachIndexed { index, item ->
                    if (editing == index) {
                        FoodSuggestField(
                            initialQuery = item.query,
                            initial = item.alternatives.map {
                                FoodSuggestion(FoodSuggestionRef("cronometer", it.foodId), it.name, it.source, it.kcalPer100g)
                            },
                            placeholder = "Type the right food",
                            onPick = { typed, s -> pickSuggestion(index, typed, s) },
                            onCancel = { editing = null },
                        )
                    } else {
                        MatchedItemRow(
                            item = item,
                            loading = busy == index,
                            onPortion = { replaceItem(index, it) },
                            onReplace = { editing = index },
                            onUseDatabase = { replaceItem(index, item.usingDatabaseMatch()) },
                            onRemove = if (e.items.size > 1) ({ replaceItem(index, null) }) else null,
                        )
                        // Photo logging v3: what the photo saw for this food and what it assumed.
                        if (!item.userEdited) photoFoodFor(estimate, item.query)?.let { PhotoFoodNote(it) }
                    }
                }
            } else if (enriching) {
                Text("LOOKING UP FOODS...", style = FTType.LabelCaps, color = FT.TextSecondary)
                Text(
                    "Matching the foods in the database for more accurate nutrition. The AI estimate is shown below meanwhile.",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            } else {
                Text(
                    "Approximate whole-meal estimate (confidence ${(estimate.confidence * 100).toInt()}%) -- edit anything that looks off, " +
                        "or add the foods one by one for database numbers.",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            }

            if (!enriching || e != null) {
                when {
                    editing == ADDING -> FoodSuggestField(
                        initialQuery = "",
                        initial = emptyList(),
                        placeholder = "Add a food, e.g. olive oil",
                        onPick = { typed, s -> pickSuggestion(ADDING, typed, s) },
                        onCancel = { editing = null },
                    )
                    busy == ADDING -> Text("Adding $busyName...", style = FTType.Caption, color = FT.TextSecondary)
                    else -> ActionLink("+ ADD INGREDIENT") { editing = ADDING }
                }
            }
            pickError?.let { Text("Couldn't load that food ($it).", style = FTType.Caption, color = FT.Critical) }

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

            FTButton(
                label = if (saving) "SAVING..." else "SAVE",
                enabled = valid && !saving && busy == null && editing == null,
                emphasis = FTButtonEmphasis.Primary,
            ) {
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
                                .saveEstimatedMeal(mealDateTime.toIsoWithOffset(), finalEstimate, aiEstimateId, scaledEnrichment, source, mealSlot)
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
    item: CronometerItemResult,
    loading: Boolean,
    onPortion: (CronometerItemResult) -> Unit,
    onReplace: () -> Unit,
    onUseDatabase: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val foodKey = item.cronometerFoodId ?: item.catalogFoodId
    val hasFood = foodKey != null

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(item.cronometerFoodName ?: "No database match", style = FTType.RowTitle, color = FT.TextPrimary)
        val kcal = item.calories()
        Text(
            "for \"${item.query}\" · ${item.servingUsed ?: "${formatAmount(item.quantityG)} g"}" +
                (kcal?.let { " · ${it.roundToInt()} kcal" } ?: "") +
                (sourceLabel(item.cronometerSource, if (item.catalogFoodId != null) "catalog" else null)?.let { " · $it" } ?: ""),
            style = FTType.Caption,
            color = FT.TextMuted,
        )
        statusLine(item)?.let { (text, warn) ->
            Text(text, style = FTType.CaptionStrong, color = if (warn) FT.Warning else FT.Emerald)
        }
        if (item.rejected.isNotEmpty()) {
            Text("Passed over: ${item.rejected.joinToString("; ")}", style = FTType.Caption, color = FT.TextMuted)
        }
        if (hasFood && item.counts()) PortionEditor(item, onPortion)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                Text("LOADING...", style = FTType.LabelCaps, color = FT.TextSecondary)
            } else {
                ActionLink(if (hasFood) "REPLACE" else "FIND FOOD", onReplace)
            }
            if (item.usedEstimate && hasFood) ActionLink("USE DATABASE", onUseDatabase)
            onRemove?.let { ActionLink("REMOVE", it) }
        }
    }
}

// Serving chips from the food's own measures, a count stepper for the chosen serving, and grams for anything else.
@Composable
private fun PortionEditor(item: CronometerItemResult, onPortion: (CronometerItemResult) -> Unit) {
    val foodKey = item.cronometerFoodId ?: item.catalogFoodId
    val measures = remember(foodKey, item.allMeasures) {
        item.allMeasures.filter { it.grams > 0 }.distinctBy { it.name }.take(6)
    }
    var measure by remember(foodKey) { mutableStateOf<FoodMeasure?>(null) }
    var count by remember(foodKey) { mutableStateOf(1.0) }
    // Not reset by its own edits (typing "18." stays as typed); follows changes made by the chips and the stepper.
    var gramsText by remember(foodKey) { mutableStateOf(formatAmount(item.quantityG)) }
    LaunchedEffect(item.quantityG) {
        if (gramsText.replace(',', '.').toDoubleOrNull() != item.quantityG) gramsText = formatAmount(item.quantityG)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (measures.isNotEmpty()) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                measures.forEach { m ->
                    val selected = measure?.name == m.name
                    Text(
                        "${m.name} · ${formatAmount(m.grams)} g",
                        style = FTType.Caption,
                        color = if (selected) FT.Emerald else FT.TextSecondary,
                        modifier = Modifier
                            .border(FT.BorderWidth, if (selected) FT.Emerald else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                            .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall)) {
                                measure = m
                                onPortion(item.withMeasure(m, count))
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            measure?.let { m ->
                StepButton("−") {
                    count = (count - 0.5).coerceAtLeast(0.5)
                    onPortion(item.withMeasure(m, count))
                }
                Text("${formatAmount(count)} ×", style = FTType.Value, color = FT.TextPrimary)
                StepButton("+") {
                    count += 0.5
                    onPortion(item.withMeasure(m, count))
                }
            }
            FieldTextField(
                gramsText,
                { text ->
                    gramsText = text
                    text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let {
                        measure = null
                        onPortion(item.withGrams(it))
                    }
                },
                "g",
                modifier = Modifier.width(96.dp),
                keyboardType = KeyboardType.Number,
            )
            Text("G", style = FTType.LabelCaps, color = FT.TextSecondary)
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = FTType.Value,
        color = FT.Emerald,
        modifier = Modifier
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
            .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private fun statusLine(item: CronometerItemResult): Pair<String, Boolean>? = when {
    item.usedEstimate && item.aiEstimate != null ->
        "Using AI estimate (~${item.aiEstimate.calories.roundToInt()} kcal) -- the database match looked wrong" to true
    !item.counts() -> "Not counted -- no reliable database match; find the right food below" to true
    item.disagreement && item.aiEstimate != null ->
        "AI estimate is ~${item.aiEstimate.calories.roundToInt()} kcal -- check the portion" to true
    item.userEdited -> "Your edit" to false
    item.fromMemory -> "Your usual choice" to false
    else -> null
}

@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = FTType.LabelCaps,
        color = FT.Emerald,
        modifier = Modifier.ftClickable(onClick = onClick),
    )
}

private val sheetHeaderTitleStyle = FTType.SectionTitle
private val sheetActionLabelStyle = FTType.LabelCaps

@Composable
private fun FormFieldLabel(text: String) {
    Text(text, style = sheetActionLabelStyle, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

private fun LocalDateTime.toIsoWithOffset(): String = this.atOffset(java.time.ZoneOffset.UTC).toString()

// ---- Photo logging v3 ----

/** The photo's own record of a food the lookup matched (looked up by its generic name). */
private fun photoFoodFor(estimate: NutritionMealEstimate, query: String): com.bioscan.fieldterminal.data.PhotoFood? =
    estimate.items.firstOrNull { it.canonicalName.equals(query, ignoreCase = true) || it.name.equals(query, ignoreCase = true) }

private fun levelColor(level: String) = when (level) {
    "high" -> FT.Emerald
    "low" -> FT.Warning
    else -> FT.TextSecondary
}

/** Under a food: what the photo saw (name, portion, preparation), its confidence, and what it assumed. */
@Composable
private fun PhotoFoodNote(food: com.bioscan.fieldterminal.data.PhotoFood) {
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val seen = listOf(food.name, food.portion, food.preparation).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        Text(
            "PHOTO: $seen · ${food.grams.roundToInt()} g · ${food.confidence.uppercase()}",
            style = FTType.Micro, color = levelColor(food.confidence),
        )
        if (food.assumptions.isNotEmpty()) {
            Text("Assumed: " + food.assumptions.joinToString("; "), style = FTType.Caption, color = FT.TextMuted)
        }
    }
}

/**
 * The photo estimate's own say: its confidence and what limits it, up to three questions with answers to pick (a tap
 * picks or changes an answer; UPDATE THE ESTIMATE reruns once with every answer picked), and a box to say what the meal
 * really is (it reruns with that as the truth, and the answers picked).
 */
@Composable
private fun PhotoEstimatePanel(
    estimate: NutritionMealEstimate,
    rerunning: Boolean,
    onAnswer: ((List<com.bioscan.fieldterminal.data.PhotoAnswer>) -> Unit)?,
    onCorrect: ((String, List<com.bioscan.fieldterminal.data.PhotoAnswer>) -> Unit)?,
) {
    var correction by remember(estimate) { mutableStateOf("") }
    var picked by remember(estimate) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val answers = picked.map { (q, a) -> com.bioscan.fieldterminal.data.PhotoAnswer(q, a) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        estimate.confidenceLevel?.let { level ->
            Text(
                "PHOTO CONFIDENCE · ${level.uppercase()}" + (estimate.confidenceReason?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = FTType.Caption, color = levelColor(level),
            )
        }
        if (rerunning) {
            Text("UPDATING THE ESTIMATE…", style = FTType.LabelCaps, color = FT.TextSecondary)
        } else if (onAnswer != null && estimate.questions.isNotEmpty()) {
            Text(
                if (estimate.questions.size > 1) "A FEW QUICK ANSWERS MAKE IT MORE ACCURATE" else "A QUICK ANSWER MAKES IT MORE ACCURATE",
                style = FTType.LabelCaps, color = FT.TextSecondary,
            )
            estimate.questions.forEach { q ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(q.question, style = FTType.BodySmall, color = FT.TextPrimary)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        q.options.forEach { option ->
                            val on = picked[q.question] == option
                            val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
                            Text(
                                option,
                                style = FTType.Label,
                                color = if (on) FT.PlatinumInk else FT.Emerald,
                                modifier = Modifier
                                    .then(if (on) Modifier.background(FT.PlatinumSolid, shape) else Modifier.border(FT.BorderWidth, FT.Emerald.copy(alpha = 0.6f), shape))
                                    // a tap picks the answer, a second tap clears it; nothing reruns until UPDATE
                                    .ftClickable(shape = shape) {
                                        picked = if (on) picked - q.question else picked + (q.question to option)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            if (picked.isNotEmpty()) {
                val left = estimate.questions.count { it.question !in picked }
                FTButton(
                    "UPDATE THE ESTIMATE" + if (left > 0) " · $left UNANSWERED" else "",
                    modifier = Modifier.fillMaxWidth(),
                    emphasis = FTButtonEmphasis.Primary,
                ) { onAnswer(answers) }
            }
        }
        if (onCorrect != null && !rerunning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldTextField(correction, { correction = it }, "Not right? Say what it is", modifier = Modifier.weight(1f))
                if (correction.isNotBlank()) ActionLink("REDO") { onCorrect(correction.trim(), answers) }
            }
        }
    }
}
