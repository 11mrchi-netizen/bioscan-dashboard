package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.BlockPlan
import com.bioscan.fieldterminal.data.DefinitionCatalog
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.model.TrainingSettingsRow
import com.bioscan.fieldterminal.domain.training.PercentBase
import com.bioscan.fieldterminal.domain.training.ResolvedMax
import com.bioscan.fieldterminal.domain.training.definition.CompositionDef
import com.bioscan.fieldterminal.domain.training.definition.Definition
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import com.bioscan.fieldterminal.domain.training.generate.DefinitionIndex
import com.bioscan.fieldterminal.domain.training.generate.GenChoices
import com.bioscan.fieldterminal.domain.training.generate.GenEquipment
import com.bioscan.fieldterminal.domain.training.generate.GeneratedBlock
import com.bioscan.fieldterminal.domain.training.generate.MaxEntry
import com.bioscan.fieldterminal.domain.training.generate.ModuleChoice
import com.bioscan.fieldterminal.domain.training.generate.SlotTime
import com.bioscan.fieldterminal.domain.training.generate.composedDayPositions
import com.bioscan.fieldterminal.domain.training.generate.generateComposedBlock
import com.bioscan.fieldterminal.domain.training.generate.generateTemplateBlock
import com.bioscan.fieldterminal.domain.training.generate.templateDayPositions
import com.bioscan.fieldterminal.domain.training.generate.templateModuleKeys
import com.bioscan.fieldterminal.domain.training.movementKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

enum class PlannerStep(val label: String) { Program("PROGRAM"), Modules("MODULES"), Exercises("EXERCISES"), Conditioning("CONDITIONING"), Review("REVIEW") }

sealed interface Program {
    val key: String
    val title: String
    data class Fixed(val template: TemplateDef) : Program { override val key get() = template.key; override val title get() = template.title }
    data class Composed(val composition: CompositionDef) : Program { override val key get() = composition.key; override val title get() = composition.title }
}

// DAV-345. Everything the setup flow holds between steps. Choices live in Compose state maps so
// the review preview regenerates whenever one changes; the generator itself stays pure.
class TrainingPlannerState(private val repo: TrainingProgramRepository, private val scope: CoroutineScope) {
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var catalog by mutableStateOf<DefinitionCatalog?>(null)
    var settings by mutableStateOf(TrainingSettingsRow())
    var maxes by mutableStateOf<Map<String, ResolvedMax>>(emptyMap())
    var importMessage by mutableStateOf<String?>(null)
    var creating by mutableStateOf(false)

    var step by mutableStateOf(PlannerStep.Program)
    var program by mutableStateOf<Program?>(null)
    var startDate by mutableStateOf(LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)))
    val weekdays = mutableStateMapOf<Int, DayOfWeek>()
    val variables = mutableStateMapOf<String, String>()
    var strengthKey by mutableStateOf<String?>(null)
    var protocolKey by mutableStateOf<String?>(null)
    var weeks by mutableIntStateOf(12)
    var deloadAfterBlock by mutableStateOf(true)
    var projectProgression by mutableStateOf(true)
    val moduleChoices = mutableStateMapOf<String, ModuleChoice>()
    val conditioningChoices = mutableStateMapOf<String, List<String>>()
    val manualMaxes = mutableStateMapOf<String, Double>()
    var name by mutableStateOf("")

    val index: DefinitionIndex? get() = catalog?.let { DefinitionIndex(it.definitions) }

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

    fun import(files: List<com.bioscan.fieldterminal.data.ImportFile>) {
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
        name = p.title
        when (p) {
            is Program.Fixed -> Unit
            is Program.Composed -> {
                strengthKey = p.composition.strength.chooseFrom.firstOrNull()
                protocolKey = p.composition.conditioning.chooseFrom.firstOrNull()
                weeks = p.composition.weeks.let { ((it.min + it.max) / 2).toInt().coerceAtLeast(1) }
            }
        }
    }

    // ---- derived selections ----
    val strengthModules: List<StrengthModuleDef>
        get() {
            val idx = index ?: return emptyList()
            return when (val p = program) {
                is Program.Fixed -> templateModuleKeys(p.template, variables).mapNotNull { idx.strength(it) }
                is Program.Composed -> listOfNotNull(strengthKey?.let { idx.strength(it) })
                null -> emptyList()
            }
        }

    val dayPositions: List<Int>
        get() {
            val idx = index ?: return emptyList()
            return when (val p = program) {
                is Program.Fixed -> templateDayPositions(p.template)
                is Program.Composed -> composedDayPositions(strengthKey?.let { idx.strength(it) }, protocolKey?.let { idx.protocol(it) })
                null -> emptyList()
            }
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
        weeks = weeks,
        deloadAfterBlock = deloadAfterBlock,
        projectProgression = projectProgression,
    )

    fun generate(): GeneratedBlock? {
        val idx = index ?: return null
        val c = genChoices()
        return when (val p = program ?: return null) {
            is Program.Fixed -> generateTemplateBlock(p.template, idx, c, equipment(), ::maxEntry)
            is Program.Composed -> generateComposedBlock(strengthKey?.let { idx.strength(it) }, protocolKey?.let { idx.protocol(it) }, idx, c, equipment(), ::maxEntry)
        }
    }

    // Definitions to freeze into the block: the program itself plus every module, protocol and
    // session its generated sessions refer to.
    private fun definitionsUsed(block: GeneratedBlock): List<Definition> {
        val idx = index ?: return emptyList()
        val keys = linkedSetOf<String>()
        (program as? Program.Composed)?.let { keys += it.composition.key }
        strengthModules.forEach { keys += it.key }
        protocolKey?.takeIf { program is Program.Composed }?.let { keys += it }
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
                val used = definitionsUsed(block)
                val plan = BlockPlan(
                    name = name.ifBlank { p.title },
                    template = (p as? Program.Fixed)?.template,
                    components = components(p),
                    definitionsUsed = used,
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
            is Program.Composed -> {
                strengthKey?.let { k -> add(buildJsonObject { put("role", "strength"); putJsonObject("ref") { put("kind", "strength_module"); put("key", k) } }) }
                protocolKey?.let { k -> add(buildJsonObject { put("role", "conditioning"); putJsonObject("ref") { put("kind", "conditioning_protocol"); put("key", k) } }) }
            }
            is Program.Fixed -> add(buildJsonObject { put("role", "template"); putJsonObject("ref") { put("kind", "template"); put("key", p.template.key) } })
        }
    }

    private fun choicesJson() = buildJsonObject {
        putJsonObject("weekday_map") { dayPositions.forEach { put(it.toString(), weekdayFor(it).name.take(3).lowercase()) } }
        putJsonObject("variables") { variables.forEach { (k, v) -> put(k, v) } }
        putJsonObject("clusters") {
            moduleChoices.forEach { (module, c) ->
                putJsonObject(module) {
                    c.variant?.let { put("variant", it) }
                    c.blockLength?.let { put("block_length", it) }
                    c.exercises.forEach { (slot, names) -> putJsonArray(slot) { names.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
                }
            }
        }
        putJsonObject("conditioning") { conditioningChoices.forEach { (cat, keys) -> putJsonArray(cat) { keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } } }
        put("weighted_percent_base", settings.weightedPercentBase)
        put("project_progression", projectProgression)
        put("deload_after_block", deloadAfterBlock)
        if (program is Program.Composed) put("weeks", weeks)
    }
}
