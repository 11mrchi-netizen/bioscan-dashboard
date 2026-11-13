package com.bioscan.fieldterminal.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.HealthConcernsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.health.ILLNESS_TYPES
import com.bioscan.fieldterminal.domain.health.IMPACT_LABELS
import com.bioscan.fieldterminal.domain.health.IllnessDraft
import com.bioscan.fieldterminal.domain.health.IllnessType
import com.bioscan.fieldterminal.domain.health.TestResult
import com.bioscan.fieldterminal.domain.health.buildIllness
import com.bioscan.fieldterminal.domain.health.checkInDate
import com.bioscan.fieldterminal.domain.score.IllnessCheckIn
import com.bioscan.fieldterminal.domain.score.gravityOf
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTChoiceChip
import com.bioscan.fieldterminal.ui.components.FTRatingSlider
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Health › + ILLNESS (docs/health-concerns/README.md, "Illness, infections and the symptom check"): an infection or another
// illness, logged as what it is rather than through the body map. KIND (a cold, a stomach bug, COVID...), when it started,
// the symptoms (the kind's usual ones first), how bad, temperature, what it does to training, a test, a clinician and
// medication. The line under SEVERITY shows what it does to CHARGE (domain/score/IllnessRead.kt), so a stuffed nose is
// not logged as the flu. Opened from Health, or from a symptom check's TRACK AS ILLNESS (its symptoms prefilled; that
// check's signals then join the new illness).

private val STARTS = listOf(0L to "TODAY", 1L to "YESTERDAY", 2L to "2 DAYS AGO", 3L to "3 DAYS AGO", 7L to "A WEEK AGO")
private val TESTS = listOf("covid" to "COVID", "flu" to "FLU", "strep" to "STREP", "other" to "OTHER")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IllnessSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    prefillSymptoms: Set<String> = emptySet(),
    /** A symptom check's signals to give to the new illness. */
    adoptSignalIds: List<Long> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val vocab = remember { HealthConcernsRepository.vocab(context) }
    val repo = remember { HealthConcernsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf<IllnessType?>(null) }
    var title by remember { mutableStateOf("") }
    var titleEdited by remember { mutableStateOf(false) }
    var startDaysAgo by remember { mutableStateOf(0L) }
    var symptoms by remember { mutableStateOf(prefillSymptoms) }
    var severity by remember { mutableStateOf<Int?>(null) }
    var impact by remember { mutableStateOf<Int?>(null) }
    var temperature by remember { mutableStateOf("") }
    var test by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var clinician by remember { mutableStateOf(false) }
    var diagnosis by remember { mutableStateOf("") }
    var medication by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val temp = temperature.replace(',', '.').trim().toDoubleOrNull()
    val ready = type != null && severity != null && (temperature.isBlank() || (temp != null && temp in 34.0..43.0))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ILLNESS OR INFECTION", style = FTType.SectionTitle, color = FT.DomainHeart)
            Text("What you have, in your words: no diagnosis needed. Small things (a headache, a stuffed nose for a day) are quicker as a SYMPTOM CHECK.", style = FTType.BodySmall, color = FT.TextSecondary)

            Label("WHAT IS IT")
            TextChipRow(ILLNESS_TYPES.map { it.label.uppercase() }, type?.label?.uppercase(), perRow = 3) { picked ->
                val t = ILLNESS_TYPES.firstOrNull { it.label.uppercase() == picked }
                type = t
                if (t != null) {
                    if (!titleEdited) title = t.label
                    if (prefillSymptoms.isEmpty() && symptoms.isEmpty()) symptoms = t.symptoms.take(3).toSet()
                }
            }
            if (type != null) {
                Label("NAME IT")
                FieldTextField(title, { title = it; titleEdited = true }, "e.g. Cold after the race")
            }

            Label("STARTED")
            TextChipRow(STARTS.map { it.second }, STARTS.firstOrNull { it.first == startDaysAgo }?.second, perRow = 3) { picked ->
                startDaysAgo = STARTS.firstOrNull { it.second == picked }?.first ?: 0L
            }

            Label("SYMPTOMS")
            val usual = type?.symptoms.orEmpty()
            val options = (usual + vocab.systemicSymptoms.map { it.code }).distinct().filter { it != "sym.other" } + "sym.other"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { code ->
                    Chip((vocab.symptom(code)?.label ?: code).uppercase(), code in symptoms) {
                        symptoms = if (code in symptoms) symptoms - code else symptoms + code
                    }
                }
            }

            FTRatingSlider("HOW BAD, AT WORST NOW", severity, 0..10, FT.Metric.Soreness, { severity = it }, lowWord = "barely", highWord = "worst", reversed = true)
            Label("TRAINING")
            TextChipRow(IMPACT_LABELS, impact?.let { IMPACT_LABELS[it] }, perRow = 2) { picked -> impact = picked?.let { IMPACT_LABELS.indexOf(it) } }
            Label("TEMPERATURE °C (OPTIONAL)")
            FieldTextField(temperature, { temperature = it }, "e.g. 38.2", keyboardType = KeyboardType.Decimal)

            // What it does to CHARGE, as the score will read it.
            severity?.let { s ->
                val g = gravityOf(IllnessCheckIn(LocalDate.now(), impact, temp, s, symptoms))
                Text(
                    "Reads as ${g.label.uppercase()}" + (g.ceiling?.let { " · CHARGE held at ${it.roundToInt()} or below" } ?: " · no ceiling on CHARGE") +
                        ". It eases as you check in better, or by itself after two days.",
                    style = FTType.Caption, color = FT.TextSecondary,
                )
            }

            Label("A TEST (OPTIONAL)")
            TextChipRow(TESTS.map { it.second }, TESTS.firstOrNull { it.first == test }?.second, perRow = 4) { picked ->
                test = TESTS.firstOrNull { it.second == picked }?.first
                if (test == null) testResult = null
            }
            if (test != null) {
                TextChipRow(TestResult.entries.map { it.label }, testResult?.label, perRow = 2) { picked -> testResult = TestResult.entries.firstOrNull { it.label == picked } }
            }
            Label("SAW A CLINICIAN")
            TextChipRow(listOf("NO", "YES"), if (clinician) "YES" else "NO", perRow = 2) { clinician = it == "YES" }
            if (clinician) FieldTextField(diagnosis, { diagnosis = it }, "What they said (optional)")
            Label("MEDICATION (OPTIONAL)")
            FieldTextField(medication, { medication = it }, "e.g. paracetamol, antibiotics")
            Label("NOTE (OPTIONAL)")
            FieldTextField(note, { note = it }, "", singleLine = false)

            error?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Bad) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton("CANCEL", modifier = Modifier.weight(1f), onClick = onDismiss)
                FTButton(if (saving) "SAVING…" else "SAVE", enabled = ready && !saving, emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) {
                    val t = type ?: return@FTButton
                    val now = ZonedDateTime.now()
                    val plan = buildIllness(
                        IllnessDraft(
                            type = t, title = title, start = checkInDate(now).minusDays(startDaysAgo), symptoms = symptoms,
                            severity = severity ?: return@FTButton, impact = impact, temperatureC = temp, test = test, testResult = testResult,
                            sawClinician = clinician, diagnosis = diagnosis.takeIf { clinician }, medication = medication, note = note,
                        ),
                        now,
                    )
                    saving = true
                    scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                        repo.saveIllness(plan)
                        repo.assignSignals(adoptSignalIds, plan.checkIn.concerns.first().id)
                        repo.refresh()
                        onSaved()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun Label(text: String) = Text(text, style = FTType.Micro, color = FT.TextMuted)

@Composable
internal fun Chip(text: String, on: Boolean, onClick: () -> Unit) = FTChoiceChip(text, on, onClick)
