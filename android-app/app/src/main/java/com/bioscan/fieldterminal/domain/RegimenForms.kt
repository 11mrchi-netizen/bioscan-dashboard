package com.bioscan.fieldterminal.domain

// The form decides how an item is handled (migration 20261120_regimen_form_dose.sql): the unit one dose is counted in,
// whether a unit can be split, and the route -- fixed by the form unless the form leaves it open (a liquid can be swallowed,
// held under the tongue or put on the skin; an injection goes under the skin or into muscle). One dose is a count of the
// form's unit; an injection's dose is its active amount, the vial turning it into a volume.

/** How a form's dose can be divided when logging. */
enum class Splitting { WHOLE, HALVES, MEASURED }

data class FormRule(
    val key: String,
    val label: String,
    /** Units one dose can be counted in; the first is the default. */
    val units: List<String>,
    /** The route when the form fixes it; null when it has to be asked. */
    val fixedRoute: String?,
    /** Routes offered when the form leaves it open, the first being the default. */
    val routes: List<String> = emptyList(),
    val splitting: Splitting,
)

val FORM_RULES: List<FormRule> = listOf(
    FormRule("capsule", "Capsule", listOf("capsule"), "oral", splitting = Splitting.WHOLE),
    FormRule("softgel", "Softgel", listOf("softgel"), "oral", splitting = Splitting.WHOLE),
    FormRule("tablet", "Tablet", listOf("tablet"), "oral", splitting = Splitting.WHOLE), // halves when scored
    FormRule("gummy", "Gummy", listOf("gummy"), "oral", splitting = Splitting.WHOLE),
    FormRule("powder", "Powder", listOf("g", "scoop"), "oral", splitting = Splitting.MEASURED),
    FormRule("liquid", "Liquid", listOf("ml", "drop"), null, listOf("oral", "sublingual", "topical"), Splitting.MEASURED),
    FormRule("cream", "Cream / gel", listOf("g", "ml"), "topical", splitting = Splitting.MEASURED),
    FormRule("patch", "Patch", listOf("patch"), "topical", splitting = Splitting.WHOLE),
    FormRule("nasal_spray", "Nasal spray", listOf("spray"), "nasal", splitting = Splitting.WHOLE),
    FormRule("injection", "Injection (vial / pen)", listOf("mcg", "mg", "IU", "unit"), null, listOf("subcutaneous", "intramuscular"), Splitting.MEASURED),
    FormRule("other", "Other", REGIMEN_UNITS, null, listOf("oral", "sublingual", "subcutaneous", "intramuscular", "nasal", "topical", "other"), Splitting.MEASURED),
)

fun formRule(key: String?): FormRule? = FORM_RULES.firstOrNull { it.key == key }

/** The route an item is taken by: the form's own, else the chosen one when the form allows it, else the form's default. */
fun routeFor(form: String?, chosen: String?): String? {
    val rule = formRule(form) ?: return chosen
    rule.fixedRoute?.let { return it }
    return chosen?.takeIf { it in rule.routes } ?: rule.routes.firstOrNull()
}

/**
 * The smallest step, as a multiple of one dose, the logging sheet allows: a whole unit for capsules and the like, half a
 * tablet when it is scored, a quarter of the dose for what is measured.
 */
fun doseStep(form: String?, scored: Boolean, doseAmount: Double?): Double {
    val rule = formRule(form)
    val unitStep = when {
        rule == null || rule.splitting == Splitting.MEASURED -> return SUPPLEMENT_QTY_STEP
        rule.key == "tablet" && scored -> 0.5
        else -> 1.0
    }
    val count = doseAmount?.takeIf { it > 0 } ?: return SUPPLEMENT_QTY_STEP
    return unitStep / count
}

/** "3 softgels", "½ tablet", "5 g": a multiple of one dose in the form's own words. */
fun unitsWords(qty: Double, doseAmount: Double?, unit: String?): String? {
    val count = doseAmount ?: return null
    val u = unit ?: return null
    val n = Math.round(qty * count * 1000) / 1000.0
    val counted = u in setOf("capsule", "softgel", "tablet", "gummy", "patch", "spray", "scoop", "drop", "pill")
    // Counted units read as fractions (½ tablet); measured ones as decimals (6.25 g), as on a scale or a syringe.
    val number = if (counted) formatSupplementQty(n) else String.format(java.util.Locale.US, "%.2f", n).trimEnd('0').trimEnd('.')
    return "$number $u${if (counted && n > 1) "s" else ""}"
}

/** One ingredient of one dose. */
data class DoseIngredient(val name: String, val keys: Set<String>, val amount: Double, val unit: String)

/**
 * A dose's ingredients from the product's label rows (per label serving) and the item's servings per dose.
 * Elemental amounts win where the label states them.
 */
fun doseIngredients(perServing: List<DoseIngredient>, servingsPerDose: Double): List<DoseIngredient> =
    perServing.map { it.copy(amount = it.amount * servingsPerDose) }

/**
 * How many label servings one dose is, when the label's serving can be compared with the dose (same unit, ignoring plurals
 * and words like "veggie"); null otherwise.
 */
fun servingsPerDose(doseAmount: Double, doseUnit: String, servingSize: Double?, servingUnit: String?): Double? {
    if (servingSize == null || servingSize <= 0 || servingUnit == null) return null
    val norm = servingUnit.lowercase().replace(Regex("""\(s\)|s\b"""), "").split(Regex("""\s+""")).lastOrNull()?.trim() ?: return null
    val unitWord = when (norm) {
        "gram" -> "g"; "milliliter", "millilitre" -> "ml"
        else -> norm
    }
    return if (unitWord == doseUnit.lowercase()) doseAmount / servingSize else null
}

/** The ingredient a dose's active amount is read from: the one named like the item or its reference, else the first. */
fun activeIngredient(ingredients: List<DoseIngredient>, vararg names: String?): DoseIngredient? {
    val wanted = names.filterNotNull().map { it.lowercase() }
    return ingredients.firstOrNull { ing -> wanted.any { w -> w.contains(ing.name.lowercase()) || ing.name.lowercase().contains(w) } }
        ?: ingredients.singleOrNull()
        ?: ingredients.firstOrNull()
}
