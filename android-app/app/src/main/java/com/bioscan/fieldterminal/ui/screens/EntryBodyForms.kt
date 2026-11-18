package com.bioscan.fieldterminal.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.score.StoolLane
import com.bioscan.fieldterminal.domain.toAppIso
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.DateTimeField
import com.bioscan.fieldterminal.ui.components.FTRatingSlider
import com.bioscan.fieldterminal.ui.components.FormSteps
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.screens.today.laneColor
import java.time.LocalDate
import java.time.LocalDateTime
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// + LOG: the body and how it feels: grip, stool, the weekly OSTRC questions, wellbeing, a note.

// Handgrip strength: the best of the attempts on a hand dynamometer, one row per hand. Logged for the record; it is shown on the
// Healthspan card but not scored until a cited norm table exists.
@Composable
internal fun GripForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialHand: String? = null,
    initialKg: Double? = null,
    onSave: (occurredAt: String, hand: String, kg: Double) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var hand by remember { mutableStateOf(initialHand) }
    var kg by remember { mutableStateOf(initialKg?.toString() ?: "") }
    val value = kg.replace(',', '.').toDoubleOrNull()?.takeIf { it in 5.0..120.0 }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Column { FormLabel("HAND"); TextChipRow(listOf("right", "left"), hand, perRow = 2) { hand = it } }
        Column { FormLabel("BEST OF 3, KG (DYNAMOMETER, 5-120)"); FieldTextField(kg, { kg = it }, "e.g. 46.5", keyboardType = KeyboardType.Decimal) }
        SaveButton(saving, hand != null && value != null) {
            onSave(dateTime.toAppIso(), hand!!, value!!)
        }
    }
}

/** The stool form's "does it feel empty?" answers: stored value to the chip's words, bad to good like the scales. */
internal val EMPTY_OPTIONS = listOf("no" to "NO", "partly" to "NOT QUITE", "yes" to "YES")

@Composable
internal fun StoolForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialBristolType: Int? = null,
    initialDiscomfort: Int? = null,
    initialEmpty: String? = null,
    onSave: (occurredAt: String, bristolType: Int, discomfort: Int?, completeEvacuation: String?) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var bristolType by remember { mutableStateOf(initialBristolType) }
    var discomfort by remember { mutableStateOf(initialDiscomfort?.toString() ?: "") }
    var empty by remember { mutableStateOf(initialEmpty) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        BristolSlider(bristolType) { bristolType = it }
        FTRatingSlider("DISCOMFORT", discomfort.toIntOrNull(), 0..10, FT.Metric.StoolLoose, { discomfort = it?.toString() ?: "" }, lowWord = "none", highWord = "severe", reversed = true)
        // The sense of complete evacuation; optional (tap the chosen one again to clear it).
        Column {
            FormLabel("DOES IT FEEL EMPTY?")
            TextChipRow(EMPTY_OPTIONS.map { it.second }, EMPTY_OPTIONS.firstOrNull { it.first == empty }?.second, perRow = 3) { picked ->
                empty = EMPTY_OPTIONS.firstOrNull { it.second == picked }?.first
            }
        }
        SaveButton(saving, bristolType != null) {
            onSave(dateTime.toAppIso(), bristolType!!, discomfort.toIntOrNull(), empty)
        }
    }
}

// OSTRC-H2's own four-question weekly prompt per body
// area -- q1/q4 (participation/performance) share one value set, q2/q3
// (training volume/performance reduction) share a different, five-value
// set. severity_score is a stored generated column -- computed
// server-side, never sent from here. body_area stays free text (the schema
// has no fixed list -- see the A1 migration's own column definition).
@Composable
internal fun OstrcForm(
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

// Arousal fold-in: morning-wood/arousal moved in from the old ArousalForm --
// one combined daily survey instead of two separate ones.
@Composable
internal fun WellnessForm(
    saving: Boolean,
    initialDate: LocalDate = LocalDate.now(),
    initialEnergy: Int? = null,
    initialMood: Int? = null,
    initialStress: Int? = null,
    initialSoreness: Int? = null,
    initialMorningWood: Int? = null,
    initialArousalLevel: Int? = null,
    onSave: (date: String, energy: Int?, mood: Int?, stress: Int?, soreness: Int?, morningWood: Int?, arousalLevel: Int?) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    var energy by remember { mutableStateOf(initialEnergy?.toString() ?: "") }
    var mood by remember { mutableStateOf(initialMood?.toString() ?: "") }
    var stress by remember { mutableStateOf(initialStress?.toString() ?: "") }
    var soreness by remember { mutableStateOf(initialSoreness?.toString() ?: "") }
    var morningWood by remember { mutableStateOf(initialMorningWood?.toString() ?: "") }
    var arousalLevel by remember { mutableStateOf(initialArousalLevel?.toString() ?: "") }

    val valid = listOf(energy, mood, stress, soreness, morningWood, arousalLevel).any { it.isNotBlank() }
    // Two steps: energy, mood and stress, then soreness and the two arousal scales.
    FormSteps(listOf("MIND", "BODY"), canSave = valid, saving = saving, onSave = {
        onSave(date.toString(), energy.toIntOrNull(), mood.toIntOrNull(), stress.toIntOrNull(), soreness.toIntOrNull(), morningWood.toIntOrNull(), arousalLevel.toIntOrNull())
    }) { step ->
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (step == 0) {
                DateField("DATE", date, { date = it })
                FTRatingSlider("ENERGY", energy.toIntOrNull(), 0..10, FT.Metric.Energy, { energy = it?.toString() ?: "" }, lowWord = "drained", highWord = "full")
                FTRatingSlider("MOOD", mood.toIntOrNull(), 0..10, FT.Metric.Mood, { mood = it?.toString() ?: "" }, lowWord = "low", highWord = "great")
                FTRatingSlider("STRESS", stress.toIntOrNull(), 0..10, FT.Metric.Stress, { stress = it?.toString() ?: "" }, lowWord = "calm", highWord = "very stressed", reversed = true)
            } else {
                FTRatingSlider("SORENESS", soreness.toIntOrNull(), 0..10, FT.Metric.Soreness, { soreness = it?.toString() ?: "" }, lowWord = "none", highWord = "very sore", reversed = true)
                FTRatingSlider("MORNING WOOD", morningWood.toIntOrNull(), 0..10, FT.Metric.Arousal, { morningWood = it?.toString() ?: "" }, lowWord = "none", highWord = "full")
                FTRatingSlider("AROUSAL LEVEL", arousalLevel.toIntOrNull(), 0..10, FT.Metric.Arousal, { arousalLevel = it?.toString() ?: "" }, lowWord = "none", highWord = "very high")
            }
        }
    }
}

@Composable
internal fun NoteForm(saving: Boolean, initialDateTime: LocalDateTime = LocalDateTime.now(), initialText: String = "", onSave: (occurredAt: String, text: String) -> Unit) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var text by remember { mutableStateOf(initialText) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Column { FormLabel("NOTE"); FieldTextField(text, { text = it }, "Write a note...", singleLine = false) }
        SaveButton(saving, text.isNotBlank()) {
            onSave(dateTime.toAppIso(), text.trim())
        }
    }
}

/** The Bristol stool form scale's seven types, in words. */
val BRISTOL_WORDS = listOf(
    "Separate hard lumps",
    "Lumpy, sausage-shaped",
    "Sausage with cracks",
    "Smooth, soft sausage",
    "Soft blobs, clear edges",
    "Mushy, ragged edges",
    "Watery, no pieces",
)

/**
 * Bristol type on a slider: each type in its lane's colour as the stool hypnogram draws it (hard, healthy, loose,
 * liquid), its words instead of its number, and the lanes named under the track. Required, so no CLEAR.
 */
@Composable
fun BristolSlider(value: Int?, onChange: (Int) -> Unit) {
    val lanes = StoolLane.DRAWN.reversed()
    FTRatingSlider(
        label = "STOOL FORM (BRISTOL)",
        value = value,
        range = 1..7,
        color = FT.Metric.StoolHealthy,
        onChange = { v -> v?.let(onChange) },
        optional = false,
        valueText = { t -> "${BRISTOL_WORDS[t - 1]} · ${com.bioscan.fieldterminal.domain.score.StoolLane.of(t)?.label?.lowercase() ?: ""}" },
        stepColor = { t -> StoolLane.of(t)?.let { laneColor(it) } ?: FT.TextMuted },
        scale = lanes.map { it.types to it.label },
    )
}
