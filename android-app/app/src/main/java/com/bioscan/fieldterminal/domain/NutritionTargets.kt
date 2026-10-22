package com.bioscan.fieldterminal.domain

import kotlin.math.abs
import kotlin.math.roundToInt

// Daily calorie and macro targets (Setup › Profile & goals › Nutrition goals). The goal is set the way sports-nutrition
// targets usually are: total calories, protein (grams, or grams per kg of body weight) and fat (grams, or % of
// calories); carbohydrate is what remains. So the macros always add up to the calories (Atwater: 4 kcal/g protein and
// carbohydrate, 9 kcal/g fat) and an incoherent goal (1,200 kcal with 400 g of carbs) cannot be saved. Only the setting
// works this way; day-to-day intake is whatever was logged.
//
// The goal is then read against an estimate of maintenance, the intake that keeps weight stable: resting energy by
// Mifflin-St Jeor (1990) times an activity factor taken from the last four weeks of logged training, or chosen by hand.
// Within 5 % of maintenance reads as maintenance; below, a deficit; above, a surplus, with the weekly weight change it
// implies at the classic ~7,700 kcal per kg (an upper bound: the change slows as the body adapts, Hall 2011).

const val KCAL_PER_G_PROTEIN = 4.0
const val KCAL_PER_G_CARBS = 4.0
const val KCAL_PER_G_FAT = 9.0

enum class ProteinBasis(val key: String, val label: String) {
    GRAMS("g", "G"),
    PER_KG("g_per_kg", "G / KG"),
    ;

    companion object {
        fun of(key: String?): ProteinBasis = entries.firstOrNull { it.key == key } ?: GRAMS
    }
}

enum class FatBasis(val key: String, val label: String) {
    GRAMS("g", "G"),
    PERCENT("pct", "% KCAL"),
    ;

    companion object {
        fun of(key: String?): FatBasis = entries.firstOrNull { it.key == key } ?: GRAMS
    }
}

/** A coherent goal: the macros' calories add up to [kcal]. */
data class MacroPlan(val kcal: Double, val proteinG: Double, val carbsG: Double, val fatG: Double) {
    val proteinKcal: Double get() = proteinG * KCAL_PER_G_PROTEIN
    val carbsKcal: Double get() = carbsG * KCAL_PER_G_CARBS
    val fatKcal: Double get() = fatG * KCAL_PER_G_FAT

    /** Shares of the calories, in percent, rounded so they add up to 100. */
    fun sharesPercent(): Triple<Int, Int, Int> {
        val p = (proteinKcal / kcal * 100).roundToInt()
        val f = (fatKcal / kcal * 100).roundToInt()
        return Triple(p, 100 - p - f, f)
    }
}

sealed interface MacroPlanResult {
    /** Nothing entered: no calorie or macro goal. */
    data object Empty : MacroPlanResult
    /** [plan] is null for a calories-only goal. [notes] are things worth knowing, never a reason to block. */
    data class Ok(val kcal: Double, val plan: MacroPlan?, val notes: List<String>) : MacroPlanResult
    data class Invalid(val message: String) : MacroPlanResult
}

/** Grams of protein for an entry in either basis, or null when per-kg is chosen without a body weight. */
fun proteinGrams(value: Double, basis: ProteinBasis, weightKg: Double?): Double? = when (basis) {
    ProteinBasis.GRAMS -> value
    ProteinBasis.PER_KG -> weightKg?.takeIf { it > 0 }?.let { value * it }
}

fun fatGrams(value: Double, basis: FatBasis, kcal: Double): Double = when (basis) {
    FatBasis.GRAMS -> value
    FatBasis.PERCENT -> kcal * value / 100 / KCAL_PER_G_FAT
}

/**
 * The goal from what was entered: calories alone, or calories with protein and fat (carbohydrate the remainder). Blank
 * entries are null. [restingKcal] is the estimated resting energy, for a note when the calories are below it.
 */
fun planMacros(
    kcal: Double?,
    protein: Double?,
    proteinBasis: ProteinBasis,
    fat: Double?,
    fatBasis: FatBasis,
    weightKg: Double?,
    restingKcal: Double? = null,
): MacroPlanResult {
    if (kcal == null) {
        return if (protein == null && fat == null) MacroPlanResult.Empty
        else MacroPlanResult.Invalid("Set the daily calories first: carbohydrate is what the calories leave after protein and fat.")
    }
    if (kcal <= 0) return MacroPlanResult.Invalid("Calories must be above 0.")
    val notes = mutableListOf<String>()
    restingKcal?.takeIf { kcal < it }?.let {
        notes += "Below your estimated resting energy (${it.roundToInt()} kcal): a deficit this deep is hard to sustain and costs lean mass."
    }
    if (protein == null && fat == null) return MacroPlanResult.Ok(kcal, null, notes)
    if (protein == null || fat == null) {
        return MacroPlanResult.Invalid("Set both protein and fat (or neither): carbohydrate is the remainder of the two.")
    }
    if (protein <= 0 || fat <= 0) return MacroPlanResult.Invalid("Protein and fat must be above 0.")
    if (fatBasis == FatBasis.PERCENT && fat >= 100) return MacroPlanResult.Invalid("Fat must be under 100 % of the calories.")
    val proteinG = proteinGrams(protein, proteinBasis, weightKg)
        ?: return MacroPlanResult.Invalid("Protein per kg needs a body weight: log one, or enter protein in grams.")
    val fatG = fatGrams(fat, fatBasis, kcal)
    val leftKcal = kcal - proteinG * KCAL_PER_G_PROTEIN - fatG * KCAL_PER_G_FAT
    if (leftKcal < 0) {
        val used = (proteinG * KCAL_PER_G_PROTEIN + fatG * KCAL_PER_G_FAT).roundToInt()
        return MacroPlanResult.Invalid(
            "Protein and fat alone come to $used kcal, more than the ${kcal.roundToInt()} kcal goal: lower one of them or raise the calories.",
        )
    }
    val plan = MacroPlan(kcal, proteinG, leftKcal / KCAL_PER_G_CARBS, fatG)
    notes += macroNotes(plan, weightKg)
    return MacroPlanResult.Ok(kcal, plan, notes)
}

// Reference ranges, quoted as context rather than enforced.
private fun macroNotes(plan: MacroPlan, weightKg: Double?): List<String> {
    val notes = mutableListOf<String>()
    val (_, _, fatPct) = plan.sharesPercent()
    if (weightKg != null && weightKg > 0) {
        val pPerKg = plan.proteinG / weightKg
        when {
            pPerKg < 0.8 -> notes += "Protein %.1f g/kg is below the adult RDA of 0.8 g/kg.".format(pPerKg)
            pPerKg < 1.4 -> notes += "Protein %.1f g/kg: people who train usually aim for 1.4–2.0 g/kg (ISSN 2017).".format(pPerKg)
            pPerKg > 2.2 -> notes += "Protein %.1f g/kg: muscle gain shows no added benefit beyond about 1.6–2.2 g/kg (Morton 2018).".format(pPerKg)
        }
        val cPerKg = plan.carbsG / weightKg
        if (cPerKg < 3.0) notes += "Carbohydrate %.1f g/kg is under 3 g/kg, the low end for light training (ACSM 2016).".format(cPerKg)
    }
    if (fatPct < 20) notes += "Fat $fatPct % of calories is below the 20–35 % range (Institute of Medicine)."
    if (fatPct > 35) notes += "Fat $fatPct % of calories is above the 20–35 % range (Institute of Medicine)."
    return notes
}

// ---- Maintenance --------------------------------------------------------------------------------------------------

/** Physical activity levels, with the multipliers commonly applied to Mifflin-St Jeor resting energy. */
enum class ActivityLevel(val key: String, val label: String, val factor: Double, val description: String) {
    SEDENTARY("sedentary", "SEDENTARY", 1.2, "little or no training"),
    LIGHT("light", "LIGHT", 1.375, "under 2.5 h of training a week"),
    MODERATE("moderate", "MODERATE", 1.55, "2.5–5 h a week"),
    VERY("very", "VERY ACTIVE", 1.725, "5–7.5 h a week"),
    EXTRA("extra", "EXTRA ACTIVE", 1.9, "over 7.5 h a week"),
    ;

    companion object {
        fun of(key: String?): ActivityLevel? = entries.firstOrNull { it.key == key }

        /** The level that weekly training minutes put a person at (the bands named in [description]). */
        fun fromWeeklyTraining(minutes: Double): ActivityLevel = when {
            minutes < 30 -> SEDENTARY
            minutes < 150 -> LIGHT
            minutes < 300 -> MODERATE
            minutes < 450 -> VERY
            else -> EXTRA
        }
    }
}

/** Mifflin-St Jeor resting energy, kcal/day; null without sex, age, height or weight. */
fun restingEnergyKcal(sex: String?, ageYears: Int?, heightCm: Double?, weightKg: Double?): Double? {
    if (ageYears == null || heightCm == null || weightKg == null || heightCm <= 0 || weightKg <= 0) return null
    val base = 10 * weightKg + 6.25 * heightCm - 5 * ageYears
    return when (sex) {
        "male" -> base + 5
        "female" -> base - 161
        else -> null
    }
}

data class MaintenanceEstimate(
    val restingKcal: Double,
    val level: ActivityLevel,
    /** True when the level came from logged training rather than a choice. */
    val fromTraining: Boolean,
    val weeklyTrainingMin: Double?,
) {
    val kcal: Double get() = restingKcal * level.factor
}

fun maintenanceEstimate(restingKcal: Double?, chosen: ActivityLevel?, weeklyTrainingMin: Double?): MaintenanceEstimate? {
    restingKcal ?: return null
    val level = chosen ?: weeklyTrainingMin?.let { ActivityLevel.fromWeeklyTraining(it) } ?: return null
    return MaintenanceEstimate(restingKcal, level, fromTraining = chosen == null, weeklyTrainingMin = weeklyTrainingMin)
}

enum class EnergyBalanceKind(val label: String) { DEFICIT("DEFICIT"), MAINTENANCE("MAINTENANCE"), SURPLUS("SURPLUS") }

/** Within this share of maintenance a goal reads as maintenance. */
const val MAINTENANCE_BAND = 0.05
/** The classic energy content of a kilogram of body-weight change. */
const val KCAL_PER_KG_WEIGHT = 7700.0

data class EnergyBalance(val kind: EnergyBalanceKind, val diffKcal: Double, val diffShare: Double) {
    /** Implied weight change per week, kg (an upper bound over the long run). */
    val kgPerWeek: Double get() = diffKcal * 7 / KCAL_PER_KG_WEIGHT
}

fun energyBalance(goalKcal: Double, maintenanceKcal: Double): EnergyBalance {
    val diff = goalKcal - maintenanceKcal
    val share = diff / maintenanceKcal
    val kind = when {
        abs(share) <= MAINTENANCE_BAND -> EnergyBalanceKind.MAINTENANCE
        diff < 0 -> EnergyBalanceKind.DEFICIT
        else -> EnergyBalanceKind.SURPLUS
    }
    return EnergyBalance(kind, diff, share)
}
