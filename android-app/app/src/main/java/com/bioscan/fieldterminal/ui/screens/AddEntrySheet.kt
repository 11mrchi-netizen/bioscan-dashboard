package com.bioscan.fieldterminal.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.GeminiApiKeyStore
import com.bioscan.fieldterminal.data.NutritionEstimationRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetails
import com.bioscan.fieldterminal.data.model.FullExerciseSessionRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import com.bioscan.fieldterminal.data.model.StrengthSetDto
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.AddEntryType
import com.bioscan.fieldterminal.domain.FoodEstimate
import com.bioscan.fieldterminal.domain.FuelSubType
import com.bioscan.fieldterminal.domain.LogEntry
import com.bioscan.fieldterminal.domain.LogSource
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.DateTimeField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import com.bioscan.fieldterminal.util.createCameraCaptureUri
import com.bioscan.fieldterminal.util.readAndCompressImage
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// Step 12 (Phase D) + the 2026-09-15 follow-up pass: the "+" add-entry flow.
// Type picker first, then a minimal per-type form that writes straight to
// the real table Step 11 already reads -- see data/AddEntryRepository.kt for
// the writes themselves. Every form now carries its own date/time picker
// (default "now", editable) per direct user request, and Food/Drink/
// Supplements share one FUEL entry point with a three-way sub-picker.
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
                    AddEntryType.Fuel -> FuelForm(saving, onSubmit)
                    AddEntryType.Encounter -> EncounterForm(saving, onSave = { date, et, n -> onSubmit { it.addEncounter(date, et, n) } })
                    AddEntryType.Stool -> StoolForm(saving, onSave = { occurredAt, bt, d -> onSubmit { it.addStool(occurredAt, bt, d) } })
                    AddEntryType.Arousal -> ArousalForm(saving, onSave = { date, mw, al -> onSubmit { it.addArousal(date, mw, al) } })
                    AddEntryType.Wellness -> WellnessForm(saving, onSave = { date, e, m, s, so -> onSubmit { it.addWellbeing(date, e, m, s, so) } })
                    AddEntryType.Note -> NoteForm(saving, onSave = { occurredAt, text -> onSubmit { it.addNote(occurredAt, text) } })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// Tapping a Log entry opens this first -- EDIT (when the source has a
// corresponding form) and DELETE, with an inline confirm step rather than a
// second popup. Sleep and Supplement have no add-entry form at all (a taken
// supplement is add-or-remove, not field-editable), so those two are
// delete-only. Exercise (Phase G3) IS editable -- not its Health-Connect-
// sourced fields, just rpe/notes via ExerciseDetailsForm. Phase G4 adds a
// DETAIL action for Exercise entries, pushing SessionDetailScreen.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryActionSheet(
    entry: LogEntry,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onViewDetail: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { AddEntryRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var confirmingDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val editable = entry.source !in setOf(LogSource.Sleep, LogSource.Supplement)

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
                if (onViewDetail != null) {
                    AmberButton(label = "DETAIL", onClick = onViewDetail)
                }
                if (editable) {
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
                    style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp),
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
            LogSource.Hydration -> repo.fetchHydration(entry.id)
            LogSource.Encounter -> repo.fetchEncounter(entry.id)
            LogSource.Stool -> repo.fetchStool(entry.id)
            LogSource.Arousal -> repo.fetchArousal(entry.id)
            LogSource.Note -> repo.fetchNote(entry.id)
            LogSource.Wellbeing -> repo.fetchWellbeing(entry.id)
            LogSource.Exercise -> repo.fetchExerciseSession(entry.id)
            LogSource.Sleep, LogSource.Supplement -> null // no edit form; EntryActionSheet never offers EDIT for these
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
                is LogMealRow -> FoodForm(
                    saving,
                    initialDateTime = parseIsoToLocalDateTime(row.loggedAt),
                    initialDescription = row.description ?: "",
                    initialCalories = row.calories,
                    initialProtein = row.proteinG,
                    initialCarbs = row.carbsG,
                    initialFat = row.fatG,
                    onSave = { dt, desc, cal, p, c, f -> onSubmit { it.updateFood(row.id, dt, desc, cal, p, c, f) } },
                )
                is LogHydrationRow -> DrinkForm(
                    saving,
                    initialDate = LocalDate.parse(row.date),
                    initialMl = row.ml,
                    onSave = { date, ml -> onSubmit { it.updateDrink(row.id, date, ml) } },
                )
                is LogEncounterRow -> EncounterForm(
                    saving,
                    initialDate = LocalDate.parse(row.date),
                    initialType = row.encounterType ?: "",
                    initialNotes = row.notes ?: "",
                    onSave = { date, et, n -> onSubmit { it.updateEncounter(row.id, date, et, n) } },
                )
                is LogStoolRow -> StoolForm(
                    saving,
                    initialDateTime = parseIsoToLocalDateTime(row.occurredAt),
                    initialBristolType = row.bristolType,
                    initialDiscomfort = row.discomfort,
                    onSave = { occurredAt, bt, d -> onSubmit { it.updateStool(row.id, occurredAt, bt, d) } },
                )
                is LogArousalRow -> ArousalForm(
                    saving,
                    initialDate = LocalDate.parse(row.date),
                    initialMorningWood = row.morningErectionQuality,
                    initialArousalLevel = row.arousalLevel,
                    onSave = { date, mw, al -> onSubmit { it.updateArousal(row.id, date, mw, al) } },
                )
                is LogNoteRow -> NoteForm(
                    saving,
                    initialDateTime = parseIsoToLocalDateTime(row.occurredAt),
                    initialText = row.text,
                    onSave = { occurredAt, text -> onSubmit { it.updateNote(row.id, occurredAt, text) } },
                )
                is LogWellbeingRow -> WellnessForm(
                    saving,
                    initialDate = LocalDate.parse(row.date),
                    initialEnergy = row.energy,
                    initialMood = row.mood,
                    initialStress = row.stress,
                    initialSoreness = row.soreness,
                    onSave = { date, e, m, s, so -> onSubmit { it.updateWellbeing(row.id, date, e, m, s, so) } },
                )
                is FullExerciseSessionRow -> ExerciseDetailsForm(
                    saving,
                    type = row.type,
                    initialRpe = row.rpe,
                    initialNotes = row.notes ?: "",
                    initialDetails = row.details,
                    onSave = { rpe, notes, details -> onSubmit { it.updateExerciseDetails(row.id, rpe, notes, details) } },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun parseIsoToLocalDateTime(iso: String): LocalDateTime =
    try {
        java.time.OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    } catch (e: Exception) {
        LocalDateTime.parse(iso)
    }

private fun LocalDateTime.toIsoWithOffset(): String = this.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

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

// FUEL: one entry point, three sub-tabs. Reuses FoodForm/DrinkForm/
// SupplementsForm exactly as they'd be used standalone -- only the picker
// wrapping them is new.
@Composable
private fun FuelForm(saving: Boolean, onSubmit: ((suspend (AddEntryRepository) -> Unit)) -> Unit) {
    var subType by remember { mutableStateOf(FuelSubType.Food) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelSubType.entries.forEach { sub ->
                val isSelected = sub == subType
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, if (isSelected) FieldColors.Amber else FieldColors.Hairline)
                        .background(if (isSelected) FieldColors.Amber.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { subType = sub }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(sub.label, style = FieldTextStyles.subTabLabel, color = if (isSelected) FieldColors.Amber else FieldColors.InkMuted)
                }
            }
        }
        when (subType) {
            FuelSubType.Food -> FoodForm(saving, onSave = { dt, desc, cal, p, c, f -> onSubmit { it.addFood(dt, desc, cal, p, c, f) } })
            FuelSubType.Drink -> DrinkForm(saving, onSave = { date, ml -> onSubmit { it.addDrink(date, ml) } })
            FuelSubType.Supplements -> SupplementsForm(saving, onSave = { takenAt, items -> onSubmit { it.addSupplementsTaken(takenAt, items) } })
        }
    }
}

@Composable
private fun FoodForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialDescription: String = "",
    initialCalories: Double? = null,
    initialProtein: Double? = null,
    initialCarbs: Double? = null,
    initialFat: Double? = null,
    onSave: (loggedAt: String, description: String, calories: Double?, proteinG: Double?, carbsG: Double?, fatG: Double?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var description by remember { mutableStateOf(initialDescription) }
    var calories by remember { mutableStateOf(initialCalories?.toString() ?: "") }
    var protein by remember { mutableStateOf(initialProtein?.toString() ?: "") }
    var carbs by remember { mutableStateOf(initialCarbs?.toString() ?: "") }
    var fat by remember { mutableStateOf(initialFat?.toString() ?: "") }

    val apiKey = remember { GeminiApiKeyStore.get(context) }
    var estimating by remember { mutableStateOf(false) }
    var estimationError by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun applyEstimate(estimate: FoodEstimate) {
        if (description.isBlank()) estimate.description?.let { description = it }
        estimate.calories?.let { calories = it.toString() }
        estimate.proteinG?.let { protein = it.toString() }
        estimate.carbsG?.let { carbs = it.toString() }
        estimate.fatG?.let { fat = it.toString() }
    }

    fun runEstimate(uri: Uri) {
        val key = apiKey ?: return
        estimating = true
        estimationError = null
        scope.launch {
            try {
                val bytes = readAndCompressImage(context, uri)
                applyEstimate(NutritionEstimationRepository(key).estimate(bytes))
            } catch (e: Exception) {
                estimationError = e.message ?: "Estimation failed"
            } finally {
                estimating = false
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCameraUri?.let { runEstimate(it) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createCameraCaptureUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } else {
            estimationError = "Camera permission denied"
        }
    }
    val pickPhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runEstimate(uri)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Column { FormLabel("DESCRIPTION"); FieldTextField(description, { description = it }, "e.g. Chicken rice bowl") }

        if (apiKey == null) {
            Text(
                "Set a Gemini API key in Setup to enable AI photo estimation.",
                style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
                color = FieldColors.InkMuted,
            )
        } else {
            Column {
                FormLabel("AI PHOTO ESTIMATION (OPTIONAL)")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhotoActionButton(label = "TAKE PHOTO", modifier = Modifier.weight(1f)) {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            val uri = createCameraCaptureUri(context)
                            pendingCameraUri = uri
                            takePictureLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                    PhotoActionButton(label = "CHOOSE PHOTO", modifier = Modifier.weight(1f)) {
                        pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                if (estimating) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        CircularProgressIndicator(color = FieldColors.Amber, modifier = Modifier.size(14.dp))
                        Text("Estimating from photo...", style = TextStyle(fontFamily = Saira, fontSize = 13.sp), color = FieldColors.InkMuted)
                    }
                }
                estimationError?.let {
                    Text(it, style = TextStyle(fontFamily = Saira, fontSize = 13.sp), color = FieldColors.Alert, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }

        Column { FormLabel("CALORIES (OPTIONAL)"); FieldTextField(calories, { calories = it }, "e.g. 650", keyboardType = KeyboardType.Number) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { FormLabel("PROTEIN G"); FieldTextField(protein, { protein = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("CARBS G"); FieldTextField(carbs, { carbs = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("FAT G"); FieldTextField(fat, { fat = it }, "0", keyboardType = KeyboardType.Number) }
        }
        SaveButton(saving, description.isNotBlank()) {
            onSave(dateTime.toIsoWithOffset(), description.trim(), calories.toDoubleOrNull(), protein.toDoubleOrNull(), carbs.toDoubleOrNull(), fat.toDoubleOrNull())
        }
    }
}

@Composable
private fun DrinkForm(saving: Boolean, initialDate: LocalDate = LocalDate.now(), initialMl: Int? = null, onSave: (date: String, ml: Int) -> Unit) {
    var date by remember { mutableStateOf(initialDate) }
    var ml by remember { mutableStateOf(initialMl?.toString() ?: "") }
    val mlValue = ml.toIntOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateField("DATE", date, { date = it })
        Column { FormLabel("AMOUNT (ML)"); FieldTextField(ml, { ml = it }, "e.g. 500", keyboardType = KeyboardType.Number) }
        SaveButton(saving, mlValue != null && mlValue > 0) {
            onSave(date.toString(), mlValue!!)
        }
    }
}

// "Bundled by time of day" (added 2026-09-15 per direct user request):
// morning/afternoon/night supplements are logged as one group -- checking
// the group logs every supplement in it, since you take them together, not
// individually. As-needed supplements get their own checkbox each, since
// which ones you take genuinely varies. Each checked supplement becomes its
// own supplement_log row on save, so afterward it's individually deletable
// from the Log feed like any other entry -- no separate "batch" concept
// needed for "add or remove single items."
@Composable
private fun SupplementsForm(saving: Boolean, initialDateTime: LocalDateTime = LocalDateTime.now(), onSave: (takenAt: String, items: List<Pair<Long, String>>) -> Unit) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var supplements by remember { mutableStateOf<List<SupplementRow>?>(null) }
    var checkedBundles by remember { mutableStateOf(setOf<String>()) }
    var checkedAsNeeded by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(Unit) {
        supplements = SupplementsRepository(SupabaseClientProvider.client).loadOverview().active
    }

    val list = supplements ?: emptyList()
    val groups = list.filter { it.timeOfDay != "as-needed" }.groupBy { it.timeOfDay }
    val asNeeded = list.filter { it.timeOfDay == "as-needed" }
    val selectedItems = buildList {
        checkedBundles.forEach { tod -> groups[tod]?.forEach { add(it.id to it.name) } }
        asNeeded.filter { it.id in checkedAsNeeded }.forEach { add(it.id to it.name) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        when {
            supplements == null -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FieldColors.Amber)
            }
            list.isEmpty() -> Text(
                "No active supplements to log.",
                style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                color = FieldColors.InkMuted,
            )
            else -> {
                listOf("morning", "afternoon", "night").forEach { timeOfDay ->
                    val itemsInGroup = groups[timeOfDay]
                    if (!itemsInGroup.isNullOrEmpty()) {
                        BundleToggleRow(
                            label = timeOfDay.uppercase(),
                            itemNames = itemsInGroup.joinToString(", ") { it.name },
                            checked = timeOfDay in checkedBundles,
                            onToggle = {
                                checkedBundles = if (timeOfDay in checkedBundles) checkedBundles - timeOfDay else checkedBundles + timeOfDay
                            },
                        )
                    }
                }
                if (asNeeded.isNotEmpty()) {
                    FormLabel("AS NEEDED")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        asNeeded.forEach { supp ->
                            CheckToggleRow(
                                label = supp.name,
                                checked = supp.id in checkedAsNeeded,
                                onToggle = {
                                    checkedAsNeeded = if (supp.id in checkedAsNeeded) checkedAsNeeded - supp.id else checkedAsNeeded + supp.id
                                },
                            )
                        }
                    }
                }
            }
        }
        SaveButton(saving, selectedItems.isNotEmpty()) {
            onSave(dateTime.toIsoWithOffset(), selectedItems)
        }
    }
}

@Composable
private fun BundleToggleRow(label: String, itemNames: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (checked) FieldColors.Green else FieldColors.Hairline)
            .background(if (checked) FieldColors.Green.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheckboxGlyph(checked)
        Column {
            Text(label, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 12.5.sp), color = FieldColors.Ink)
            Text(itemNames, style = TextStyle(fontFamily = Saira, fontSize = 13.sp), color = FieldColors.InkMuted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun CheckToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (checked) FieldColors.Green else FieldColors.Hairline)
            .background(if (checked) FieldColors.Green.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheckboxGlyph(checked)
        Text(label, style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.Medium, fontSize = 15.sp), color = FieldColors.Ink)
    }
}

@Composable
private fun CheckboxGlyph(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(1.dp, if (checked) FieldColors.Green else FieldColors.Hairline)
            .background(if (checked) FieldColors.Green else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", style = TextStyle(fontSize = 12.sp), color = FieldColors.Ground)
        }
    }
}

@Composable
private fun PhotoActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .border(1.dp, FieldColors.Amber)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = FieldTextStyles.subTabLabel, color = FieldColors.Amber)
    }
}

@Composable
private fun EncounterForm(
    saving: Boolean,
    initialDate: LocalDate = LocalDate.now(),
    initialType: String = "",
    initialNotes: String = "",
    onSave: (date: String, encounterType: String?, notes: String?) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    var encounterType by remember { mutableStateOf(initialType) }
    var notes by remember { mutableStateOf(initialNotes) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateField("DATE", date, { date = it })
        Column { FormLabel("TYPE (OPTIONAL)"); FieldTextField(encounterType, { encounterType = it }, "e.g. date, call, hangout") }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Notes...", singleLine = false) }
        SaveButton(saving, true) {
            onSave(date.toString(), encounterType.trim().ifBlank { null }, notes.trim().ifBlank { null })
        }
    }
}

@Composable
private fun StoolForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialBristolType: Int? = null,
    initialDiscomfort: Int? = null,
    onSave: (occurredAt: String, bristolType: Int, discomfort: Int?) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var bristolType by remember { mutableStateOf(initialBristolType) }
    var discomfort by remember { mutableStateOf(initialDiscomfort?.toString() ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
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
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp),
                            color = if (selected) FieldColors.Amber else FieldColors.InkMuted,
                        )
                    }
                }
            }
        }
        Column { FormLabel("DISCOMFORT 0-10 (OPTIONAL)"); FieldTextField(discomfort, { discomfort = it }, "e.g. 2", keyboardType = KeyboardType.Number) }
        SaveButton(saving, bristolType != null) {
            onSave(dateTime.toIsoWithOffset(), bristolType!!, discomfort.toIntOrNull())
        }
    }
}

@Composable
private fun ArousalForm(
    saving: Boolean,
    initialDate: LocalDate = LocalDate.now(),
    initialMorningWood: Int? = null,
    initialArousalLevel: Int? = null,
    onSave: (date: String, morningWood: Int, arousalLevel: Int) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    var morningWood by remember { mutableStateOf(initialMorningWood?.toString() ?: "5") }
    var arousalLevel by remember { mutableStateOf(initialArousalLevel?.toString() ?: "5") }
    val morningWoodValue = morningWood.toIntOrNull()
    val arousalValue = arousalLevel.toIntOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateField("DATE", date, { date = it })
        Column { FormLabel("MORNING WOOD (0-10)"); FieldTextField(morningWood, { morningWood = it }, "5", keyboardType = KeyboardType.Number) }
        Column { FormLabel("AROUSAL LEVEL (0-10)"); FieldTextField(arousalLevel, { arousalLevel = it }, "5", keyboardType = KeyboardType.Number) }
        val valid = morningWoodValue != null && morningWoodValue in 0..10 && arousalValue != null && arousalValue in 0..10
        SaveButton(saving, valid) {
            onSave(date.toString(), morningWoodValue!!, arousalValue!!)
        }
    }
}

@Composable
private fun WellnessForm(
    saving: Boolean,
    initialDate: LocalDate = LocalDate.now(),
    initialEnergy: Int? = null,
    initialMood: Int? = null,
    initialStress: Int? = null,
    initialSoreness: Int? = null,
    onSave: (date: String, energy: Int?, mood: Int?, stress: Int?, soreness: Int?) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    var energy by remember { mutableStateOf(initialEnergy?.toString() ?: "") }
    var mood by remember { mutableStateOf(initialMood?.toString() ?: "") }
    var stress by remember { mutableStateOf(initialStress?.toString() ?: "") }
    var soreness by remember { mutableStateOf(initialSoreness?.toString() ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateField("DATE", date, { date = it })
        Column { FormLabel("ENERGY 0-10 (OPTIONAL)"); FieldTextField(energy, { energy = it }, "e.g. 7", keyboardType = KeyboardType.Number) }
        Column { FormLabel("MOOD 0-10 (OPTIONAL)"); FieldTextField(mood, { mood = it }, "e.g. 7", keyboardType = KeyboardType.Number) }
        Column { FormLabel("STRESS 0-10 (OPTIONAL)"); FieldTextField(stress, { stress = it }, "e.g. 3", keyboardType = KeyboardType.Number) }
        Column { FormLabel("SORENESS 0-10 (OPTIONAL)"); FieldTextField(soreness, { soreness = it }, "e.g. 2", keyboardType = KeyboardType.Number) }
        val valid = listOf(energy, mood, stress, soreness).any { it.isNotBlank() }
        SaveButton(saving, valid) {
            onSave(date.toString(), energy.toIntOrNull(), mood.toIntOrNull(), stress.toIntOrNull(), soreness.toIntOrNull())
        }
    }
}

private val ROUTE_TYPE_OPTIONS = listOf("road", "trail", "mixed", "track")
private val RUN_TYPE_OPTIONS = listOf("easy", "tempo", "long", "hills", "intervals", "race", "recovery")

// Phase G3 + Phase B follow-up: Health Connect still supplies the time,
// distance, and heart rate for every session -- never editable here. RPE and
// notes were the only hand-entered fields until now; type='run' sessions now
// also get route_type/run_type chip pickers, and type='strength' sessions
// get a real exercises/sets editor, matching the exercise_sessions.details
// shapes Phase B's CHECK constraints enforce. Every other type keeps the
// original rpe/notes-only form -- no jsonb shape is spec'd for those.
@Composable
private fun ExerciseDetailsForm(
    saving: Boolean,
    type: String,
    initialRpe: Int? = null,
    initialNotes: String = "",
    initialDetails: ExerciseSessionDetails = ExerciseSessionDetails(),
    onSave: (rpe: Int?, notes: String?, details: ExerciseSessionDetails) -> Unit,
) {
    var rpe by remember { mutableStateOf(initialRpe?.toString() ?: "") }
    var notes by remember { mutableStateOf(initialNotes) }
    var routeType by remember { mutableStateOf(initialDetails.routeType) }
    var runType by remember { mutableStateOf(initialDetails.runType) }
    val exercises = remember {
        val seeded = initialDetails.exercises.orEmpty().map { it.toEditable() }
        mutableStateListOf(*seeded.ifEmpty { listOf(EditableExercise()) }.toTypedArray())
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Health Connect supplies the time, distance, and heart rate for this $type session — " +
                "everything below is entered by hand.",
            style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
            color = FieldColors.InkMuted,
        )

        if (type == "run") {
            Column { FormLabel("ROUTE (OPTIONAL)"); TextChipRow(ROUTE_TYPE_OPTIONS, routeType) { routeType = it } }
            Column { FormLabel("RUN TYPE (OPTIONAL)"); TextChipRow(RUN_TYPE_OPTIONS, runType) { runType = it } }
        }

        if (type == "strength") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FormLabel("EXERCISES")
                exercises.forEachIndexed { i, exercise -> ExerciseEditor(exercise, canRemove = exercises.size > 1) { exercises.removeAt(i) } }
                Text(
                    "+ ADD EXERCISE",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp),
                    color = FieldColors.Amber,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { exercises.add(EditableExercise()) },
                )
            }
        }

        Column { FormLabel("RPE 0-10 (OPTIONAL)"); FieldTextField(rpe, { rpe = it }, "e.g. 6", keyboardType = KeyboardType.Number) }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Anything else worth noting", singleLine = false) }
        SaveButton(saving, true) {
            val details = ExerciseSessionDetails(
                routeType = routeType,
                runType = runType,
                exercises = if (type == "strength") exercises.mapNotNull { it.toDtoOrNull() }.ifEmpty { null } else initialDetails.exercises,
            )
            onSave(rpe.toIntOrNull(), notes.trim().ifBlank { null }, details)
        }
    }
}

// Tap-to-select chip row over a small fixed vocabulary, generalizing
// StoolForm's Bristol-type row (below) to string values -- these fields are
// optional (unlike Bristol), so tapping the already-selected chip again
// clears it back to null rather than always leaving exactly one selected.
@Composable
private fun TextChipRow(options: List<String>, selected: String?, perRow: Int = 4, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(perRow).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowOptions.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, if (isSelected) FieldColors.Amber else FieldColors.Hairline)
                            .background(if (isSelected) FieldColors.Amber.copy(alpha = 0.18f) else FieldColors.RaisedSurface)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(if (isSelected) null else option) }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option.uppercase(),
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                            color = if (isSelected) FieldColors.Amber else FieldColors.InkMuted,
                        )
                    }
                }
                repeat(perRow - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// Local editable mirror of StrengthExerciseDto/StrengthSetDto -- text-field-
// backed (reps/weight_kg parse to Int/Double only on Save) since a set
// mid-entry is routinely blank or partial, which the DTO's non-nullable
// reps/weight_kg can't represent.
private class EditableSet(reps: String = "", weightKg: String = "", rpe: String = "", percentOneRm: String = "") {
    var reps by mutableStateOf(reps)
    var weightKg by mutableStateOf(weightKg)
    var rpe by mutableStateOf(rpe)
    var percentOneRm by mutableStateOf(percentOneRm)
}

private class EditableExercise(name: String = "") {
    var name by mutableStateOf(name)
    val sets = mutableStateListOf(EditableSet())
}

private fun StrengthExerciseDto.toEditable() = EditableExercise(name).also { editable ->
    editable.sets.clear()
    sets.forEach { s -> editable.sets.add(EditableSet(s.reps.toString(), s.weightKg.toString(), s.rpe?.toString() ?: "", s.percentOneRm?.toString() ?: "")) }
}

// Drops a set with no reps/weight rather than saving a garbage 0; drops an
// exercise with no valid sets entirely rather than saving an empty shell.
private fun EditableExercise.toDtoOrNull(): StrengthExerciseDto? {
    if (name.isBlank()) return null
    val validSets = sets.mapNotNull { s ->
        val reps = s.reps.toIntOrNull()
        val weightKg = s.weightKg.toDoubleOrNull()
        if (reps == null || weightKg == null) null
        else StrengthSetDto(reps = reps, weightKg = weightKg, rpe = s.rpe.toIntOrNull(), percentOneRm = s.percentOneRm.toDoubleOrNull())
    }
    return if (validSets.isEmpty()) null else StrengthExerciseDto(name.trim(), validSets)
}

// TODO(human): render one exercise's editable form -- the name field, its
// list of sets, and the add-set/remove-set controls.
//
// `exercise` is the live EditableExercise (its `name` is a mutableStateOf
// String, `sets` is a mutableStateListOf<EditableSet>, each EditableSet's
// reps/weightKg/rpe/percentOneRm are also mutableStateOf String -- mutate
// them directly, Compose will recompose). `onRemove` removes this whole
// exercise from the list one level up; only call it when `canRemove` is
// true (the form always keeps at least one exercise row on screen).
//
// Reuse FieldTextField(value, onValueChange, placeholder, modifier, keyboardType,
// singleLine) for every field -- KeyboardType.Number for reps/weightKg/rpe/
// percentOneRm. A real layout choice is yours: how much of each set's four
// fields to show side-by-side in one Row (reps and weight_kg are required;
// rpe and percent_1rm are optional per the handoff and could be visually
// secondary, e.g. smaller/narrower), and how "add set" / "remove set" should
// read (a text link like the "+ ADD EXERCISE" control above it, or something
// else consistent with this file's plain-text-button style -- there are no
// icons anywhere in this file).
@Composable
private fun ExerciseEditor(exercise: EditableExercise, canRemove: Boolean, onRemove: () -> Unit) {
}

@Composable
private fun NoteForm(saving: Boolean, initialDateTime: LocalDateTime = LocalDateTime.now(), initialText: String = "", onSave: (occurredAt: String, text: String) -> Unit) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var text by remember { mutableStateOf(initialText) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Column { FormLabel("NOTE"); FieldTextField(text, { text = it }, "Write a note...", singleLine = false) }
        SaveButton(saving, text.isNotBlank()) {
            onSave(dateTime.toIsoWithOffset(), text.trim())
        }
    }
}
