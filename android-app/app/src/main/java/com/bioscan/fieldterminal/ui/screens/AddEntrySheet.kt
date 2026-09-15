package com.bioscan.fieldterminal.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogRunRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.domain.AddEntryType
import com.bioscan.fieldterminal.domain.LogEntry
import com.bioscan.fieldterminal.domain.LogSource
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import kotlinx.coroutines.launch

// Step 12 (Phase D): the "+" add-entry flow. Type picker first, then a
// minimal per-type form that writes straight to the real table Step 11
// already reads -- see data/AddEntryRepository.kt for the writes themselves.
// This is read-write's first appearance in the app; Step 11 was read-only by
// design, per the roadmap's own sequencing.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntrySheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedType by remember { mutableStateOf<AddEntryType?>(null) }
    val repo = remember { AddEntryRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FieldColors.Panel,
        contentColor = FieldColors.Ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val type = selectedType
            if (type == null) {
                Text("LOG NEW ENTRY", style = FieldTextStyles.headerTitle, color = FieldColors.Amber)
                Spacer(Modifier.height(16.dp))
                TypePickerGrid(onSelect = { selectedType = it })
            } else {
                SheetBackHeader(label = type.label, onBack = { selectedType = null })
                Spacer(Modifier.height(16.dp))
                val onSubmit: ((suspend (AddEntryRepository) -> Unit)) -> Unit = { write ->
                    saving = true
                    scope.launch {
                        write(repo)
                        saving = false
                        onSaved()
                    }
                }
                when (type) {
                    AddEntryType.Training -> TrainingForm(saving, onSave = { d, dur, hr -> onSubmit { it.addTraining(d, dur, hr) } })
                    AddEntryType.Food -> FoodForm(saving, onSave = { desc, cal, p, c, f -> onSubmit { it.addFood(desc, cal, p, c, f) } })
                    AddEntryType.Drink -> DrinkForm(saving, onSave = { ml -> onSubmit { it.addDrink(ml) } })
                    AddEntryType.Encounter -> EncounterForm(saving, onSave = { et, n -> onSubmit { it.addEncounter(et, n) } })
                    AddEntryType.Stool -> StoolForm(saving, onSave = { bt, d -> onSubmit { it.addStool(bt, d) } })
                    AddEntryType.Arousal -> ArousalForm(saving, onSave = { mw, al -> onSubmit { it.addArousal(mw, al) } })
                    AddEntryType.Note -> NoteForm(saving, onSave = { text -> onSubmit { it.addNote(text) } })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// Tapping a Log entry opens this first -- EDIT (when the source has a
// corresponding form) and DELETE, with an inline confirm step rather than a
// second popup. Sleep has no add-entry form (Step 12's picker never offered
// it), so it's delete-only here too.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryActionSheet(entry: LogEntry, onDismiss: () -> Unit, onEdit: () -> Unit, onDeleted: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { AddEntryRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var confirmingDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FieldColors.Panel,
        contentColor = FieldColors.Ink,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(entry.headline, style = FieldTextStyles.headerTitle, color = FieldColors.Amber)

            if (!confirmingDelete) {
                if (entry.source != LogSource.Sleep) {
                    AmberButton(label = "EDIT", onClick = onEdit)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, FieldColors.Alert)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { confirmingDelete = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("DELETE", style = FieldTextStyles.subTabLabel, color = FieldColors.Alert)
                }
            } else {
                Text(
                    "Delete this entry? This can't be undone.",
                    style = TextStyle(fontFamily = Saira, fontSize = 13.sp),
                    color = FieldColors.InkMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, FieldColors.Hairline)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { confirmingDelete = false }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("CANCEL", style = FieldTextStyles.subTabLabel, color = FieldColors.InkMuted)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(FieldColors.Alert)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (!deleting) {
                                    deleting = true
                                    scope.launch {
                                        repo.deleteEntry(entry.source, entry.id)
                                        deleting = false
                                        onDeleted()
                                    }
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (deleting) "DELETING..." else "CONFIRM", style = FieldTextStyles.subTabLabel, color = FieldColors.Ground)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// Fetches the full row fresh (rather than reusing the Log feed's already-
// formatted headline/detail strings) so editing works from real field
// values, not a re-parse of display text.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntrySheet(entry: LogEntry, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { AddEntryRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(entry.id) {
        loaded = when (entry.source) {
            LogSource.Meal -> repo.fetchMeal(entry.id)
            LogSource.Run -> repo.fetchRun(entry.id)
            LogSource.Hydration -> repo.fetchHydration(entry.id)
            LogSource.Encounter -> repo.fetchEncounter(entry.id)
            LogSource.Stool -> repo.fetchStool(entry.id)
            LogSource.Arousal -> repo.fetchArousal(entry.id)
            LogSource.Note -> repo.fetchNote(entry.id)
            LogSource.Sleep -> null // no edit form for Sleep; EntryActionSheet never offers EDIT for it
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FieldColors.Panel,
        contentColor = FieldColors.Ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SheetBackHeader(label = "EDIT ${entry.kind.label}", onBack = onDismiss)
            Spacer(Modifier.height(16.dp))

            val row = loaded
            val onSubmit: ((suspend (AddEntryRepository) -> Unit)) -> Unit = { write ->
                saving = true
                scope.launch {
                    write(repo)
                    saving = false
                    onSaved()
                }
            }

            if (row == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FieldColors.Amber)
                }
            } else when (row) {
                is LogRunRow -> TrainingForm(
                    saving,
                    initialDistanceKm = row.distanceKm,
                    initialDurationMin = row.durationMin,
                    initialAvgHr = row.avgHr,
                    onSave = { d, dur, hr -> onSubmit { it.updateTraining(row.id, row.date, d, dur, hr) } },
                )
                is LogMealRow -> FoodForm(
                    saving,
                    initialDescription = row.description ?: "",
                    initialCalories = row.calories,
                    initialProtein = row.proteinG,
                    initialCarbs = row.carbsG,
                    initialFat = row.fatG,
                    onSave = { desc, cal, p, c, f -> onSubmit { it.updateFood(row.id, row.loggedAt, desc, cal, p, c, f) } },
                )
                is LogHydrationRow -> DrinkForm(
                    saving,
                    initialMl = row.ml,
                    onSave = { ml -> onSubmit { it.updateDrink(row.id, row.date, ml) } },
                )
                is LogEncounterRow -> EncounterForm(
                    saving,
                    initialType = row.encounterType ?: "",
                    initialNotes = row.notes ?: "",
                    onSave = { et, n -> onSubmit { it.updateEncounter(row.id, row.date, et, n) } },
                )
                is LogStoolRow -> StoolForm(
                    saving,
                    initialBristolType = row.bristolType,
                    initialDiscomfort = row.discomfort,
                    onSave = { bt, d -> onSubmit { it.updateStool(row.id, row.occurredAt, bt, d) } },
                )
                is LogArousalRow -> ArousalForm(
                    saving,
                    initialMorningWood = row.morningErectionQuality,
                    initialArousalLevel = row.arousalLevel,
                    onSave = { mw, al -> onSubmit { it.updateArousal(row.id, row.date, mw, al) } },
                )
                is LogNoteRow -> NoteForm(
                    saving,
                    initialText = row.text,
                    onSave = { text -> onSubmit { it.updateNote(row.id, row.occurredAt, text) } },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetBackHeader(label: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "BACK",
            style = FieldTextStyles.subTabLabel,
            color = FieldColors.InkMuted,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
        )
        Text(label, style = FieldTextStyles.headerTitle, color = FieldColors.Amber)
    }
}

@Composable
private fun TypePickerGrid(onSelect: (AddEntryType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AddEntryType.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { type ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, FieldColors.Hairline)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(type) }
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(type.label, style = FieldTextStyles.subTabLabel, color = FieldColors.Ink)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        style = FieldTextStyles.subTabLabel,
        color = FieldColors.InkMuted,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SaveButton(saving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    AmberButton(label = if (saving) "SAVING..." else "SAVE") {
        if (enabled && !saving) onClick()
    }
}

@Composable
private fun TrainingForm(
    saving: Boolean,
    initialDistanceKm: Double? = null,
    initialDurationMin: Double? = null,
    initialAvgHr: Double? = null,
    onSave: (distanceKm: Double, durationMin: Double, avgHr: Double?) -> Unit,
) {
    var distance by remember { mutableStateOf(initialDistanceKm?.toString() ?: "") }
    var duration by remember { mutableStateOf(initialDurationMin?.toString() ?: "") }
    var avgHr by remember { mutableStateOf(initialAvgHr?.toString() ?: "") }
    val distanceKm = distance.toDoubleOrNull()
    val durationMin = duration.toDoubleOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column { FormLabel("DISTANCE (KM)"); FieldTextField(distance, { distance = it }, "e.g. 10.0", keyboardType = KeyboardType.Decimal) }
        Column { FormLabel("DURATION (MIN)"); FieldTextField(duration, { duration = it }, "e.g. 55", keyboardType = KeyboardType.Decimal) }
        Column { FormLabel("AVG HR (OPTIONAL)"); FieldTextField(avgHr, { avgHr = it }, "e.g. 152", keyboardType = KeyboardType.Number) }
        SaveButton(saving, distanceKm != null && durationMin != null) {
            onSave(distanceKm!!, durationMin!!, avgHr.toDoubleOrNull())
        }
    }
}

@Composable
private fun FoodForm(
    saving: Boolean,
    initialDescription: String = "",
    initialCalories: Double? = null,
    initialProtein: Double? = null,
    initialCarbs: Double? = null,
    initialFat: Double? = null,
    onSave: (description: String, calories: Double?, proteinG: Double?, carbsG: Double?, fatG: Double?) -> Unit,
) {
    var description by remember { mutableStateOf(initialDescription) }
    var calories by remember { mutableStateOf(initialCalories?.toString() ?: "") }
    var protein by remember { mutableStateOf(initialProtein?.toString() ?: "") }
    var carbs by remember { mutableStateOf(initialCarbs?.toString() ?: "") }
    var fat by remember { mutableStateOf(initialFat?.toString() ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column { FormLabel("DESCRIPTION"); FieldTextField(description, { description = it }, "e.g. Chicken rice bowl") }
        Column { FormLabel("CALORIES (OPTIONAL)"); FieldTextField(calories, { calories = it }, "e.g. 650", keyboardType = KeyboardType.Number) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { FormLabel("PROTEIN G"); FieldTextField(protein, { protein = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("CARBS G"); FieldTextField(carbs, { carbs = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("FAT G"); FieldTextField(fat, { fat = it }, "0", keyboardType = KeyboardType.Number) }
        }
        SaveButton(saving, description.isNotBlank()) {
            onSave(description.trim(), calories.toDoubleOrNull(), protein.toDoubleOrNull(), carbs.toDoubleOrNull(), fat.toDoubleOrNull())
        }
    }
}

@Composable
private fun DrinkForm(saving: Boolean, initialMl: Int? = null, onSave: (ml: Int) -> Unit) {
    var ml by remember { mutableStateOf(initialMl?.toString() ?: "") }
    val mlValue = ml.toIntOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column { FormLabel("AMOUNT (ML)"); FieldTextField(ml, { ml = it }, "e.g. 500", keyboardType = KeyboardType.Number) }
        SaveButton(saving, mlValue != null && mlValue > 0) {
            onSave(mlValue!!)
        }
    }
}

@Composable
private fun EncounterForm(
    saving: Boolean,
    initialType: String = "",
    initialNotes: String = "",
    onSave: (encounterType: String?, notes: String?) -> Unit,
) {
    var encounterType by remember { mutableStateOf(initialType) }
    var notes by remember { mutableStateOf(initialNotes) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column { FormLabel("TYPE (OPTIONAL)"); FieldTextField(encounterType, { encounterType = it }, "e.g. date, call, hangout") }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Notes...", singleLine = false) }
        SaveButton(saving, true) {
            onSave(encounterType.trim().ifBlank { null }, notes.trim().ifBlank { null })
        }
    }
}

@Composable
private fun StoolForm(
    saving: Boolean,
    initialBristolType: Int? = null,
    initialDiscomfort: Int? = null,
    onSave: (bristolType: Int, discomfort: Int?) -> Unit,
) {
    var bristolType by remember { mutableStateOf(initialBristolType) }
    var discomfort by remember { mutableStateOf(initialDiscomfort?.toString() ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            FormLabel("BRISTOL TYPE (1-7)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 1..7) {
                    val selected = bristolType == n
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, if (selected) FieldColors.Amber else FieldColors.Hairline)
                            .background(if (selected) FieldColors.Amber.copy(alpha = 0.18f) else FieldColors.RaisedSurface)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { bristolType = n }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$n",
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp),
                            color = if (selected) FieldColors.Amber else FieldColors.InkMuted,
                        )
                    }
                }
            }
        }
        Column { FormLabel("DISCOMFORT 0-10 (OPTIONAL)"); FieldTextField(discomfort, { discomfort = it }, "e.g. 2", keyboardType = KeyboardType.Number) }
        SaveButton(saving, bristolType != null) {
            onSave(bristolType!!, discomfort.toIntOrNull())
        }
    }
}

@Composable
private fun ArousalForm(
    saving: Boolean,
    initialMorningWood: Int? = null,
    initialArousalLevel: Int? = null,
    onSave: (morningWood: Int, arousalLevel: Int) -> Unit,
) {
    var morningWood by remember { mutableStateOf(initialMorningWood?.toString() ?: "5") }
    var arousalLevel by remember { mutableStateOf(initialArousalLevel?.toString() ?: "5") }
    val morningWoodValue = morningWood.toIntOrNull()
    val arousalValue = arousalLevel.toIntOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column { FormLabel("MORNING WOOD (0-10)"); FieldTextField(morningWood, { morningWood = it }, "5", keyboardType = KeyboardType.Number) }
        Column { FormLabel("AROUSAL LEVEL (0-10)"); FieldTextField(arousalLevel, { arousalLevel = it }, "5", keyboardType = KeyboardType.Number) }
        val valid = morningWoodValue != null && morningWoodValue in 0..10 && arousalValue != null && arousalValue in 0..10
        SaveButton(saving, valid) {
            onSave(morningWoodValue!!, arousalValue!!)
        }
    }
}

@Composable
private fun NoteForm(saving: Boolean, initialText: String = "", onSave: (text: String) -> Unit) {
    var text by remember { mutableStateOf(initialText) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column { FormLabel("NOTE"); FieldTextField(text, { text = it }, "Write a note...", singleLine = false) }
        SaveButton(saving, text.isNotBlank()) {
            onSave(text.trim())
        }
    }
}
