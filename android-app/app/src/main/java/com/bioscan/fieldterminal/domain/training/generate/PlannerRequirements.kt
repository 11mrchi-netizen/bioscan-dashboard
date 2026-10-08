package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.definition.CompositionDef
import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import com.bioscan.fieldterminal.domain.training.definition.SlotDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// DAV-345. What the setup flow must ask the lifter, derived from a definition: which template
// variables need a choice, which modules end up in play, which days of the week are used and which
// slots take an exercise. Pure, so the screen only renders and the rules are tested.

data class VariableSpec(val name: String, val kind: String, val options: List<String>, val note: String?)

private val VAR_REF = Regex("^(strength|se|cond):\\$([A-Za-z0-9_]+)$")

fun templateVariables(t: TemplateDef): List<VariableSpec> {
    val declared = t.variables.orEmpty()
    val used = LinkedHashSet<String>()
    for (w in t.grid) for (d in w.days) for (c in d.cells) VAR_REF.matchEntire(c.ref)?.let { used += it.groupValues[2] }
    return used.map { name ->
        val spec = declared[name] as? JsonObject
        VariableSpec(
            name = name,
            kind = spec?.get("kind")?.jsonPrimitive?.content ?: "conditioning_session",
            options = (spec?.get("choose_from") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            note = spec?.get("note")?.jsonPrimitive?.content,
        )
    }
}

// Strength and SE module keys a template uses, given the variable choices (unchosen variables are skipped).
fun templateModuleKeys(t: TemplateDef, variables: Map<String, String>): List<String> {
    val keys = LinkedHashSet<String>()
    for (w in t.grid) for (d in w.days) for (c in d.cells) {
        val kind = c.ref.substringBefore(':')
        if (kind != "strength" && kind != "se") continue
        val target = c.ref.substringAfter(':', "")
        val key = if (target.startsWith("$")) variables[target.drop(1)] else target
        if (key != null && key.isNotBlank()) keys += key
    }
    return keys.toList()
}

fun templateDayPositions(t: TemplateDef): List<Int> = t.grid.flatMap { w -> w.days.filter { it.cells.any { c -> c.ref != "rest" } }.map { it.day } }.distinct().sorted()

fun composedDayPositions(strength: StrengthModuleDef?, protocol: ConditioningProtocolDef?): List<Int> =
    ((strength?.dayPositions.orEmpty()) + (protocol?.defaultLayout.orEmpty().filter { it.kind != "rest" }.map { it.day })).distinct().sorted()

// Slots that take an exercise: every slot the module's sessions use, optional ones included
// (the screen shows them as optional).
fun slotsToChoose(m: StrengthModuleDef): List<SlotDef> {
    val used = m.sessions.flatMap { it.slots }.toSet() + m.variants.flatMap { v -> v.weeks.flatMap { w -> w.sessions.flatMap { s -> s.items.map { it.slot } } } }
    return m.slots.filter { it.id in used }
}

fun compositionStrengthOptions(c: CompositionDef): List<String> = c.strength.chooseFrom
fun compositionProtocolOptions(c: CompositionDef): List<String> = c.conditioning.chooseFrom

// The main strength programs a lifter chooses between first; each has several versions chosen
// afterwards. Groups come from the template key and family, not from a hand-kept list of titles.
data class StrengthGroup(val id: String, val label: String, val defaultKey: String)

val STRENGTH_GROUPS = listOf(
    StrengthGroup("operator", "OPERATOR", "tb3.operator"),
    StrengthGroup("zulu", "ZULU", "tb3.zulu"),
    StrengthGroup("fighter", "FIGHTER", "tb3.fighter"),
    StrengthGroup("mass", "MASS", "mass.mt"),
    StrengthGroup("green", "GREEN", "green.op_pro"),
    StrengthGroup("more", "MORE", "tb3.breacher"),
)

fun strengthGroupOf(m: StrengthModuleDef): String {
    val book = m.key.substringBefore('.')
    return when {
        book == "mass" -> "mass"
        book == "green" -> "green"
        book == "tb3" && m.family == "operator" -> "operator"
        book == "tb3" && m.family == "zulu" -> "zulu"
        book == "tb3" && m.family == "fighter" -> "fighter"
        else -> "more"
    }
}

// The versions inside one group, the group's default first.
fun versionsOf(group: StrengthGroup, modules: List<StrengthModuleDef>): List<StrengthModuleDef> =
    modules.filter { strengthGroupOf(it) == group.id }.sortedWith(compareBy({ it.key != group.defaultKey }, { it.title }))

// Conditioning templates worth showing first; the rest sit behind "other templates".
fun isMainProtocol(p: ConditioningProtocolDef): Boolean =
    p.key.startsWith("tb3.polarized_") || p.key.startsWith("mass.") || p.key == "tb3.work_capacity"
