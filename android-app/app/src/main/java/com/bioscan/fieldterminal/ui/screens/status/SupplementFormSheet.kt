package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.data.catching
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.bioscan.fieldterminal.domain.InteractionRecord
import com.bioscan.fieldterminal.domain.RosterItem
import com.bioscan.fieldterminal.domain.detectInteractions
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.bioscan.fieldterminal.domain.formatAmount
import com.bioscan.fieldterminal.domain.spec

// DAV-81. The roster (`supplements`) itself had no UI to create or edit --
// only ever read, by SupplementsScreen.kt's display and AddEntrySheet.kt's
// logging flow, both of which assumed rows already existed. Add and edit
// share this one sheet since they're the same 3 fields either way; ending a
// supplement is a separate, single-tap action here rather than a 4th field,
// since it's a status transition, not part of "what/how much/when."
private val sheetHeaderTitleStyle = FTType.SectionTitle
private val sheetActionLabelStyle = FTType.LabelCaps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplementFormSheet(
    existing: SupplementRow?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    // Phase 6: the active roster and the interaction registry, to check this item against while it is edited.
    roster: List<RosterItem> = emptyList(),
    interactions: List<InteractionRecord> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { SupplementsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var name by remember { mutableStateOf(existing?.name ?: "") }
    // Regimen model: kind, dose, form and route, times of day, schedule, cycle and dose steps (RegimenFields.kt).
    val regimen = remember(existing?.id) { RegimenEditorState(existing) }
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
            // The rows are per label serving; the sheet shows one dose (label x servings per dose).
            val spd = existing.servingsPerDose
            fun perDose(v: Double) = com.bioscan.fieldterminal.domain.formatAmount(Math.round(v * spd * 1000) / 1000.0)
            ingredients.clear()
            full.forEach { ing ->
                ingredients.add(EditableIngredient().apply {
                    // `this.` is required: a bare `name` resolves to the sheet's own
                    // local `var name` (locals beat implicit-receiver members) and
                    // overwrote the supplement NAME field with the ingredient name.
                    this.name = ing.name
                    nutrientKey = ing.nutrientKey ?: ""
                    compoundAmount = perDose(ing.compoundAmount)
                    compoundUnit = ing.compoundUnit
                    elementalAmount = ing.elementalAmount?.let(::perDose) ?: ""
                    elementalUnit = ing.elementalUnit ?: ""
                })
            }
        } catch (_: Exception) {}
    }

    ModalBottomSheet(
        onDismissRequest = { if (!com.bioscan.fieldterminal.util.ExternalActivityGuard.active) onDismiss() },
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
                if (existing == null) "ADD TO YOUR REGIMEN" else "EDIT ${regimen.kind.label.uppercase()}",
                style = sheetHeaderTitleStyle,
                color = FT.Emerald,
            )

            Column { FormFieldLabel("NAME"); FieldTextField(name, { name = it }, "e.g. Vitamin D3 (NOW Foods)") }
            RegimenFields(regimen)

            // One dose: how much of the form, then what that dose contains.
            DoseCountField(regimen)

            IngredientsSection(ingredients)

            // The edited item against everyone else; only pairs that involve it. A new item has no id yet.
            val draftId = existing?.id ?: -1L
            val mine = detectInteractions(
                roster.filter { it.id != draftId } + RosterItem(
                    id = draftId,
                    name = name,
                    timeOfDay = regimen.firstSlot,
                    everyNDays = regimen.everyNDaysOrNull,
                    slots = if (regimen.asNeeded) emptyList() else com.bioscan.fieldterminal.domain.REGIMEN_SLOTS.filter { it in regimen.slots },
                    ingredientKeys = roster.firstOrNull { it.id == draftId }?.ingredientKeys.orEmpty(),
                    ingredientNames = ingredients.map { it.name },
                ),
                interactions,
            ).filter { it.a.id == draftId || it.b?.id == draftId }
            if (name.isNotBlank()) InteractionNotice(mine)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (scanningBarcode) "SCANNING..." else "SCAN BARCODE TO IMPORT INGREDIENTS",
                    style = FTType.LabelCaps,
                    color = if (scanningBarcode) FT.TextMuted else FT.Emerald,
                    modifier = Modifier.ftClickable(enabled = !scanningBarcode) {
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
                    Text(it, style = FTType.Caption, color = FT.Critical)
                }
            }

            if (showEnrichConfirm) {
                AlertDialog(
                    onDismissRequest = { showEnrichConfirm = false; enrichPending = null },
                    title = { Text("REPLACE INGREDIENTS?", style = FTType.Telemetry) },
                    text = { Text("Scanned ingredients differ from what you entered. Replace them with the barcode data?", style = FTType.BodySmall) },
                    confirmButton = {
                        TextButton(onClick = {
                            enrichPending?.let { pending ->
                                ingredients.clear()
                                pending.forEach { ingredients.add(it) }
                            }
                            showEnrichConfirm = false
                            enrichPending = null
                        }) { Text("REPLACE", style = FTType.LabelCaps, color = FT.Emerald) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEnrichConfirm = false; enrichPending = null }) {
                            Text("KEEP MINE", style = FTType.MonoCaption, color = FT.TextSecondary)
                        }
                    },
                    containerColor = FT.Surface,
                    titleContentColor = FT.TextPrimary,
                    textContentColor = FT.TextSecondary,
                )
            }

            // Medications, hormones, peptides: a reference card from RxNorm and the FDA label (or PubMed); supplements keep
            // their label-database check below.
            if (regimen.kind != com.bioscan.fieldterminal.domain.RegimenKind.SUPPLEMENT) {
                RegimenReferenceSection(existing?.id, name.trim(), roster.filter { it.id != existing?.id }.map { it.id to it.name },
                    unit = regimen.unit, dose = regimen.amount.replace(',', '.').toDoubleOrNull())
            }

            existing?.productId?.let { ProductVerifySection(it) }

            error?.let {
                Text("Couldn't save ($it).", style = FTType.Caption, color = FT.Critical)
            }

            val (draft, missing) = regimen.draft(name)
            if (missing != null && name.isNotBlank()) Text(missing, style = FTType.Caption, color = FT.TextMuted)
            FTButton(label = if (saving) "SAVING..." else "SAVE", emphasis = FTButtonEmphasis.Primary) {
                if (draft != null && !saving) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            // Supplement Intelligence Phase 1: only ingredient
                            // rows with a real name count -- a blank row left
                            // over from "+ ADD INGREDIENT" is silently dropped
                            // rather than saved as an empty ingredient.
                            val perDose = ingredients.mapNotNull { it.toInputOrNull() }
                            // The ingredients are entered per dose; the product keeps them per label serving, so the
                            // pantry (which counts servings) and the nutrient totals stay right. A label serving that can
                            // be compared with the dose sets servings per dose; otherwise the serving becomes one unit
                            // (capsules, tablets...) or the dose itself (measured forms).
                            val wholeUnits = com.bioscan.fieldterminal.domain.formRule(draft.form)?.splitting != com.bioscan.fieldterminal.domain.Splitting.MEASURED
                            val pid = existing?.productId
                            val (labelSize, labelUnit) = pid?.let { catching { repo.loadProductServing(it) }.getOrDefault(null to null) } ?: (null to null)
                            val known = com.bioscan.fieldterminal.domain.servingsPerDose(draft.amount, draft.unit, labelSize, labelUnit)
                            val spd = known ?: if (wholeUnits) draft.amount else 1.0
                            val servingSize = if (known != null) labelSize!! else if (wholeUnits) 1.0 else draft.amount
                            fun perServing(list: List<IngredientInput>) = list.map { i ->
                                i.copy(compoundAmount = i.compoundAmount / spd, elementalAmount = i.elementalAmount?.let { it / spd })
                            }
                            val productId = when {
                                perDose.isNotEmpty() && pid != null -> {
                                    repo.replaceProductIngredients(pid, perServing(perDose))
                                    if (known == null) repo.setProductServing(pid, servingSize, draft.unit)
                                    pid
                                }
                                perDose.isNotEmpty() -> repo.createProductWithIngredients(name.trim(), servingSize, draft.unit, perServing(perDose))
                                pid != null && known == null && labelSize == null -> { repo.setProductServing(pid, servingSize, draft.unit); pid }
                                else -> null
                            }
                            // Servings per dose change only with a label they were worked out from (compared, or just set).
                            val draft = draft.copy(servingsPerDose = if (known != null || productId != null) spd else null)

                            if (existing == null) {
                                // DAV-83: best-effort only -- a failed or
                                // missing-key blurb attempt never blocks
                                // adding the supplement itself, same as
                                // NutritionEstimationRepository's own
                                // review-before-save stance on estimation
                                // failures elsewhere in this app.
                                val aiNote = GeminiApiKeyStore.get(context)?.let { key ->
                                    try {
                                        SupplementImpactRepository(key).describeImpact(name.trim(), "${formatAmount(draft.amount)} ${draft.unit}")
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                repo.saveRegimen(null, draft, productId, aiNote)
                            } else {
                                repo.saveRegimen(existing.id, draft, productId)
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
                    if (ending) "ENDING..." else "STOP TAKING IT",
                    style = FTType.BodySmall,
                    color = FT.Critical,
                    modifier = Modifier
                        .fillMaxWidth()
                        .ftClickable {
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
            if (existing != null && existing.status == "ended") {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ending) "RESTARTING..." else "START TAKING IT AGAIN",
                    style = FTType.LabelCaps,
                    color = FT.Emerald,
                    modifier = Modifier.fillMaxWidth().ftClickable {
                        if (!ending) {
                            ending = true
                            scope.launch {
                                try {
                                    // Saves any edits first, so a restart can come back at a new dose or schedule.
                                    draft?.let { repo.saveRegimen(existing.id, it, existing.productId) }
                                    repo.restartSupplement(existing.id)
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
            if (existing != null) {
                var itemWatches by remember(existing.id) { mutableStateOf<List<com.bioscan.fieldterminal.domain.LabWatch>>(emptyList()) }
                LaunchedEffect(existing.id) {
                    itemWatches = catching { com.bioscan.fieldterminal.data.LabWatchRepository(SupabaseClientProvider.client).watches() }
                        .getOrDefault(emptyList()).filter { it.itemId == existing.id }
                }
                LabWatchSection(itemWatches, title = "LABS TO WATCH", showItem = false)
                RegimenEffectsSection(existing.id)
                if (regimen.injectable) {
                    InjectableSection(existing.id, existing.spec(), regimen.syringeUnits.replace(',', '.').toDoubleOrNull() ?: existing.syringeUnits)
                }
                RegimenHistory(existing.id)
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
        FormFieldLabel("INGREDIENTS IN ONE DOSE (OPTIONAL)")
        Text("Amounts for the dose above. Scanned labels list one serving: check they match your dose.", style = FTType.Caption, color = FT.TextMuted)
        ingredients.forEachIndexed { i, ingredient -> IngredientEditor(ingredient) { ingredients.removeAt(i) } }
        Text(
            "+ ADD INGREDIENT",
            style = FTType.MonoCaption,
            color = FT.Emerald,
            modifier = Modifier.ftClickable { ingredients.add(EditableIngredient()) },
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
                    .ftClickable(onClick = onRemove),
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
