package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.GeminiApiKeyStore
import com.bioscan.fieldterminal.data.IngredientInput
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementImpactRepository
import com.bioscan.fieldterminal.data.SupplementLookupRepository
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.util.scanBarcode
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDate

// DAV-81. The roster (`supplements`) itself had no UI to create or edit --
// only ever read, by SupplementsScreen.kt's display and AddEntrySheet.kt's
// logging flow, both of which assumed rows already existed. Add and edit
// share this one sheet since they're the same 3 fields either way; ending a
// supplement is a separate, single-tap action here rather than a 4th field,
// since it's a status transition, not part of "what/how much/when."
private val TIME_OF_DAY_OPTIONS = listOf("morning", "afternoon", "night", "as-needed")

// null = daily (no restriction). Values match the every_n_days column.
private val EVERY_N_DAYS_OPTIONS: List<Pair<Int?, String>> = listOf(
    null to "DAILY",
    2 to "2 DAYS",
    3 to "3 DAYS",
    4 to "4 DAYS",
    7 to "WEEKLY",
)
private val sheetHeaderTitleStyle = FTType.SectionTitle
private val sheetActionLabelStyle = FTType.LabelCaps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplementFormSheet(existing: SupplementRow?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { SupplementsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var dose by remember { mutableStateOf(existing?.dose ?: "") }
    var timeOfDay by remember { mutableStateOf(existing?.timeOfDay ?: "morning") }
    var everyNDays by remember { mutableStateOf(existing?.everyNDays) }
    var saving by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ingredients = remember { mutableStateListOf<EditableIngredient>() }
    var scanningBarcode by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var enrichPending by remember { mutableStateOf<List<EditableIngredient>?>(null) }
    var showEnrichConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(existing?.productId) {
        val pid = existing?.productId ?: return@LaunchedEffect
        try {
            val full = repo.loadProductIngredientsFull(pid)
            ingredients.clear()
            full.forEach { ing ->
                ingredients.add(EditableIngredient().apply {
                    // `this.` is required: a bare `name` resolves to the sheet's own
                    // local `var name` (locals beat implicit-receiver members) and
                    // overwrote the supplement NAME field with the ingredient name.
                    this.name = ing.name
                    nutrientKey = ing.nutrientKey ?: ""
                    compoundAmount = ing.compoundAmount.toString()
                    compoundUnit = ing.compoundUnit
                    elementalAmount = ing.elementalAmount?.toString() ?: ""
                    elementalUnit = ing.elementalUnit ?: ""
                })
            }
        } catch (_: Exception) {}
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
            Text(
                if (existing == null) "ADD SUPPLEMENT" else "EDIT SUPPLEMENT",
                style = sheetHeaderTitleStyle,
                color = FT.Emerald,
            )

            Column { FormFieldLabel("NAME"); FieldTextField(name, { name = it }, "e.g. Vitamin D3 (NOW Foods)") }
            Column { FormFieldLabel("DOSE"); FieldTextField(dose, { dose = it }, "e.g. 5000 IU") }

            Column {
                FormFieldLabel("TIME OF DAY")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TIME_OF_DAY_OPTIONS.forEach { option ->
                        val selected = option == timeOfDay
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .border(FT.BorderWidth, if (selected) FT.Emerald else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                                .background(if (selected) FT.Emerald.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(FT.RadiusSmall))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { timeOfDay = option }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option.uppercase(),
                                style = FTType.MonoCaption,
                                color = if (selected) FT.Emerald else FT.TextSecondary,
                            )
                        }
                    }
                }
            }

            Column {
                FormFieldLabel("FREQUENCY")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EVERY_N_DAYS_OPTIONS.forEach { (days, label) ->
                        val selected = days == everyNDays
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .border(FT.BorderWidth, if (selected) FT.Emerald else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                                .background(if (selected) FT.Emerald.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(FT.RadiusSmall))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { everyNDays = days }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = FTType.Micro,
                                color = if (selected) FT.Emerald else FT.TextSecondary,
                            )
                        }
                    }
                }
            }

            IngredientsSection(ingredients)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (scanningBarcode) "SCANNING..." else "SCAN BARCODE TO IMPORT INGREDIENTS",
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp),
                    color = if (scanningBarcode) FT.TextMuted else FT.Emerald,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !scanningBarcode,
                    ) {
                        scanningBarcode = true
                        scanError = null
                        scope.launch {
                            try {
                                val barcode = scanBarcode(context) { err -> scanError = err }
                                if (barcode != null) {
                                    val lookup = SupplementLookupRepository(SupabaseClientProvider.client).lookupBarcode(barcode)
                                    val scanned: List<EditableIngredient> = run {
                                        val dsld = lookup.dsld.value?.labels?.firstOrNull()?.ingredients
                                            ?.filter { it.name.isNotBlank() && it.amount != null && !it.unit.isNullOrBlank() }
                                            ?.map { row ->
                                                EditableIngredient().apply {
                                                    this.name = row.name
                                                    compoundAmount = row.amount!!.toString()
                                                    compoundUnit = row.unit!!
                                                }
                                            }
                                        if (!dsld.isNullOrEmpty()) return@run dsld
                                        lookup.suppco.value?.products?.firstOrNull()?.ingredients
                                            ?.filter { it.name.isNotBlank() && it.amount != null && !it.unit.isNullOrBlank() }
                                            ?.map { row ->
                                                EditableIngredient().apply {
                                                    this.name = row.name
                                                    nutrientKey = row.nutrientId ?: ""
                                                    compoundAmount = row.amount!!.toString()
                                                    compoundUnit = row.unit!!
                                                }
                                            } ?: emptyList()
                                    }
                                    if (scanned.isEmpty()) {
                                        scanError = "No ingredient data found for this barcode."
                                    } else {
                                        val hasExisting = ingredients.any { it.toInputOrNull() != null }
                                        if (hasExisting) {
                                            enrichPending = scanned
                                            showEnrichConfirm = true
                                        } else {
                                            ingredients.clear()
                                            scanned.forEach { ingredients.add(it) }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                scanError = e.message ?: "Scan failed"
                            } finally {
                                scanningBarcode = false
                            }
                        }
                    },
                )
                scanError?.let {
                    Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = FT.Critical)
                }
            }

            if (showEnrichConfirm) {
                AlertDialog(
                    onDismissRequest = { showEnrichConfirm = false; enrichPending = null },
                    title = { Text("REPLACE INGREDIENTS?", style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)) },
                    text = { Text("Scanned ingredients differ from what you entered. Replace them with the barcode data?", style = TextStyle(fontFamily = Inter, fontSize = 13.sp)) },
                    confirmButton = {
                        TextButton(onClick = {
                            enrichPending?.let { pending ->
                                ingredients.clear()
                                pending.forEach { ingredients.add(it) }
                            }
                            showEnrichConfirm = false
                            enrichPending = null
                        }) { Text("REPLACE", style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = FT.Emerald) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEnrichConfirm = false; enrichPending = null }) {
                            Text("KEEP MINE", style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp), color = FT.TextSecondary)
                        }
                    },
                    containerColor = FT.Surface,
                    titleContentColor = FT.TextPrimary,
                    textContentColor = FT.TextSecondary,
                )
            }

            existing?.productId?.let { ProductVerifySection(it) }

            error?.let {
                Text("Couldn't save ($it).", style = FTType.Caption, color = FT.Critical)
            }

            val valid = name.isNotBlank() && dose.isNotBlank()
            AmberButton(label = if (saving) "SAVING..." else "SAVE") {
                if (valid && !saving) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            // Supplement Intelligence Phase 1: only ingredient
                            // rows with a real name count -- a blank row left
                            // over from "+ ADD INGREDIENT" is silently dropped
                            // rather than saved as an empty ingredient.
                            val filledIngredients = ingredients.mapNotNull { it.toInputOrNull() }
                            val productId = when {
                                filledIngredients.isNotEmpty() && existing?.productId != null -> {
                                    repo.replaceProductIngredients(existing.productId, filledIngredients)
                                    existing.productId
                                }
                                filledIngredients.isNotEmpty() -> repo.createProductWithIngredients(name.trim(), null, null, filledIngredients)
                                else -> null
                            }

                            if (existing == null) {
                                // DAV-83: best-effort only -- a failed or
                                // missing-key blurb attempt never blocks
                                // adding the supplement itself, same as
                                // NutritionEstimationRepository's own
                                // review-before-save stance on estimation
                                // failures elsewhere in this app.
                                val aiNote = GeminiApiKeyStore.get(context)?.let { key ->
                                    try {
                                        SupplementImpactRepository(key).describeImpact(name.trim(), dose.trim())
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                repo.addSupplement(name.trim(), dose.trim(), timeOfDay, LocalDate.now(), aiNote, everyNDays, productId)
                            } else {
                                repo.updateSupplement(existing.id, name.trim(), dose.trim(), timeOfDay, everyNDays, productId)
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

            if (existing != null && existing.status == "active") {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ending) "ENDING..." else "END THIS SUPPLEMENT",
                    style = FTType.BodySmall,
                    color = FT.Critical,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!ending) {
                                ending = true
                                scope.launch {
                                    try {
                                        repo.endSupplement(existing.id)
                                        onSaved()
                                    } catch (e: Exception) {
                                        error = e.message ?: "Unknown error"
                                        ending = false
                                    }
                                }
                            }
                        },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FormFieldLabel(text: String) {
    Text(text, style = sheetActionLabelStyle, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

// Supplement Intelligence Phase 1 (DAV-328): one ingredient row's editable
// state -- mirrors this session's EditableSexualActivityInstance pattern
// (AddEntrySheet.kt) for a dynamic add/remove list.
private class EditableIngredient {
    var name by mutableStateOf("")
    var compoundAmount by mutableStateOf("")
    var compoundUnit by mutableStateOf("")
    var nutrientKey by mutableStateOf("")
    var elementalAmount by mutableStateOf("")
    var elementalUnit by mutableStateOf("")

    // Null when the row was never really filled in (left over from
    // "+ ADD INGREDIENT") -- a blank name or unparseable amount silently
    // drops the row rather than saving a broken ingredient.
    fun toInputOrNull(): IngredientInput? {
        val amount = compoundAmount.toDoubleOrNull() ?: return null
        if (name.isBlank() || compoundUnit.isBlank()) return null
        return IngredientInput(
            name = name.trim(),
            category = null,
            nutrientKey = nutrientKey.trim().ifBlank { null },
            compoundAmount = amount,
            compoundUnit = compoundUnit.trim(),
            elementalAmount = elementalAmount.toDoubleOrNull(),
            elementalUnit = elementalUnit.trim().ifBlank { null },
        )
    }
}

@Composable
private fun IngredientsSection(ingredients: androidx.compose.runtime.snapshots.SnapshotStateList<EditableIngredient>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormFieldLabel("INGREDIENTS (OPTIONAL)")
        ingredients.forEachIndexed { i, ingredient -> IngredientEditor(ingredient) { ingredients.removeAt(i) } }
        Text(
            "+ ADD INGREDIENT",
            style = FTType.MonoCaption,
            color = FT.Emerald,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { ingredients.add(EditableIngredient()) },
        )
    }
}

@Composable
private fun IngredientEditor(ingredient: EditableIngredient, onRemove: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FieldTextField(ingredient.name, { ingredient.name = it }, "Ingredient, e.g. Magnesium bisglycinate", modifier = Modifier.weight(1f))
            Text(
                "REMOVE",
                style = FTType.MonoCaption,
                color = FT.Critical,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRemove),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldTextField(ingredient.compoundAmount, { ingredient.compoundAmount = it }, "Amount, e.g. 2000", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            FieldTextField(ingredient.compoundUnit, { ingredient.compoundUnit = it }, "Unit, e.g. mg", modifier = Modifier.weight(1f))
        }
        FieldTextField(ingredient.nutrientKey, { ingredient.nutrientKey = it }, "Nutrient key (optional), e.g. magnesium")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldTextField(ingredient.elementalAmount, { ingredient.elementalAmount = it }, "Elemental amount (optional)", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            FieldTextField(ingredient.elementalUnit, { ingredient.elementalUnit = it }, "Elemental unit (optional)", modifier = Modifier.weight(1f))
        }
        Text(
            "Leave elemental blank unless the label states it separately (e.g. \"2000mg magnesium bisglycinate providing 200mg elemental magnesium\") -- never guessed.",
            style = FTType.Caption,
            color = FT.TextMuted,
        )
    }
}
