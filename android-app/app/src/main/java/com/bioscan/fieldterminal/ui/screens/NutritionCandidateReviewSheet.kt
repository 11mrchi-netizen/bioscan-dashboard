package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.ConfirmedMealItem
import com.bioscan.fieldterminal.data.MealItemSource
import com.bioscan.fieldterminal.data.NutritionFoodSearchRepository
import com.bioscan.fieldterminal.data.NutritionMealSaveRepository
import com.bioscan.fieldterminal.data.NutritionResolverRepository
import com.bioscan.fieldterminal.data.ResolvedMealItem
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.FoodRow
import com.bioscan.fieldterminal.data.model.FoodServingRow
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

// DAV-168. The single review/correction step every candidate source
// (barcode/text/image) funnels through before a real meal_items row exists
// -- built once, driven by a source-agnostic ReviewSeedItem rather than
// three separate review UIs. Every item's totals come from
// NutritionResolverRepository (DAV-164); this file never computes a
// nutrient value itself, matching the same boundary the resolver's own
// design doc draws.

// Local copies of AddEntrySheet.kt's own sheet-chrome styles/helpers --
// those stay file-private there (MapScreen.kt independently keeps its own
// copies too), so this file follows the same established per-file pattern
// rather than widening visibility across files for a few one-line helpers.
private val sheetHeaderTitleStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private val sheetActionLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)

@Composable
private fun SheetBackHeader(label: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "BACK",
            style = sheetActionLabelStyle,
            color = FT.TextSecondary,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
        )
        Text(label, style = sheetHeaderTitleStyle, color = FT.DomainLog)
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, style = sheetActionLabelStyle, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SaveButton(saving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    AmberButton(label = if (saving) "SAVING..." else "SAVE") {
        if (enabled && !saving) onClick()
    }
}

data class ReviewSeedItem(
    val description: String,
    val quantityValue: Double? = null,
    val quantityUnit: String? = null,
    val quantityLow: Double? = null,
    val quantityHigh: Double? = null,
    val isBeverage: Boolean = false,
    val foodConfidence: Double? = null,
    val portionConfidence: Double? = null,
    val ambiguous: Boolean = false,
    val source: MealItemSource,
    val aiEstimateId: Long? = null,
    val preMatchedFood: FoodRow? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionCandidateReviewSheet(
    seedItems: List<ReviewSeedItem>,
    mealDateTime: LocalDateTime,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val supabase = SupabaseClientProvider.client
    val searchRepo = remember { NutritionFoodSearchRepository(supabase) }
    val resolverRepo = remember { NutritionResolverRepository(supabase) }
    val saveRepo = remember { NutritionMealSaveRepository(supabase, resolverRepo) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var rows by remember { mutableStateOf(seedItems.mapIndexed { i, seed -> i.toString() to seed }) }
    var nextKey by remember { mutableStateOf(seedItems.size) }
    val confirmed = remember { mutableStateMapOf<String, ConfirmedMealItem>() }
    var description by remember { mutableStateOf(seedItems.joinToString(", ") { it.description }.ifBlank { "Meal" }) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetBackHeader(label = "REVIEW & CONFIRM", onBack = onDismiss)

            Column { FormLabel("MEAL DESCRIPTION"); FieldTextField(description, { description = it }, "e.g. Lunch") }

            rows.forEach { (key, seed) ->
                ReviewItemCard(
                    seed = seed,
                    searchRepo = searchRepo,
                    resolverRepo = resolverRepo,
                    onConfirmedChange = { confirmed[key] = it },
                    onConfirmedClear = { confirmed.remove(key) },
                    onRemove = {
                        rows = rows.filter { it.first != key }
                        confirmed.remove(key)
                    },
                )
            }

            AmberButton(label = "ADD ITEM") {
                val key = nextKey.toString()
                nextKey += 1
                rows = rows + (key to ReviewSeedItem(description = "", source = MealItemSource.Manual))
            }

            saveError?.let {
                Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.Critical)
            }

            val allResolved = rows.isNotEmpty() && rows.all { confirmed.containsKey(it.first) }
            SaveButton(saving = saving, enabled = allResolved) {
                saving = true
                saveError = null
                scope.launch {
                    try {
                        saveRepo.saveMeal(mealDateTime.toIsoWithOffset(), description.trim(), rows.mapNotNull { confirmed[it.first] })
                        saving = false
                        onSaved()
                    } catch (e: Exception) {
                        saveError = e.message ?: "Could not save meal"
                        saving = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewItemCard(
    seed: ReviewSeedItem,
    searchRepo: NutritionFoodSearchRepository,
    resolverRepo: NutritionResolverRepository,
    onConfirmedChange: (ConfirmedMealItem) -> Unit,
    onConfirmedClear: () -> Unit,
    onRemove: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf(seed.description) }
    var searchResults by remember { mutableStateOf<List<FoodRow>>(emptyList()) }
    var matchedFood by remember { mutableStateOf(seed.preMatchedFood) }
    var showSearch by remember { mutableStateOf(seed.preMatchedFood == null) }
    var servings by remember { mutableStateOf<List<FoodServingRow>>(emptyList()) }
    var selectedServing by remember { mutableStateOf<FoodServingRow?>(null) }
    var quantityText by remember {
        mutableStateOf(
            seed.quantityValue?.toString()
                ?: (if (seed.quantityLow != null && seed.quantityHigh != null) ((seed.quantityLow + seed.quantityHigh) / 2).toString() else "100"),
        )
    }
    var quantityUnit by remember { mutableStateOf(seed.quantityUnit ?: if (seed.isBeverage) "ml" else "g") }
    var resolved by remember { mutableStateOf<ResolvedMealItem?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    // Debounced food-name search -- 300ms matches this app's other
    // type-to-search affordances closely enough without a shared constant.
    LaunchedEffect(searchQuery, showSearch) {
        if (!showSearch || searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searchResults = try { searchRepo.search(searchQuery) } catch (e: Exception) { emptyList() }
    }

    LaunchedEffect(matchedFood?.id) {
        val foodId = matchedFood?.id
        selectedServing = null
        servings = if (foodId != null) try { searchRepo.loadServings(foodId) } catch (e: Exception) { emptyList() } else emptyList()
    }

    // Re-resolves on every food/quantity/serving change -- "quantity edits
    // immediately recalculate totals" holds because this is the only path
    // that ever sets `resolved`, and it always calls DAV-164's resolver
    // fresh rather than adjusting a cached total.
    LaunchedEffect(matchedFood?.id, quantityText, quantityUnit, selectedServing?.id) {
        val food = matchedFood
        val foodId = food?.id
        val qty = quantityText.toDoubleOrNull()
        if (food == null || foodId == null || qty == null || qty <= 0.0) {
            resolved = null
            onConfirmedClear()
            return@LaunchedEffect
        }
        resolving = true
        resolveError = null
        try {
            val serving = selectedServing
            val result = resolverRepo.resolveMealItem(
                foodId = foodId,
                quantity = if (serving == null) qty else 0.0,
                quantityUnit = quantityUnit,
                servingId = serving?.id,
                servingCount = if (serving != null) qty else null,
            )
            resolved = result
            onConfirmedChange(
                ConfirmedMealItem(
                    description = seed.description.ifBlank { food.name },
                    foodId = foodId,
                    quantity = qty,
                    quantityUnit = quantityUnit,
                    servingId = serving?.id,
                    servingCount = if (serving != null) qty else null,
                    quantityLow = seed.quantityLow,
                    quantityHigh = seed.quantityHigh,
                    source = seed.source,
                    isEstimated = seed.source != MealItemSource.Manual,
                    confidence = seed.foodConfidence,
                    aiEstimateId = seed.aiEstimateId,
                ),
            )
        } catch (e: Exception) {
            resolveError = e.message ?: "Could not resolve this item"
            resolved = null
            onConfirmedClear()
        } finally {
            resolving = false
        }
    }

    FTCard(title = seed.description.ifBlank { "New item" }) {
        if (seed.source != MealItemSource.Manual) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                seed.foodConfidence?.let { ConfidenceBadge("FOOD", it) }
                seed.portionConfidence?.let { ConfidenceBadge("PORTION", it) }
                if (seed.ambiguous) {
                    Text(
                        "NEEDS CONFIRMATION",
                        style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.1f.em),
                        color = FT.Warning,
                    )
                }
            }
        }

        if (matchedFood != null && !showSearch) {
            val food = matchedFood!!
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(food.name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp), color = FT.TextPrimary)
                    food.brand?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = FT.TextSecondary) }
                }
                Text(
                    "CHANGE",
                    style = sheetActionLabelStyle,
                    color = FT.TextSecondary,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showSearch = true },
                )
            }
        } else {
            FieldTextField(searchQuery, { searchQuery = it }, "Search foods...")
            searchResults.take(6).forEach { food ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            matchedFood = food
                            showSearch = false
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        food.name + (food.brand?.let { " ($it)" } ?: ""),
                        style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                        color = FT.TextPrimary,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FormLabel(if (selectedServing != null) "SERVING COUNT" else "QUANTITY")
                FieldTextField(quantityText, { quantityText = it }, "100", keyboardType = KeyboardType.Number)
            }
            if (selectedServing == null) {
                Column(Modifier.weight(1f)) { FormLabel("UNIT"); FieldTextField(quantityUnit, { quantityUnit = it }, "g") }
            }
        }
        if (seed.quantityLow != null && seed.quantityHigh != null) {
            Text(
                "Estimated range: ${seed.quantityLow} - ${seed.quantityHigh} $quantityUnit",
                style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                color = FT.TextSecondary,
            )
        }

        if (servings.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                servings.forEach { serving ->
                    val isSelected = selectedServing?.id == serving.id
                    val shape = RoundedCornerShape(FT.RadiusSmall)
                    Row(
                        modifier = Modifier
                            .border(FT.BorderWidth, if (isSelected) FT.DomainLog else FT.GlassBorder, shape)
                            .background(if (isSelected) FT.DomainLog.copy(alpha = 0.14f) else Color.Transparent, shape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (isSelected) {
                                    selectedServing = null
                                } else {
                                    selectedServing = serving
                                    quantityText = "1"
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            serving.servingName,
                            style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
                            color = if (isSelected) FT.DomainLog else FT.TextSecondary,
                        )
                    }
                }
            }
        }

        when {
            resolving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.padding(2.dp))
                Text("Calculating...", style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.TextSecondary)
            }
            resolveError != null -> Text(resolveError!!, style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.Critical)
            resolved != null -> ResolvedTotalsRow(resolved!!)
        }

        Text(
            "REMOVE",
            style = sheetActionLabelStyle,
            color = FT.Critical,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRemove),
        )
    }
}

@Composable
private fun ResolvedTotalsRow(resolved: ResolvedMealItem) {
    val n = resolved.nutrients
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            n.calories?.let { StatChip("KCAL", "%.0f".format(it)) }
            n.proteinG?.let { StatChip("P", "%.1fg".format(it)) }
            n.carbsG?.let { StatChip("C", "%.1fg".format(it)) }
            n.fatG?.let { StatChip("F", "%.1fg".format(it)) }
        }
        if (n.waterMl != null || n.caffeineMg != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                n.waterMl?.let { StatChip("WATER", "%.0fml".format(it)) }
                n.caffeineMg?.let { StatChip("CAFFEINE", "%.0fmg".format(it)) }
                resolved.hydration?.let { StatChip("EST. HYDRATION", "%.0fml".format(it.effectiveHydrationMl)) }
            }
            if (resolved.hydration != null) {
                Text(
                    "Effective hydration is a modeled estimate (${resolved.hydration.modelVersion}), not a measured value.",
                    style = TextStyle(fontFamily = Inter, fontSize = 11.sp),
                    color = FT.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column {
        Text(label, style = TextStyle(fontFamily = RobotoMono, fontSize = 9.5.sp), color = FT.TextSecondary)
        Text(value, style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = FT.TextPrimary)
    }
}

@Composable
private fun ConfidenceBadge(label: String, confidence: Double) {
    val color = when {
        confidence >= 0.75 -> FT.Emerald
        confidence >= 0.4 -> FT.Warning
        else -> FT.Critical
    }
    Text(
        "$label ${(confidence * 100).toInt()}%",
        style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.1f.em),
        color = color,
    )
}

private fun LocalDateTime.toIsoWithOffset(): String = this.atOffset(java.time.ZoneOffset.UTC).toString()
