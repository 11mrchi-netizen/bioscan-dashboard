package com.bioscan.fieldterminal.ui.screens.training

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.ImportFile
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.domain.training.definition.ConditioningSessionDef
import com.bioscan.fieldterminal.domain.training.definition.SeModuleDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import com.bioscan.fieldterminal.domain.training.generate.GeneratedBlock
import com.bioscan.fieldterminal.domain.training.generate.deriveBlockDomains
import com.bioscan.fieldterminal.domain.training.generate.slotsToChoose
import com.bioscan.fieldterminal.domain.training.generate.summaryLines
import com.bioscan.fieldterminal.domain.training.generate.templateVariables
import com.bioscan.fieldterminal.domain.training.movementKey
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SubTabChip
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal val methodologyLabels = mapOf("tb3" to "TB III", "tb2" to "TB II", "mass" to "MASS", "green" to "GREEN")
internal fun methodologyOf(key: String) = key.substringBefore('.')

// DAV-345. Block-first setup: pick a program, the modules and dates, the exercises of each
// cluster, the conditioning sessions, then review the generated block before it is created.
@Composable
fun TrainingPlannerScreen(onBack: () -> Unit, onCreated: () -> Unit, initialProgram: String? = null) {
    val scope = rememberCoroutineScope()
    val state = remember { TrainingPlannerState(TrainingProgramRepository(SupabaseClientProvider.client), scope) }
    LaunchedEffect(Unit) { state.load() }
    // Started from Settings: "build" opens the build path, a template key opens that published program.
    LaunchedEffect(state.loading, initialProgram) {
        if (!state.loading && initialProgram != null && state.program == null) {
            if (initialProgram == "build") { state.choose(Program.Build); state.step = PlannerStep.Strength }
            else state.catalog?.definitions.orEmpty().filterIsInstance<TemplateDef>().firstOrNull { it.key == initialProgram }?.let { state.choose(Program.Fixed(it)); state.step = PlannerStep.Modules }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = { if (state.creating || !state.back()) onBack() })
        Text(
            "STEP ${state.steps.indexOf(state.step) + 1} OF ${state.steps.size} · ${state.step.label}",
            color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 11.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
        )
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FT.DomainTraining) }
                else -> when (state.step) {
                    PlannerStep.Program -> ProgramStep(state)
                    PlannerStep.Modules -> ModulesStep(state)
                    PlannerStep.Strength -> StrengthStep(state)
                    PlannerStep.Protocol -> ProtocolStep(state)
                    PlannerStep.Layout -> LayoutStep(state)
                    PlannerStep.Timeline -> TimelineStep(state)
                    PlannerStep.Exercises -> ExercisesStep(state)
                    PlannerStep.Conditioning -> ConditioningStep(state)
                    PlannerStep.Review -> ReviewStep(state)
                }
            }
            state.error?.let { Text(it, color = FT.Critical, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(24.dp))
        }
        if (!state.loading) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val last = state.step == PlannerStep.Review
                if (state.steps.indexOf(state.step) > 0) AmberButton("BACK", enabled = !state.creating) { state.back() }
                AmberButton(
                    if (last) (if (state.creating) "CREATING..." else "CREATE BLOCK") else "NEXT",
                    enabled = state.canContinue() && !state.creating,
                ) {
                    if (last) state.create { onCreated() } else state.next()
                }
            }
        }
    }
}

@Composable
internal fun Label(text: String) = Text(text, color = FT.TextMuted, fontFamily = RobotoMono, fontSize = 10.5.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
internal fun Body(text: String, muted: Boolean = false) = Text(text, color = if (muted) FT.TextSecondary else FT.TextPrimary, fontFamily = Inter, fontSize = 13.5.sp)

@Composable
internal fun Chips(options: List<String>, selected: Set<String>, label: (String) -> String = { it }, onToggle: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { SubTabChip(label(it), it in selected) { onToggle(it) } }
    }
}

// ---------------- Step 1: how to start ----------------
@Composable
private fun ProgramStep(state: TrainingPlannerState) {
    val templates = state.catalog?.definitions.orEmpty().filterIsInstance<TemplateDef>()
    var showPublished by remember { mutableStateOf(state.program is Program.Fixed) }
    var method by remember { mutableStateOf("all") }
    if (state.catalog?.definitions.isNullOrEmpty()) {
        Body("No programs yet. Import your program definitions in Settings, under Training programs.", muted = true)
        return
    }
    Label("HOW DO YOU WANT TO START?")
    ProgramCard(
        "Build a block", "pick a strength template and a conditioning template, then decide how they share the week",
        "You choose the days, the length and the deload weeks.", state.program is Program.Build,
    ) { state.choose(Program.Build); showPublished = false }
    ProgramCard(
        "Start from a published program", "${templates.size} fixed week-by-day programs (Capacity, Velocity, Outcome, Activation, Hybrid...)",
        "These come as one piece: strength and conditioning are already placed.", showPublished || state.program is Program.Fixed,
    ) { showPublished = true }
    if (showPublished || state.program is Program.Fixed) {
        val methods = listOf("all") + templates.map { methodologyOf(it.key) }.distinct().sorted()
        Chips(methods, setOf(method), { methodologyLabels[it] ?: it.uppercase() }) { method = it }
        val selectedKey = state.program?.key
        templates.filter { method == "all" || methodologyOf(it.key) == method }.sortedBy { it.title }.forEach { t ->
            ProgramCard(t.title, "${t.weeks} weeks · ${t.domains.joinToString(", ") { it.replace('_', ' ') }}", t.notes.firstOrNull(), t.key == selectedKey) { state.choose(Program.Fixed(t)) }
        }
    }
}

@Composable
internal fun ProgramCard(title: String, meta: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth()
            .border(BorderStroke(FT.BorderWidth, if (selected) FT.DomainTraining else FT.GlassBorder), shape)
            .background(if (selected) FT.DomainTraining.copy(alpha = 0.10f) else FT.GlassFill, shape)
            .clickable { onClick() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = FT.TextPrimary, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(meta, color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 11.sp)
        note?.let { Text(it.take(160), color = FT.TextMuted, fontFamily = Inter, fontSize = 12.sp) }
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
        AmberButton("IMPORT FOLDER") { picker.launch(null) }
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

// ---------------- Step 2: modules and dates ----------------
@Composable
private fun ModulesStep(state: TrainingPlannerState) {
    val idx = state.index ?: return
    val p = state.program ?: run { Body("Pick a program first.", muted = true); return }
    Label("NAME")
    FieldTextField(state.name, { state.name = it }, "Block name")
    when (p) {
        is Program.Fixed -> {
            val vars = templateVariables(p.template).filter { it.kind == "strength_module" }
            if (vars.isEmpty()) Body("This program fixes its strength work.", muted = true)
            vars.forEach { v ->
                Label("${v.name} — STRENGTH TEMPLATE")
                v.note?.let { Body(it, muted = true) }
                Chips(v.options, setOfNotNull(state.variables[v.name]), { idx.strength(it)?.title ?: it }) { state.variables[v.name] = it }
            }
            state.strengthModules.forEach { m -> ModuleOptions(state, m) }
        }
        is Program.Build -> Unit
    }
    Label("START DATE")
    DateField("Start", state.startDate) { state.startDate = it }
    Label("TRAINING DAYS")
    Body("Each day of the programme's week lands on the weekday you choose.", muted = true)
    state.dayPositions.forEach { pos ->
        Text("DAY $pos", color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Chips(DayOfWeek.entries.map { it.name }, setOf(state.weekdayFor(pos).name), { DayOfWeek.valueOf(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase() }) { state.weekdays[pos] = DayOfWeek.valueOf(it) }
    }
    Label("PROGRESSION")
    Chips(listOf("project", "hold"), setOf(if (state.projectProgression) "project" else "hold"), { if (it == "project") "PROJECT MAX INCREASES" else "USE TODAY'S MAXES" }) { state.projectProgression = it == "project" }
}

@Composable
internal fun ModuleOptions(state: TrainingPlannerState, m: StrengthModuleDef) {
    Label("${m.title.uppercase()} — OPTIONS")
    if (m.variants.size > 1) {
        Chips(m.variants.map { it.key }, setOf(state.choiceFor(m.key).variant ?: m.variants.first().key), { k -> m.variants.first { it.key == k }.title.take(40) }) { k -> state.updateChoice(m.key) { it.copy(variant = k) } }
    }
    if (m.blockLengths.size > 1) {
        Text("BLOCK LENGTH", color = FT.TextMuted, fontFamily = RobotoMono, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        val current = (state.choiceFor(m.key).blockLength ?: m.blockLengths.last()).toString()
        Chips(m.blockLengths.map { it.toString() }, setOf(current), { "$it WEEKS" }) { k -> state.updateChoice(m.key) { it.copy(blockLength = k.toInt()) } }
    }
    if (m.options.isNotEmpty()) {
        m.options.take(4).forEach { o -> Text("${o.title}: ${o.description}".take(200), color = FT.TextMuted, fontFamily = Inter, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ---------------- Step 3: exercises and maxes ----------------
@Composable
private fun ExercisesStep(state: TrainingPlannerState) {
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
                if (slot.pick != null) {
                    Body("Pick ${slot.pick.min.toInt()}-${slot.pick.max.toInt()} exercises", muted = true)
                    val options = (slot.alternates + chosen).distinct()
                    if (options.isNotEmpty()) Chips(options, chosen.toSet()) { n -> state.updateChoice(m.key) { c -> c.copy(exercises = c.exercises + (slot.id to (if (n in chosen) chosen - n else chosen + n))) } }
                    var draft by remember(m.key, slot.id) { mutableStateOf("") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { FieldTextField(draft, { draft = it }, "Add an exercise") }
                        AmberButton("ADD", enabled = draft.isNotBlank()) { val n = draft.trim(); state.updateChoice(m.key) { c -> c.copy(exercises = c.exercises + (slot.id to (chosen + n).distinct())) }; draft = "" }
                    }
                } else {
                    val options = (listOf(slot.standard) + slot.alternates).distinct()
                    val current = chosen.firstOrNull() ?: slot.standard
                    Chips(options, setOf(current)) { n -> state.updateChoice(m.key) { c -> c.copy(exercises = c.exercises + (slot.id to listOf(n))) } }
                    var custom by remember(m.key, slot.id) { mutableStateOf("") }
                    FieldTextField(custom, { custom = it; if (it.isNotBlank()) state.updateChoice(m.key) { c -> c.copy(exercises = c.exercises + (slot.id to listOf(it.trim()))) } }, "Or type another exercise")
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
            AmberButton("ADD", enabled = draft.isNotBlank()) { state.updateChoice(se.key) { c -> c.copy(exercises = c.exercises + ("SE" to (chosen + draft.trim()).distinct())) }; draft = "" }
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
                color = if (kg != null) FT.TextPrimary else FT.Warning, fontFamily = RobotoMono, fontSize = 12.sp,
            )
            Text(if (manual != null) "entered here" else resolved?.basedOn ?: "enter a tested or estimated max", color = FT.TextMuted, fontFamily = Inter, fontSize = 11.sp)
        }
        Box(Modifier.width(110.dp)) {
            FieldTextField(text, { text = it; it.toDoubleOrNull()?.takeIf { v -> v > 0 }?.let { v -> state.manualMaxes[key] = v } ?: state.manualMaxes.remove(key) }, "kg", keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
        }
    }
}

// ---------------- Step 4: conditioning ----------------
@Composable
private fun ConditioningStep(state: TrainingPlannerState) {
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
                val current = state.variables[v.name]?.split(',')?.filter { it.isNotBlank() }.orEmpty().toSet()
                Chips(options, current, { idx.session(it)?.title ?: it }) { k ->
                    val next = if (k in current) current - k else current + k
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
                Chips(options, current.toSet(), { idx.session(it)?.title ?: it }) { k -> state.conditioningChoices[cat] = if (k in current) current - k else current + k }
            }
        }
        null -> Body("Pick a program first.", muted = true)
    }
}

// ---------------- Step 5: review ----------------
@Composable
private fun ReviewStep(state: TrainingPlannerState) {
    val block: GeneratedBlock? = remember(state.step, state.program, state.startDate, state.projectProgression, state.variables.toMap(), state.moduleChoices.toMap(), state.conditioningChoices.toMap(), state.weekdays.toMap(), state.manualMaxes.toMap(), state.strengthKey, state.protocolKey, state.layout, state.timeline) { state.generate() }
    if (block == null) { Body("Nothing to review yet.", muted = true); return }
    FTCard(title = state.name.uppercase().ifBlank { "BLOCK" }, modifier = Modifier.padding(top = 8.dp)) {
        Body("${block.calendarWeeks} calendar weeks (${block.countedWeeks} counted) · ${block.sessions.size} sessions", muted = true)
        Body("${state.startDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))} to ${block.endDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}", muted = true)
        val domains = deriveBlockDomains(block.sessions)
        if (domains.isNotEmpty()) Body(domains.joinToString(" · ") { "${it.domain.replace('_', ' ')} (${it.role})" }, muted = true)
    }
    if (state.program is Program.Build) {
        FTCard(title = "THE WEEK", modifier = Modifier.padding(top = 12.dp)) {
            val start = state.startDate.dayOfWeek
            state.layout.sortedBy { (it.weekday.value - start.value + 7) % 7 }.filter { !it.isRest }.forEach { d ->
                val parts = listOfNotNull(
                    d.strengthSession?.let { state.strengthModule?.sessions?.getOrNull(it)?.label?.let { l -> "strength: $l" } },
                    d.conditioning?.kind?.uppercase(),
                )
                Text(d.weekday.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase() + "  " + parts.joinToString(" + "), color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 12.sp)
            }
            Text("Weeks: " + state.timeline.joinToString(" ") { it.label.take(1) }, color = FT.TextMuted, fontFamily = RobotoMono, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (block.warnings.isNotEmpty()) {
        FTCard(title = "NEEDS ATTENTION", modifier = Modifier.padding(top = 12.dp)) {
            block.warnings.take(12).forEach { Text("! $it", color = FT.Warning, fontFamily = Inter, fontSize = 12.5.sp) }
            if (block.warnings.size > 12) Body("and ${block.warnings.size - 12} more", muted = true)
        }
    }
    if (block.progressions.isNotEmpty()) {
        FTCard(title = "PROJECTED MAX INCREASES", modifier = Modifier.padding(top = 12.dp)) {
            block.progressions.groupBy { it.weekIndex }.forEach { (week, list) ->
                Text("Week $week: " + list.joinToString(", ") { "${it.exercise} ${"%.1f".format(it.fromKg)} → ${"%.1f".format(it.toKg)} kg" }, color = FT.TextSecondary, fontFamily = Inter, fontSize = 12.sp)
            }
            Body("Projections only: confirm each increase when its block ends.", muted = true)
        }
    }
    block.sessions.groupBy { it.weekIndex }.forEach { (week, list) ->
        val first = list.first()
        FTCard(title = "WEEK $week" + if (!first.countsTowardBlock) " · DELOAD (NOT COUNTED)" else "", modifier = Modifier.padding(top = 12.dp)) {
            list.forEach { s ->
                Text(
                    s.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).uppercase() + (s.startTime?.let { " · $it" } ?: "") + if (s.slotInDay > 1) " · 2ND SESSION" else "",
                    color = FT.DomainTraining, fontFamily = RobotoMono, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Text(s.title, color = FT.TextPrimary, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                s.summaryLines().forEach { Text(it, color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 11.5.sp) }
                s.notes.firstOrNull()?.let { Text(it.take(140), color = FT.TextMuted, fontFamily = Inter, fontSize = 11.sp) }
            }
        }
    }
}
