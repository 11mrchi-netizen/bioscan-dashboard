package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.FuelReviewRepository
import com.bioscan.fieldterminal.data.FuelSettingsStore
import com.bioscan.fieldterminal.data.ReviewContext
import com.bioscan.fieldterminal.data.StepStatus
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.formatHoursMinutes
import com.bioscan.fieldterminal.domain.events.parseHoursMinutes
import com.bioscan.fieldterminal.domain.fuel.ToleranceSuggestion
import com.bioscan.fieldterminal.domain.fuel.suggestTolerance
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

// The review after an event (data/FuelReviewRepository.kt): what of the plan was taken, how the gut and the legs coped,
// the weigh-ins. Saving logs the fuel against the recorded session, makes the race a sweat test when both weights are in,
// and may suggest a new tolerated carbohydrate rate (never applied without the person).

private fun String.num(): Double? = replace(',', '.').trim().toDoubleOrNull()
private val SCORES = (0..10).map { it.toString() to it.toString() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelReviewSheet(event: AthleteEvent, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { FuelReviewRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var ctx by remember { mutableStateOf<ReviewContext?>(null) }
    val statuses = remember { mutableStateListOf<StepStatus>() }
    var duration by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fluid by remember { mutableStateOf("") }
    var gi by remember { mutableStateOf("1") }
    var energy by remember { mutableStateOf("7") }
    var before by remember { mutableStateOf("") }
    var after by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var suggestion by remember { mutableStateOf<ToleranceSuggestion?>(null) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(event.id) {
        val c = runCatching { repo.context(event) }.getOrNull() ?: ReviewContext(null, null, null)
        ctx = c
        statuses.clear()
        statuses.addAll(c.plan?.steps.orEmpty().map { StepStatus.TAKEN })
        (c.sessionDurationMin ?: event.plannedDurationMin)?.let { duration = formatHoursMinutes(it) }
        c.existing?.let { r ->
            r.giScore?.let { gi = it.toString() }
            r.energyScore?.let { energy = it.toString() }
            r.weightBeforeKg?.let { before = it.toString() }
            r.weightAfterKg?.let { after = it.toString() }
            notes = r.notes.orEmpty()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("REVIEW FUELLING", style = FTType.SectionTitle, color = FT.DomainFuel)
            Text(event.name, style = FTType.BodySmall, color = FT.TextSecondary)
            val c = ctx
            if (c == null) {
                Text("Loading the plan…", style = FTType.BodySmall, color = FT.TextSecondary)
                return@Column
            }
            if (event.exerciseSessionId == null) {
                Text("The recorded session isn't linked yet (it links once synced); the fuel log will be kept with the review only.", style = FTType.Caption, color = FT.TextSecondary)
            }
            Column { Label("DURATION"); FieldTextField(duration, { duration = it }, "h:mm") }
            val steps = c.plan?.steps.orEmpty()
            if (steps.isEmpty()) {
                Text("No plan was kept for this event. Enter what you took in total.", style = FTType.Caption, color = FT.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Label("CARBS G"); FieldTextField(carbs, { carbs = it }, "total", keyboardType = KeyboardType.Number) }
                    Column(Modifier.weight(1f)) { Label("FLUID ML"); FieldTextField(fluid, { fluid = it }, "total", keyboardType = KeyboardType.Number) }
                }
            } else {
                Label("WHAT YOU TOOK")
                steps.forEachIndexed { i, s ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${hm(s.elapsedMin.toDouble())} · ${s.items.joinToString(", ")}", style = FTType.BodySmall, color = FT.TextPrimary)
                        if (i < statuses.size) SegmentedToggle(StepStatus.entries, statuses[i], { it.label }, { statuses[i] = it }, accentColor = FT.DomainFuel)
                    }
                }
                val taken = steps.indices.sumOf { steps[it].carbsG * (statuses.getOrNull(it)?.share ?: 1.0) }
                Text("TAKEN ${taken.roundToInt()} G CARBOHYDRATE", style = FTType.LabelCaps, color = FT.TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { Label("GUT (0 FINE - 10 BAD)"); FTDropdown(SCORES, gi, "0-10") { gi = it } }
                Column(Modifier.weight(1f)) { Label("ENERGY (0 EMPTY - 10 STRONG)"); FTDropdown(SCORES, energy, "0-10") { energy = it } }
            }
            Text("Weigh-ins (optional): without clothes, before the start and at the finish before drinking. They make this race a sweat test.", style = FTType.Caption, color = FT.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { Label("BEFORE KG"); FieldTextField(before, { before = it }, "e.g. 70.2", keyboardType = KeyboardType.Decimal) }
                Column(Modifier.weight(1f)) { Label("AFTER KG"); FieldTextField(after, { after = it }, "e.g. 68.9", keyboardType = KeyboardType.Decimal) }
            }
            Column { Label("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "What worked, what didn't", singleLine = false) }
            error?.let { Text(it, style = FTType.Caption, color = FT.Critical) }

            if (!saved) {
                val d = parseHoursMinutes(duration)
                FTButton(if (saving) "SAVING…" else "SAVE REVIEW", enabled = d != null && !saving, emphasis = FTButtonEmphasis.Primary) {
                    saving = true
                    scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                        val outcome = repo.save(
                            event, steps.mapIndexed { i, s -> s to (statuses.getOrNull(i) ?: StepStatus.TAKEN) }, d!!,
                            carbs.num().takeIf { steps.isEmpty() }, fluid.num().takeIf { steps.isEmpty() },
                            gi.toIntOrNull() ?: 0, energy.toIntOrNull(), before.num(), after.num(), notes,
                        )
                        suggestion = suggestTolerance(outcome, FuelSettingsStore.get(context).toleratedGPerHour)
                        saved = true
                        if (suggestion == null) onSaved()
                    }
                }
            } else {
                suggestion?.let { s ->
                    Text(s.reason, style = FTType.BodySmall, color = FT.TextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FTButton("SET ${s.newGPerHour.roundToInt()} G/H", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) {
                            FuelSettingsStore.save(context, FuelSettingsStore.get(context).copy(toleratedGPerHour = s.newGPerHour))
                            onSaved()
                        }
                        FTButton("KEEP AS IS", modifier = Modifier.weight(1f)) { onSaved() }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}
