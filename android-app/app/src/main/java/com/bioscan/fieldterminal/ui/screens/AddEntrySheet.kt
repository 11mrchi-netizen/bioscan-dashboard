package com.bioscan.fieldterminal.ui.screens

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.FullExerciseSessionRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogGripRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogOstrcRow
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.LogSleepDetailRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.LogSupplementTakenRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import com.bioscan.fieldterminal.data.softLoad
import com.bioscan.fieldterminal.domain.AddEntryChoice
import com.bioscan.fieldterminal.domain.AddEntryGroup
import com.bioscan.fieldterminal.domain.AddEntryStart
import com.bioscan.fieldterminal.domain.AddEntryTarget
import com.bioscan.fieldterminal.domain.AddEntryType
import com.bioscan.fieldterminal.domain.FuelSubType
import com.bioscan.fieldterminal.domain.LogEntry
import com.bioscan.fieldterminal.domain.LogSource
import com.bioscan.fieldterminal.domain.formatRecentChoices
import com.bioscan.fieldterminal.domain.logEntryWhen
import com.bioscan.fieldterminal.domain.parseRecentChoices
import com.bioscan.fieldterminal.domain.recordRecentChoice
import com.bioscan.fieldterminal.domain.target
import com.bioscan.fieldterminal.ui.components.AddEntryStartSaver
import com.bioscan.fieldterminal.ui.components.AddEntryTargetSaver
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.Toasts
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.screens.events.EventFormSheet
import com.bioscan.fieldterminal.ui.screens.events.SweatTestSheet
import com.bioscan.fieldterminal.ui.screens.health.HealthCheckInSheet
import com.bioscan.fieldterminal.ui.screens.health.IllnessSheet
import com.bioscan.fieldterminal.ui.screens.health.SymptomCheckSheet
import com.bioscan.fieldterminal.ui.screens.status.BodyCheckInSheet
import com.bioscan.fieldterminal.ui.screens.status.ScaleWeighInSheet
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.util.ExternalActivityGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Step 12 (Phase D) + the 2026-09-15 follow-up pass: the "+" add-entry flow.
// Type picker first, then a minimal per-type form that writes straight to
// the real table Step 11 already reads -- see data/AddEntryRepository.kt for
// the writes themselves. Every form now carries its own date/time picker
// (default "now", editable) per direct user request, and Food/Drink/
// Supplements share one FUEL entry point with a three-way sub-picker.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntrySheet(onDismiss: () -> Unit, onSaved: () -> Unit, start: AddEntryStart? = null) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // UX review P1: the Status quick-log row opens the sheet on the right form; back from it still leads to the picker.
    // Saveable: the forms here can open the camera, file and photo pickers, and the app may be restored meanwhile.
    var selectedType by rememberSaveable { mutableStateOf(
        when (start) {
            is AddEntryStart.Fuel -> AddEntryType.Fuel
            is AddEntryStart.Form -> start.type
            else -> null
        },
    ) }
    // Cleared on back, so picking FUEL again from the picker opens on FOOD as usual.
    var fuelStart by rememberSaveable(stateSaver = AddEntryStartSaver) { mutableStateOf<AddEntryStart?>(start as? AddEntryStart.Fuel) }
    // UX review P3: the last three choices are one tap away in a RECENT row (was a single LAST button).
    val addPrefs = LocalContext.current.getSharedPreferences("ft_ui", Context.MODE_PRIVATE)
    var recent by remember { mutableStateOf(parseRecentChoices(addPrefs.getString("recent_add_choices", null))) }
    // Injury and illness have their own form sheets (shared with Training › Injury); picking one swaps this sheet for it.
    var healthForm by rememberSaveable(stateSaver = AddEntryTargetSaver) { mutableStateOf<AddEntryTarget?>(null) }
    // A symptom check's symptoms and signals, carried into the illness it is tracked as.
    var illnessPrefill by remember { mutableStateOf<Pair<Set<String>, List<Long>>>(emptySet<String>() to emptyList()) }
    // "say it" (LogAgentForm) and its hand-off of a food proposal to the normal food form.
    var sayIt by rememberSaveable { mutableStateOf(start is AddEntryStart.SayIt) }
    var foodPrefill by rememberSaveable { mutableStateOf<Pair<String, LocalDateTime>?>(null) }
    val repo = remember { AddEntryRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Blocks onDismissRequest while the camera / photo-picker / barcode-scanner
    // Activity holds foreground -- those launches trigger the sheet's dismiss
    // callback even though the user hasn't actually dismissed the sheet.
    val externalActivityActive = remember { mutableStateOf(false) }

    val pick: (AddEntryChoice) -> Unit = { choice ->
        recent = recordRecentChoice(recent, choice)
        addPrefs.edit().putString("recent_add_choices", formatRecentChoices(recent)).apply()
        when (val target = choice.target) {
            is AddEntryTarget.Form -> {
                fuelStart = target.fuel?.let { AddEntryStart.Fuel(it) }
                selectedType = target.type
            }
            else -> healthForm = target
        }
    }

    when (healthForm) {
        AddEntryTarget.HealthCheckIn -> HealthCheckInSheet(onDismiss = onDismiss, onSaved = onSaved)
        AddEntryTarget.IllnessForm -> IllnessSheet(
            onDismiss = onDismiss, onSaved = onSaved, prefillSymptoms = illnessPrefill.first, adoptSignalIds = illnessPrefill.second,
        )
        // TRACK AS ILLNESS / INJURY from here goes on to the matching sheet in place of this one.
        AddEntryTarget.SymptomCheck -> SymptomCheckSheet(
            onDismiss = onDismiss, onSaved = onSaved,
            onTrackIllness = { symptoms, signals -> illnessPrefill = symptoms to signals; healthForm = AddEntryTarget.IllnessForm },
            onTrackInjury = { healthForm = AddEntryTarget.HealthCheckIn },
        )
        AddEntryTarget.BodyCheckIn -> BodyCheckInSheet(onDismiss = onDismiss, onSaved = onSaved)
        AddEntryTarget.EventForm -> EventFormSheet(onDismiss = onDismiss, onSaved = { onSaved() })
        AddEntryTarget.SweatTest -> SweatTestSheet(onDismiss = onDismiss, onSaved = onSaved)
        AddEntryTarget.ScaleWeighIn -> ScaleWeighInSheet(onDismiss = onDismiss, onSaved = onSaved)
        else -> ModalBottomSheet(
            onDismissRequest = { if (!externalActivityActive.value && !ExternalActivityGuard.active) onDismiss() },
            sheetState = sheetState,
            shape = FT.SheetShape,
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
                // System back inside a form goes back to the picker, as BACK does, instead of closing the sheet and
                // dropping what was typed.
                BackHandler(enabled = sayIt || type != null) {
                    if (sayIt) sayIt = false else { selectedType = null; foodPrefill = null; fuelStart = null }
                }
                if (sayIt) {
                    SheetBackHeader(label = "SAY IT", onBack = { sayIt = false })
                    Spacer(Modifier.height(16.dp))
                    LogAgentForm(
                        onSaved = onSaved,
                        onFoodHandoff = { text, at ->
                            foodPrefill = text to at
                            sayIt = false
                            selectedType = AddEntryType.Fuel
                        },
                    )
                } else if (type == null) {
                    Text("LOG NEW ENTRY", style = sheetHeaderTitleStyle, color = FT.DomainLog)
                    // SAY IT is no longer offered here (the menu goes straight to the types).
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (recent.isNotEmpty()) ChoiceSection("RECENT", recent, pick)
                        AddEntryGroup.entries.forEach { group -> ChoiceSection(group.label, AddEntryChoice.entries.filter { it.group == group }, pick) }
                    }
                } else {
                    SheetBackHeader(label = type.label, onBack = { selectedType = null; foodPrefill = null; fuelStart = null })
                    Spacer(Modifier.height(16.dp))
                    // Optimistic (ui/components/UndoToast.kt): the sheet closes at once and the write runs in the app's
                    // scope; the page refreshes when it lands. A rejected write says so with RETRY, which runs the same
                    // write again (the values are already in it), so nothing typed is lost.
                    val onSubmit: ((suspend (AddEntryRepository) -> Unit)) -> Unit = { write ->
                        Toasts.optimistic(
                            apply = onDismiss,
                            save = {
                                write(repo)
                                onSaved()
                            },
                            revert = {},
                            what = "Couldn't save the entry",
                        )
                    }
                    saveError?.let { Text("Couldn't save ($it).", style = FTType.Caption, color = FT.Bad) }
                    when (type) {
                        AddEntryType.Fuel -> FuelForm(
                            saving, onSubmit, onCanonicalSaved = onSaved, externalActivityActive = externalActivityActive, foodPrefill = foodPrefill,
                            initialSubType = (fuelStart as? AddEntryStart.Fuel)?.subType ?: FuelSubType.Food,
                            supplementBundle = (fuelStart as? AddEntryStart.Fuel)?.supplementBundle,
                        )
                        AddEntryType.Encounter -> EncounterForm(
                            saving,
                            onSave = { date, occurredAt, et, loc, dur, acts, rating, n ->
                                onSubmit { it.addEncounter(date = date, encounterType = et, notes = n, occurredAt = occurredAt, locationType = loc, durationMin = dur, activities = acts, myRating = rating) }
                            },
                        )
                        AddEntryType.Stool -> StoolForm(saving, onSave = { occurredAt, bt, d, empty -> onSubmit { it.addStool(occurredAt, bt, d, empty) } })
                        AddEntryType.Wellness -> WellnessForm(saving, onSave = { date, e, m, s, so, mw, al -> onSubmit { it.addWellbeing(date, e, m, s, so, mw, al) } })
                        AddEntryType.Note -> NoteForm(saving, onSave = { occurredAt, text -> onSubmit { it.addNote(occurredAt, text) } })
                        AddEntryType.Ostrc -> OstrcForm(saving, onSave = { date, ba, q1, q2, q3, q4, n -> onSubmit { it.addOstrc(date, ba, q1, q2, q3, q4, n) } })
                        AddEntryType.SexualActivity -> SexualActivityForm(saving, onSave = { date, type, instances, n -> onSubmit { it.addSexualActivity(date, type, instances, n) } })
                        AddEntryType.Grip -> GripForm(saving, onSave = { occurredAt, hand, kg -> onSubmit { it.addGrip(occurredAt, hand, kg) } })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// Tapping a Log entry opens the entry itself (UX review P4, adapted to PR #20's Log): what it is and when, its values, then
// DETAIL / EDIT / DELETE. It used to open a bare action menu. EDIT appears when the source has a corresponding form;
// Sleep has no add-entry form and no editable fields, so it stays delete-only. Supplement (DAV-156) is editable -- dose
// is a real field, even though which supplement was taken still isn't (that's "delete and re-log," not "edit").
// Exercise IS editable -- not its Health-Connect-sourced fields, just rpe/notes via ExerciseDetailsForm.
// DETAIL pushes SessionDetailScreen for exercise and opens the sleep detail for sleep. DELETE asks first
// (contract A2) and says when it failed.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(
    entry: LogEntry,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onViewDetail: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deleteError by remember { mutableStateOf<String?>(null) }
    val editable = entry.source != LogSource.Sleep

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(logEntryWhen(entry, LocalDate.now()), style = FTType.LabelCaps, color = FT.TextMuted)
                Text(entry.headline, style = sheetHeaderTitleStyle, color = FT.TextPrimary)
                entry.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = FTType.Body, color = FT.TextSecondary) }
            }

            // Deleting hides the entry at once and holds the delete for a few seconds with UNDO (ui/components/UndoToast.kt).
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                onViewDetail?.let { FTButton(label = "DETAIL", modifier = Modifier.weight(1f), onClick = it) }
                if (editable) FTButton(label = "EDIT", modifier = Modifier.weight(1f), onClick = onEdit)
                FTButton(label = "DELETE", emphasis = FTButtonEmphasis.Destructive, modifier = Modifier.weight(1f)) { onDeleted() }
            }
            deleteError?.let { Text("Couldn't delete: $it", style = FTType.BodySmall, color = FT.Bad) }
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
    var saveError by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(entry.id) {
        try {
            loaded = when (entry.source) {
            LogSource.Meal -> repo.fetchMeal(entry.id)
            LogSource.Hydration -> repo.fetchHydration(entry.id)
            LogSource.Encounter -> repo.fetchEncounter(entry.id)
            LogSource.Stool -> repo.fetchStool(entry.id)
            LogSource.Grip -> repo.fetchGrip(entry.id)
            LogSource.Note -> repo.fetchNote(entry.id)
            LogSource.Wellbeing -> repo.fetchWellbeing(entry.id)
            LogSource.Exercise -> repo.fetchExerciseSession(entry.id)
            LogSource.Ostrc -> repo.fetchOstrc(entry.id)
            LogSource.Masturbation -> repo.fetchSexualActivity(entry.id)
            LogSource.Supplement -> repo.fetchSupplementTaken(entry.id)
            LogSource.Sleep -> null // no edit form; EntrySheet never offers EDIT for this
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saveError = "Couldn't load this entry (${describeLoadFailure(e)})"
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
                saveError = null
                scope.launch {
                    try {
                        write(repo)
                        saving = false
                        onSaved()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        saving = false
                        saveError = e.message ?: "Save failed"
                    }
                }
            }
            saveError?.let { Text("Couldn't save ($it).", style = FTType.Caption, color = FT.Bad) }

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
                    initialMealSlot = row.mealSlot,
                    editingMealId = row.id,
                    onSave = { dt, desc, cal, p, c, f, fi, su, so, slot -> onSubmit { it.updateFood(row.id, dt, desc, cal, p, c, f, fi, su, so, slot) } },
                )
                is LogHydrationRow -> DrinkForm(
                    saving,
                    initialDate = LocalDate.parse(row.date),
                    initialMl = row.ml,
                    onSave = { date, ml -> onSubmit { it.updateDrink(row.id, date, ml) } },
                )
                is LogEncounterRow -> EncounterForm(
                    saving,
                    initialDateTime = row.occurredAt?.let { parseIsoToLocalDateTime(it) } ?: LocalDateTime.of(LocalDate.parse(row.date), LocalTime.of(21, 0)),
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
                    initialEmpty = row.completeEvacuation,
                    onSave = { occurredAt, bt, d, empty -> onSubmit { it.updateStool(row.id, occurredAt, bt, d, empty) } },
                )
                is LogGripRow -> GripForm(
                    saving,
                    initialDateTime = parseIsoToLocalDateTime(row.occurredAt),
                    initialHand = row.hand,
                    initialKg = row.kg,
                    onSave = { occurredAt, hand, kg -> onSubmit { it.updateGrip(row.id, occurredAt, hand, kg) } },
                )
                is LogSexualActivityRow -> SexualActivityForm(
                    saving,
                    lockedActivityType = row.activityType,
                    initialDate = LocalDate.parse(row.date),
                    initialInstances = row.instances,
                    initialNotes = row.notes ?: "",
                    onSave = { date, type, instances, n -> onSubmit { it.updateSexualActivity(row.id, date, type, instances, n) } },
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
                    initialMorningWood = row.morningErectionQuality,
                    initialArousalLevel = row.arousalLevel,
                    onSave = { date, e, m, s, so, mw, al -> onSubmit { it.updateWellbeing(row.id, date, e, m, s, so, mw, al) } },
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
// action Exercise already uses (EntrySheet's onViewDetail) rather than
// a new nav route -- a bottom sheet is proportionate for a handful of read-
// only stats, matching every other sheet in this file. Sleep still has no
// edit form (see LogSource's own doc comment); this only reads.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepDetailSheet(entryId: Long, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var row by remember { mutableStateOf<LogSleepDetailRow?>(null) }

    LaunchedEffect(entryId) {
        row = softLoad { AddEntryRepository(SupabaseClientProvider.client).fetchSleepDetail(entryId) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
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
        Text(label, style = FTType.Body, color = FT.TextSecondary)
        Text(
            value,
            style = FTType.Value,
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
        OffsetDateTime.parse(iso).toLocalDateTime()
    } catch (e: Exception) {
        LocalDateTime.parse(iso)
    }
