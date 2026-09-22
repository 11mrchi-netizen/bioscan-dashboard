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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.ExerciseLibraryRepository
import com.bioscan.fieldterminal.data.NutritionBarcodeLookupRepository
import com.bioscan.fieldterminal.data.NutritionImageEstimateRepository
import com.bioscan.fieldterminal.data.NutritionMealSaveRepository
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.NutritionTextEstimateRepository
import com.bioscan.fieldterminal.data.MealItemSource
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.model.ExerciseLibraryMatch
import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetails
import com.bioscan.fieldterminal.data.model.FullExerciseSessionRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import com.bioscan.fieldterminal.data.model.StrengthSetDto
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogOstrcRow
import com.bioscan.fieldterminal.data.model.LogMasturbationRow
import com.bioscan.fieldterminal.data.model.LogSleepDetailRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import com.bioscan.fieldterminal.data.model.LogSupplementTakenRow
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.AddEntryType
import com.bioscan.fieldterminal.domain.FuelSubType
import com.bioscan.fieldterminal.domain.LogEntry
import com.bioscan.fieldterminal.domain.LogSource
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.DateTimeField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.util.createCameraCaptureUri
import com.bioscan.fieldterminal.util.readAndCompressImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Shared text styles for this sheet's own chrome (sheet titles, action/
// confirm labels) -- DAV-108 follow-up migration off FieldTextStyles'
// legacy JetBrainsMono onto the FT contract's Inter/Roboto Mono split.
private val sheetHeaderTitleStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private val sheetActionLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)

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
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val type = selectedType
            if (type == null) {
                Text("LOG NEW ENTRY", style = sheetHeaderTitleStyle, color = FT.DomainLog)
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
                    AddEntryType.Fuel -> FuelForm(saving, onSubmit, onCanonicalSaved = onSaved)
                    AddEntryType.Encounter -> EncounterForm(
                        saving,
                        onSave = { date, occurredAt, et, loc, dur, acts, rating, n ->
                            onSubmit { it.addEncounter(date = date, encounterType = et, notes = n, occurredAt = occurredAt, locationType = loc, durationMin = dur, activities = acts, myRating = rating) }
                        },
                    )
                    AddEntryType.Stool -> StoolForm(saving, onSave = { occurredAt, bt, d -> onSubmit { it.addStool(occurredAt, bt, d) } })
                    AddEntryType.Arousal -> ArousalForm(saving, onSave = { date, mw, al -> onSubmit { it.addArousal(date, mw, al) } })
                    AddEntryType.Wellness -> WellnessForm(saving, onSave = { date, e, m, s, so -> onSubmit { it.addWellbeing(date, e, m, s, so) } })
                    AddEntryType.Note -> NoteForm(saving, onSave = { occurredAt, text -> onSubmit { it.addNote(occurredAt, text) } })
                    AddEntryType.Ostrc -> OstrcForm(saving, onSave = { date, ba, q1, q2, q3, q4, n -> onSubmit { it.addOstrc(date, ba, q1, q2, q3, q4, n) } })
                    AddEntryType.Masturbation -> MasturbationForm(saving, onSave = { occurredAt, wp, ls, oi, n -> onSubmit { it.addMasturbation(occurredAt, wp, ls, oi, n) } })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// Tapping a Log entry opens this first -- EDIT (when the source has a
// corresponding form) and DELETE, with an inline confirm step rather than a
// second popup. Sleep has no add-entry form and no editable fields, so it
// stays delete-only. Supplement (DAV-156) is editable now -- dose is a real
// field, even though which supplement was taken still isn't (that's "delete
// and re-log," not "edit"). Exercise (Phase G3) IS editable -- not its
// Health-Connect-sourced fields, just rpe/notes via ExerciseDetailsForm.
// Phase G4 adds a DETAIL action for Exercise entries, pushing SessionDetailScreen.
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
    val editable = entry.source != LogSource.Sleep

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(entry.headline, style = sheetHeaderTitleStyle, color = FT.DomainLog)

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
                        .border(FT.BorderWidth, FT.Critical, RoundedCornerShape(FT.RadiusSmall))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { confirmingDelete = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("DELETE", style = sheetActionLabelStyle, color = FT.Critical)
                }
            } else {
                Text(
                    "Delete this entry? This can't be undone.",
                    style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp),
                    color = FT.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { confirmingDelete = false }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("CANCEL", style = sheetActionLabelStyle, color = FT.TextSecondary)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(FT.Critical, RoundedCornerShape(FT.RadiusSmall))
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
                        Text(if (deleting) "DELETING..." else "CONFIRM", style = sheetActionLabelStyle, color = FT.Base)
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
            LogSource.Ostrc -> repo.fetchOstrc(entry.id)
            LogSource.Masturbation -> repo.fetchMasturbation(entry.id)
            LogSource.Supplement -> repo.fetchSupplementTaken(entry.id)
            LogSource.Sleep -> null // no edit form; EntryActionSheet never offers EDIT for this
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
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
                    CircularProgressIndicator(color = FT.DomainLog)
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
                    initialFiber = row.fiberG,
                    initialSugar = row.sugarG,
                    initialSodium = row.sodiumMg,
                    onSave = { dt, desc, cal, p, c, f, fi, su, so -> onSubmit { it.updateFood(row.id, dt, desc, cal, p, c, f, fi, su, so) } },
                )
                is LogHydrationRow -> DrinkForm(
                    saving,
                    initialDate = LocalDate.parse(row.date),
                    initialMl = row.ml,
                    onSave = { date, ml -> onSubmit { it.updateDrink(row.id, date, ml) } },
                )
                is LogEncounterRow -> EncounterForm(
                    saving,
                    initialDateTime = row.occurredAt?.let { parseIsoToLocalDateTime(it) } ?: LocalDateTime.of(LocalDate.parse(row.date), java.time.LocalTime.of(21, 0)),
                    initialType = row.encounterType ?: "",
                    initialLocationType = row.locationType ?: "",
                    initialDurationMin = row.durationMin,
                    initialActivities = row.activities?.joinToString(", ") ?: "",
                    initialMyRating = row.myRating,
                    initialNotes = row.notes ?: "",
                    onSave = { date, occurredAt, et, loc, dur, acts, rating, n ->
                        onSubmit { it.updateEncounter(row.id, date, occurredAt, et, loc, dur, acts, rating, n) }
                    },
                )
                is LogStoolRow -> StoolForm(
                    saving,
                    initialDateTime = parseIsoToLocalDateTime(row.occurredAt),
                    initialBristolType = row.bristolType,
                    initialDiscomfort = row.discomfort,
                    onSave = { occurredAt, bt, d -> onSubmit { it.updateStool(row.id, occurredAt, bt, d) } },
                )
                is LogMasturbationRow -> MasturbationForm(
                    saving,
                    initialDateTime = parseIsoToLocalDateTime(row.occurredAt),
                    initialWatchedPorn = row.watchedPorn,
                    initialLoadSize = row.loadSize,
                    initialOrgasmIntensity = row.orgasmIntensity,
                    initialNotes = row.notes ?: "",
                    onSave = { occurredAt, wp, ls, oi, n -> onSubmit { it.updateMasturbation(row.id, occurredAt, wp, ls, oi, n) } },
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
                is LogOstrcRow -> OstrcForm(
                    saving,
                    initialDate = LocalDate.parse(row.checkDate),
                    initialBodyArea = row.bodyArea,
                    initialQ1 = row.q1,
                    initialQ2 = row.q2,
                    initialQ3 = row.q3,
                    initialQ4 = row.q4,
                    initialNotes = row.notes ?: "",
                    onSave = { date, ba, q1, q2, q3, q4, n -> onSubmit { it.updateOstrc(row.id, date, ba, q1, q2, q3, q4, n) } },
                )
                is LogSupplementTakenRow -> SupplementEditForm(
                    saving,
                    supplementName = row.supplementName,
                    initialDateTime = parseIsoToLocalDateTime(row.takenAt),
                    initialDoseValue = row.doseValue,
                    initialDoseUnit = row.doseUnit ?: "",
                    onSave = { takenAt, doseValue, doseUnit -> onSubmit { it.updateSupplementTaken(row.id, takenAt, doseValue, doseUnit) } },
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

// DAV-160. Read-only detail view for Sleep, opened via the same DETAIL
// action Exercise already uses (EntryActionSheet's onViewDetail) rather than
// a new nav route -- a bottom sheet is proportionate for a handful of read-
// only stats, matching every other sheet in this file. Sleep still has no
// edit form (see LogSource's own doc comment); this only reads.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepDetailSheet(entryId: Long, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var row by remember { mutableStateOf<LogSleepDetailRow?>(null) }

    LaunchedEffect(entryId) {
        row = AddEntryRepository(SupabaseClientProvider.client).fetchSleepDetail(entryId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            SheetBackHeader(label = "SLEEP DETAIL", onBack = onDismiss)
            Spacer(Modifier.height(16.dp))
            val r = row
            if (r == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.DomainLog)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SleepDetailLine("Duration", formatSleepMinutes(r.hours?.let { it * 60 }))
                    SleepDetailLine("Bedtime", r.bedtime?.let { formatSleepTime(it) } ?: "—")
                    SleepDetailLine("Wake time", r.wakeTime?.let { formatSleepTime(it) } ?: "—")
                    SleepDetailLine("Deep sleep", formatSleepMinutes(r.deepMin))
                    SleepDetailLine("REM sleep", formatSleepMinutes(r.remMin))
                    SleepDetailLine("Light sleep", formatSleepMinutes(r.lightMin))
                    SleepDetailLine("Respiratory rate", r.respiratoryRate?.let { "%.1f breaths/min".format(it) } ?: "—")
                    SleepDetailLine("Score", r.score?.toString() ?: "—")
                    SleepDetailLine("Source", r.source?.replace("_", " ")?.uppercase() ?: "—")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SleepDetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextSecondary)
        Text(
            value,
            style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp),
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

// "—" distinguishes a genuinely missing metric (null) from a real zero,
// per this ticket's own requirement -- never render "0m" for a metric
// Health Connect simply didn't report for this night.
private fun formatSleepMinutes(minutes: Double?): String {
    if (minutes == null) return "—"
    val h = (minutes / 60).toInt()
    val m = (minutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// Same naive local-wall-clock convention every other real timestamp in this
// app uses (see parseIsoToLocalDateTime above) -- bedtime/wake_time are
// written by HealthConnectDailySyncRepository following that same rule.
private fun formatSleepTime(iso: String): String =
    parseIsoToLocalDateTime(iso).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))

// Every real-time-of-day column in this app is written as the device's
// local wall-clock reading labeled with a fake zero/UTC offset -- readers
// everywhere else (domain/Log.kt's parseTimestamp, Training.kt, etc.) take
// the digits as-is and never apply a real zone conversion. These two
// functions used to use the device's REAL offset instead (atZone(...)), which
// is "more correct" in isolation but wrong for this app: Postgres normalizes
// a timestamptz to true UTC on write regardless of the offset you send, so a
// real +08:00 offset got silently converted to a real UTC value in storage,
// which every naive reader then misread as local -- an 8-hour error, real
// on-device bug (a supplement logged at 07:42 local showed up as 23:42 the
// previous day). Matching the naive convention here, on both read and write,
// fixes it without touching any of the naive readers.
private fun parseIsoToLocalDateTime(iso: String): LocalDateTime =
    try {
        java.time.OffsetDateTime.parse(iso).toLocalDateTime()
    } catch (e: Exception) {
        LocalDateTime.parse(iso)
    }

private fun LocalDateTime.toIsoWithOffset(): String = this.atOffset(java.time.ZoneOffset.UTC).toString()

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
private fun TypePickerGrid(onSelect: (AddEntryType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AddEntryType.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { type ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(type) }
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(type.label, style = sheetActionLabelStyle, color = FT.TextPrimary)
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
        style = sheetActionLabelStyle,
        color = FT.TextSecondary,
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
private fun FuelForm(saving: Boolean, onSubmit: ((suspend (AddEntryRepository) -> Unit)) -> Unit, onCanonicalSaved: () -> Unit) {
    var subType by remember { mutableStateOf(FuelSubType.Food) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelSubType.entries.forEach { sub ->
                val isSelected = sub == subType
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .border(FT.BorderWidth, if (isSelected) FT.DomainLog else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                        .background(if (isSelected) FT.DomainLog.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(FT.RadiusSmall))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { subType = sub }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(sub.label, style = sheetActionLabelStyle, color = if (isSelected) FT.DomainLog else FT.TextSecondary)
                }
            }
        }
        when (subType) {
            FuelSubType.Food -> FoodForm(
                saving,
                onSave = { dt, desc, cal, p, c, f, fi, su, so -> onSubmit { it.addFood(dt, desc, cal, p, c, f, fi, su, so) } },
                onCanonicalSaved = onCanonicalSaved,
            )
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
    initialFiber: Double? = null,
    initialSugar: Double? = null,
    initialSodium: Double? = null,
    onSave: (
        loggedAt: String,
        description: String,
        calories: Double?,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
        fiberG: Double?,
        sugarG: Double?,
        sodiumMg: Double?,
    ) -> Unit,
    // DAV-168: the canonical (foods/meal_items) save path doesn't go through
    // AddEntryRepository at all -- NutritionMealSaveRepository writes
    // directly -- so it can't reuse onSave's addFood-shaped contract above.
    // This is the same "the whole sheet is done" signal AddEntrySheet's own
    // onSaved already is, just reachable from inside this nested form.
    onCanonicalSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var description by remember { mutableStateOf(initialDescription) }
    var calories by remember { mutableStateOf(initialCalories?.toString() ?: "") }
    var protein by remember { mutableStateOf(initialProtein?.toString() ?: "") }
    var carbs by remember { mutableStateOf(initialCarbs?.toString() ?: "") }
    var fat by remember { mutableStateOf(initialFat?.toString() ?: "") }
    var fiber by remember { mutableStateOf(initialFiber?.toString() ?: "") }
    var sugar by remember { mutableStateOf(initialSugar?.toString() ?: "") }
    var sodium by remember { mutableStateOf(initialSodium?.toString() ?: "") }

    var estimating by remember { mutableStateOf(false) }
    var estimationError by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // DAV-168: candidates from any of the three sources funnel into the
    // same review sheet before ever touching meal_items.
    var reviewSeedItems by remember { mutableStateOf<List<ReviewSeedItem>?>(null) }
    var barcodeInput by remember { mutableStateOf("") }
    var barcodeLooking by remember { mutableStateOf(false) }
    var barcodeError by remember { mutableStateOf<String?>(null) }
    var showRecentMeals by remember { mutableStateOf(false) }
    var recentMeals by remember { mutableStateOf<List<MealRow>?>(null) }
    var cloning by remember { mutableStateOf(false) }

    fun runImageEstimate(uri: Uri) {
        estimating = true
        estimationError = null
        scope.launch {
            try {
                val bytes = readAndCompressImage(context, uri)
                val result = NutritionImageEstimateRepository(SupabaseClientProvider.client).estimate(bytes)
                reviewSeedItems = result.candidates.map { c ->
                    ReviewSeedItem(
                        description = c.description,
                        quantityLow = c.quantityLow,
                        quantityHigh = c.quantityHigh,
                        quantityUnit = c.quantityUnit,
                        isBeverage = c.isBeverage,
                        foodConfidence = c.foodConfidence,
                        portionConfidence = c.portionConfidence,
                        ambiguous = c.ambiguous,
                        source = MealItemSource.AiImage,
                        aiEstimateId = result.estimateId,
                    )
                }
            } catch (e: Exception) {
                estimationError = e.message ?: "Estimation failed"
            } finally {
                estimating = false
            }
        }
    }

    // DAV-90/DAV-166: same idea, no photo required -- estimate straight
    // from whatever's typed in DESCRIPTION, now via the server-side
    // candidates-only Gemini path instead of NutritionEstimationRepository.
    fun runTextEstimate() {
        if (description.isBlank()) return
        estimating = true
        estimationError = null
        scope.launch {
            try {
                val result = NutritionTextEstimateRepository(SupabaseClientProvider.client).estimate(description)
                reviewSeedItems = result.candidates.map { c ->
                    ReviewSeedItem(
                        description = c.description,
                        quantityValue = c.quantityValue,
                        quantityUnit = c.quantityUnit,
                        quantityLow = c.quantityLow,
                        quantityHigh = c.quantityHigh,
                        isBeverage = c.isBeverage,
                        foodConfidence = c.foodConfidence,
                        portionConfidence = c.portionConfidence,
                        ambiguous = c.ambiguous,
                        source = MealItemSource.AiText,
                        aiEstimateId = result.estimateId,
                    )
                }
            } catch (e: Exception) {
                estimationError = e.message ?: "Estimation failed"
            } finally {
                estimating = false
            }
        }
    }

    fun runBarcodeLookup() {
        val barcode = barcodeInput.trim()
        if (barcode.isBlank()) return
        barcodeLooking = true
        barcodeError = null
        scope.launch {
            try {
                val food = NutritionBarcodeLookupRepository(SupabaseClientProvider.client).lookup(barcode)
                if (food == null) {
                    barcodeError = "Barcode not recognized -- try search instead."
                } else {
                    reviewSeedItems = listOf(
                        ReviewSeedItem(
                            description = food.name,
                            isBeverage = food.beverageClass != null,
                            foodConfidence = 1.0,
                            source = MealItemSource.Barcode,
                            preMatchedFood = com.bioscan.fieldterminal.data.model.FoodRow(
                                id = food.id,
                                foodSourceId = 0,
                                name = food.name,
                                brand = food.brand,
                                barcode = food.barcode,
                                category = food.category,
                                beverageClass = food.beverageClass,
                                beverageSubtype = food.beverageSubtype,
                            ),
                        ),
                    )
                }
            } catch (e: Exception) {
                barcodeError = e.message ?: "Lookup failed"
            } finally {
                barcodeLooking = false
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCameraUri?.let { runImageEstimate(it) }
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
        if (uri != null) runImageEstimate(uri)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Column { FormLabel("DESCRIPTION"); FieldTextField(description, { description = it }, "e.g. Chicken rice bowl") }

        // DAV-168: this section now talks to the server-side Gemini/barcode
        // pipeline (DAV-165/166/167) -- no local Gemini key needed anymore,
        // unlike the plain-macro-entry fields further down which are
        // untouched. A candidate from any of these three actions opens the
        // review sheet; nothing here writes to `meals` directly.
        Column {
            FormLabel("LOG WITH AI OR BARCODE")
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
            PhotoActionButton(
                label = "FROM DESCRIPTION",
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                enabled = description.isNotBlank(),
            ) { runTextEstimate() }
            if (estimating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.size(14.dp))
                    Text("Estimating...", style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.TextSecondary)
                }
            }
            estimationError?.let {
                Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.Critical, modifier = Modifier.padding(top = 10.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                FieldTextField(
                    barcodeInput,
                    { barcodeInput = it },
                    "Barcode number",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                )
                PhotoActionButton(label = if (barcodeLooking) "..." else "LOOKUP", enabled = barcodeInput.isNotBlank() && !barcodeLooking) {
                    runBarcodeLookup()
                }
            }
            barcodeError?.let {
                Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.Critical, modifier = Modifier.padding(top = 8.dp))
            }

            Text(
                if (showRecentMeals) "HIDE RECENT MEALS" else "REPEAT A RECENT MEAL",
                style = sheetActionLabelStyle,
                color = FT.TextSecondary,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        showRecentMeals = !showRecentMeals
                    },
            )
            if (showRecentMeals) {
                LaunchedEffect(Unit) {
                    if (recentMeals == null) {
                        recentMeals = try { NutritionRepository(SupabaseClientProvider.client).loadRecentMeals() } catch (e: Exception) { emptyList() }
                    }
                }
                val meals = recentMeals
                if (meals == null) {
                    CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.padding(top = 8.dp).size(14.dp))
                } else if (meals.isEmpty()) {
                    Text("No recent meals yet.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        meals.forEach { meal ->
                            Text(
                                meal.description ?: "Meal",
                                style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                                color = if (cloning) FT.TextMuted else FT.TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !cloning) {
                                        val id = meal.id ?: return@clickable
                                        cloning = true
                                        scope.launch {
                                            try {
                                                NutritionMealSaveRepository(SupabaseClientProvider.client).cloneMeal(id, dateTime.toIsoWithOffset())
                                                onCanonicalSaved()
                                            } catch (e: Exception) {
                                                barcodeError = e.message ?: "Could not repeat this meal"
                                            } finally {
                                                cloning = false
                                            }
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        Column { FormLabel("CALORIES (OPTIONAL)"); FieldTextField(calories, { calories = it }, "e.g. 650", keyboardType = KeyboardType.Number) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { FormLabel("PROTEIN G"); FieldTextField(protein, { protein = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("CARBS G"); FieldTextField(carbs, { carbs = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("FAT G"); FieldTextField(fat, { fat = it }, "0", keyboardType = KeyboardType.Number) }
        }
        // DAV-77: kept to fiber/sugar/sodium -- the smallest real
        // "beyond macros" set, not the dozens of vitamins/minerals a
        // photo/description estimate can't plausibly guess anyway.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { FormLabel("FIBER G"); FieldTextField(fiber, { fiber = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("SUGAR G"); FieldTextField(sugar, { sugar = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("SODIUM MG"); FieldTextField(sodium, { sodium = it }, "0", keyboardType = KeyboardType.Number) }
        }
        SaveButton(saving, description.isNotBlank()) {
            onSave(
                dateTime.toIsoWithOffset(),
                description.trim(),
                calories.toDoubleOrNull(),
                protein.toDoubleOrNull(),
                carbs.toDoubleOrNull(),
                fat.toDoubleOrNull(),
                fiber.toDoubleOrNull(),
                sugar.toDoubleOrNull(),
                sodium.toDoubleOrNull(),
            )
        }
    }

    reviewSeedItems?.let { seeds ->
        NutritionCandidateReviewSheet(
            seedItems = seeds,
            mealDateTime = dateTime,
            onDismiss = { reviewSeedItems = null },
            onSaved = {
                reviewSeedItems = null
                onCanonicalSaved()
            },
        )
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
private fun SupplementsForm(saving: Boolean, initialDateTime: LocalDateTime = LocalDateTime.now(), onSave: (takenAt: String, items: List<Triple<Long, String, String>>) -> Unit) {
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
    // DAV-156: each selected item carries its roster dose string along so
    // addSupplementsTaken can parse a real per-supplement dose default --
    // Creatine's "5 g" and Boron's "10 mg" are different doses, so this has
    // to travel per-item, not as one shared field for the whole save.
    val selectedItems = buildList {
        checkedBundles.forEach { tod -> groups[tod]?.forEach { add(Triple(it.id, it.name, it.dose)) } }
        asNeeded.filter { it.id in checkedAsNeeded }.forEach { add(Triple(it.id, it.name, it.dose)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        when {
            supplements == null -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.DomainLog)
            }
            list.isEmpty() -> Text(
                "No active supplements to log.",
                style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                color = FT.TextSecondary,
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

// DAV-156. The first edit form Supplement has ever had -- previously
// delete-only, since a taken supplement was "add or remove as a whole," but
// dose being a real editable field makes it field-editable now too. Name
// isn't editable here (picking a different supplement is "delete and
// re-log," not "edit"), but WHEN is, matching every other manual log type
// per DAV-157.
@Composable
private fun SupplementEditForm(
    saving: Boolean,
    supplementName: String,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialDoseValue: Double? = null,
    initialDoseUnit: String = "",
    onSave: (takenAt: String, doseValue: Double?, doseUnit: String?) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var doseValue by remember { mutableStateOf(initialDoseValue?.let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() } ?: "") }
    var doseUnit by remember { mutableStateOf(initialDoseUnit) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(supplementName, style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp), color = FT.TextPrimary)
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { FormLabel("DOSE (OPTIONAL)"); FieldTextField(doseValue, { doseValue = it }, "e.g. 500", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("UNIT (OPTIONAL)"); FieldTextField(doseUnit, { doseUnit = it }, "e.g. mg") }
        }
        SaveButton(saving, true) {
            onSave(dateTime.toIsoWithOffset(), doseValue.toDoubleOrNull(), doseUnit.trim().ifBlank { null })
        }
    }
}

@Composable
private fun BundleToggleRow(label: String, itemNames: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, if (checked) FT.Emerald else FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .background(if (checked) FT.Emerald.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(FT.RadiusModule))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheckboxGlyph(checked)
        Column {
            Text(label, style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 12.5.sp), color = FT.TextPrimary)
            Text(itemNames, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun CheckToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, if (checked) FT.Emerald else FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .background(if (checked) FT.Emerald.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(FT.RadiusModule))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheckboxGlyph(checked)
        Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp), color = FT.TextPrimary)
    }
}

@Composable
private fun CheckboxGlyph(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(1.dp, if (checked) FT.Emerald else FT.GlassBorder)
            .background(if (checked) FT.Emerald else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", style = TextStyle(fontSize = 12.sp), color = FT.Base)
        }
    }
}

@Composable
private fun PhotoActionButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) FT.DomainLog else FT.GlassBorder
    Box(
        modifier = modifier
            .border(FT.BorderWidth, color, RoundedCornerShape(FT.RadiusSmall))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = sheetActionLabelStyle, color = color)
    }
}

// DAV-158: DateTimeField instead of the old date-only DateField -- encounters
// previously had no time-of-day in storage at all (see the fake
// ENCOUNTER_NOMINAL_TIME fallback in domain/Log.kt), just a bare DATE column.
// DAV-159: location/duration/activities/rating restore real columns
// (`encounters.location_type`/`duration_min`/`activities`/`my_rating`) that
// already existed in the schema but had no UI anywhere. Of the schema's three
// separate rating columns (my_rating/physical_rating/connection_rating,
// confirmed unused in every real row), only my_rating is surfaced here --
// "exactly one rating system" per this ticket's acceptance criterion.
@Composable
private fun EncounterForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialType: String = "",
    initialLocationType: String = "",
    initialDurationMin: Int? = null,
    initialActivities: String = "",
    initialMyRating: Int? = null,
    initialNotes: String = "",
    onSave: (
        date: String,
        occurredAt: String,
        encounterType: String?,
        locationType: String?,
        durationMin: Int?,
        activities: List<String>?,
        myRating: Int?,
        notes: String?,
    ) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var encounterType by remember { mutableStateOf(initialType) }
    var locationType by remember { mutableStateOf(initialLocationType) }
    var durationMin by remember { mutableStateOf(initialDurationMin?.toString() ?: "") }
    var activities by remember { mutableStateOf(initialActivities) }
    var myRating by remember { mutableStateOf(initialMyRating) }
    var notes by remember { mutableStateOf(initialNotes) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Column { FormLabel("TYPE (OPTIONAL)"); FieldTextField(encounterType, { encounterType = it }, "e.g. date, call, hangout") }
        Column { FormLabel("LOCATION (OPTIONAL)"); FieldTextField(locationType, { locationType = it }, "e.g. their place, restaurant") }
        Column { FormLabel("DURATION MIN (OPTIONAL)"); FieldTextField(durationMin, { durationMin = it }, "e.g. 90", keyboardType = KeyboardType.Number) }
        Column { FormLabel("ACTIVITIES (OPTIONAL)"); FieldTextField(activities, { activities = it }, "comma-separated, e.g. dinner, movie") }
        Column { FormLabel("RATING 1-5 (OPTIONAL)"); IntChipRow(1..5, myRating) { myRating = it } }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Notes...", singleLine = false) }
        SaveButton(saving, true) {
            onSave(
                dateTime.toLocalDate().toString(),
                dateTime.toIsoWithOffset(),
                encounterType.trim().ifBlank { null },
                locationType.trim().ifBlank { null },
                durationMin.toIntOrNull(),
                activities.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { null },
                myRating,
                notes.trim().ifBlank { null },
            )
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
        Column { FormLabel("BRISTOL TYPE (1-7)"); IntChipRow(1..7, bristolType) { bristolType = it } }
        Column { FormLabel("DISCOMFORT 0-10 (OPTIONAL)"); FieldTextField(discomfort, { discomfort = it }, "e.g. 2", keyboardType = KeyboardType.Number) }
        SaveButton(saving, bristolType != null) {
            onSave(dateTime.toIsoWithOffset(), bristolType!!, discomfort.toIntOrNull())
        }
    }
}

// Extracted from StoolForm's own Bristol-scale chip row -- a small bounded
// integer scale is also exactly what Encounter's 1-5 rating needs, so this
// is shared rather than a second copy of the same 20 lines.
@Composable
private fun IntChipRow(range: IntRange, selected: Int?, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in range) {
            val isSelected = selected == n
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(FT.BorderWidth, if (isSelected) FT.DomainLog else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                    .background(if (isSelected) FT.DomainLog.copy(alpha = 0.18f) else FT.GlassFill, RoundedCornerShape(FT.RadiusSmall))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(n) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n",
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp),
                    color = if (isSelected) FT.DomainLog else FT.TextSecondary,
                )
            }
        }
    }
}

private val YES_NO_OPTIONS = listOf("Yes", "No")

// DAV-91. Alongside the existing Arousal logging, but its own table
// (masturbation_log) rather than columns on arousal_daily -- that table's
// real unique(user_id, date) constraint makes it a once-per-day row, and
// this can genuinely happen more than once in a day.
@Composable
private fun MasturbationForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialWatchedPorn: Boolean? = null,
    initialLoadSize: Int? = null,
    initialOrgasmIntensity: Int? = null,
    initialNotes: String = "",
    onSave: (occurredAt: String, watchedPorn: Boolean, loadSize: Int?, orgasmIntensity: Int?, notes: String?) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var watchedPorn by remember { mutableStateOf(initialWatchedPorn?.let { if (it) "Yes" else "No" }) }
    var loadSize by remember { mutableStateOf(initialLoadSize?.toString()) }
    var orgasmIntensity by remember { mutableStateOf(initialOrgasmIntensity?.toString() ?: "") }
    var notes by remember { mutableStateOf(initialNotes) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Column { FormLabel("WATCHED PORN"); TextChipRow(YES_NO_OPTIONS, watchedPorn) { watchedPorn = it } }
        Column { FormLabel("LOAD SIZE 1-5 (OPTIONAL)"); TextChipRow(listOf("1", "2", "3", "4", "5"), loadSize, perRow = 5) { loadSize = it } }
        Column { FormLabel("ORGASM INTENSITY 0-10 (OPTIONAL)"); FieldTextField(orgasmIntensity, { orgasmIntensity = it }, "e.g. 7", keyboardType = KeyboardType.Number) }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Anything else worth noting", singleLine = false) }
        val valid = watchedPorn != null
        SaveButton(saving, valid) {
            onSave(dateTime.toIsoWithOffset(), watchedPorn == "Yes", loadSize?.toIntOrNull(), orgasmIntensity.toIntOrNull(), notes.trim().ifBlank { null })
        }
    }
}

private val OSTRC_Q1Q4_OPTIONS = listOf("0", "8", "17", "25")
private val OSTRC_Q2Q3_OPTIONS = listOf("0", "6", "13", "19", "25")

// Phase A4 (Category 8). OSTRC-H2's own four-question weekly prompt per body
// area -- q1/q4 (participation/performance) share one value set, q2/q3
// (training volume/performance reduction) share a different, five-value
// set. severity_score is a stored generated column (Phase A1) -- computed
// server-side, never sent from here. body_area stays free text (the schema
// has no fixed list -- see the A1 migration's own column definition).
@Composable
private fun OstrcForm(
    saving: Boolean,
    initialDate: LocalDate = LocalDate.now(),
    initialBodyArea: String = "",
    initialQ1: Int? = null,
    initialQ2: Int? = null,
    initialQ3: Int? = null,
    initialQ4: Int? = null,
    initialNotes: String = "",
    onSave: (date: String, bodyArea: String, q1: Int, q2: Int, q3: Int, q4: Int, notes: String?) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    var bodyArea by remember { mutableStateOf(initialBodyArea) }
    var q1 by remember { mutableStateOf(initialQ1?.toString()) }
    var q2 by remember { mutableStateOf(initialQ2?.toString()) }
    var q3 by remember { mutableStateOf(initialQ3?.toString()) }
    var q4 by remember { mutableStateOf(initialQ4?.toString()) }
    var notes by remember { mutableStateOf(initialNotes) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateField("WEEK OF", date, { date = it })
        Column { FormLabel("BODY AREA"); FieldTextField(bodyArea, { bodyArea = it }, "e.g. Right knee") }
        Column { FormLabel("Q1 — PARTICIPATION IN SPORT"); TextChipRow(OSTRC_Q1Q4_OPTIONS, q1) { q1 = it } }
        Column { FormLabel("Q2 — TRAINING VOLUME REDUCED"); TextChipRow(OSTRC_Q2Q3_OPTIONS, q2, perRow = 5) { q2 = it } }
        Column { FormLabel("Q3 — PERFORMANCE AFFECTED"); TextChipRow(OSTRC_Q2Q3_OPTIONS, q3, perRow = 5) { q3 = it } }
        Column { FormLabel("Q4 — PAIN DURING PARTICIPATION"); TextChipRow(OSTRC_Q1Q4_OPTIONS, q4) { q4 = it } }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Anything else worth noting", singleLine = false) }
        val valid = bodyArea.isNotBlank() && q1 != null && q2 != null && q3 != null && q4 != null
        SaveButton(saving, valid) {
            onSave(date.toString(), bodyArea.trim(), q1!!.toInt(), q2!!.toInt(), q3!!.toInt(), q4!!.toInt(), notes.trim().ifBlank { null })
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
            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
            color = FT.TextSecondary,
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
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp),
                    color = FT.DomainLog,
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
                            .border(FT.BorderWidth, if (isSelected) FT.DomainLog else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                            .background(if (isSelected) FT.DomainLog.copy(alpha = 0.18f) else FT.GlassFill, RoundedCornerShape(FT.RadiusSmall))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(if (isSelected) null else option) }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option.uppercase(),
                            style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
                            color = if (isSelected) FT.DomainLog else FT.TextSecondary,
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

// One exercise's editable form: name, its list of sets, and add/remove
// controls -- all plain text links (REMOVE / + ADD SET), matching this
// file's existing convention (no icons anywhere else in it). Reps and
// weight_kg get the wider, primary row; RPE and % 1RM are optional per the
// handoff's own scope and sit in a secondary row underneath.
//
// Phase C follow-up: the name field autocompletes against exercise_library's
// 876 real entries via pg_trgm similarity (ExerciseLibraryRepository),
// debounced so every keystroke doesn't fire a query. A read-side
// convenience only -- tapping a suggestion just fills in that exact string,
// the field stays free text underneath (matching the original handoff's own
// "typo-tolerant at entry time" framing).
@Composable
private fun ExerciseEditor(exercise: EditableExercise, canRemove: Boolean, onRemove: () -> Unit) {
    val libraryRepo = remember { ExerciseLibraryRepository(SupabaseClientProvider.client) }
    var suggestions by remember { mutableStateOf<List<ExerciseLibraryMatch>>(emptyList()) }
    var suppressSearch by remember { mutableStateOf(false) }

    LaunchedEffect(exercise.name) {
        if (suppressSearch) {
            suppressSearch = false
        } else if (exercise.name.trim().length >= 3) {
            delay(250)
            suggestions = runCatching { libraryRepo.matchExerciseName(exercise.name) }.getOrDefault(emptyList())
        } else {
            suggestions = emptyList()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldTextField(exercise.name, { exercise.name = it }, "Exercise name", modifier = Modifier.weight(1f))
            if (canRemove) {
                Text(
                    "REMOVE",
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
                    color = FT.Critical,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onRemove() },
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall)),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                suggestions.forEach { match ->
                    Text(
                        match.name,
                        style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                        color = FT.TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                suppressSearch = true
                                exercise.name = match.name
                                suggestions = emptyList()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        exercise.sets.forEachIndexed { i, set ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldTextField(set.reps, { set.reps = it }, "Reps", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    FieldTextField(set.weightKg, { set.weightKg = it }, "Weight (kg)", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldTextField(set.rpe, { set.rpe = it }, "RPE (optional)", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    FieldTextField(set.percentOneRm, { set.percentOneRm = it }, "% 1RM (optional)", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    if (exercise.sets.size > 1) {
                        Text(
                            "REMOVE",
                            style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
                            color = FT.Critical,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { exercise.sets.removeAt(i) },
                        )
                    }
                }
            }
        }

        Text(
            "+ ADD SET",
            style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp),
            color = FT.DomainLog,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                // Most sets in a real workout repeat or closely follow the
                // one before it -- default to the previous set's values
                // instead of blank fields, per direct user feedback.
                val previous = exercise.sets.lastOrNull()
                exercise.sets.add(
                    if (previous != null) {
                        EditableSet(previous.reps, previous.weightKg, previous.rpe, previous.percentOneRm)
                    } else {
                        EditableSet()
                    },
                )
            },
        )
    }
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
