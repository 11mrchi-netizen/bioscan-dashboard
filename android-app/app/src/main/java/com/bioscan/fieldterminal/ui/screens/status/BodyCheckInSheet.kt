package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.BodyCheckinDraft
import com.bioscan.fieldterminal.data.BodyCheckinRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.domain.BodyMeasurement
import com.bioscan.fieldterminal.domain.BodySlot
import com.bioscan.fieldterminal.domain.MeasureMethod
import com.bioscan.fieldterminal.domain.activeSlots
import com.bioscan.fieldterminal.domain.maxReadings
import com.bioscan.fieldterminal.domain.median
import com.bioscan.fieldterminal.domain.minReadings
import com.bioscan.fieldterminal.domain.needsAnotherReading
import com.bioscan.fieldterminal.domain.nextSlot
import com.bioscan.fieldterminal.domain.parseReading
import com.bioscan.fieldterminal.domain.tolerance
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.LocalDateTime

// The monthly body check-in (domain/BodyCheckin.kt, docs/body-checkin/README.md), one step per screen so it can be done with a
// tape in one hand: the conditions, grip, the tape sites from the neck down, then the skinfolds in rounds. Each site
// asks for its readings one at a time and moves on by itself once they agree; a site or a whole section can be skipped.

private sealed interface CheckInStep {
    data object Setup : CheckInStep
    data object Grip : CheckInStep
    data class Measure(val slot: BodySlot) : CheckInStep
    data object Review : CheckInStep
}

private const val PROTOCOL =
    "Morning, after the toilet, before eating, drinking or training, in underwear. Tape level all round and snug " +
        "without pressing the skin; trunk sites at the end of a normal breath out. Skinfolds on the right side: pinch " +
        "1 cm above the mark, jaws on the mark, read 2 seconds after letting go of the trigger, keep holding the fold."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyCheckInSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { BodyCheckinRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()

    var history by remember { mutableStateOf<List<BodyCheckinRecord>>(emptyList()) }
    var knownHeight by remember { mutableStateOf<Double?>(null) }
    var caliper by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        history = runCatching { repo.loadHistory() }.getOrElse { emptyList() }
        knownHeight = runCatching { repo.latestHeightCm() }.getOrNull()
        // Last time's caliper, unless the person already typed one.
        if (caliper.isEmpty()) caliper = history.lastOrNull()?.caliper.orEmpty()
    }
    val last = history.lastOrNull()

    // Setup
    var fasted by remember { mutableStateOf<Boolean?>(true) }
    var beforeTraining by remember { mutableStateOf<Boolean?>(true) }
    var height by remember { mutableStateOf("") }
    var tape by remember { mutableStateOf(true) }
    var calipers by remember { mutableStateOf(true) }
    var flexedArms by remember { mutableStateOf(false) }
    var chestFold by remember { mutableStateOf(false) }

    // Grip
    var gripRight by remember { mutableStateOf("") }
    var gripLeft by remember { mutableStateOf("") }

    // Measurements
    var readings by remember { mutableStateOf<Map<BodySlot, List<Double>>>(emptyMap()) }
    var skipped by remember { mutableStateOf<Set<BodySlot>>(emptySet()) }
    var notes by remember { mutableStateOf("") }

    var step by remember { mutableStateOf<CheckInStep>(CheckInStep.Setup) }
    var trail by remember { mutableStateOf<List<CheckInStep>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val slots = activeSlots(tape, calipers, flexedArms, chestFold)
    fun go(next: CheckInStep) {
        trail = trail + step
        step = next
    }
    fun back() {
        trail.lastOrNull()?.let { step = it; trail = trail.dropLast(1) }
    }
    fun advanceFrom(current: BodySlot?) {
        go(nextSlot(slots, readings, skipped, current)?.let { CheckInStep.Measure(it) } ?: CheckInStep.Review)
    }

    fun save() {
        saving = true
        error = null
        val measurements = slots.mapNotNull { s ->
            readings[s]?.takeIf { it.isNotEmpty() && s !in skipped }?.let { BodyMeasurement(s.site, s.side, it) }
        }
        val grip = buildMap {
            gripRight.replace(',', '.').toDoubleOrNull()?.takeIf { it in 5.0..120.0 }?.let { put("right", it) }
            gripLeft.replace(',', '.').toDoubleOrNull()?.takeIf { it in 5.0..120.0 }?.let { put("left", it) }
        }
        val typedHeight = height.replace(',', '.').toDoubleOrNull()?.takeIf { it in 100.0..250.0 }
        scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }, onDone = { saving = false }) {
            repo.save(
                BodyCheckinDraft(
                    occurredAt = LocalDateTime.now(),
                    fasted = fasted,
                    beforeTraining = beforeTraining,
                    caliper = caliper.takeIf { calipers },
                    notes = notes,
                    measurements = measurements,
                    grip = grip,
                ),
            )
            if (typedHeight != null) repo.saveHeight(LocalDate.now(), typedHeight)
            onSaved()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("BODY CHECK-IN", style = FTType.SectionTitle, color = FT.DomainFuel)
            when (val s = step) {
                CheckInStep.Setup -> {
                    Text(
                        last?.let { "Last check-in ${it.date}. Same conditions as then make the months comparable." }
                            ?: "Your first check-in. The same conditions every month make the months comparable.",
                        style = FTType.BodySmall,
                        color = FT.TextSecondary,
                    )
                    Text(PROTOCOL, style = FTType.Caption, color = FT.TextMuted)
                    Label("FASTED")
                    TextChipRow(listOf("YES", "NO"), fasted?.let { if (it) "YES" else "NO" }, perRow = 2) { fasted = it?.let { v -> v == "YES" } }
                    Label("BEFORE TRAINING TODAY")
                    TextChipRow(listOf("YES", "NO"), beforeTraining?.let { if (it) "YES" else "NO" }, perRow = 2) { beforeTraining = it?.let { v -> v == "YES" } }
                    if (knownHeight == null) {
                        Label("HEIGHT, CM (ONCE: FOR WAIST-TO-HEIGHT)")
                        FieldTextField(height, { height = it }, "e.g. 178", keyboardType = KeyboardType.Decimal)
                    }
                    Label("SECTIONS")
                    Toggle("Tape girths (about 8 minutes)", tape) { tape = it }
                    if (tape) Toggle("Also flexed upper arms", flexedArms) { flexedArms = it }
                    Toggle("Skinfolds with a caliper (about 6 minutes)", calipers) { calipers = it }
                    if (calipers) {
                        Toggle("Also the chest skinfold", chestFold) { chestFold = it }
                        Label("CALIPER (MAKE AND MODEL; A DIFFERENT ONE IS NOT COMPARED)")
                        FieldTextField(caliper, { caliper = it }, "e.g. plastic slide caliper")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FTButton("CANCEL", modifier = Modifier.weight(1f), onClick = onDismiss)
                        FTButton("START", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) { go(CheckInStep.Grip) }
                    }
                }

                CheckInStep.Grip -> {
                    Text("GRIP STRENGTH", style = FTType.RowTitle, color = FT.TextPrimary)
                    Text(
                        "Standing, arm at your side, elbow straight. Three squeezes per hand, alternating hands, a few seconds " +
                            "apart. Enter the best of the three.",
                        style = FTType.BodySmall,
                        color = FT.TextSecondary,
                    )
                    last?.grip?.takeIf { it.isNotEmpty() }?.let { g ->
                        Text("Last time: " + g.entries.sortedByDescending { it.key }.joinToString(" · ") { "${it.key} %.1f kg".format(it.value) }, style = FTType.Caption, color = FT.TextMuted)
                    }
                    Label("RIGHT HAND, KG")
                    FieldTextField(gripRight, { gripRight = it }, "e.g. 46.5", keyboardType = KeyboardType.Decimal)
                    Label("LEFT HAND, KG")
                    FieldTextField(gripLeft, { gripLeft = it }, "e.g. 44.0", keyboardType = KeyboardType.Decimal)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FTButton("BACK", modifier = Modifier.weight(1f)) { back() }
                        FTButton("SKIP", modifier = Modifier.weight(1f)) { gripRight = ""; gripLeft = ""; advanceFrom(null) }
                        FTButton("NEXT", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) { advanceFrom(null) }
                    }
                }

                is CheckInStep.Measure -> MeasureStep(
                    slot = s.slot,
                    position = slots.indexOf(s.slot) + 1,
                    count = slots.size,
                    taken = readings[s.slot].orEmpty(),
                    lastValue = last?.value(s.slot.site, s.slot.side),
                    onReading = { v ->
                        val updated = readings[s.slot].orEmpty() + v
                        readings = readings + (s.slot to updated)
                        // Tape: on to the next site once the readings agree. Skinfolds: on to the next site after every
                        // reading (the rounds), which nextSlot works out.
                        if (s.slot.site.method == MeasureMethod.CALIPER || !needsAnotherReading(s.slot.site.method, updated)) advanceFrom(s.slot)
                    },
                    onRemoveLast = { readings = readings + (s.slot to readings[s.slot].orEmpty().dropLast(1)) },
                    onNext = { advanceFrom(s.slot) },
                    onBack = { back() },
                    onSkip = {
                        skipped = skipped + s.slot
                        advanceFrom(s.slot)
                    },
                    onSkipSection = {
                        skipped = skipped + slots.filter { it.site.method == s.slot.site.method }
                        advanceFrom(s.slot)
                    },
                )

                CheckInStep.Review -> {
                    Text("REVIEW", style = FTType.RowTitle, color = FT.TextPrimary)
                    val done = slots.filter { it !in skipped && readings[it].orEmpty().isNotEmpty() }
                    if (gripRight.isNotBlank() || gripLeft.isNotBlank()) {
                        Text("Grip: right ${gripRight.ifBlank { "–" }} kg · left ${gripLeft.ifBlank { "–" }} kg", style = FTType.BodySmall, color = FT.TextSecondary)
                    }
                    done.forEach { slot ->
                        val r = readings[slot].orEmpty()
                        val unit = slot.site.method.unit
                        Text(
                            "${slot.label}: %.1f $unit".format(median(r)) + "  (${r.joinToString(" / ") { "%.1f".format(it) }})",
                            style = FTType.Caption,
                            color = FT.TextSecondary,
                        )
                    }
                    if (done.isEmpty() && gripRight.isBlank() && gripLeft.isBlank()) {
                        Text("Nothing measured yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                    }
                    Label("NOTES (OPTIONAL)")
                    FieldTextField(notes, { notes = it }, "e.g. new tape, late night before", singleLine = false)
                    error?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Critical) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FTButton("BACK", modifier = Modifier.weight(1f)) { back() }
                        FTButton(
                            if (saving) "SAVING..." else "SAVE",
                            enabled = !saving && (done.isNotEmpty() || gripRight.isNotBlank() || gripLeft.isNotBlank()),
                            emphasis = FTButtonEmphasis.Primary,
                            modifier = Modifier.weight(1f),
                        ) { save() }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasureStep(
    slot: BodySlot,
    position: Int,
    count: Int,
    taken: List<Double>,
    lastValue: Double?,
    onReading: (Double) -> Unit,
    onRemoveLast: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onSkipSection: () -> Unit,
) {
    val method = slot.site.method
    val unit = method.unit
    var text by remember(slot, taken.size) { mutableStateOf("") }
    val parsed = parseReading(slot.site, text)
    val section = if (method == MeasureMethod.TAPE) "TAPE" else "SKINFOLD · RIGHT SIDE"

    Text("$section · $position OF $count", style = FTType.LabelCaps, color = FT.TextMuted)
    Text(slot.label.uppercase(), style = FTType.RowTitle, color = FT.TextPrimary)
    Text(slot.site.instruction, style = FTType.BodySmall, color = FT.TextSecondary)
    lastValue?.let { Text("Last time %.1f $unit".format(it), style = FTType.Caption, color = FT.TextMuted) }

    val done = !needsAnotherReading(method, taken)
    val want = when {
        done -> "Done: %.1f $unit (median). UNDO LAST to take a reading again.".format(median(taken))
        taken.size < minReadings(method) -> "Reading ${taken.size + 1} of ${minReadings(method)}"
        else -> "Readings ${"%.1f".format(taken.max() - taken.min())} $unit apart (more than ${"%.1f".format(tolerance(method))}): one more, ${taken.size + 1} of ${maxReadings(method)}"
    }
    Text(want, style = FTType.Label, color = FT.TextPrimary)
    if (taken.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(taken.joinToString("  ") { "%.1f".format(it) } + " $unit", style = FTType.MonoCaption, color = FT.TextSecondary)
            Box(
                Modifier
                    .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                    .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall)) { onRemoveLast() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("UNDO LAST", style = FTType.LabelCaps, color = FT.TextSecondary) }
        }
    }
    if (done) {
        FTButton("NEXT", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth(), onClick = onNext)
    } else {
        FieldTextField(text, { text = it }, "${unit.uppercase()} (${"%.0f".format(slot.site.range.start)}–${"%.0f".format(slot.site.range.endInclusive)})", keyboardType = KeyboardType.Decimal)
        if (text.isNotBlank() && parsed == null) {
            Text("Outside ${"%.0f".format(slot.site.range.start)}–${"%.0f".format(slot.site.range.endInclusive)} $unit for this site: check the reading.", style = FTType.Caption, color = FT.Warning)
        }
        FTButton("ENTER READING", enabled = parsed != null, emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth()) { parsed?.let(onReading) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FTButton("BACK", modifier = Modifier.weight(1f), onClick = onBack)
        FTButton("SKIP SITE", modifier = Modifier.weight(1f), onClick = onSkip)
        FTButton(if (method == MeasureMethod.TAPE) "SKIP TAPE" else "SKIP FOLDS", modifier = Modifier.weight(1f), onClick = onSkipSection)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary)
}

@Composable
private fun Toggle(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, if (on) FT.DomainFuel else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
            .background(if (on) FT.DomainFuel.copy(alpha = 0.12f) else FT.GlassFill, RoundedCornerShape(FT.RadiusSmall))
            .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall)) { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (on) "■" else "□", style = FTType.Label, color = if (on) FT.DomainFuel else FT.TextMuted, modifier = Modifier.padding(end = 10.dp))
        Text(text, style = FTType.BodySmall, color = FT.TextPrimary)
    }
}
