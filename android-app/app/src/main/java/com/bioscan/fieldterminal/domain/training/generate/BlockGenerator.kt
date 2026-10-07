package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.PrescriptionItem
import com.bioscan.fieldterminal.domain.training.ResolveContext
import com.bioscan.fieldterminal.domain.training.ResolvedLoad
import com.bioscan.fieldterminal.domain.training.Missing
import com.bioscan.fieldterminal.domain.training.definition.Cell
import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import com.bioscan.fieldterminal.domain.training.definition.ConditioningSessionDef
import com.bioscan.fieldterminal.domain.training.definition.CompositionDef
import com.bioscan.fieldterminal.domain.training.definition.Definition
import com.bioscan.fieldterminal.domain.training.definition.ItemDto
import com.bioscan.fieldterminal.domain.training.definition.NumRange
import com.bioscan.fieldterminal.domain.training.definition.SeModuleDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import com.bioscan.fieldterminal.domain.training.lbToKg
import com.bioscan.fieldterminal.domain.training.loadStepKg
import com.bioscan.fieldterminal.domain.training.resolveLoad
import com.bioscan.fieldterminal.domain.training.roundToStep
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.LocalDate

// DAV-345. Deterministic block generation (architecture 3.3): expand a fixed-grid template
// or a composed strength + conditioning block into dated sessions with resolved loads.
// Pure Kotlin: no clock, no IO. The same inputs always give the same block.

class DefinitionIndex(defs: Collection<Definition>) {
    private val byKey: Map<String, Definition> = defs.groupBy { it.key }.mapValues { (_, v) -> v.maxBy { it.version } }
    private val aliases: Map<String, ConditioningSessionDef> =
        defs.filterIsInstance<ConditioningSessionDef>().flatMap { s -> s.aliases.map { it to s } }.toMap()

    fun any(key: String): Definition? = byKey[key]
    fun strength(key: String) = byKey[key] as? StrengthModuleDef
    fun se(key: String) = byKey[key] as? SeModuleDef
    fun session(key: String) = (byKey[key] as? ConditioningSessionDef) ?: aliases[key]
    fun protocol(key: String) = byKey[key] as? ConditioningProtocolDef
    fun template(key: String) = byKey[key] as? TemplateDef
    fun composition(key: String) = byKey[key] as? CompositionDef
}

private val DB_DOMAINS = setOf(
    "max_strength", "hypertrophy", "strength_endurance", "power", "aerobic_base", "anaerobic_capacity",
    "speed", "work_capacity", "specific_endurance", "sport_skill", "recovery",
)

private fun categoryDomain(category: String?) = when (category) {
    "lic" -> "aerobic_base"
    "hic" -> "anaerobic_capacity"
    "wc" -> "work_capacity"
    "power_hic" -> "power"
    "core" -> "strength_endurance"
    else -> "aerobic_base"
}

private fun sessionDomain(s: ConditioningSessionDef) = s.domains.firstOrNull { it in DB_DOMAINS } ?: categoryDomain(s.category)

private fun JsonElement.toNumRange(): NumRange? = when (this) {
    is JsonPrimitive -> doubleOrNull?.let { NumRange.of(it) }
    is JsonObject -> {
        val lo = (this["min"] as? JsonPrimitive)?.doubleOrNull
        val hi = (this["max"] as? JsonPrimitive)?.doubleOrNull
        if (lo != null && hi != null) NumRange(lo, hi) else null
    }
    else -> null
}

private fun nameKey(name: String) = name.trim().lowercase()

// Converts a definition's progression increment (a range, in the definition's unit) to the
// kilograms actually added, on the lifter's loadable step.
//
// TODO(human): decide where in the book's range to land and how to round. Today it takes the
// bottom of the range and rounds to the nearest loadable step, never below one step. Options:
// the midpoint, the top for lower-body lifts, or "hold" when the range converts to less than half a step.
fun progressionIncrementKg(range: NumRange, unit: String, stepKg: Double): Double {
    val kg = if (unit == "lb") lbToKg(range.min) else range.min
    return maxOf(stepKg, roundToStep(kg, stepKg))
}

private class Gen(
    val idx: DefinitionIndex,
    val c: GenChoices,
    val eq: GenEquipment,
    val maxes: (String) -> MaxEntry?,
) {
    val sessions = mutableListOf<GeneratedSession>()
    val warnings = mutableListOf<String>()
    val progressions = mutableListOf<MaxProgression>()
    private var seq = 0
    private val liveMax = mutableMapOf<String, Double>()
    private val warnedMissing = mutableSetOf<String>()
    private val stepKg = loadStepKg(eq.platesKg)

    fun weekdayFor(day: Int): DayOfWeek = c.weekdays[day] ?: c.startDate.dayOfWeek.plus((day - 1).toLong())

    fun dateFor(week: Int, day: Int): LocalDate {
        val offset = (weekdayFor(day).value - c.startDate.dayOfWeek.value + 7) % 7
        return c.startDate.plusDays(7L * (week - 1) + offset)
    }

    fun emit(
        week: Int, counts: Boolean, day: Int, slotInDay: Int, domain: String, workKind: String, moduleRef: String, title: String,
        items: List<GeneratedItem> = emptyList(), conditioning: GeneratedConditioning? = null, notes: List<String> = emptyList(),
    ) {
        val time = c.slotTimes[slotInDay]
        sessions += GeneratedSession(
            sequenceNo = ++seq, weekIndex = week, countsTowardBlock = counts, daySlot = day, slotInDay = slotInDay,
            date = dateFor(week, day), startTime = time?.start, durationMin = time?.durationMin,
            domain = domain, workKind = workKind, moduleRef = moduleRef, title = title, items = items, conditioning = conditioning, notes = notes,
        )
    }

    private fun currentOneRm(name: String): Double? = liveMax[nameKey(name)] ?: maxes(name)?.oneRmKg

    fun resolveItems(m: StrengthModuleDef, choice: ModuleChoice, dtos: List<ItemDto>): List<GeneratedItem> {
        val out = mutableListOf<GeneratedItem>()
        for (dto in dtos) {
            val slot = m.slots.firstOrNull { it.id == dto.slot } ?: continue
            val chosen = choice.exercises[slot.id].orEmpty()
            val names = when {
                slot.pick != null -> chosen
                chosen.isNotEmpty() -> listOf(chosen.first())
                else -> listOf(slot.standard)
            }
            if (names.isEmpty()) {
                if (!slot.optional && !dto.optional) warnings += "${m.title}: choose exercises for slot ${slot.id}"
                continue
            }
            for (name in names) {
                val entry = maxes(name)
                val ctx = ResolveContext(
                    oneRmKg = currentOneRm(name), trainingMaxKg = entry?.trainingMaxKg, maxReps = entry?.maxReps, bodyweightKg = c.bodyweightKg,
                    barKg = eq.barKg, platesKg = eq.platesKg, addedStepKg = eq.addedStepKg, weightedBase = eq.weightedBase,
                )
                val spec = dto.load.toSpec()
                val pi = PrescriptionItem(
                    slot = slot.id,
                    sets = (dto.sets?.min?.toInt() ?: 1)..(dto.sets?.max?.toInt() ?: 1),
                    reps = dto.reps?.let { it.min.toInt()..it.max.toInt() },
                    load = spec, weightedCalisthenics = slot.weightedCalisthenics,
                )
                val load = resolveLoad(pi, ctx)
                if (load is ResolvedLoad.Unresolved && warnedMissing.add(nameKey(name))) {
                    val what = when (load.missing) {
                        Missing.OneRm -> "a 1RM"; Missing.TrainingMax -> "a training max"; Missing.MaxReps -> "a max-reps test"; Missing.Bodyweight -> "bodyweight"
                    }
                    warnings += "No $what for $name: its loads stay unresolved until one is recorded"
                }
                out += GeneratedItem(slot.id, name, dto.role, dto.sets, dto.reps, load, dto.technique, dto.optional, dto.note, projected = nameKey(name) in liveMax)
            }
        }
        return out
    }

    // Forced progression at the start of a new block of the same module: every exercise the
    // module used so far moves up by the module's own increment (upper vs lower body).
    fun progress(m: StrengthModuleDef, choice: ModuleChoice, week: Int) {
        if (!c.projectProgression) return
        val prog = m.progression ?: return
        for (slot in m.slots) {
            val names = choice.exercises[slot.id].orEmpty().ifEmpty { if (slot.pick == null) listOf(slot.standard) else emptyList() }
            val lower = slot.role == "squat" || slot.role == "hinge"
            for (name in names) {
                val from = currentOneRm(name) ?: continue
                val inc = progressionIncrementKg(if (lower) prog.lower else prog.upper, prog.unit, stepKg)
                val to = from + inc
                liveMax[nameKey(name)] = to
                progressions += MaxProgression(week, m.key, name, from, to)
            }
        }
    }

    fun conditioningFor(key: String?, label: String?, category: String?, params: JsonObject?, defaultMinutes: NumRange? = null): GeneratedConditioning {
        val def = key?.let { idx.session(it) }
        val minutes = params?.get("minutes")?.toNumRange() ?: defaultMinutes ?: def?.prescription?.minutes
        return GeneratedConditioning(def?.key ?: key, label ?: def?.title ?: "Conditioning", def?.category ?: category, params, minutes)
    }
}

private fun pickVariant(m: StrengthModuleDef, choice: ModuleChoice) =
    m.variants.firstOrNull { it.key == choice.variant } ?: m.variants.first()

private class Cycle(var lastWeek: Int = -10, var seg: Int = 0, var seen: Boolean = false)

// ---- Fixed-grid templates ----
fun generateTemplateBlock(
    template: TemplateDef, idx: DefinitionIndex, c: GenChoices, eq: GenEquipment = GenEquipment(), maxes: (String) -> MaxEntry? = { null },
): GeneratedBlock {
    val g = Gen(idx, c, eq, maxes)
    val cycles = mutableMapOf<String, Cycle>()
    var counted = 0
    for (gw in template.grid) {
        if (gw.countsTowardBlock) counted++
        val perModuleCount = mutableMapOf<String, Int>()
        val moduleWeekSeen = mutableSetOf<String>()
        for (day in gw.days.sortedBy { it.day }) {
            day.cells.forEachIndexed { cellIdx, cell ->
                val slotInDay = cellIdx + 1
                val kindRef = cell.ref.substringBefore(':')
                val target = cell.ref.substringAfter(':', "")
                when (kindRef) {
                    "rest" -> Unit
                    "test" -> g.emit(gw.week, gw.countsTowardBlock, day.day, slotInDay, "max_strength", "test", cell.ref, cell.label ?: "Test: ${target.replace('_', ' ')}")
                    "inline" -> g.emit(gw.week, gw.countsTowardBlock, day.day, slotInDay, "recovery", if (gw.kind == "deload" || gw.kind == "taper") "deload" else "recovery", cell.ref, cell.label ?: "Session")
                    "cond" -> {
                        val key = if (target.startsWith("$")) c.variables[target.drop(1)] else target
                        val def = key?.let { idx.session(it) }
                        if (def == null) {
                            g.warnings += "Week ${gw.week} day ${day.day}: choose a session for ${cell.label ?: cell.ref}"
                            g.emit(gw.week, gw.countsTowardBlock, day.day, slotInDay, "aerobic_base", "progression", cell.ref, cell.label ?: "Choose a session",
                                conditioning = g.conditioningFor(null, cell.label ?: "Choose a session", null, cell.params))
                        } else {
                            g.emit(gw.week, gw.countsTowardBlock, day.day, slotInDay, sessionDomain(def), if (gw.kind == "deload") "deload" else "progression", "cond:${def.key}", def.title,
                                conditioning = g.conditioningFor(def.key, null, null, cell.params))
                        }
                    }
                    "strength", "se" -> generateStrengthCell(g, idx, c, cell, target, gw.week, gw.countsTowardBlock, day.day, slotInDay, cycles, perModuleCount, moduleWeekSeen)
                    else -> g.warnings += "Week ${gw.week} day ${day.day}: unknown cell '${cell.ref}'"
                }
            }
        }
    }
    return finish(g, c, counted)
}

private fun generateStrengthCell(
    g: Gen, idx: DefinitionIndex, c: GenChoices, cell: Cell, target: String, week: Int, counts: Boolean, day: Int, slotInDay: Int,
    cycles: MutableMap<String, Cycle>, perModuleCount: MutableMap<String, Int>, moduleWeekSeen: MutableSet<String>,
) {
    val key = if (target.startsWith("$")) c.variables[target.drop(1)] else target
    val strength = key?.let { idx.strength(it) }
    val seMod = key?.let { idx.se(it) }
    if (strength == null && seMod == null) {
        g.warnings += "Week $week day $day: choose a strength module for ${cell.label ?: cell.ref}"
        g.emit(week, counts, day, slotInDay, "max_strength", "progression", cell.ref, cell.label ?: "Choose a strength module")
        return
    }
    val moduleKey = (strength?.key ?: seMod!!.key)
    val cyc = cycles.getOrPut(moduleKey) { Cycle() }
    if (moduleKey !in moduleWeekSeen) {
        moduleWeekSeen += moduleKey
        cyc.seg = if (cyc.lastWeek == week - 1 && cyc.seen) cyc.seg + 1 else 0
        cyc.lastWeek = week
    }
    val n = perModuleCount.getOrDefault(moduleKey, 0)
    perModuleCount[moduleKey] = n + 1

    if (strength != null) {
        val choice = c.modules[moduleKey] ?: ModuleChoice()
        val variant = pickVariant(strength, choice)
        val len = (choice.blockLength ?: variant.weeks.size).coerceIn(1, variant.weeks.size)
        val pos = cyc.seg % len
        if (pos == 0 && cyc.seen && n == 0) g.progress(strength, choice, week)
        cyc.seen = true
        val sessionId = (cell.params?.get("session") as? JsonPrimitive)?.content ?: strength.sessions.getOrNull(n % strength.sessions.size)?.id
        val sessionDef = strength.sessions.firstOrNull { it.id == sessionId }
        val vw = variant.weeks.getOrNull(pos)
        val ws = vw?.sessions?.firstOrNull { it.session == sessionId }
        if (ws == null || sessionDef == null) {
            g.warnings += "Week $week: ${strength.title} has no prescription for $sessionId in its week ${pos + 1}"
            return
        }
        val notes = buildList { ws.note?.let { add(it) }; if (vw.kind == "peak") strength.peak?.note?.let { add(it) } }
        g.emit(week, counts, day, slotInDay, strength.domain, if (vw.kind == "deload") "deload" else "progression", "strength:${strength.key}", "${strength.title}: ${sessionDef.label}",
            items = g.resolveItems(strength, choice, ws.items), notes = notes)
    } else {
        val se = seMod!!
        val choice = c.modules[moduleKey] ?: ModuleChoice()
        val pos = cyc.seg.coerceAtMost(se.weeks.size - 1)
        cyc.seen = true
        val w = se.weeks[pos]
        val names = choice.exercises["SE"].orEmpty()
        val items = names.map { GeneratedItem("SE", it, "circuit", w.circuits, w.reps, ResolvedLoad.NoLoad, note = se.loadPct1rm?.let { r -> "light: ${r.min.toInt()}-${r.max.toInt()}% of 1RM, reps first" }) }
        if (names.isEmpty()) g.warnings += "${se.title}: choose the exercises of the circuit"
        g.emit(week, counts, day, slotInDay, "strength_endurance", "progression", "se:${se.key}", se.title, items = items)
    }
}

// ---- Composed blocks: a strength module and a conditioning protocol ----
fun generateComposedBlock(
    strength: StrengthModuleDef?, protocol: ConditioningProtocolDef?, idx: DefinitionIndex, c: GenChoices,
    eq: GenEquipment = GenEquipment(), maxes: (String) -> MaxEntry? = { null },
): GeneratedBlock {
    require(strength != null || protocol != null) { "a composed block needs a strength module or a conditioning protocol" }
    val g = Gen(idx, c, eq, maxes)
    val total = c.weeks ?: 6
    val choice = strength?.let { c.modules[it.key] ?: ModuleChoice() } ?: ModuleChoice()
    val variant = strength?.let { pickVariant(it, choice) }
    val len = variant?.let { (choice.blockLength ?: it.weeks.size).coerceIn(1, it.weeks.size) } ?: 1
    var blockPos = 0
    var counted = 0
    var blocksDone = 0
    val rotation = mutableMapOf<String, Int>()
    for (week in 1..total) {
        val deload = strength != null && c.deloadAfterBlock && blockPos == len
        if (deload) blockPos = 0
        val counts = !deload
        if (counts) counted++
        val strengthDays = mutableSetOf<Int>()
        if (strength != null && variant != null) {
            if (deload) {
                val day = strength.dayPositions.first()
                strengthDays += day
                g.emit(week, false, day, 1, "recovery", "deload", "inline", "Deload week: reduced volume and intensity")
                g.progress(strength, choice, week)
                blocksDone++
            } else {
                if (!c.deloadAfterBlock && blockPos == len) { blockPos = 0; g.progress(strength, choice, week); blocksDone++ }
                val vw = variant.weeks[blockPos]
                strength.sessions.forEachIndexed { i, sd ->
                    val ws = vw.sessions.firstOrNull { it.session == sd.id } ?: return@forEachIndexed
                    val day = strength.dayPositions.getOrElse(i) { i + 1 }
                    strengthDays += day
                    val notes = buildList { ws.note?.let { add(it) }; if (vw.kind == "peak") strength.peak?.note?.let { add(it) } }
                    g.emit(week, true, day, 1, strength.domain, "progression", "strength:${strength.key}", "${strength.title}: ${sd.label}", items = g.resolveItems(strength, choice, ws.items), notes = notes)
                }
                blockPos++
            }
        }
        if (protocol != null) addConditioning(g, protocol, week, counts, deload, strengthDays, rotation)
    }
    return finish(g, c, counted)
}

private fun addConditioning(g: Gen, p: ConditioningProtocolDef, week: Int, counts: Boolean, deload: Boolean, strengthDays: Set<Int>, rotation: MutableMap<String, Int>) {
    val b = p.budget
    val hicWeek = when {
        b?.highIntensityPerWeek != null -> b.highIntensityPerWeek.max > 0
        b?.highIntensityEveryNWeeks != null -> (week - 1) % b.highIntensityEveryNWeeks == 0
        else -> false
    }
    data class Slot(val day: Int, val kind: String, val minutes: NumRange?)
    val slots: List<Slot> = if (p.defaultLayout.isNotEmpty()) {
        p.defaultLayout.filter { it.kind == "lic" || it.kind == "hic" || it.kind == "wc" }.filter { it.kind != "hic" || hicWeek }.map { Slot(it.day, it.kind, it.minutes) }
    } else {
        val n = (g.c.conditioningPerWeek ?: b?.sessionsPerWeek?.min?.toInt() ?: 1).coerceAtLeast(0)
        val free = (1..7).filter { it !in strengthDays }
        val kind = if (p.suggested.hic.isNotEmpty() && p.suggested.lic.isEmpty()) "hic" else "lic"
        if (n == 0 || free.isEmpty()) emptyList() else List(n.coerceAtMost(free.size)) { i -> Slot(free[(i * free.size) / n.coerceAtMost(free.size)], kind, null) }
    }
    val licCount = slots.count { it.kind == "lic" }
    val adjust = p.weekAdjustments.firstOrNull { it.`when` == if (hicWeek) "week_has_high_intensity" else "week_without_high_intensity" }
    val licBudget = adjust?.lowIntensityMinutesPerWeek ?: b?.lowIntensityMinutesPerWeek
    for (s in slots) {
        val list = g.c.conditioning[s.kind].orEmpty().ifEmpty {
            when (s.kind) { "lic" -> p.suggested.lic; "hic" -> p.suggested.hic; else -> p.suggested.wc }
        }
        val i = rotation.getOrDefault(s.kind, 0)
        rotation[s.kind] = i + 1
        val key = list.getOrNull(i % list.size.coerceAtLeast(1))
        val share = if (s.kind == "lic" && licBudget != null && licCount > 0) {
            val minSession = b?.sessionMinMinutes?.toDouble() ?: 0.0
            NumRange.of(maxOf(minSession, (licBudget.min / licCount).let { kotlin.math.floor(it / 5) * 5 }))
        } else null
        val minutes = s.minutes ?: share
        val def = key?.let { g.idx.session(it) }
        val label = def?.title ?: "Choose a ${s.kind.uppercase()} session"
        if (def == null) g.warnings += "Week $week: choose a ${s.kind.uppercase()} session for ${p.title}"
        g.emit(week, counts, s.day, if (s.day in strengthDays) 2 else 1, def?.let { sessionDomain(it) } ?: categoryDomain(s.kind), if (deload) "deload" else "progression", def?.let { "cond:${it.key}" } ?: "cond:${s.kind}", label,
            conditioning = g.conditioningFor(key, label, s.kind, null, minutes))
    }
}

private fun finish(g: Gen, c: GenChoices, counted: Int): GeneratedBlock {
    val weeks = g.sessions.maxOfOrNull { it.weekIndex } ?: 0
    val end = c.startDate.plusDays(7L * weeks.coerceAtLeast(1) - 1)
    return GeneratedBlock(g.sessions.toList(), weeks, counted, end, g.progressions.toList(), g.warnings.distinct())
}
