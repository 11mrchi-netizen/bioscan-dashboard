package com.bioscan.fieldterminal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor

// Injectables (docs/regimen/README.md, phase 5; migration 20261118_injectables.sql): injection sites with rotation, and
// the open vial -- what it holds, its concentration, how many of the person's doses are left in it and when it should be
// thrown away. The dose is always the person's own (as prescribed); the app never works one out. Syringe units the
// prescriber gave are shown as given and only cross-checked against the vial's mix, with a warning when they disagree.

data class InjectionSite(val key: String, val label: String, val subcutaneous: Boolean, val intramuscular: Boolean)

val INJECTION_SITES: List<InjectionSite> = listOf(
    InjectionSite("abdomen_upper_left", "Belly, upper left", true, false),
    InjectionSite("abdomen_upper_right", "Belly, upper right", true, false),
    InjectionSite("abdomen_lower_left", "Belly, lower left", true, false),
    InjectionSite("abdomen_lower_right", "Belly, lower right", true, false),
    InjectionSite("thigh_left_upper", "Left thigh, upper", true, true),
    InjectionSite("thigh_left_lower", "Left thigh, lower", true, true),
    InjectionSite("thigh_right_upper", "Right thigh, upper", true, true),
    InjectionSite("thigh_right_lower", "Right thigh, lower", true, true),
    InjectionSite("arm_left", "Back of left arm", true, false),
    InjectionSite("arm_right", "Back of right arm", true, false),
    InjectionSite("glute_left", "Left glute", false, true),
    InjectionSite("glute_right", "Right glute", false, true),
    InjectionSite("deltoid_left", "Left shoulder (deltoid)", false, true),
    InjectionSite("deltoid_right", "Right shoulder (deltoid)", false, true),
)

/** Injected: given under the skin or into muscle, or its form is an injection. */
val RegimenSpec.injectable: Boolean
    get() = route == "subcutaneous" || route == "intramuscular" || form == "injection"

/** The sites that suit the item's route; an injection with no route set gets every site. */
fun sitesFor(spec: RegimenSpec): List<InjectionSite> = when (spec.route) {
    "subcutaneous" -> INJECTION_SITES.filter { it.subcutaneous }
    "intramuscular" -> INJECTION_SITES.filter { it.intramuscular }
    else -> INJECTION_SITES
}

fun siteLabel(key: String): String = INJECTION_SITES.firstOrNull { it.key == key }?.label ?: key

/** The site rested longest: one never used first (in list order), else the one whose last use is oldest. */
fun nextSite(sites: List<InjectionSite>, history: List<Pair<String, Instant>>): InjectionSite? {
    if (sites.isEmpty()) return null
    val last = history.groupBy({ it.first }, { it.second }).mapValues { (_, t) -> t.max() }
    return sites.firstOrNull { it.key !in last } ?: sites.minBy { last.getValue(it.key) }
}

data class Vial(
    val amount: Double,
    val amountUnit: String,
    val diluentMl: Double?,
    val volumeMl: Double?,
    val openedOn: LocalDate,
    val discardAfterDays: Int?,
    val spares: Int,
)

data class VialState(
    /** Amount per ml once mixed, in the vial's unit. */
    val perMl: Double,
    val remaining: Double,
    /** Whole doses of [doseAmount] left; null when the dose cannot be put in the vial's unit. */
    val dosesLeft: Int?,
    val discardOn: LocalDate?,
    /** Volume and U-100 syringe units one dose takes from this mix; null when the units cannot be compared. */
    val doseMl: Double?,
    val doseUnits: Double?,
    /** Set when the prescribed units differ from this mix's by more than 5 %. */
    val unitsMismatch: Boolean,
)

/** [amount] in [from] expressed in [to]: mg, mcg and g convert; IU and unit only to themselves. */
fun convertAmount(amount: Double, from: String, to: String): Double? {
    if (from.equals(to, ignoreCase = true)) return amount
    val mg = mapOf("g" to 1000.0, "mg" to 1.0, "mcg" to 0.001)
    val a = mg[from] ?: return null
    val b = mg[to] ?: return null
    return amount * a / b
}

/**
 * The open vial after the doses logged since it was opened ([usedSinceOpened], in the dose's unit). [prescribedUnits]
 * is what the prescriber gave for one dose, if entered.
 */
fun vialState(vial: Vial, doseAmount: Double?, doseUnit: String?, usedSinceOpened: Double, prescribedUnits: Double?): VialState {
    val volume = vial.volumeMl ?: vial.diluentMl!!
    val perMl = vial.amount / volume
    val dose = if (doseAmount != null && doseUnit != null) convertAmount(doseAmount, doseUnit, vial.amountUnit) else null
    val used = if (doseUnit != null) convertAmount(usedSinceOpened, doseUnit, vial.amountUnit) ?: 0.0 else 0.0
    val remaining = (vial.amount - used).coerceAtLeast(0.0)
    val doseMl = dose?.let { it / perMl }
    val units = doseMl?.let { it * 100 }
    return VialState(
        perMl = perMl,
        remaining = remaining,
        dosesLeft = dose?.takeIf { it > 0 }?.let { floor(remaining / it + 1e-9).toInt() },
        discardOn = vial.discardAfterDays?.let { vial.openedOn.plusDays(it.toLong()) },
        doseMl = doseMl,
        doseUnits = units,
        unitsMismatch = prescribedUnits != null && units != null && abs(units - prescribedUnits) > 0.05 * prescribedUnits,
    )
}

/** Days until the vial should be thrown away (negative once past). */
fun daysToDiscard(state: VialState, today: LocalDate): Long? = state.discardOn?.let { ChronoUnit.DAYS.between(today, it) }
