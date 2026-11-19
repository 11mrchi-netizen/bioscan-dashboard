package com.bioscan.fieldterminal.ui.screens.fuel

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FTRatingSlider
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.FlowEssentials
import com.bioscan.fieldterminal.ui.components.FlowMore
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowPickGrid
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CoroutineScope
import java.util.UUID
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The debrief after a race or long run (docs/race-fuel-rework/plan.md), as a flow of pages that never scroll: what you took
// (the schedule's blocks, three to a page, each one tap: as planned, less, more, none), the totals with anything extra,
// the discomfort and the gut, how the energy, tiredness and thirst were, then the optional weigh-ins and notes. Run fuel
// (Add entry) walks the same pages after its own ACTIVITY page.

internal enum class Took(val label: String) { AsPlanned("PLANNED"), Less("LESS"), More("MORE"), None("NONE") }


/** Everything a debrief holds while it is filled in, and its pages. */
@Stable
class DebriefState internal constructor(val target: DebriefTarget, private val repo: FuelDebriefRepository) {
    internal val blocks = target.schedule?.blocks.orEmpty()
    internal val took = mutableStateMapOf<String, Took>()
    internal val countText = mutableStateMapOf<String, String>()
    internal val amountText = mutableStateMapOf<String, String>()
    internal val extras = mutableStateListOf<FuelBlock>()
    internal val symptoms = mutableStateListOf<FuelSymptom>().also { l -> target.existing?.symptoms?.mapNotNull(FuelSymptom::of)?.let(l::addAll) }
    internal var duration by mutableStateOf(target.durationMin?.let { formatHoursMinutes(it) }.orEmpty())
    internal var gi by mutableStateOf(target.existing?.giScore?.toString() ?: "0")
    internal var energy by mutableStateOf(target.existing?.energyScore?.toString() ?: "7")
    internal var fatigue by mutableStateOf(target.existing?.fatigueScore?.toString() ?: "")
    internal var thirst by mutableStateOf(target.existing?.thirstScore?.toString() ?: "")
    internal var before by mutableStateOf(target.existing?.weightBeforeKg?.toString().orEmpty())
    internal var after by mutableStateOf(target.existing?.weightAfterKg?.toString().orEmpty())
    internal var notes by mutableStateOf(target.existing?.notes.orEmpty())
    internal var manualCarbs by mutableStateOf("")
    internal var manualFluid by mutableStateOf("")
    internal var addingExtra by mutableStateOf(false)
    var saving by mutableStateOf(false)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    /** Set once saved, when the debrief suggests a new tolerated carbohydrate rate: the flow then shows it. */
    var suggestion by mutableStateOf<ToleranceSuggestion?>(null)
        internal set

    val canSave: Boolean get() = parseHoursMinutes(duration) != null

    /**
     * Two pages (Logging Flows Redesign Plan): WHAT YOU TOOK, planned against actual (the duration, every block marked AS
     * PLANNED until tapped, extras, and the totals live under them), then HOW IT WENT (gut and feeling, about the same
     * session; weigh-ins and notes folded).
     */
    val pages: List<FlowPage>
        get() = listOf(FlowPage("WHAT YOU TOOK", ready = canSave), FlowPage("HOW IT WENT"))

    internal fun actuals(): List<BlockActual> = blocks.map { b ->
        val scale = (amountText[b.id]?.replace(',', '.')?.toDoubleOrNull()?.div(100.0))?.takeIf { took[b.id] == Took.Less || took[b.id] == Took.More }
        when (took[b.id] ?: Took.AsPlanned) {
            Took.AsPlanned -> BlockActual(b.id)
            Took.None -> BlockActual(b.id, taken = 0)
            Took.Less, Took.More -> BlockActual(b.id, taken = countText[b.id]?.toIntOrNull() ?: b.count, amountScale = scale ?: 1.0)
        }
    } + extras.map { BlockActual(it.id, taken = it.count) }

    /** Saves; [onSaved] runs at once unless there is a tolerance suggestion to show first. */
    fun save(context: Context, scope: CoroutineScope, onSaved: () -> Unit) {
        val d = parseHoursMinutes(duration) ?: return
        saving = true
        scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
            val outcome = repo.save(
                target, blocks + extras, actuals(),
                DebriefAnswers(
                    durationMin = d, symptoms = symptoms.toSet(), giScore = gi.toIntOrNull() ?: 0, energyScore = energy.toIntOrNull(),
                    fatigueScore = fatigue.toIntOrNull(), thirstScore = thirst.toIntOrNull(),
                    weightBeforeKg = before.replace(',', '.').toDoubleOrNull(), weightAfterKg = after.replace(',', '.').toDoubleOrNull(),
                    notes = notes, manualCarbsG = manualCarbs.replace(',', '.').toDoubleOrNull(), manualFluidMl = manualFluid.replace(',', '.').toDoubleOrNull(),
                ),
            )
            suggestion = suggestTolerance(outcome, FuelSettingsStore.get(context).toleratedGPerHour)
            if (suggestion == null) onSaved()
        }
    }

    /** One page of the debrief, [index] into [pages]. */
    @Composable
    fun Page(index: Int) {
        if (index == 0) {
            TookPage()
            TotalsPage()
        } else {
            GutPage()
            FeelingPage()
            FlowMore("debrief_weigh_ins", label = "WEIGH-INS AND NOTES", summary = "Before and after weights make it a sweat test") { ExtrasPage() }
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
    private fun TookPage() {
        FlowEssentials {
            Text(target.title, style = FTType.BodySmall, color = FT.TextSecondary)
            Column { DLabel("DURATION"); FieldTextField(duration, { duration = it }, "h:mm") }
        }
        if (blocks.isNotEmpty()) Text("Everything is AS PLANNED until you tap what differed.", style = FTType.Caption, color = FT.TextMuted)
        blocks.forEach { b ->
            val state = took[b.id] ?: Took.AsPlanned
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${b.label.uppercase()} · ${blockSummary(b)}", style = FTType.BodySmall, color = FT.TextPrimary)
                SegmentedToggle(Took.entries, state, { it.label }, {
                    took[b.id] = it
                    if (it == Took.More && countText[b.id] == null) countText[b.id] = (b.count + 1).toString()
                    else if (it == Took.Less && countText[b.id] == null) countText[b.id] = (b.count - 1).coerceAtLeast(1).toString()
                }, accentColor = FT.DomainFuel)
                if (state == Took.Less || state == Took.More) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) { DLabel("HOW MANY (PLANNED ${b.count})"); FieldTextField(countText[b.id].orEmpty(), { countText[b.id] = it }, b.count.toString(), keyboardType = KeyboardType.Number) }
                        Column(Modifier.weight(1f)) { DLabel("EACH, % OF PLANNED"); FieldTextField(amountText[b.id].orEmpty(), { amountText[b.id] = it }, "100", keyboardType = KeyboardType.Number) }
                    }
                }
            }
        }
    }

    @Composable
    private fun TotalsPage() {
        if (target.sessionId == null) {
            Text("The recorded session isn't linked yet (it links once synced): the debrief is kept, the intake rows are written when it is.", style = FTType.Caption, color = FT.TextSecondary)
        }
        if (blocks.isEmpty()) Text("No schedule was made for this one. Add what you took, or enter the totals.", style = FTType.Caption, color = FT.TextSecondary)
        // Extras as one line each, the latest three: the totals below count them all.
        extras.takeLast(3).forEach { x -> Text("EXTRA · ${x.label.uppercase()} · ${blockSummary(x)}", style = FTType.BodySmall, color = FT.TextPrimary) }
        if (extras.size > 3) Text("and ${extras.size - 3} more", style = FTType.Caption, color = FT.TextMuted)
        FTButton("ADD SOMETHING YOU TOOK", modifier = Modifier.fillMaxWidth()) { addingExtra = true }
        if (blocks.isEmpty() && extras.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { DLabel("TOTAL CARBS G"); FieldTextField(manualCarbs, { manualCarbs = it }, "total", keyboardType = KeyboardType.Decimal) }
                Column(Modifier.weight(1f)) { DLabel("TOTAL FLUID ML"); FieldTextField(manualFluid, { manualFluid = it }, "total", keyboardType = KeyboardType.Decimal) }
            }
        } else {
            val adherence = scheduleAdherence(blocks + extras, actuals())
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "TAKEN ${adherence.takenCarbsG.roundToInt()} G CARBS · ${"%.1f".format(adherence.takenFluidMl / 1000)} L · ${adherence.takenSodiumMg.roundToInt()} MG NA" +
                        if (adherence.takenCaffeineMg > 0) " · ${adherence.takenCaffeineMg.roundToInt()} MG CAFFEINE" else "",
                    style = FTType.LabelCaps, color = FT.TextPrimary,
                )
                adherence.verdict?.let { v ->
                    Text(
                        "SCHEDULE ${v.label} · ${(adherence.carbShare!! * 100).roundToInt()} % of the planned carbohydrate",
                        style = FTType.LabelCaps, color = if (v == ScheduleVerdict.HELD) FT.Ok else FT.Warn,
                    )
                }
                adherence.slipped.take(3).forEach { Text("· ${it.label}: ${it.taken} of ${it.planned} taken", style = FTType.Caption, color = FT.TextSecondary) }
            }
        }
    }

    @Composable
    private fun GutPage() {
        FlowQuestion("Any discomfort?", "Nothing ticked: no discomfort.")
        FlowPickGrid(FuelSymptom.entries.map { it.key to it.label }, symptoms.map { it.key }.toSet(), { key ->
            val s = FuelSymptom.of(key) ?: return@FlowPickGrid
            if (s in symptoms) symptoms.remove(s) else symptoms.add(s)
        })
        FTRatingSlider("GUT", gi.toIntOrNull(), 0..10, FT.Metric.StoolLoose, { gi = it?.toString() ?: "" }, lowWord = "fine", highWord = "bad", reversed = true)
    }

    @Composable
    private fun FeelingPage() {
        // Ratings are slid, not picked from a list, each in its metric's hue.
        FTRatingSlider("ENERGY", energy.toIntOrNull(), 0..10, FT.Metric.Energy, { energy = it?.toString() ?: "" }, lowWord = "empty", highWord = "strong")
        FTRatingSlider("TIREDNESS", fatigue.toIntOrNull(), 0..10, FT.Metric.Atl, { fatigue = it?.toString() ?: "" }, lowWord = "fresh", highWord = "spent", reversed = true)
        FTRatingSlider("THIRST", thirst.toIntOrNull(), 0..10, FT.Metric.Hydration, { thirst = it?.toString() ?: "" }, lowWord = "none", highWord = "severe", reversed = true)
    }

    @Composable
    private fun ExtrasPage() {
        Text("Weigh-ins: without clothes, before the start and at the finish before drinking. They make this a sweat test.", style = FTType.Caption, color = FT.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { DLabel("BEFORE KG"); FieldTextField(before, { before = it }, "e.g. 70.2", keyboardType = KeyboardType.Decimal) }
            Column(Modifier.weight(1f)) { DLabel("AFTER KG"); FieldTextField(after, { after = it }, "e.g. 68.9", keyboardType = KeyboardType.Decimal) }
        }
        Column { DLabel("NOTES"); FieldTextField(notes, { notes = it }, "What worked, what didn't", singleLine = false) }
    }
}

@Composable
fun rememberDebriefState(target: DebriefTarget): DebriefState {
    val repo = remember { FuelDebriefRepository(SupabaseClientProvider.client) }
    return remember(target) { DebriefState(target, repo) }
}

/** After saving, when the debrief suggests a new tolerated rate: the reason, then SET or KEEP. */
@Composable
internal fun ToleranceResult(s: ToleranceSuggestion) {
    FlowQuestion("Saved.", s.reason)
}

@Composable
internal fun ToleranceBar(s: ToleranceSuggestion, onSaved: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FTButton("KEEP AS IS", modifier = Modifier.weight(1f)) { onSaved() }
        FTButton("SET ${s.newGPerHour.roundToInt()} G/H", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) {
            FuelSettingsStore.save(context, FuelSettingsStore.get(context).copy(toleratedGPerHour = s.newGPerHour))
            onSaved()
        }
    }
}

/** The debrief for one recorded activity, as a full-screen flow. */
@Composable
fun FuelDebriefSheet(target: DebriefTarget, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = rememberDebriefState(target)
    val suggestion = d.suggestion
    FTFlow(
        title = "FUEL DEBRIEF", pages = if (suggestion != null) listOf(FlowPage("SUGGESTION")) else d.pages, state = rememberFlowState(), onClose = onDismiss,
        canSave = d.canSave, saving = d.saving, saveLabel = "SAVE DEBRIEF", wash = FT.Domain.Fuel, error = d.error,
        onSave = { d.save(context, scope, onSaved) },
        bottomBar = suggestion?.let { s -> { _ -> ToleranceBar(s, onSaved) } },
    ) { page -> if (suggestion != null) ToleranceResult(suggestion) else d.Page(page) }
}

@Composable
private fun DLabel(text: String) {
    Text(text, color = FT.TextMuted, style = FTType.LabelCaps, modifier = Modifier.padding(bottom = 6.dp))
}
