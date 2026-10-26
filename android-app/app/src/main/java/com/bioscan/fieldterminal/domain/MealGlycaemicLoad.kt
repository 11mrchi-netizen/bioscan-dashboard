package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.MealRow
import java.time.OffsetDateTime

// Glucose-spiking estimate (Today › Glucose spiking): a coarse, per-meal relative glycaemic load from what is logged
// (carbohydrate, fibre, sugar, protein, fat). It is NOT a glucose prediction: there is no glucose or glycaemic-index data
// here, individual responses to the same meal differ widely (Zeevi 2015; Berry 2020), and the modifier sizes below are
// heuristics, so the output is an ordinal band, never mg/dL. docs/ft-redesign/model-plans.md, section 2.
//
// Load = available carbohydrate (carbs minus fibre) scaled up for a high share of sugar and down for protein, fat and
// fibre eaten with it (slower gastric emptying and absorption; direction from the food-order and mixed-meal literature,
// size chosen conservatively). Bands are the conventional glycaemic-load cut-offs (10 and 20 per serving) applied to that
// score, a proxy because the glycaemic index is taken as mid-range.

enum class GlycaemicBand(val label: String) { Low("LOW"), Moderate("MODERATE"), High("HIGH") }

/** Meals closer together than this share one rise. */
const val MEAL_GROUP_GAP_MIN = 90

const val GL_LOW_MAX = 10.0
const val GL_MODERATE_MAX = 20.0

// Heuristic modifier caps (each is the most a nutrient can lower or raise the score).
private const val SUGAR_SPEEDS_BY = 0.30      // a meal that is all sugar scores up to 30 % higher
private const val PROTEIN_SLOWS_BY = 0.25     // reached at 30 g
private const val FAT_SLOWS_BY = 0.15         // reached at 25 g
private const val FIBRE_SLOWS_BY = 0.15       // reached at 8 g
private const val PROTEIN_FULL_G = 30.0
private const val FAT_FULL_G = 25.0
private const val FIBRE_FULL_G = 8.0

data class MealGlycaemicEstimate(
    val startedAt: String,
    val meals: Int,
    val description: String,
    val carbsG: Double?,
    val score: Double?,
    val band: GlycaemicBand?,
    val confidence: ConfidenceLevel,
    /** What pushed the estimate up or down, in plain words. */
    val drivers: List<String>,
    /** Nutrients the estimate could not use because they were not logged (never read as zero). */
    val missing: List<String>,
)

/** Meals in time order, a new group wherever the gap to the previous meal exceeds [gapMin]. */
fun groupMeals(meals: List<MealRow>, gapMin: Int = MEAL_GROUP_GAP_MIN): List<List<MealRow>> {
    val sorted = meals.mapNotNull { m -> runCatching { OffsetDateTime.parse(m.loggedAt) }.getOrNull()?.let { it to m } }.sortedBy { it.first }
    val groups = mutableListOf<MutableList<Pair<OffsetDateTime, MealRow>>>()
    for (p in sorted) {
        val last = groups.lastOrNull()?.last()
        if (last != null && java.time.Duration.between(last.first, p.first).toMinutes() <= gapMin) groups.last() += p else groups += mutableListOf(p)
    }
    return groups.map { g -> g.map { it.second } }
}

private fun List<Double?>.sumKnown(): Double? = if (any { it == null }) null else sumOf { it!! }

fun estimateMealResponses(meals: List<MealRow>): List<MealGlycaemicEstimate> = groupMeals(meals).map { g ->
    val carbs = g.map { it.carbsG }.sumKnown()
    val fibre = g.map { it.fiberG }.sumKnown()
    val sugar = g.map { it.sugarG }.sumKnown()
    val protein = g.map { it.proteinG }.sumKnown()
    val fat = g.map { it.fatG }.sumKnown()
    val missing = buildList {
        if (carbs == null) add("carbs")
        if (fibre == null) add("fibre")
        if (sugar == null) add("sugar")
        if (protein == null) add("protein")
        if (fat == null) add("fat")
    }
    val description = g.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } }.joinToString(", ").ifBlank { "Meal" }
    if (carbs == null) {
        return@map MealGlycaemicEstimate(g.first().loggedAt, g.size, description, null, null, null, ConfidenceLevel.Low, emptyList(), missing)
    }
    val available = (carbs - (fibre ?: 0.0)).coerceAtLeast(0.0)
    val sugarShare = if (sugar != null && carbs > 0) (sugar / carbs).coerceIn(0.0, 1.0) else 0.0
    val buffer = 1.0 -
        (protein?.let { PROTEIN_SLOWS_BY * (it / PROTEIN_FULL_G).coerceAtMost(1.0) } ?: 0.0) -
        (fat?.let { FAT_SLOWS_BY * (it / FAT_FULL_G).coerceAtMost(1.0) } ?: 0.0) -
        (fibre?.let { FIBRE_SLOWS_BY * (it / FIBRE_FULL_G).coerceAtMost(1.0) } ?: 0.0)
    val score = available * (1.0 + SUGAR_SPEEDS_BY * sugarShare) * buffer
    val band = when {
        score <= GL_LOW_MAX -> GlycaemicBand.Low
        score <= GL_MODERATE_MAX -> GlycaemicBand.Moderate
        else -> GlycaemicBand.High
    }
    val drivers = buildList {
        add("%.0f g available carbs".format(available))
        if (sugar != null && sugarShare >= 0.4) add("%.0f g sugar speeds it".format(sugar))
        if (protein != null && protein >= 10) add("%.0f g protein slows it".format(protein))
        if (fat != null && fat >= 10) add("%.0f g fat slows it".format(fat))
        if (fibre != null && fibre >= 3) add("%.0f g fibre slows it".format(fibre))
    }
    MealGlycaemicEstimate(
        g.first().loggedAt, g.size, description, carbs, score, band,
        // Medium only when every input was logged; the model is a heuristic, so never High.
        if (missing.isEmpty()) ConfidenceLevel.Medium else ConfidenceLevel.Low,
        drivers, missing,
    )
}
