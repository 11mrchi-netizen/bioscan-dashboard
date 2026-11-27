package com.bioscan.fieldterminal.domain.training

// Lifting gear (straps, belt, ...): the person's rules for when they use it ("straps on deadlifts from 140 kg") and what a new
// set is pre-filled with. Mirror of supabase/functions/_shared/training-core/gear.ts; both sides test the same cases.
//
// A set's gear is a list of tags. null means "not recorded" (every set before this existed), an empty list means "recorded: no gear".

/** The tags, in the standard order (the database allows exactly these). */
val GEAR_TAGS = listOf("straps", "belt", "sleeves", "wraps", "chalk", "hook", "shoes")

val GEAR_LABEL = mapOf(
    "straps" to "Straps", "belt" to "Belt", "sleeves" to "Sleeves", "wraps" to "Wraps",
    "chalk" to "Chalk", "hook" to "Hook grip", "shoes" to "Lifting shoes",
)

/** One rule: use [gear] on the lift [movementKey] from [fromKg] upward (null: at any load). */
data class GearRule(val movementKey: String, val gear: String, val fromKg: Double? = null)

/** Known tags only, in the standard order, no repeats. */
fun cleanGear(g: Collection<String>): List<String> = GEAR_TAGS.filter { it in g }

/** The recorded gear of a set; null when it was not recorded. */
fun recordedGear(setGear: List<String>?): List<String>? = setGear?.let { cleanGear(it) }

/** The gear the rules call for on a set of [name] at [weightKg]. A rule without a threshold applies at any load; with no weight only those do. */
fun gearForLoad(rules: List<GearRule>, name: String, weightKg: Double?): List<String> {
    val key = movementKey(name)
    val w = weightKg?.takeIf { it.isFinite() }
    return cleanGear(rules.filter { it.movementKey == key && it.gear in GEAR_TAGS && (it.fromKg == null || (w != null && w >= it.fromKg)) }.map { it.gear })
}

/**
 * What to pre-fill on a new set: the rules' gear at that load, or null (leave it not recorded) when the lift has no rule at all, so a
 * lift the person never set up is not recorded as "no gear" by default.
 */
fun suggestGear(rules: List<GearRule>, name: String, weightKg: Double?): List<String>? {
    val key = movementKey(name)
    if (rules.none { it.movementKey == key }) return null
    return gearForLoad(rules, name, weightKg)
}

private fun kgText(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

/** The rules of one lift as a line, lowest threshold first: "straps from 140 kg · belt from 160 kg". Empty when none. */
fun describeGearRules(rules: List<GearRule>, name: String): String {
    val key = movementKey(name)
    return rules.filter { it.movementKey == key && it.gear in GEAR_TAGS }
        .sortedWith(compareBy<GearRule> { it.fromKg ?: 0.0 }.thenBy { GEAR_TAGS.indexOf(it.gear) })
        .joinToString(" · ") { (GEAR_LABEL.getValue(it.gear)).lowercase() + (it.fromKg?.let { k -> " from ${kgText(k)} kg" } ?: "") }
}
