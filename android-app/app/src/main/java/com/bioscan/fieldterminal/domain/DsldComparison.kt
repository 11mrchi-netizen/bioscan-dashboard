package com.bioscan.fieldterminal.domain

import kotlin.math.abs

// DAV-359. Compares a user's own supplement product against an NIH DSLD label.
// Verification only: the result is stored as a snapshot and never overwrites the
// product. Only ingredients that match by name are compared; amounts are
// converted g/mg/mcg only (convertMass), never guessed across other units.

data class DsldIngredient(val name: String, val category: String?, val amount: Double?, val unit: String?)

data class ProductIngredientView(
    val name: String,
    val compoundAmount: Double,
    val compoundUnit: String,
    val elementalAmount: Double?,
    val elementalUnit: String?,
)

enum class DsldConflictKind { AMOUNT_DIFFERS, UNIT_NOT_COMPARABLE }

data class DsldConflict(val kind: DsldConflictKind, val ingredient: String, val productText: String, val dsldText: String)

data class DsldComparison(
    val matchedIngredients: Int,
    val conflicts: List<DsldConflict>,
    // DSLD rows with no counterpart in the user's product (e.g. a multi's other
    // vitamins): counted, not listed as conflicts.
    val dsldOnlyCount: Int,
    val productOnly: List<String>,
)

private const val AMOUNT_TOLERANCE = 0.05

// "Vitamin D3 (Now Food)" -> "vitamin d3": brand/form parentheticals dropped.
fun normalizeIngredientName(name: String): String =
    name.lowercase()
        .replace(Regex("\\(.*?\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

private fun namesMatch(a: String, b: String): Boolean {
    val ta = normalizeIngredientName(a).split(' ').filter { it.isNotEmpty() }.toSet()
    val tb = normalizeIngredientName(b).split(' ').filter { it.isNotEmpty() }.toSet()
    if (ta.isEmpty() || tb.isEmpty()) return false
    return ta == tb || ta.containsAll(tb) || tb.containsAll(ta)
}

private fun fmt(amount: Double, unit: String): String =
    "${if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()} $unit"

fun compareToDsld(product: List<ProductIngredientView>, dsld: List<DsldIngredient>): DsldComparison {
    val unmatchedDsld = dsld.toMutableList()
    val unmatchedProduct = mutableListOf<String>()
    val conflicts = mutableListOf<DsldConflict>()
    var matched = 0

    for (p in product) {
        val d = unmatchedDsld.firstOrNull { namesMatch(p.name, it.name) }
        if (d == null) {
            unmatchedProduct += p.name
            continue
        }
        unmatchedDsld.remove(d)
        matched++

        val dAmount = d.amount
        val dUnit = d.unit
        if (dAmount == null || dUnit == null) continue // proprietary blend / no quantity: nothing to compare

        // The label amount may describe the compound or the elemental form, so
        // either of the user's two amounts agreeing counts as consistent.
        val candidates = buildList {
            add(p.compoundAmount to p.compoundUnit)
            if (p.elementalAmount != null && p.elementalUnit != null) add(p.elementalAmount to p.elementalUnit)
        }
        val comparable = candidates.mapNotNull { (amt, unit) ->
            if (unit.equals(dUnit, ignoreCase = true)) amt else convertMass(amt, unit, dUnit)
        }
        val productText = candidates.joinToString(" / ") { (a, u) -> fmt(a, u) }
        val dsldText = fmt(dAmount, dUnit)

        when {
            comparable.isEmpty() ->
                conflicts += DsldConflict(DsldConflictKind.UNIT_NOT_COMPARABLE, p.name, productText, dsldText)
            comparable.none { abs(it - dAmount) <= AMOUNT_TOLERANCE * maxOf(abs(dAmount), 1e-9) } ->
                conflicts += DsldConflict(DsldConflictKind.AMOUNT_DIFFERS, p.name, productText, dsldText)
        }
    }

    return DsldComparison(matched, conflicts, unmatchedDsld.count { it.name.isNotBlank() }, unmatchedProduct)
}
