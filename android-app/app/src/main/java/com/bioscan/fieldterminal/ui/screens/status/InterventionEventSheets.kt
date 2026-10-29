package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.bioscan.fieldterminal.data.InterventionEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.EpisodicIntervention
import com.bioscan.fieldterminal.data.model.ExpectationRow
import com.bioscan.fieldterminal.data.model.TrackedOutcomeRow
import com.bioscan.fieldterminal.domain.ExpectationDraft
import com.bioscan.fieldterminal.domain.ExpectationRole
import com.bioscan.fieldterminal.domain.ExpectedDirection
import com.bioscan.fieldterminal.domain.OutcomeSeries
import com.bioscan.fieldterminal.domain.TrackedScale
import com.bioscan.fieldterminal.domain.WINDOW_PRESETS
import com.bioscan.fieldterminal.domain.localStamp
import com.bioscan.fieldterminal.domain.parseOutcomeSeries
import com.bioscan.fieldterminal.domain.parseTrackedValue
import com.bioscan.fieldterminal.domain.validateExpectations
import com.bioscan.fieldterminal.domain.windowText
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime

// Sheets for repeatable, timed interventions (docs: "Interventions as repeatable, timed events"). Declaring an
// intervention fixes what it is expected to change and the window each outcome is read in; the database locks those once
// the first event is logged, so a window can't be re-chosen after seeing data.

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary)
}

private val DOMAINS = listOf("sleep", "nutrition", "lifestyle", "training", "supplement", "other")
private val CONFOUNDERS = listOf("illness", "travel", "heavy_training", "poor_sleep_before")

/** The daily stored series an expectation can name, from the packaged metric-series.json. */
@Composable
internal fun rememberOutcomeSeries(): List<OutcomeSeries> {
    val context = LocalContext.current
    return remember {
        runCatching { context.applicationContext.assets.open("metric-series.json").bufferedReader().use { parseOutcomeSeries(it.readText()) } }.getOrDefault(emptyList())
    }
}

// ---------------------------------------------------------------- new intervention

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterventionSheet(tracked: List<TrackedOutcomeRow>, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { InterventionEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val series = rememberOutcomeSeries()
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf<String?>("lifestyle") }
    val drafts = remember { mutableStateListOf<ExpectationDraft>() }
    // the draft being added
    var outcome by remember { mutableStateOf<String?>(null) } // "series:<id>" or "tracked:<id>"
    var role by remember { mutableStateOf(if (drafts.isEmpty()) ExpectationRole.PRIMARY else ExpectationRole.SECONDARY) }
    var direction by remember { mutableStateOf(ExpectedDirection.DOWN) }
    var preset by remember { mutableStateOf(WINDOW_PRESETS.first()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val options = series.map { "series:${it.id}" to it.label } + tracked.map { "tracked:${it.id}" to "${it.name} (yours)" }
    fun labelOf(key: String) = options.firstOrNull { it.first == key }?.second ?: key

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("NEW INTERVENTION · REPEATABLE", style = FTType.SectionTitle, color = FT.DomainLabs)
            Text(
                "Something you try now and then (nasal strips, a lactose meal, a cold shower). Say what you expect it to change and when; " +
                    "that is fixed once you log the first one. One try never counts as an answer: evidence builds up over many.",
                style = FTType.BodySmall, color = FT.TextSecondary,
            )
            SheetLabel("NAME")
            FieldTextField(name, { name = it }, "e.g. Nasal strips")
            SheetLabel("HOW (OPTIONAL)")
            FieldTextField(instructions, { instructions = it }, "e.g. Brand, placement", singleLine = false)
            SheetLabel("KIND")
            TextChipRow(DOMAINS, domain, perRow = 3) { domain = it ?: domain }

            SheetLabel("WHAT YOU EXPECT IT TO CHANGE")
            drafts.forEachIndexed { i, d ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${d.role.label}: ${d.label} ${d.direction.arrow} (${windowText(d.startH, d.endH)})",
                        style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f),
                    )
                    FTButton(label = "REMOVE") { drafts.removeAt(i) }
                }
            }
            FTDropdown(options, outcome, "Outcome", Modifier.fillMaxWidth()) { outcome = it }
            TextChipRow(ExpectedDirection.entries.map { it.label }, direction.label, perRow = 3) { picked -> ExpectedDirection.entries.firstOrNull { it.label == picked }?.let { direction = it } }
            SheetLabel("READ IT")
            TextChipRow(WINDOW_PRESETS.map { it.label }, preset.label, perRow = 2) { picked -> WINDOW_PRESETS.firstOrNull { it.label == picked }?.let { preset = it } }
            Text("For something in the evening, “that night” is the 12 hours after it; the others count from the moment you log it.", style = FTType.Caption, color = FT.TextMuted)
            TextChipRow(ExpectationRole.entries.map { it.label }, role.label, perRow = 3) { picked -> ExpectationRole.entries.firstOrNull { it.label == picked }?.let { role = it } }
            FTButton(label = "ADD OUTCOME", enabled = outcome != null) {
                val key = outcome ?: return@FTButton
                val (kind, id) = key.split(":", limit = 2)
                drafts.add(ExpectationDraft(kind, id, labelOf(key).removeSuffix(" (yours)"), role, direction, preset.startH, preset.endH, if (kind == "tracked") "max" else "mean"))
                outcome = null
                role = ExpectationRole.SECONDARY
                error = null
            }
            if (tracked.isEmpty()) Text("For something you rate or note yourself (stool consistency, headache), add a tracked outcome first.", style = FTType.Caption, color = FT.TextMuted)
            (error)?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }

            FTButton(label = if (saving) "SAVING..." else "SAVE INTERVENTION", enabled = name.trim().length >= 2 && !saving, emphasis = FTButtonEmphasis.Primary) {
                validateExpectations(drafts)?.let { error = it; return@FTButton }
                saving = true
                scope.launch {
                    try {
                        repo.createIntervention(name, instructions, domain ?: "other", drafts.toList())
                        onCreated()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save"
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- log an event

private val WHEN_CHOICES = listOf("Now" to 0L, "1 h ago" to 60L, "2 h ago" to 120L, "4 h ago" to 240L, "8 h ago" to 480L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogEventSheet(intervention: EpisodicIntervention, expectations: List<ExpectationRow>, onDismiss: () -> Unit, onLogged: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { InterventionEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var whenLabel by remember { mutableStateOf<String?>("Now") }
    var partial by remember { mutableStateOf(false) }
    val flagged = remember { mutableStateListOf<String>() }
    var dose by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("LOG · ${intervention.name.uppercase()}", style = FTType.SectionTitle, color = FT.DomainLabs)
            SheetLabel("WHEN")
            TextChipRow(WHEN_CHOICES.map { it.first }, whenLabel, perRow = 3) { whenLabel = it ?: whenLabel }
            SheetLabel("HOW IT WENT")
            TextChipRow(listOf("As planned", "Only partly"), if (partial) "Only partly" else "As planned", perRow = 2) { partial = it == "Only partly" }
            SheetLabel("ANYTHING ELSE GOING ON? (SO THIS NIGHT CAN BE LEFT OUT)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CONFOUNDERS.take(2).forEach { c -> ToggleButton(c, c in flagged, Modifier.weight(1f)) { if (c in flagged) flagged.remove(c) else flagged.add(c) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CONFOUNDERS.drop(2).forEach { c -> ToggleButton(c, c in flagged, Modifier.weight(1f)) { if (c in flagged) flagged.remove(c) else flagged.add(c) } }
            }
            Text("Alcohol, late caffeine and late meals come from your evening check-in; no need to repeat them.", style = FTType.Caption, color = FT.TextMuted)
            SheetLabel("AMOUNT OR DETAIL (OPTIONAL)")
            FieldTextField(dose, { dose = it }, "e.g. 2 glasses of milk")
            SheetLabel("NOTE (OPTIONAL)")
            FieldTextField(note, { note = it }, "", singleLine = false)
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            FTButton(label = if (saving) "SAVING..." else "LOG IT", enabled = !saving, emphasis = FTButtonEmphasis.Primary) {
                val minutesAgo = WHEN_CHOICES.firstOrNull { it.first == whenLabel }?.second ?: 0L
                val at = ZonedDateTime.now().minusMinutes(minutesAgo)
                saving = true
                scope.launch {
                    try {
                        repo.logEvent(intervention, expectations, Instant.from(at), localStamp(at), if (partial) "partial" else "yes", flagged.toList(), dose, note)
                        onLogged()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save"
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FTButton(label = (if (on) "✓ " else "") + label.replace('_', ' ').uppercase(), modifier = modifier, onClick = onClick)
}

// ---------------------------------------------------------------- how it felt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateEventSheet(eventId: String, interventionName: String, onDismiss: () -> Unit, onRated: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { InterventionEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var feeling by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("HOW DID IT FEEL · ${interventionName.uppercase()}", style = FTType.SectionTitle, color = FT.DomainLabs)
            Text("Rate it before looking at any numbers, so the data can't colour it. 0 is worst, 10 is best.", style = FTType.BodySmall, color = FT.TextSecondary)
            TextChipRow((0..10).map { it.toString() }, feeling, perRow = 6) { feeling = it }
            SheetLabel("NOTE (OPTIONAL)")
            FieldTextField(note, { note = it }, "e.g. strip came off at 3am", singleLine = false)
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            FTButton(label = if (saving) "SAVING..." else "SAVE", enabled = feeling != null && !saving, emphasis = FTButtonEmphasis.Primary) {
                val f = feeling?.toIntOrNull() ?: return@FTButton
                saving = true
                scope.launch {
                    try {
                        repo.rateEvent(eventId, f, note)
                        onRated()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save"
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tracked outcomes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackedOutcomeSheet(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { InterventionEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var scale by remember { mutableStateOf(TrackedScale.BRISTOL) }
    var better by remember { mutableStateOf("none") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("NEW TRACKED OUTCOME", style = FTType.SectionTitle, color = FT.DomainLabs)
            Text("Something you record yourself, like stool consistency or a headache, so an intervention can be read against it.", style = FTType.BodySmall, color = FT.TextSecondary)
            SheetLabel("NAME")
            FieldTextField(name, { name = it }, "e.g. Stool consistency")
            SheetLabel("SCALE")
            TextChipRow(TrackedScale.entries.map { it.label }, scale.label, perRow = 1) { picked -> TrackedScale.entries.firstOrNull { it.label == picked }?.let { scale = it } }
            SheetLabel("WHICH WAY IS BETTER")
            TextChipRow(listOf("Higher", "Lower", "Neither"), when (better) { "up" -> "Higher"; "down" -> "Lower"; else -> "Neither" }, perRow = 3) {
                better = when (it) { "Higher" -> "up"; "Lower" -> "down"; else -> "none" }
            }
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            FTButton(label = if (saving) "SAVING..." else "SAVE", enabled = name.trim().length >= 2 && !saving, emphasis = FTButtonEmphasis.Primary) {
                saving = true
                scope.launch {
                    try {
                        repo.createTrackedOutcome(name, scale.key, better, null)
                        onCreated()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save"
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackedValueSheet(tracked: List<TrackedOutcomeRow>, onDismiss: () -> Unit, onLogged: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { InterventionEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(tracked.firstOrNull()?.id) }
    var whenLabel by remember { mutableStateOf<String?>("Now") }
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val outcome = tracked.firstOrNull { it.id == chosen }
    val scale = TrackedScale.entries.firstOrNull { it.key == outcome?.scale } ?: TrackedScale.RATING

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("LOG A VALUE", style = FTType.SectionTitle, color = FT.DomainLabs)
            FTDropdown(tracked.map { it.id to it.name }, chosen, "Outcome", Modifier.fillMaxWidth()) { chosen = it }
            SheetLabel("WHEN")
            TextChipRow(WHEN_CHOICES.map { it.first }, whenLabel, perRow = 3) { whenLabel = it ?: whenLabel }
            SheetLabel("VALUE (${scale.min.toInt()} TO ${scale.max.toInt()})")
            FieldTextField(text, { text = it }, "", keyboardType = KeyboardType.Decimal)
            SheetLabel("NOTE (OPTIONAL)")
            FieldTextField(note, { note = it }, "", singleLine = false)
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            FTButton(label = if (saving) "SAVING..." else "SAVE", enabled = outcome != null && !saving, emphasis = FTButtonEmphasis.Primary) {
                val v = parseTrackedValue(scale, text)
                if (v == null) { error = "Enter a value from ${scale.min.toInt()} to ${scale.max.toInt()}${if (scale.whole) " (whole numbers)" else ""}."; return@FTButton }
                val minutesAgo = WHEN_CHOICES.firstOrNull { it.first == whenLabel }?.second ?: 0L
                val at = ZonedDateTime.now().minusMinutes(minutesAgo)
                saving = true
                scope.launch {
                    try {
                        repo.logTrackedValue(outcome!!.id, Instant.from(at), localStamp(at), v, note)
                        onLogged()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save"
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}
