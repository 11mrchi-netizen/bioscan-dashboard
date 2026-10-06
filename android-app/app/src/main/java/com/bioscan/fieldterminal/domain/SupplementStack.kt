package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.analysis.Provenance
import java.util.Locale

// DAV-358. Stack analysis over the ACTIVE supplement roster: ingredient
// overlap, per-day supplement exposure against the app's own NUTRIENT_REFERENCE,
// and data-quality warnings. Findings, never a score. Pure -- the repository
// builds the StackEntry list from supplements / products / ingredients.
//
// Scope: what the stack itself supplies. Food + supplement totals against the
// RDA already live in the nutrient breakdown; tolerable upper limits for many
// nutrients apply to supplemental intake, so the stack is compared on its own.

data class StackIngredient(
    val canonicalKey: String?,
    val nutrientKey: String?,
    // Elemental amount when known, otherwise the compound amount.
    val amount: Double,
    val unit: String,
    val hasElemental: Boolean,
)

data class StackEntry(
    val supplementId: Long,
    val name: String,
    val everyNDays: Int?,
    val asNeeded: Boolean,
    // supplement_products.match_confidence ("low" = inferred, "manual" = entered).
    val matchConfidence: String?,
    val ingredients: List<StackIngredient>,
)

data class NutrientExposure(
    val nutrientKey: String,
    val amountPerDay: Double,
    val unit: String,
    val rda: Double?,
    val upperLimit: Double?,
    val contributors: List<String>,
    // True when any contributor only had a compound-weight amount, so the
    // total may overstate the real nutrient.
    val approximate: Boolean,
)

enum class StackFindingKind {
    OVERLAP, ABOVE_UPPER_LIMIT, UNIT_NOT_COMPARABLE, UNMAPPED_NUTRIENT, COMPOUND_AMOUNT_USED, LOW_CONFIDENCE_COMPOSITION,
}

data class StackFinding(
    val kind: StackFindingKind,
    val subject: String,
    val message: String,
    val supplements: List<String>,
)

data class StackAnalysis(
    val exposures: List<NutrientExposure>,
    val findings: List<StackFinding>,
    val provenance: Provenance,
)

private class ExposureAcc(val unit: String) {
    var amount = 0.0
    val contributors = linkedSetOf<String>()
    var approximate = false
}

private fun fmt(x: Double): String = String.format(Locale.US, "%.1f", x)

fun analyzeStack(entries: List<StackEntry>, reference: Map<String, NutrientInfo> = NUTRIENT_REFERENCE): StackAnalysis {
    val findings = mutableListOf<StackFinding>()

    // Overlap: the same canonical ingredient supplied by 2+ different supplements.
    entries
        .flatMap { e -> e.ingredients.mapNotNull { i -> i.canonicalKey?.let { it to e.name } } }
        .groupBy({ it.first }, { it.second })
        .forEach { (key, names) ->
            val distinct = names.distinct()
            if (distinct.size >= 2) {
                findings += StackFinding(
                    StackFindingKind.OVERLAP, key,
                    "${nutrientDisplayName(key)} comes from ${distinct.size} supplements: ${distinct.joinToString()}.",
                    distinct,
                )
            }
        }

    val acc = linkedMapOf<String, ExposureAcc>()
    for (entry in entries) {
        if (entry.matchConfidence == "low") {
            findings += StackFinding(
                StackFindingKind.LOW_CONFIDENCE_COMPOSITION, entry.name,
                "${entry.name}: composition was inferred, not verified against the label.",
                listOf(entry.name),
            )
        }

        val compoundOnly = entry.ingredients.filter { it.nutrientKey != null && !it.hasElemental }
        if (compoundOnly.isNotEmpty()) {
            findings += StackFinding(
                StackFindingKind.COMPOUND_AMOUNT_USED, entry.name,
                "${entry.name}: elemental amount unknown for " +
                    "${compoundOnly.joinToString { nutrientDisplayName(it.nutrientKey!!) }}; " +
                    "compound amounts are used, so exposure may be overstated.",
                listOf(entry.name),
            )
        }

        for (ing in entry.ingredients) {
            if (ing.nutrientKey == null) {
                val ck = ing.canonicalKey
                if (ck != null && ck in reference) {
                    findings += StackFinding(
                        StackFindingKind.UNMAPPED_NUTRIENT, ck,
                        "${entry.name}: ${nutrientDisplayName(ck)} has a nutrient reference but no nutrient mapping, so it is left out of exposure totals.",
                        listOf(entry.name),
                    )
                }
                continue
            }
            if (entry.asNeeded) continue // no schedule, so no per-day exposure

            val key = ing.nutrientKey
            val targetUnit = reference[key]?.unit ?: acc[key]?.unit ?: ing.unit
            val converted = if (ing.unit.equals(targetUnit, ignoreCase = true)) ing.amount
            else convertMass(ing.amount, ing.unit, targetUnit)
            if (converted == null) {
                findings += StackFinding(
                    StackFindingKind.UNIT_NOT_COMPARABLE, key,
                    "${entry.name}: ${nutrientDisplayName(key)} is in ${ing.unit}, which can't be converted to $targetUnit, so it is left out of exposure totals.",
                    listOf(entry.name),
                )
                continue
            }
            val perDay = if (entry.everyNDays != null && entry.everyNDays > 1) converted / entry.everyNDays else converted
            val a = acc.getOrPut(key) { ExposureAcc(targetUnit) }
            a.amount += perDay
            a.contributors += entry.name
            if (!ing.hasElemental) a.approximate = true
        }
    }

    val exposures = acc.entries.sortedBy { it.key }.map { (key, a) ->
        val info = reference[key]
        NutrientExposure(key, a.amount, a.unit, info?.rda, info?.upperLimit, a.contributors.toList(), a.approximate)
    }

    for (e in exposures) {
        val ul = e.upperLimit ?: continue
        if (e.amountPerDay > ul) {
            findings += StackFinding(
                StackFindingKind.ABOVE_UPPER_LIMIT, e.nutrientKey,
                "Stack supplies ${fmt(e.amountPerDay)} ${e.unit}/day of ${nutrientDisplayName(e.nutrientKey)}, " +
                    "above the reference upper limit (${fmt(ul)} ${e.unit})" +
                    (if (e.approximate) "; approximate, includes compound-weight amounts." else "."),
                e.contributors,
            )
        }
    }

    return StackAnalysis(exposures, findings, Provenance(origin = "computed", algorithm = "supplement_stack", algorithmVersion = "1"))
}
