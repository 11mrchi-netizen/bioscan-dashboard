package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ExerciseLibraryRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.ExerciseLibraryMatch
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetails
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import com.bioscan.fieldterminal.data.model.StrengthSetDto
import com.bioscan.fieldterminal.domain.editedDetails
import com.bioscan.fieldterminal.domain.spec
import com.bioscan.fieldterminal.ui.components.FTRatingSlider
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.delay
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// + LOG: the details of a recorded session (run type, RPE, notes) and the strength exercise editor.

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
internal fun ExerciseDetailsForm(
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
            style = FTType.Caption,
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
                    style = FTType.MonoCaption,
                    color = FT.DomainLog,
                    modifier = Modifier.ftClickable { exercises.add(EditableExercise()) },
                )
            }
        }

        FTRatingSlider("RPE (EFFORT)", rpe.toIntOrNull(), 0..10, FT.Metric.Rpe, { rpe = it?.toString() ?: "" }, lowWord = "rest", highWord = "max")
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Anything else worth noting", singleLine = false) }
        SaveButton(saving, true) {
            // Changing the exercises makes them the person's own, so no Zepp sync overwrites them; saving only RPE or
            // notes leaves a Zepp list following Zepp (domain/StrengthImport.kt).
            val details = editedDetails(
                initial = initialDetails,
                routeType = routeType,
                runType = runType,
                exercises = if (type == "strength") exercises.mapNotNull { it.toDtoOrNull() }.ifEmpty { null } else initialDetails.exercises,
            )
            onSave(rpe.toIntOrNull(), notes.trim().ifBlank { null }, details)
        }
    }
}

// Local editable mirror of StrengthExerciseDto/StrengthSetDto -- text-field-
// backed (reps/weight_kg parse to Int/Double only on Save) since a set
// mid-entry is routinely blank or partial, which the DTO's non-nullable
// reps/weight_kg can't represent.
// What the watch measured (set duration, heart rate, detected reps, and the exercise's Zepp code) is not editable but
// rides along unchanged: saving used to drop it, so the first edit, even of RPE alone, lost the link to Zepp's data.
private class EditableSet(
    reps: String = "",
    weightKg: String = "",
    rpe: String = "",
    percentOneRm: String = "",
    val durationSec: Int? = null,
    val avgHr: Int? = null,
    val detectedReps: Int? = null,
) {
    var reps by mutableStateOf(reps)
    var weightKg by mutableStateOf(weightKg)
    var rpe by mutableStateOf(rpe)
    var percentOneRm by mutableStateOf(percentOneRm)
}

private class EditableExercise(name: String = "", val exerciseCode: Int? = null) {
    // The Zepp code names the exercise Zepp recorded; renamed, it would point at the wrong one, so it is dropped then.
    val originalName = name
    var name by mutableStateOf(name)
    val sets = mutableStateListOf(EditableSet())
}

private fun StrengthExerciseDto.toEditable() = EditableExercise(name, exerciseCode).also { editable ->
    editable.sets.clear()
    sets.forEach { s ->
        editable.sets.add(
            EditableSet(
                s.reps.toString(), s.weightKg.toString(), s.rpe?.toString() ?: "", s.percentOneRm?.toString() ?: "",
                durationSec = s.durationSec, avgHr = s.avgHr, detectedReps = s.detectedReps,
            ),
        )
    }
}

// Drops a set with no reps/weight rather than saving a garbage 0; drops an
// exercise with no valid sets entirely rather than saving an empty shell.
private fun EditableExercise.toDtoOrNull(): StrengthExerciseDto? {
    if (name.isBlank()) return null
    val validSets = sets.mapNotNull { s ->
        val reps = s.reps.toIntOrNull()
        val weightKg = s.weightKg.toDoubleOrNull()
        if (reps == null || weightKg == null) null
        else StrengthSetDto(
            reps = reps,
            weightKg = weightKg,
            rpe = s.rpe.toIntOrNull(),
            percentOneRm = s.percentOneRm.toDoubleOrNull(),
            durationSec = s.durationSec,
            avgHr = s.avgHr,
            detectedReps = s.detectedReps,
        )
    }
    val code = exerciseCode.takeIf { name.trim() == originalName.trim() }
    return if (validSets.isEmpty()) null else StrengthExerciseDto(name.trim(), exerciseCode = code, sets = validSets)
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
            suggestions = catching { libraryRepo.matchExerciseName(exercise.name) }.getOrDefault(emptyList())
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
                    style = FTType.MonoCaption,
                    color = FT.Bad,
                    modifier = Modifier.ftClickable { onRemove() },
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
                        style = FTType.BodySmall,
                        color = FT.TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .ftClickable {
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
                            style = FTType.MonoCaption,
                            color = FT.Bad,
                            modifier = Modifier.ftClickable { exercise.sets.removeAt(i) },
                        )
                    }
                }
            }
        }

        Text(
            "+ ADD SET",
            style = FTType.MonoCaption,
            color = FT.DomainLog,
            modifier = Modifier.ftClickable {
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
