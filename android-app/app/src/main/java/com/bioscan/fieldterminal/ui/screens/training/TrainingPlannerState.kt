package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.BlockPlan
import com.bioscan.fieldterminal.data.DefinitionCatalog
import com.bioscan.fieldterminal.data.ImportFile
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.model.TrainingSettingsRow
import com.bioscan.fieldterminal.domain.training.PercentBase
import com.bioscan.fieldterminal.domain.training.ResolvedMax
import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import com.bioscan.fieldterminal.domain.training.definition.Definition
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import com.bioscan.fieldterminal.domain.training.generate.BlockBlueprint
import com.bioscan.fieldterminal.domain.training.generate.DayPlan
import com.bioscan.fieldterminal.domain.training.generate.DefinitionIndex
import com.bioscan.fieldterminal.domain.training.generate.GenChoices
import com.bioscan.fieldterminal.domain.training.generate.GenEquipment
import com.bioscan.fieldterminal.domain.training.generate.GeneratedBlock
import com.bioscan.fieldterminal.domain.training.generate.MaxEntry
import com.bioscan.fieldterminal.domain.training.generate.ModuleChoice
import com.bioscan.fieldterminal.domain.training.generate.SlotTime
import com.bioscan.fieldterminal.domain.training.generate.WeekKind
import com.bioscan.fieldterminal.domain.training.generate.cycleWeekKind
import com.bioscan.fieldterminal.domain.training.generate.defaultTimeline
import com.bioscan.fieldterminal.domain.training.generate.generateMergedBlock
import com.bioscan.fieldterminal.domain.training.generate.generateTemplateBlock
import com.bioscan.fieldterminal.domain.training.generate.layoutWarnings
import com.bioscan.fieldterminal.domain.training.generate.resizeTimeline
import com.bioscan.fieldterminal.domain.training.generate.suggestLayout
import com.bioscan.fieldterminal.domain.training.generate.weeksForBlocks
import com.bioscan.fieldterminal.domain.training.generate.templateDayPositions
import com.bioscan.fieldterminal.domain.training.generate.templateModuleKeys
import com.bioscan.fieldterminal.domain.training.movementKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

enum class PlannerStep(val label: String) {
    Program("START"),
    Modules("MODULES"),
    Strength("STRENGTH TEMPLATE"),
    Protocol("CONDITIONING TEMPLATE"),
    Layout("THE WEEK"),
    Timeline("LENGTH AND DELOADS"),
    Exercises("EXERCISES"),
    Conditioning("SESSIONS"),
    Review("REVIEW"),
}

sealed interface Program {
    val key: String
    val title: String

    // A published week-by-day grid (Capacity, Velocity, Outcome, Activation, Hybrid...).
    data class Fixed(val template: TemplateDef) : Program { override val key get() = template.key; override val title get() = template.title }

    // The lifter's own block: a strength template and a conditioning template, merged by hand.
    data object Build : Program { override val key = "build"; override val title = "Strength + conditioning block" }
}

// DAV-345. Everything the setup flow holds between steps. Choices live in Compose state so the
// review preview regenerates whenever one changes; the generator itself stays pure.
class TrainingPlannerState(private val repo: TrainingProgramRepository, private val scope: CoroutineScope) {
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var catalog by mutableStateOf<DefinitionCatalog?>(null)
    var settings by mutableStateOf(TrainingSettingsRow())
    var maxes by mutableStateOf<Map<String, ResolvedMax>>(emptyMap())
    var importMessage by mutableStateOf<String?>(null)
    var settingsMessage by mutableStateOf<String?>(null)
    var creating by mutableStateOf(false)

    var step by mutableStateOf(PlannerStep.Program)
    var program by mutableStateOf<Program?>(null)
    var startDate by mutableStateOf(LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)))
    val variables = mutableStateMapOf<String, String>()
    val weekdays = mutableStateMapOf<Int, DayOfWeek>()
    var strengthKey by mutableStateOf<String?>(null)
    var protocolKey by mutableStateOf<String?>(null)
    var projectProgression by mutableStateOf(true)
    val moduleChoices = mutableStateMapOf<String, ModuleChoice>()
    val conditioningChoices = mutableStateMapOf<String, List<String>>()
    val manualMaxes = mutableStateMapOf<String, Double>()
    var name by mutableStateOf("")

    // The build path: how the two templates share the week, and what each calendar week is.
    var layout by mutableStateOf<List<DayPlan>>(emptyList())
    var timeline by mutableStateOf<List<WeekKind>>(emptyList())
    var weeks by mutableStateOf(8)
    var openSection by mutableStateOf<String?>(null)
    var startWithTest by mutableStateOf(false)

    val index: DefinitionIndex? get() = catalog?.let { DefinitionIndex(it.definitions) }

    // The steps the current path walks through.
    val steps: List<PlannerStep>
        get() = when (program) {
            is Program.Build -> listOf(PlannerStep.Program, PlannerStep.Strength, PlannerStep.Protocol, PlannerStep.Review)
            is Program.Fixed -> listOf(PlannerStep.Program, PlannerStep.Modules, PlannerStep.Exercises, PlannerStep.Conditioning, PlannerStep.Review)
            null -> listOf(PlannerStep.Program)
        }

    fun next() { val s = steps; step = s.getOrNull(s.indexOf(step) + 1) ?: step }
    fun back(): Boolean { val s = steps; val i = s.indexOf(step); return if (i > 0) { step = s[i - 1]; true } else false }

    fun load() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                catalog = repo.loadCatalog()
                settings = repo.loadSettings()
                maxes = repo.loadMaxes()
            }.onFailure { error = it.message ?: "Could not load" }
            loading = false
        }
    }

    fun saveSettings(new: TrainingSettingsRow) {
        scope.launch {
            runCatching { repo.saveSettings(new) }
                .onSuccess { settings = new; settingsMessage = "Saved" }
                .onFailure { settingsMessage = "Could not save: ${it.message}" }
        }
    }

    fun import(files: List<ImportFile>) {
        scope.launch {
            importMessage = "Importing ${files.size} files..."
            runCatching { repo.importDefinitions(files) }
                .onSuccess { r ->
                    importMessage = "Imported ${r.imported}; rejected ${r.rejected.size}" + (r.rejected.firstOrNull()?.let { " (first: $it)" } ?: "") +
                        (if (r.setErrors.isNotEmpty()) "; ${r.setErrors.size} cross-reference problems" else "")
                    load()
                }
                .onFailure { importMessage = "Import failed: ${it.message}" }
        }
    }

    fun choose(p: Program) {
        program = p
        variables.clear(); moduleChoices.clear(); conditioningChoices.clear(); weekdays.clear(); manualMaxes.clear()
        strengthKey = null; protocolKey = null; layout = emptyList(); timeline = emptyList(); startWithTest = false
        name = p.title
    }

    // ---- build path ----
    val strengthModule: StrengthModuleDef? get() = strengthKey?.let { index?.strength(it) }
    val protocol: ConditioningProtocolDef? get() = protocolKey?.let { index?.protocol(it) }

    fun pickStrength(key: String) {
        strengthKey = key
        moduleChoices.remove(key)
        weeks = weeksForBlocks(2, blockLength())
        resetLayout(); resetTimeline()
    }

    fun pickProtocol(key: String?) {
        protocolKey = key
        conditioningChoices.clear()
        resetLayout()
    }

    fun resetLayout() { layout = suggestLayout(strengthModule, protocol, startDate.dayOfWeek) }

    fun blockLength(): Int {
        val m = strengthModule ?: return 3
        val choice = moduleChoices[m.key]
        val variant = m.variants.firstOrNull { it.key == choice?.variant } ?: m.variants.first()
        return (choice?.blockLength ?: m.blockLengths.lastOrNull() ?: variant.weeks.size).coerceIn(1, variant.weeks.size)
    }

    fun resetTimeline() { timeline = if (strengthModule == null) List(weeks) { WeekKind.Normal } else defaultTimeline(blockLength(), weeks, startWithTest) }

    fun changeWeeks(n: Int) {
        weeks = n.coerceIn(1, 52)
        timeline = if (strengthModule == null) List(weeks) { WeekKind.Normal } else resizeTimeline(timeline, blockLength(), weeks)
    }

    fun toggleWeek(i: Int) { timeline = timeline.mapIndexed { j, k -> if (j == i) cycleWeekKind(k) else k } }

    fun setDay(weekday: DayOfWeek, f: (DayPlan) -> DayPlan) { layout = layout.map { if (it.weekday == weekday) f(it) else it } }

    val layoutWarnings: List<String> get() = layoutWarnings(layout, strengthModule, protocol)

    fun blueprint() = BlockBlueprint(strengthKey, protocolKey, layout, timeline)

    // ---- derived selections ----
    val strengthModules: List<StrengthModuleDef>
        get() {
            val idx = index ?: return emptyList()
            return when (val p = program) {
                is Program.Fixed -> templateModuleKeys(p.template, variables).mapNotNull { idx.strength(it) }
                is Program.Build -> listOfNotNull(strengthModule)
                null -> emptyList()
            }
        }

    val dayPositions: List<Int>
        get() = when (val p = program) {
            is Program.Fixed -> templateDayPositions(p.template)
            else -> emptyList()
        }

    fun weekdayFor(position: Int): DayOfWeek = weekdays[position] ?: startDate.dayOfWeek.plus((position - 1).toLong())

    fun choiceFor(key: String): ModuleChoice = moduleChoices[key] ?: ModuleChoice()
    fun updateChoice(key: String, f: (ModuleChoice) -> ModuleChoice) { moduleChoices[key] = f(choiceFor(key)) }

    fun maxEntry(name: String): MaxEntry? {
        val k = movementKey(name)
        val base = maxes[k]?.entry
        val manual = manualMaxes[k]
        return when {
            manual != null -> (base ?: MaxEntry()).copy(oneRmKg = manual)
            else -> base
        }
    }

    fun equipment() = GenEquipment(
        platesKg = settings.platesKg,
        weightedBase = if (settings.weightedPercentBase == "total") PercentBase.TotalLoad else PercentBase.AddedLoad,
    )

    private fun time(s: String) = runCatching { LocalTime.parse(s) }.getOrDefault(LocalTime.of(7, 0))

    fun genChoices() = GenChoices(
        startDate = startDate,
        weekdays = weekdays.toMap(),
        slotTimes = mapOf(
            1 to SlotTime(time(settings.defaultStartTime), settings.defaultDurationMin),
            2 to SlotTime(time(settings.secondStartTime), settings.secondDurationMin),
        ),
        variables = variables.toMap(),
        modules = moduleChoices.toMap(),
        conditioning = conditioningChoices.toMap(),
        projectProgression = projectProgression,
    )

    fun canContinue(): Boolean = when (step) {
        PlannerStep.Program -> program != null
        PlannerStep.Strength -> strengthKey != null
        else -> program != null
    }

    fun generate(): GeneratedBlock? {
        val idx = index ?: return null
        val c = genChoices()
        return when (val p = program ?: return null) {
            is Program.Fixed -> generateTemplateBlock(p.template, idx, c, equipment(), ::maxEntry)
            is Program.Build -> if (strengthKey == null && protocolKey == null) null else generateMergedBlock(blueprint(), idx, c, equipment(), ::maxEntry)
        }
    }

    // Definitions to freeze into the block: the program itself plus every module, protocol and
    // session its generated sessions refer to.
    private fun definitionsUsed(block: GeneratedBlock): List<Definition> {
        val idx = index ?: return emptyList()
        val keys = linkedSetOf<String>()
        strengthModules.forEach { keys += it.key }
        protocolKey?.let { keys += it }
        block.sessions.forEach { s ->
            s.moduleRef.substringAfter(':', "").takeIf { it.isNotBlank() && !it.startsWith("$") }?.let { keys += it }
            s.conditioning?.sessionKey?.let { keys += it }
        }
        return keys.mapNotNull { idx.any(it) }
    }

    fun create(onDone: (Long) -> Unit) {
        val block = generate() ?: return
        val p = program ?: return
        scope.launch {
            creating = true
            error = null
            runCatching {
                manualMaxes.forEach { (key, kg) ->
                    val label = maxes[key]?.name ?: key
                    repo.saveMax(label, "1rm", kg, "kg", "manual")
                }
                val plan = BlockPlan(
                    name = name.ifBlank { p.title },
                    template = (p as? Program.Fixed)?.template,
                    components = components(p),
                    definitionsUsed = definitionsUsed(block),
                    choices = choicesJson(),
                    startDate = startDate,
                    generated = block,
                )
                repo.createBlock(plan)
            }.onSuccess { onDone(it) }.onFailure { error = it.message ?: "Could not create the block" }
            creating = false
        }
    }

    private fun components(p: Program): JsonArray = buildJsonArray {
        when (p) {
            is Program.Build -> {
                strengthKey?.let { k -> add(buildJsonObject { put("role", "strength"); putJsonObject("ref") { put("kind", "strength_module"); put("key", k) } }) }
                protocolKey?.let { k -> add(buildJsonObject { put("role", "conditioning"); putJsonObject("ref") { put("kind", "conditioning_protocol"); put("key", k) } }) }
            }
            is Program.Fixed -> add(buildJsonObject { put("role", "template"); putJsonObject("ref") { put("kind", "template"); put("key", p.template.key) } })
        }
    }

    private fun choicesJson() = buildJsonObject {
        put("path", if (program is Program.Build) "build" else "published")
        putJsonObject("weekday_map") { dayPositions.forEach { put(it.toString(), weekdayFor(it).name.take(3).lowercase()) } }
        putJsonObject("variables") { variables.forEach { (k, v) -> put(k, v) } }
        putJsonObject("clusters") {
            moduleChoices.forEach { (module, c) ->
                putJsonObject(module) {
                    c.variant?.let { put("variant", it) }
                    c.blockLength?.let { put("block_length", it) }
                    c.exercises.forEach { (slot, names) -> putJsonArray(slot) { names.forEach { add(JsonPrimitive(it)) } } }
                }
            }
        }
        putJsonObject("conditioning") { conditioningChoices.forEach { (cat, keys) -> putJsonArray(cat) { keys.forEach { add(JsonPrimitive(it)) } } } }
        if (program is Program.Build) {
            putJsonArray("layout") {
                layout.forEach { d ->
                    add(buildJsonObject {
                        put("weekday", d.weekday.name.take(3).lowercase())
                        d.strengthSession?.let { put("strength_session", it) }
                        d.conditioning?.let { put("conditioning", it.kind) }
                    })
                }
            }
            putJsonArray("timeline") { timeline.forEach { add(JsonPrimitive(it.key)) } }
            put("start_with_test", startWithTest)
        }
        put("weighted_percent_base", settings.weightedPercentBase)
        put("project_progression", projectProgression)
    }
}
