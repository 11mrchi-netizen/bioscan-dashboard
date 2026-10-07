package com.bioscan.fieldterminal.domain.training

import kotlin.math.floor

// DAV-343. A prescription is a rule (80% of the 1RM), never a stored kilogram. The
// resolver turns the rule into an executable target from the inputs it is given and
// says explicitly when an input is missing instead of guessing.

sealed interface LoadSpec {
    data class Pct1rm(val pct: Double) : LoadSpec
    data class PctTm(val pct: Double) : LoadSpec
    data class PctMaxReps(val pct: Double) : LoadSpec
    // "Work up to a comfortable 2-3RM": self-regulated, so there is no target load.
    data class WorkUpRm(val minRm: Int, val maxRm: Int) : LoadSpec
    // An explicit override.
    data class Fixed(val kg: Double) : LoadSpec
    data class Rpe(val rpe: Double) : LoadSpec
    data object Bodyweight : LoadSpec
    data object None : LoadSpec
}

data class PrescriptionItem(
    val slot: String,
    val sets: IntRange,
    val reps: IntRange?,
    val load: LoadSpec,
    val restSeconds: IntRange? = null,
    val optional: Boolean = false,
    // Pull-up style movements: the 1RM includes bodyweight, and loads at or below
    // bodyweight switch to a percentage of maximum reps.
    val weightedCalisthenics: Boolean = false,
)

// What a weighted-calisthenics percentage is a percentage of. The book includes
// bodyweight (TotalLoad); the user's own logs apply it to the added weight only
// (AddedLoad). The engine follows whichever the user chooses, never assumes one.
enum class PercentBase { TotalLoad, AddedLoad }

data class ResolveContext(
    val oneRmKg: Double? = null,
    val trainingMaxKg: Double? = null,
    val maxReps: Int? = null,
    val bodyweightKg: Double? = null,
    val barKg: Double = 20.0,
    val platesKg: List<Double> = listOf(1.25, 2.5, 5.0, 10.0, 15.0, 20.0),
    // Smallest added-weight increment for belts/vests (one plate).
    val addedStepKg: Double = 1.25,
    val weightedBase: PercentBase = PercentBase.TotalLoad,
)

enum class Missing { OneRm, TrainingMax, MaxReps, Bodyweight }

sealed interface ResolvedLoad {
    data class Barbell(val pct: Double, val targetKg: Double, val loadable: Loadable) : ResolvedLoad
    // Weighted calisthenics above bodyweight: the added load, with the total it makes
    // (null when the user's convention never needed bodyweight).
    data class Added(val pct: Double, val totalKg: Double?, val addedKg: Double) : ResolvedLoad
    // Bodyweight reps as a fraction of the user's maximum reps (the prescribed reps are ignored).
    data class MaxRepsFraction(val pct: Double, val reps: Int) : ResolvedLoad
    data class WorkUp(val minRm: Int, val maxRm: Int) : ResolvedLoad
    data class FixedKg(val kg: Double, val loadable: Loadable) : ResolvedLoad
    data class Rpe(val rpe: Double) : ResolvedLoad
    data object Bodyweight : ResolvedLoad
    data object NoLoad : ResolvedLoad
    data class Unresolved(val missing: Missing) : ResolvedLoad
}

private fun roundHalfUp(x: Double): Int = floor(x + 0.5).toInt()

fun resolveLoad(item: PrescriptionItem, ctx: ResolveContext): ResolvedLoad = when (val spec = item.load) {
    is LoadSpec.Pct1rm -> when {
        item.weightedCalisthenics -> resolveWeighted(spec.pct, ctx)
        ctx.oneRmKg == null -> ResolvedLoad.Unresolved(Missing.OneRm)
        else -> barbell(spec.pct, ctx.oneRmKg, ctx)
    }
    is LoadSpec.PctTm -> ctx.trainingMaxKg?.let { barbell(spec.pct, it, ctx) } ?: ResolvedLoad.Unresolved(Missing.TrainingMax)
    is LoadSpec.PctMaxReps -> ctx.maxReps?.let { ResolvedLoad.MaxRepsFraction(spec.pct, roundHalfUp(spec.pct / 100.0 * it)) }
        ?: ResolvedLoad.Unresolved(Missing.MaxReps)
    is LoadSpec.WorkUpRm -> ResolvedLoad.WorkUp(spec.minRm, spec.maxRm)
    is LoadSpec.Fixed -> ResolvedLoad.FixedKg(spec.kg, loadableFor(spec.kg, ctx.barKg, ctx.platesKg))
    is LoadSpec.Rpe -> ResolvedLoad.Rpe(spec.rpe)
    LoadSpec.Bodyweight -> ResolvedLoad.Bodyweight
    LoadSpec.None -> ResolvedLoad.NoLoad
}

private fun barbell(pct: Double, maxKg: Double, ctx: ResolveContext): ResolvedLoad {
    val target = pct / 100.0 * maxKg
    return ResolvedLoad.Barbell(pct, target, loadableFor(target, ctx.barKg, ctx.platesKg))
}

private fun resolveWeighted(pct: Double, ctx: ResolveContext): ResolvedLoad {
    val oneRm = ctx.oneRmKg ?: return ResolvedLoad.Unresolved(Missing.OneRm)
    if (ctx.weightedBase == PercentBase.AddedLoad) {
        val added = roundToStep(pct / 100.0 * oneRm, ctx.addedStepKg)
        return ResolvedLoad.Added(pct, ctx.bodyweightKg?.let { it + added }, added)
    }
    val bodyweight = ctx.bodyweightKg ?: return ResolvedLoad.Unresolved(Missing.Bodyweight)
    val total = pct / 100.0 * oneRm
    if (total <= bodyweight) {
        val maxReps = ctx.maxReps ?: return ResolvedLoad.Unresolved(Missing.MaxReps)
        return ResolvedLoad.MaxRepsFraction(pct, roundHalfUp(pct / 100.0 * maxReps))
    }
    return ResolvedLoad.Added(pct, total, roundToStep(total - bodyweight, ctx.addedStepKg))
}

data class ResolvedItem(val item: PrescriptionItem, val load: ResolvedLoad)

fun resolveItems(items: List<PrescriptionItem>, ctxFor: (slot: String) -> ResolveContext): List<ResolvedItem> =
    items.map { ResolvedItem(it, resolveLoad(it, ctxFor(it.slot))) }
