package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.DebriefAnswers
import com.bioscan.fieldterminal.data.DebriefTarget
import com.bioscan.fieldterminal.data.FuelDebriefRepository
import com.bioscan.fieldterminal.data.FuelSettingsStore
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.events.formatHoursMinutes
import com.bioscan.fieldterminal.domain.events.parseHoursMinutes
import com.bioscan.fieldterminal.domain.fuel.BlockActual
import com.bioscan.fieldterminal.domain.fuel.FuelBlock
import com.bioscan.fieldterminal.domain.fuel.FuelSymptom
import com.bioscan.fieldterminal.domain.fuel.ScheduleVerdict
import com.bioscan.fieldterminal.domain.fuel.ToleranceSuggestion
import com.bioscan.fieldterminal.domain.fuel.scheduleAdherence
import com.bioscan.fieldterminal.domain.fuel.suggestTolerance
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.util.UUID
import kotlin.math.roundToInt

// The debrief after a race or long run (docs/race-fuel-rework/plan.md). It opens on "everything as planned": each block of the
// schedule is one tap (as planned, less, more, none), the totals and whether the schedule held are worked out, then the
// discomfort ticked, how the gut, energy, tiredness and thirst were, and the optional weigh-ins.

private val SCORES = (0..10).map { it.toString() to it.toString() }

private enum class Took(val label: String) { AsPlanned("AS PLANNED"), Less("LESS"), More("MORE"), None("NONE") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelDebriefSheet(target: DebriefTarget, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            FuelDebriefContent(target, onSaved)
        }
    }
}

@Composable
fun FuelDebriefContent(target: DebriefTarget, onSaved: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { FuelDebriefRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val blocks = remember(target) { target.schedule?.blocks.orEmpty() }
    val took = remember(target) { mutableStateMapOf<String, Took>() }
    val countText = remember(target) { mutableStateMapOf<String, String>() }
    val amountText = remember(target) { mutableStateMapOf<String, String>() }
    val extras = remember(target) { mutableStateListOf<FuelBlock>() }
    val symptoms = remember(target) { mutableStateListOf<FuelSymptom>().also { l -> target.existing?.symptoms?.mapNotNull(FuelSymptom::of)?.let(l::addAll) } }
    var duration by remember(target) { mutableStateOf(target.durationMin?.let { formatHoursMinutes(it) }.orEmpty()) }
    var gi by remember(target) { mutableStateOf(target.existing?.giScore?.toString() ?: "0") }
    var energy by remember(target) { mutableStateOf(target.existing?.energyScore?.toString() ?: "7") }
    var fatigue by remember(target) { mutableStateOf(target.existing?.fatigueScore?.toString() ?: "") }
    var thirst by remember(target) { mutableStateOf(target.existing?.thirstScore?.toString() ?: "") }
    var before by remember(target) { mutableStateOf(target.existing?.weightBeforeKg?.toString().orEmpty()) }
    var after by remember(target) { mutableStateOf(target.existing?.weightAfterKg?.toString().orEmpty()) }
    var notes by remember(target) { mutableStateOf(target.existing?.notes.orEmpty()) }
    var manualCarbs by remember(target) { mutableStateOf("") }
    var manualFluid by remember(target) { mutableStateOf("") }
    var addingExtra by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var suggestion by remember { mutableStateOf<ToleranceSuggestion?>(null) }

    fun actuals(): List<BlockActual> = blocks.map { b ->
        val scale = (amountText[b.id]?.replace(',', '.')?.toDoubleOrNull()?.div(100.0))?.takeIf { took[b.id] == Took.Less || took[b.id] == Took.More }
        when (took[b.id] ?: Took.AsPlanned) {
            Took.AsPlanned -> BlockActual(b.id)
            Took.None -> BlockActual(b.id, taken = 0)
            Took.Less, Took.More -> BlockActual(b.id, taken = countText[b.id]?.toIntOrNull() ?: b.count, amountScale = scale ?: 1.0)
        }
    } + extras.map { BlockActual(it.id, taken = it.count) }

    val allBlocks = blocks + extras
    val adherence = scheduleAdherence(allBlocks, actuals())
    val noLink = target.sessionId == null

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("FUEL DEBRIEF", style = FTType.SectionTitle, color = FT.DomainFuel)
        Text(target.title, style = FTType.BodySmall, color = FT.TextSecondary)
        if (noLink) {
            Text("The recorded session isn't linked yet (it links once synced): the debrief is kept, the intake rows are written when it is.", style = FTType.Caption, color = FT.TextSecondary)
        }
        Column { DLabel("DURATION"); FieldTextField(duration, { duration = it }, "h:mm") }

        if (blocks.isEmpty()) {
            Text("No schedule was made for this one. Add what you took, or enter the totals.", style = FTType.Caption, color = FT.TextSecondary)
        } else {
            DLabel("WHAT YOU TOOK")
            blocks.forEach { b ->
                val state = took[b.id] ?: Took.AsPlanned
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${b.label.uppercase()} · ${blockSummary(b)}", style = FTType.BodySmall, color = FT.TextPrimary)
                    SegmentedToggle(Took.entries, state, { it.label }, { took[b.id] = it; if (it == Took.More && countText[b.id] == null) countText[b.id] = (b.count + 1).toString() else if (it == Took.Less && countText[b.id] == null) countText[b.id] = (b.count - 1).coerceAtLeast(1).toString() }, accentColor = FT.DomainFuel)
                    if (state == Took.Less || state == Took.More) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) { DLabel("HOW MANY (PLANNED ${b.count})"); FieldTextField(countText[b.id].orEmpty(), { countText[b.id] = it }, b.count.toString(), keyboardType = KeyboardType.Number) }
                            Column(Modifier.weight(1f)) { DLabel("EACH, % OF PLANNED"); FieldTextField(amountText[b.id].orEmpty(), { amountText[b.id] = it }, "100", keyboardType = KeyboardType.Number) }
                        }
                    }
                }
            }
        }
        extras.forEach { x -> Text("EXTRA · ${x.label.uppercase()} · ${blockSummary(x)}", style = FTType.BodySmall, color = FT.TextPrimary) }
        FTButton("ADD SOMETHING YOU TOOK", modifier = Modifier.fillMaxWidth()) { addingExtra = true }

        if (blocks.isEmpty() && extras.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { DLabel("TOTAL CARBS G"); FieldTextField(manualCarbs, { manualCarbs = it }, "total", keyboardType = KeyboardType.Decimal) }
                Column(Modifier.weight(1f)) { DLabel("TOTAL FLUID ML"); FieldTextField(manualFluid, { manualFluid = it }, "total", keyboardType = KeyboardType.Decimal) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "TAKEN ${adherence.takenCarbsG.roundToInt()} G CARBS · ${"%.1f".format(adherence.takenFluidMl / 1000)} L · ${adherence.takenSodiumMg.roundToInt()} MG NA" +
                        if (adherence.takenCaffeineMg > 0) " · ${adherence.takenCaffeineMg.roundToInt()} MG CAFFEINE" else "",
                    style = FTType.LabelCaps, color = FT.TextPrimary,
                )
                adherence.verdict?.let { v ->
                    Text(
                        "SCHEDULE ${v.label} · ${(adherence.carbShare!! * 100).roundToInt()} % of the planned carbohydrate",
                        style = FTType.LabelCaps, color = if (v == ScheduleVerdict.HELD) FT.Emerald else FT.Warning,
                    )
                }
                adherence.slipped.forEach { Text("· ${it.label}: ${it.taken} of ${it.planned} taken", style = FTType.Caption, color = FT.TextSecondary) }
            }
        }

        DLabel("DISCOMFORT")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelSymptom.entries.forEach { s ->
                val on = s in symptoms
                val shape = RoundedCornerShape(FT.RadiusSmall)
                Text(
                    s.label.uppercase(), style = FTType.LabelCaps, color = if (on) FT.Warning else FT.TextSecondary,
                    modifier = Modifier
                        .border(FT.BorderWidth, if (on) FT.Warning else FT.GlassBorder, shape)
                        .background(if (on) FT.Warning.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent, shape)
                        .ftClickable(shape = shape) { if (on) symptoms.remove(s) else symptoms.add(s) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (symptoms.isEmpty()) Text("Nothing ticked: no discomfort.", style = FTType.Caption, color = FT.TextMuted)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { DLabel("GUT (0 FINE - 10 BAD)"); FTDropdown(SCORES, gi, "0-10") { gi = it } }
            Column(Modifier.weight(1f)) { DLabel("ENERGY (0 EMPTY - 10 STRONG)"); FTDropdown(SCORES, energy, "0-10") { energy = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { DLabel("TIREDNESS (0 FRESH - 10 SPENT)"); FTDropdown(SCORES, fatigue, "0-10") { fatigue = it } }
            Column(Modifier.weight(1f)) { DLabel("THIRST (0 NONE - 10 SEVERE)"); FTDropdown(SCORES, thirst, "0-10") { thirst = it } }
        }
        Text("Weigh-ins (optional): without clothes, before the start and at the finish before drinking. They make this a sweat test.", style = FTType.Caption, color = FT.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { DLabel("BEFORE KG"); FieldTextField(before, { before = it }, "e.g. 70.2", keyboardType = KeyboardType.Decimal) }
            Column(Modifier.weight(1f)) { DLabel("AFTER KG"); FieldTextField(after, { after = it }, "e.g. 68.9", keyboardType = KeyboardType.Decimal) }
        }
        Column { DLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "What worked, what didn't", singleLine = false) }
        error?.let { Text(it, style = FTType.Caption, color = FT.Critical) }

        if (!saved) {
            val d = parseHoursMinutes(duration)
            FTButton(if (saving) "SAVING…" else "SAVE DEBRIEF", enabled = d != null && !saving, emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth()) {
                saving = true
                scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                    val outcome = repo.save(
                        target, allBlocks, actuals(),
                        DebriefAnswers(
                            durationMin = d!!, symptoms = symptoms.toSet(), giScore = gi.toIntOrNull() ?: 0, energyScore = energy.toIntOrNull(),
                            fatigueScore = fatigue.toIntOrNull(), thirstScore = thirst.toIntOrNull(),
                            weightBeforeKg = before.replace(',', '.').toDoubleOrNull(), weightAfterKg = after.replace(',', '.').toDoubleOrNull(),
                            notes = notes, manualCarbsG = manualCarbs.replace(',', '.').toDoubleOrNull(), manualFluidMl = manualFluid.replace(',', '.').toDoubleOrNull(),
                        ),
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

    if (addingExtra) {
        FuelBlockSheet(
            initial = FuelBlock(UUID.randomUUID().toString().take(8), "", "gel", startMin = 0, intervalMin = 0, count = 1),
            isNew = true,
            onDismiss = { addingExtra = false },
            onSave = { extras.add(it); addingExtra = false },
            onDelete = { addingExtra = false },
        )
    }
}

@Composable
private fun DLabel(text: String) {
    Text(text, color = FT.TextMuted, style = FTType.LabelCaps, modifier = Modifier.padding(bottom = 6.dp))
}
