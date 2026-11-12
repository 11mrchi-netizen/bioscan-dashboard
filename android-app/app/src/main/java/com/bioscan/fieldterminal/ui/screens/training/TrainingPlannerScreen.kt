package com.bioscan.fieldterminal.ui.screens.training

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.bioscan.fieldterminal.data.ImportFile
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.domain.training.definition.ConditioningSessionDef
import com.bioscan.fieldterminal.domain.training.definition.SeModuleDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import com.bioscan.fieldterminal.domain.training.generate.GeneratedBlock
import com.bioscan.fieldterminal.domain.training.generate.deriveBlockDomains
import com.bioscan.fieldterminal.domain.training.generate.isCluster
import com.bioscan.fieldterminal.domain.training.generate.slotsToChoose
import com.bioscan.fieldterminal.domain.training.generate.summaryLines
import com.bioscan.fieldterminal.domain.training.generate.templateVariables
import com.bioscan.fieldterminal.domain.training.movementKey
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMultiDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SubTabChip
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal val methodologyLabels = mapOf("tb3" to "TB III", "tb2" to "TB II", "mass" to "MASS", "green" to "GREEN")
internal fun methodologyOf(key: String) = key.substringBefore('.')

// DAV-345, re-laid out (owner, Nov 2026). One fixed walk for both routes, each step a page of its own that opens at
// the top: WHEN (name, start, an event, the length) → PROGRAM (own block: strength + conditioning; or a published
// program) → THE WEEK → THE WEEKS (own block only) → DETAILS (version options, exercises and maxes, sessions: each a
// summary that opens in a sheet) → REVIEW (a summary and a compact calendar; EDIT opens a step and comes back).
// The step bar opens any step already walked; the bottom bar carries NEXT and a one-line summary of the block.
@Composable
fun TrainingPlannerScreen(onBack: () -> Unit, onCreated: () -> Unit, preset: TrainingPlannerState? = null) {
    val scope = rememberCoroutineScope()
    // [preset]: a state already loaded (the screenshot tests); the app always loads its own.
    val state = remember { preset ?: TrainingPlannerState(TrainingProgramRepository(SupabaseClientProvider.client), scope) }
    LaunchedEffect(Unit) { if (preset == null) state.load() }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(
            onBack = { if (state.creating || !state.back()) onBack() },
            title = "PLAN A BLOCK",
            subtitle = "STEP ${state.steps.indexOf(state.step) + 1} OF ${state.steps.size} · ${state.step.label}",
            accent = FT.DomainTraining,
        )
        if (!state.loading) StepBar(state)
        // A fresh scroll for each step: a new step opens at its top.
        val scroll = remember(state.step) { androidx.compose.foundation.ScrollState(0) }
        Column(modifier = Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 22.dp)) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FT.DomainTraining) }
                else -> when (state.step) {
                    PlannerStep.When -> WhenStep(state)
                    PlannerStep.Program -> ProgramStep(state)
                    PlannerStep.Week -> WeekStep(state)
                    PlannerStep.Weeks -> TimelineStep(state)
                    PlannerStep.Details -> DetailsStep(state)
                    PlannerStep.Review -> ReviewStep(state)
                }
            }
            state.error?.let { Text(it, color = FT.Critical, style = FTType.BodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(24.dp))
        }
        if (!state.loading) BottomBar(state, onCreated)
    }
}

/** The steps as a row: done ones open with a tap, the current one is lit, later ones wait. */
@Composable
private fun StepBar(state: TrainingPlannerState) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        state.steps.forEachIndexed { i, s ->
            val current = s == state.step
            val reachable = i <= state.furthest
            val shape = RoundedCornerShape(50)
            Text(
                "${i + 1} ${s.label}",
                style = FTType.Micro,
                color = when { current -> FT.PlatinumInk; reachable -> FT.TextSecondary; else -> FT.TextMuted.copy(alpha = 0.5f) },
                modifier = Modifier
                    .then(if (current) Modifier.background(FT.PlatinumSolid, shape) else Modifier.border(FT.BorderWidth, if (reachable) FT.GlassBorder else FT.GlassBorder.copy(alpha = 0.4f), shape))
                    .ftClickable(enabled = reachable && !current && !state.creating, shape = shape) { state.goTo(s) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** NEXT (or CREATE, or BACK TO REVIEW) with a one-line summary of the block as it stands. */
@Composable
private fun BottomBar(state: TrainingPlannerState, onCreated: () -> Unit) {
    val block = remember(
        state.program, state.startDate, state.projectProgression, state.variables.toMap(), state.moduleChoices.toMap(), state.conditioningChoices.toMap(),
        state.weekdays.toMap(), state.manualMaxes.toMap(), state.strengthKey, state.protocolKey, state.layout, state.timeline,
    ) { runCatching { state.generate() }.getOrNull() }
    Column(Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 22.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val days = (block?.sessions?.groupBy { it.weekIndex }?.values?.map { w -> w.map { it.date }.distinct().size }?.maxOrNull())
        val end = block?.endDate ?: state.startDate.plusWeeks(state.weeks.toLong()).minusDays(1)
        val weeks = block?.calendarWeeks ?: state.weeks
        Text(
            listOfNotNull("$weeks weeks", days?.let { "$it days a week" }, "${state.startDate.format(DateTimeFormatter.ofPattern("d MMM"))} → ${end.format(DateTimeFormatter.ofPattern("d MMM"))}")
                .joinToString(" · "),
            style = FTType.MonoCaption, color = FT.TextSecondary,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val last = state.step == PlannerStep.Review
            if (state.steps.indexOf(state.step) > 0 && !state.editingFromReview) FTButton("BACK", enabled = !state.creating) { state.back() }
            FTButton(
                when {
                    last -> if (state.creating) "CREATING..." else "CREATE BLOCK"
                    state.editingFromReview -> "BACK TO REVIEW"
                    else -> "NEXT"
                },
                enabled = state.canContinue() && !state.creating,
                emphasis = FTButtonEmphasis.Primary,
                modifier = Modifier.weight(1f),
            ) {
                if (last) state.create { onCreated() } else state.next()
            }
        }
    }
}

@Composable
internal fun Label(text: String) = Text(text, color = FT.TextMuted, style = FTType.LabelCaps, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
internal fun Body(text: String, muted: Boolean = false) = Text(text, color = if (muted) FT.TextSecondary else FT.TextPrimary, style = FTType.BodySmall)

@Composable
internal fun Chips(options: List<String>, selected: Set<String>, label: (String) -> String = { it }, onToggle: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { SubTabChip(label(it), it in selected) { onToggle(it) } }
    }
}

// ---------------- 1 · WHEN ----------------
@Composable
private fun WhenStep(state: TrainingPlannerState) {
    Label("NAME")
    FieldTextField(state.name, { state.name = it }, state.targetEvent?.let { "Toward ${it.name}" } ?: "e.g. Base block, winter")
    Label("START DATE")
    DateField("Start", state.startDate) { state.changeStart(it) }
    if (state.program is Program.Fixed) {
        Body("A published program sets its own length.", muted = true)
    } else {
        FrameFields(state)
    }
}

// ---------------- 2 · PROGRAM ----------------
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProgramStep(state: TrainingPlannerState) {
    var library by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(false) }
    if (state.catalog?.definitions.isNullOrEmpty()) {
        ImportCard(state)
        return
    }
    when (val p = state.program) {
        is Program.Fixed -> {
            ProgramCard(p.template.title, "${p.template.weeks} weeks · published program", "Tap to pick another published program", true) { published = true }
            FixedModules(state, p)
            ProgramCard("Build my own block instead", "pick a strength and a conditioning program", null, false) { state.choose(Program.Build) }
        }
        else -> {
            StrengthStep(state)
            ProtocolStep(state)
            Spacer(Modifier.height(8.dp))
            ProgramCard("Start from a published program instead", "fixed week-by-day programs, strength and conditioning already placed", null, false) { published = true }
        }
    }
    Text(
        "PROGRAM LIBRARY ›", style = FTType.LabelCaps, color = FT.TextSecondary,
        modifier = Modifier.padding(top = 16.dp).ftClickable { library = true }.padding(vertical = 6.dp),
    )
    if (published) PublishedPicker(state) { published = false }
    if (library) PlannerSheet("PROGRAM LIBRARY", { library = false }) { ImportCard(state) }
}

/** The published programs, in a sheet: filter by method, tap one to use it. */
@Composable
private fun PublishedPicker(state: TrainingPlannerState, onDone: () -> Unit) {
    val templates = state.catalog?.definitions.orEmpty().filterIsInstance<TemplateDef>()
    var method by remember { mutableStateOf("all") }
    PlannerSheet("PUBLISHED PROGRAMS", onDone) {
        val methods = listOf("all") + templates.map { methodologyOf(it.key) }.distinct().sorted()
        Chips(methods, setOf(method), { methodologyLabels[it] ?: it.uppercase() }) { method = it }
        templates.filter { method == "all" || methodologyOf(it.key) == method }.sortedBy { it.title }.forEach { t ->
            ProgramCard(t.title, "${t.weeks} weeks · ${t.domains.joinToString(", ") { it.replace('_', ' ') }}", t.notes.firstOrNull(), t.key == state.program?.key) {
                state.choose(Program.Fixed(t)); onDone()
            }
        }
    }
}

/** A published program's own strength choices (which template fills each slot). */
@Composable
private fun FixedModules(state: TrainingPlannerState, p: Program.Fixed) {
    val idx = state.index ?: return
    val vars = templateVariables(p.template).filter { it.kind == "strength_module" }
    vars.forEach { v ->
        Label("${v.name} — STRENGTH TEMPLATE")
        v.note?.let { Body(it, muted = true) }
        Chips(v.options, setOfNotNull(state.variables[v.name]), { idx.strength(it)?.title ?: it }) { state.variables[v.name] = it }
    }
}

/** A sheet with a title, scrolling content and DONE. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerSheet(title: String, onDone: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(title, style = FTType.SectionTitle, color = FT.TextPrimary)
            content()
            FTButton("DONE", modifier = Modifier.fillMaxWidth().padding(top = 16.dp), emphasis = FTButtonEmphasis.Primary, onClick = onDone)
        }
    }
}

// ---------------- 3 · THE WEEK ----------------
@Composable
private fun WeekStep(state: TrainingPlannerState) {
    when (state.program) {
        is Program.Fixed -> {
            Label("TRAINING DAYS")
            Body("Each day of the program's week lands on the weekday you choose.", muted = true)
            state.dayPositions.forEach { pos ->
                Text("DAY $pos", color = FT.TextSecondary, style = FTType.MonoCaption, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Chips(DayOfWeek.entries.map { it.name }, setOf(state.weekdayFor(pos).name), { DayOfWeek.valueOf(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase() }) { state.weekdays[pos] = DayOfWeek.valueOf(it) }
            }
        }
        else -> {
            Label("THE WEEK")
            LayoutStep(state)
        }
    }
}

// ---------------- 5 · DETAILS ----------------
@Composable
private fun DetailsStep(state: TrainingPlannerState) {
    var open by remember { mutableStateOf<String?>(null) }
    Body("Everything here starts from the program's defaults. Open what you want to change.", muted = true)
    val modules = state.strengthModules
    modules.filter { it.variants.size > 1 || it.blockLengths.size > 1 || it.options.isNotEmpty() }.forEach { m ->
        val c = state.choiceFor(m.key)
        val variant = m.variants.firstOrNull { it.key == c.variant } ?: m.variants.first()
        DetailRow("VERSION OPTIONS · ${m.title.uppercase()}", "${variant.title.take(40)} · blocks of ${state.blockLength()} weeks") { open = "options:${m.key}" }
    }
    if (modules.isNotEmpty() || (state.program as? Program.Fixed) != null) {
        val missing = state.missingMaxes()
        DetailRow(
            "EXERCISES AND MAXES",
            if (missing.isEmpty()) "defaults from the book · every max known" else "defaults from the book · ${missing.size} max${if (missing.size == 1) "" else "es"} missing",
            warn = missing.isNotEmpty(),
        ) { open = "exercises" }
    }
    val hasConditioning = state.protocol != null || (state.program as? Program.Fixed)?.let { p -> templateVariables(p.template).any { it.kind != "strength_module" } } == true
    if (hasConditioning) {
        val chosen = state.conditioningChoices.values.sumOf { it.size } + state.variables.count { (k, _) -> (state.program as? Program.Fixed)?.let { p -> templateVariables(p.template).any { it.name == k && it.kind != "strength_module" } } == true }
        DetailRow("CONDITIONING SESSIONS", if (chosen == 0) "the program's own sessions" else "$chosen chosen") { open = "sessions" }
    }
    if (modules.isNotEmpty()) {
        DetailRow("PROGRESSION", if (state.projectProgression) "project max increases between blocks" else "use today's maxes throughout") { open = "progression" }
    }
    when (val o = open) {
        null -> Unit
        "exercises" -> PlannerSheet("EXERCISES AND MAXES", { open = null }) { ExercisesStep(state) }
        "sessions" -> PlannerSheet("CONDITIONING SESSIONS", { open = null }) { ConditioningStep(state) }
        "progression" -> PlannerSheet("PROGRESSION", { open = null }) {
            Chips(listOf("project", "hold"), setOf(if (state.projectProgression) "project" else "hold"), { if (it == "project") "PROJECT MAX INCREASES" else "USE TODAY'S MAXES" }) { state.projectProgression = it == "project" }
        }
        else -> modules.firstOrNull { "options:${it.key}" == o }?.let { m -> PlannerSheet(m.title.uppercase(), { open = null }) { ModuleOptions(state, m) } }
    }
}

@Composable
private fun DetailRow(title: String, summary: String, warn: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().border(BorderStroke(FT.BorderWidth, FT.GlassBorder), shape).background(FT.Glass, shape)
            .ftClickable(shape = shape, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = FT.TextPrimary, style = FTType.RowTitle)
            Text(summary, color = if (warn) FT.Warning else FT.TextSecondary, style = FTType.MonoCaption)
        }
        com.bioscan.fieldterminal.ui.components.FTChevron(tint = FT.TextSecondary)
    }
}

@Composable
internal fun ProgramCard(title: String, meta: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth()
            .border(BorderStroke(FT.BorderWidth, if (selected) FT.SelectedBorder else FT.GlassBorder), shape)
            .background(if (selected) FT.SelectedGlass else FT.GlassFill, shape)
            .ftClickable(shape = shape, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = FT.TextPrimary, style = FTType.RowTitle)
        Text(meta, color = FT.TextSecondary, style = FTType.MonoCaption)
        note?.let { Text(it.take(160), color = FT.TextMuted, style = FTType.Caption) }
    }
}

@Composable
internal fun ImportCard(state: TrainingPlannerState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { state.import(withContext(Dispatchers.IO) { readJsonFiles(context, uri) }) }
    }
    val empty = state.catalog?.definitions.isNullOrEmpty()
    FTCard(title = if (empty) "NO PROGRAMS YET" else "PROGRAM LIBRARY", modifier = Modifier.padding(top = 8.dp)) {
        Body(
            if (empty) "Programs are private definitions you provide as JSON files. Pick the folder that holds them."
            else "${state.catalog?.definitions?.size ?: 0} definitions loaded. Import a folder to add or update programs.",
            muted = true,
        )
        state.catalog?.unreadable?.takeIf { it.isNotEmpty() }?.let { Body("${it.size} unreadable definitions, first: ${it.first()}", muted = true) }
        state.importMessage?.let { Body(it) }
        Spacer(Modifier.height(8.dp))
        FTButton("IMPORT FOLDER") { picker.launch(null) }
    }
}

private fun readJsonFiles(context: Context, tree: Uri): List<ImportFile> {
    val treeId = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
    val out = mutableListOf<ImportFile>()
    context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            if (!name.endsWith(".json")) continue
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            val text = runCatching { context.contentResolver.openInputStream(doc)?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: continue
            out += ImportFile(name, text)
        }
    }
    return out
}

@Composable
internal fun ModuleOptions(state: TrainingPlannerState, m: StrengthModuleDef) {
    Label("${m.title.uppercase()} — OPTIONS")
    if (m.variants.size > 1) {
        Chips(m.variants.map { it.key }, setOf(state.choiceFor(m.key).variant ?: m.variants.first().key), { k -> m.variants.first { it.key == k }.title.take(40) }) { k -> state.updateChoice(m.key) { it.copy(variant = k) } }
    }
    if (m.blockLengths.size > 1) {
        Text("BLOCK LENGTH", color = FT.TextMuted, style = FTType.Micro, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        val current = (state.choiceFor(m.key).blockLength ?: m.blockLengths.last()).toString()
        Chips(m.blockLengths.map { it.toString() }, setOf(current), { "$it WEEKS" }) { k -> state.updateChoice(m.key) { it.copy(blockLength = k.toInt()) } }
    }
    if (m.options.isNotEmpty()) {
        m.options.take(4).forEach { o -> Text("${o.title}: ${o.description}".take(200), color = FT.TextMuted, style = FTType.Caption, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ---------------- Step 3: exercises and maxes ----------------
@Composable
internal fun ExercisesStep(state: TrainingPlannerState) {
    val mods = state.strengthModules
    val ses = (state.index?.let { idx -> (state.program as? Program.Fixed)?.template?.let { t -> com.bioscan.fieldterminal.domain.training.generate.templateModuleKeys(t, state.variables).mapNotNull { idx.se(it) } } }).orEmpty()
    if (mods.isEmpty() && ses.isEmpty()) { Body("No strength work in this program.", muted = true); return }
    mods.forEach { m ->
        FTCard(title = m.title.uppercase(), modifier = Modifier.padding(top = 12.dp)) {
            Body("Defaults come from the book; any exercise can replace them and the rules stay the same.", muted = true)
            slotsToChoose(m).forEach { slot ->
                val choice = state.choiceFor(m.key)
                val chosen = choice.exercises[slot.id].orEmpty()
                Label("${slot.id} · ${slot.role.uppercase()}" + if (slot.optional) " · OPTIONAL" else "")
                if (isCluster(m, slot)) {
                    val effective = if (slot.pick != null) chosen else chosen.ifEmpty { listOf(slot.standard) }
                    Body(
                        if (slot.pick != null) "Pick ${slot.pick.min.toInt()}-${slot.pick.max.toInt()} exercises"
                        else "A cluster: add every exercise the circuit uses; each one is done every session.",
                        muted = true,
                    )
                    val options = ((if (slot.pick == null) listOf(slot.standard) else emptyList()) + slot.alternates + effective).distinct()
                    FTMultiDropdown(options.map { it to it }, effective, "Choose exercises") { next -> state.updateChoice(m.key) { c -> c.copy(exercises = c.exercises + (slot.id to next)) } }
                    var draft by remember(m.key, slot.id) { mutableStateOf("") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { FieldTextField(draft, { draft = it }, "Add another exercise") }
                        FTButton("ADD", enabled = draft.isNotBlank()) { state.updateChoice(m.key) { c -> c.copy(exercises = c.exercises + (slot.id to (effective + draft.trim()).distinct())) }; draft = "" }
                    }
                    if (slot.pick == null) effective.forEach { MaxRow(state, it) }
                } else {
                    val options = (listOf(slot.standard) + slot.alternates).distinct()
                    val current = chosen.firstOrNull() ?: slot.standard
                    Chips(options, setOf(current)) { n -> state.updateChoice(m.key) { c -> c.copy(exercises = c.exercises + (slot.id to listOf(n))) } }
                    var custom by remember(m.key, slot.id) { mutableStateOf("") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { FieldTextField(custom, { custom = it }, "Or type another exercise") }
                        FTButton("USE", enabled = custom.isNotBlank()) { state.updateChoice(m.key) { c -> c.copy(exercises = c.exercises + (slot.id to listOf(custom.trim()))) }; custom = "" }
                    }
                    MaxRow(state, chosen.firstOrNull() ?: slot.standard)
                }
            }
        }
    }
    ses.forEach { se -> SeCircuit(state, se) }
}

@Composable
private fun SeCircuit(state: TrainingPlannerState, se: SeModuleDef) {
    FTCard(title = se.title.uppercase(), modifier = Modifier.padding(top = 12.dp)) {
        Body("Pick ${se.clusterSize.min.toInt()}-${se.clusterSize.max.toInt()} exercises for the circuit (press, pull, legs, core).", muted = true)
        val chosen = state.choiceFor(se.key).exercises["SE"].orEmpty()
        val suggestions = se.sampleClusters.flatMap { it.sessions.values.flatten() }.distinct()
        if (suggestions.isNotEmpty()) Chips(suggestions, chosen.toSet()) { n -> state.updateChoice(se.key) { c -> c.copy(exercises = c.exercises + ("SE" to (if (n in chosen) chosen - n else chosen + n))) } }
        var draft by remember(se.key) { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FieldTextField(draft, { draft = it }, "Add an exercise") }
            FTButton("ADD", enabled = draft.isNotBlank()) { state.updateChoice(se.key) { c -> c.copy(exercises = c.exercises + ("SE" to (chosen + draft.trim()).distinct())) }; draft = "" }
        }
    }
}

@Composable
private fun MaxRow(state: TrainingPlannerState, exercise: String) {
    val key = movementKey(exercise)
    val resolved = state.maxes[key]
    val manual = state.manualMaxes[key]
    val kg = manual ?: resolved?.entry?.oneRmKg
    var text by remember(key) { mutableStateOf(manual?.toString() ?: "") }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (kg != null) "MAX ${"%.1f".format(kg)} kg" else "NO MAX YET",
                color = if (kg != null) FT.TextPrimary else FT.Warning, style = FTType.Value,
            )
            Text(if (manual != null) "entered here" else resolved?.basedOn ?: "enter a tested or estimated max", color = FT.TextMuted, style = FTType.Caption)
        }
        Box(Modifier.width(110.dp)) {
            FieldTextField(text, { text = it; it.toDoubleOrNull()?.takeIf { v -> v > 0 }?.let { v -> state.manualMaxes[key] = v } ?: state.manualMaxes.remove(key) }, "kg", keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
        }
    }
}

// ---------------- Step 4: conditioning ----------------
@Composable
internal fun ConditioningStep(state: TrainingPlannerState) {
    val idx = state.index ?: return
    val sessions = state.catalog?.definitions.orEmpty().filterIsInstance<ConditioningSessionDef>()
    when (val p = state.program) {
        is Program.Fixed -> {
            val vars = templateVariables(p.template).filter { it.kind != "strength_module" }
            if (vars.isEmpty()) Body("The conditioning in this program is fixed.", muted = true)
            vars.forEach { v ->
                Label("${v.name} — PICK ONE OR MORE (THEY ROTATE)")
                v.note?.let { Body(it, muted = true) }
                val options = v.options.ifEmpty { sessions.map { it.key } }
                val current = state.variables[v.name]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
                FTMultiDropdown(options.map { it to (idx.session(it)?.title ?: it) }, current, "Choose sessions") { next ->
                    if (next.isEmpty()) state.variables.remove(v.name) else state.variables[v.name] = next.joinToString(",")
                }
            }
        }
        is Program.Build -> {
            val proto = state.protocol
            if (proto == null) { Body("This block has no conditioning protocol.", muted = true); return }
            Body("${proto.title}: sessions rotate week to week in the order you pick them.", muted = true)
            listOf("lic" to "LOW INTENSITY", "hic" to "HIGH INTENSITY", "wc" to "WORK CAPACITY").forEach { (cat, title) ->
                val suggested = when (cat) { "lic" -> proto.suggested.lic; "hic" -> proto.suggested.hic; else -> proto.suggested.wc }
                if (suggested.isEmpty() && proto.defaultLayout.none { it.kind == cat }) return@forEach
                Label(title)
                val options = (suggested + sessions.filter { it.category == cat }.map { it.key }).distinct()
                val current = state.conditioningChoices[cat].orEmpty()
                FTMultiDropdown(options.map { it to (idx.session(it)?.title ?: it) }, current, "Choose sessions") { next -> state.conditioningChoices[cat] = next }
            }
        }
        null -> Body("Pick a program first.", muted = true)
    }
}

// ---------------- 6 · REVIEW ----------------
@Composable
private fun ReviewStep(state: TrainingPlannerState) {
    val block: GeneratedBlock? = remember(state.step, state.program, state.startDate, state.projectProgression, state.variables.toMap(), state.moduleChoices.toMap(), state.conditioningChoices.toMap(), state.weekdays.toMap(), state.manualMaxes.toMap(), state.strengthKey, state.protocolKey, state.layout, state.timeline) { state.generate() }
    if (block == null) { Body("Nothing to review yet.", muted = true); return }
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
    FTCard(title = state.name.uppercase().ifBlank { "BLOCK" }, modifier = Modifier.padding(top = 8.dp)) {
        ReviewLine("WHEN", "${state.startDate.format(fmt)} → ${block.endDate.format(fmt)} · ${block.calendarWeeks} weeks (${block.countedWeeks} counted)" +
            (state.targetEvent?.let { " · toward ${it.name}" } ?: "")) { state.editFromReview(PlannerStep.When) }
        ReviewLine("PROGRAM", when (val p = state.program) {
            is Program.Fixed -> p.template.title
            else -> listOfNotNull(state.strengthModule?.title, state.protocol?.title).joinToString(" + ").ifBlank { "—" }
        }) { state.editFromReview(PlannerStep.Program) }
        ReviewLine("THE WEEK", "${block.sessions.size} sessions · " + deriveBlockDomains(block.sessions).joinToString(" · ") { it.domain.replace('_', ' ') }) { state.editFromReview(PlannerStep.Week) }
        if (state.program !is Program.Fixed) {
            val t = state.timeline
            ReviewLine("THE WEEKS", "${t.count { it == com.bioscan.fieldterminal.domain.training.generate.WeekKind.Deload }} deload · ${t.count { it == com.bioscan.fieldterminal.domain.training.generate.WeekKind.Test }} test") { state.editFromReview(PlannerStep.Weeks) }
        }
        val missing = state.missingMaxes()
        ReviewLine("DETAILS", if (missing.isEmpty()) "defaults, every max known" else "${missing.size} max${if (missing.size == 1) "" else "es"} missing", warn = missing.isNotEmpty()) { state.editFromReview(PlannerStep.Details) }
    }
    FTCard(title = "CALENDAR", modifier = Modifier.padding(top = 12.dp)) {
        CompactCalendar(state, block)
    }
    if (block.warnings.isNotEmpty()) {
        FTCard(title = "NEEDS ATTENTION", modifier = Modifier.padding(top = 12.dp)) {
            block.warnings.take(12).forEach { Text("! $it", color = FT.Warning, style = FTType.Caption) }
            if (block.warnings.size > 12) Body("and ${block.warnings.size - 12} more", muted = true)
        }
    }
    if (block.progressions.isNotEmpty()) {
        FTCard(title = "PROJECTED MAX INCREASES", modifier = Modifier.padding(top = 12.dp)) {
            block.progressions.groupBy { it.weekIndex }.forEach { (week, list) ->
                Text("Week $week: " + list.joinToString(", ") { "${it.exercise} ${"%.1f".format(it.fromKg)} → ${"%.1f".format(it.toKg)} kg" }, color = FT.TextSecondary, style = FTType.Caption)
            }
            Body("Projections only: confirm each increase when its block ends.", muted = true)
        }
    }
}

@Composable
private fun ReviewLine(label: String, value: String, warn: Boolean = false, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = FT.TextMuted, style = FTType.Micro)
            Text(value, color = if (warn) FT.Warning else FT.TextPrimary, style = FTType.BodySmall)
        }
        Text("EDIT", color = FT.TextSecondary, style = FTType.LabelCaps, modifier = Modifier.ftClickable(onClick = onEdit).padding(8.dp))
    }
}

/**
 * The block as a grid: a row per week, a cell per day from the start weekday. A cell shows S (strength) and its
 * conditioning kind; a deload week is dimmed and marked D, a test week T. Tap a week for its sessions.
 */
@Composable
private fun CompactCalendar(state: TrainingPlannerState, block: GeneratedBlock) {
    var openWeek by remember { mutableStateOf<Int?>(null) }
    val start = state.startDate
    val days = (0 until 7).map { start.plusDays(it.toLong()).dayOfWeek }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Spacer(Modifier.width(34.dp))
        days.forEach { d -> Text(d.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
    val byWeek = block.sessions.groupBy { it.weekIndex }
    val kinds = state.timeline
    (1..block.calendarWeeks).forEach { w ->
        val list = byWeek[w].orEmpty()
        val kind = kinds.getOrNull(w - 1)
        val deload = list.firstOrNull()?.countsTowardBlock == false || kind == com.bioscan.fieldterminal.domain.training.generate.WeekKind.Deload
        val test = kind == com.bioscan.fieldterminal.domain.training.generate.WeekKind.Test
        val weekStart = start.plusWeeks((w - 1).toLong())
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).ftClickable(enabled = list.isNotEmpty()) { openWeek = w },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$w" + if (deload) " D" else if (test) " T" else "", style = FTType.Micro, color = if (deload) FT.Warning else if (test) FT.Info else FT.TextSecondary, modifier = Modifier.width(34.dp))
            (0 until 7).forEach { di ->
                val date = weekStart.plusDays(di.toLong())
                val here = list.filter { it.date == date }
                val strength = here.any { it.conditioning == null }
                val cond = here.mapNotNull { it.conditioning?.category }.firstOrNull()
                val shape = RoundedCornerShape(4.dp)
                Box(
                    Modifier.weight(1f).padding(1.5.dp).height(26.dp)
                        .background(if (here.isEmpty()) Color.Transparent else FT.GlassFill, shape)
                        .border(FT.BorderWidth, FT.GlassBorder, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    val alpha = if (deload) 0.5f else 1f
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (strength) Text("S", style = FTType.Micro, color = FT.DomainTraining.copy(alpha = alpha))
                        if (cond != null) Text(cond.take(1).uppercase(), style = FTType.Micro, color = FT.DomainHeart.copy(alpha = alpha))
                        else if (here.any { it.conditioning != null }) Text("C", style = FTType.Micro, color = FT.DomainHeart.copy(alpha = alpha))
                    }
                }
            }
        }
    }
    Text("S strength · L/H/W low, high intensity, work capacity · D deload · T test · tap a week to see it", style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
    openWeek?.let { w ->
        val list = byWeek[w].orEmpty()
        PlannerSheet("WEEK $w" + if (list.firstOrNull()?.countsTowardBlock == false) " · DELOAD (NOT COUNTED)" else "", { openWeek = null }) {
            list.forEach { s ->
                Text(
                    s.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).uppercase() + (s.startTime?.let { " · $it" } ?: "") + if (s.slotInDay > 1) " · 2ND SESSION" else "",
                    color = FT.DomainTraining, style = FTType.MonoCaption, modifier = Modifier.padding(top = 10.dp),
                )
                Text(s.title, color = FT.TextPrimary, style = FTType.RowTitle)
                s.summaryLines().forEach { Text(it, color = FT.TextSecondary, style = FTType.MonoCaption) }
                s.notes.firstOrNull()?.let { Text(it.take(140), color = FT.TextMuted, style = FTType.Caption) }
            }
        }
    }
}
