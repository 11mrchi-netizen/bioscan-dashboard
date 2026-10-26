package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

// The monthly body check-in (docs/body-checkin/README.md): grip strength, tape girths and self-measured skinfolds in one
// sitting. Everything here is pure so the reading rules, the error model and the derived numbers are unit-tested.
//
// Measurement rules (ISAK-style landmarks, adapted to measuring oneself):
//  - Tape: a non-stretch tape (a spring-loaded one such as a MyoTape keeps the tension the same), level all round, skin
//    not compressed, trunk sites at the end of a normal breath out. Two readings; a third when they differ by more than
//    0.5 cm. The value is the median.
//  - Skinfolds: right side only (the side one can reach and see). Pinch the fold 1 cm above the mark, put the jaws on
//    the mark, read about 2 seconds after releasing the trigger while still holding the fold. Three readings, rotating
//    through the sites so the fat is not compressed by the reading before; a fourth when the three span more than 2 mm.
//    The value is the median. The same caliper every time (it is recorded).
//  - Same conditions each month: morning, after the toilet, before eating, drinking or training.
//
// Changes are judged against each site's own error: the typical error of a single reading, from the repeats taken at
// every check-in, divided by the root of the readings behind the value, and never below a floor for finding the
// landmark again on another day. A change smaller than 2.77 typical errors (the smallest real difference at 95%) is
// within error, the same for the difference between the left and right side.

enum class MeasureMethod(val key: String, val unit: String) { TAPE("tape", "cm"), CALIPER("caliper", "mm") }

enum class BodySide(val key: String, val label: String) { LEFT("left", "LEFT"), RIGHT("right", "RIGHT"), NONE("none", "") }

enum class BodySite(
    val key: String,
    val label: String,
    val method: MeasureMethod,
    val bilateral: Boolean,
    val range: ClosedFloatingPointRange<Double>,
    val instruction: String,
    val optional: Boolean = false,
) {
    NECK("neck", "Neck", MeasureMethod.TAPE, false, 25.0..60.0,
        "Just below the Adam's apple, tape sloping slightly down to the front. Look straight ahead, shoulders down."),
    SHOULDERS("shoulders", "Shoulders", MeasureMethod.TAPE, false, 70.0..170.0,
        "Around the widest point of the shoulders, over the deltoids, arms hanging relaxed. Check in a mirror that the tape is level, or ask someone to hold it at the back."),
    CHEST("chest", "Chest", MeasureMethod.TAPE, false, 60.0..160.0,
        "Under the armpits, across the nipples, tape level all round. Read at the end of a normal breath out."),
    WAIST_NAVEL("waist_navel", "Waist at the navel", MeasureMethod.TAPE, false, 50.0..180.0,
        "Level with the navel, standing relaxed, belly not held in. Read at the end of a normal breath out."),
    WAIST_MIDPOINT("waist_midpoint", "Waist, mid (WHO)", MeasureMethod.TAPE, false, 50.0..180.0,
        "At the side, find the lowest rib and the top of the hip bone; the tape goes halfway between, level all round. End of a normal breath out. This is the waist the waist-to-height ratio uses."),
    HIPS("hips", "Hips", MeasureMethod.TAPE, false, 60.0..180.0,
        "Around the widest part of the buttocks, feet together, tape level (check in a mirror)."),
    BICEPS_RELAXED("biceps_relaxed", "Upper arm, relaxed", MeasureMethod.TAPE, true, 15.0..60.0,
        "Arm hanging relaxed at the side, halfway between the shoulder tip and the elbow."),
    BICEPS_FLEXED("biceps_flexed", "Upper arm, flexed", MeasureMethod.TAPE, true, 15.0..65.0,
        "Arm raised to the side, elbow bent, biceps fully tensed: around the peak.", optional = true),
    FOREARM("forearm", "Forearm", MeasureMethod.TAPE, true, 15.0..50.0,
        "Widest part just below the elbow, arm relaxed, palm up."),
    THIGH("thigh", "Thigh", MeasureMethod.TAPE, true, 30.0..100.0,
        "Standing, weight on both feet: halfway between the hip crease and the top of the kneecap."),
    CALF("calf", "Calf", MeasureMethod.TAPE, true, 20.0..65.0,
        "Standing, weight on both feet: the widest part of the calf."),
    SF_ABDOMINAL("sf_abdominal", "Abdominal skinfold", MeasureMethod.CALIPER, false, 2.0..60.0,
        "Vertical fold 2 cm to the right of the navel."),
    SF_SUPRAILIAC("sf_suprailiac", "Suprailiac skinfold", MeasureMethod.CALIPER, false, 2.0..60.0,
        "Diagonal fold just above the top of the right hip bone, in line with the front of the armpit, sloping down to the front."),
    SF_THIGH("sf_thigh", "Thigh skinfold", MeasureMethod.CALIPER, false, 2.0..60.0,
        "Sitting, knee bent at a right angle, thigh relaxed: vertical fold on the front of the thigh, halfway between the hip crease and the top of the kneecap."),
    SF_CHEST("sf_chest", "Chest skinfold", MeasureMethod.CALIPER, false, 2.0..60.0,
        "Diagonal fold halfway between the front of the armpit and the nipple. With it, the three-site formula can be worked out.", optional = true),
    ;

    companion object {
        fun of(key: String): BodySite? = entries.firstOrNull { it.key == key }
    }
}

/** One place to measure: a site and, for a limb, a side. Skinfolds are taken on the right. */
data class BodySlot(val site: BodySite, val side: BodySide) {
    val label: String get() = if (side == BodySide.NONE || site.method == MeasureMethod.CALIPER) site.label else "${site.label} · ${side.label.lowercase()}"
}

/**
 * The check-in's order: tape from the neck down (right before left), then the skinfolds. Redesign: the waist is taken once, at the
 * navel; the WHO midpoint stays in [BodySite] only so check-ins already saved with it still read.
 */
val BODY_CHECKIN_SLOTS: List<BodySlot> = BodySite.entries.filter { it != BodySite.WAIST_MIDPOINT }.flatMap { site ->
    when {
        site.method == MeasureMethod.CALIPER -> listOf(BodySlot(site, BodySide.RIGHT))
        site.bilateral -> listOf(BodySlot(site, BodySide.RIGHT), BodySlot(site, BodySide.LEFT))
        else -> listOf(BodySlot(site, BodySide.NONE))
    }
}

const val TAPE_TOLERANCE_CM = 0.5
const val CALIPER_TOLERANCE_MM = 2.0

fun tolerance(method: MeasureMethod): Double = if (method == MeasureMethod.TAPE) TAPE_TOLERANCE_CM else CALIPER_TOLERANCE_MM
fun minReadings(method: MeasureMethod): Int = if (method == MeasureMethod.TAPE) 2 else 3
fun maxReadings(method: MeasureMethod): Int = if (method == MeasureMethod.TAPE) 3 else 4

/** True while this site wants another reading: fewer than the minimum, or the readings still disagree. */
fun needsAnotherReading(method: MeasureMethod, readings: List<Double>): Boolean =
    readings.size < minReadings(method) ||
        (readings.size < maxReadings(method) && (readings.max() - readings.min()) > tolerance(method) + 1e-9)

/** A typed reading, or null when it is not a number in the site's plausible range. */
fun parseReading(site: BodySite, text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in site.range }

data class BodyMeasurement(val site: BodySite, val side: BodySide, val readings: List<Double>, val value: Double = median(readings))

data class BodyCheckinRecord(
    val id: Long,
    val occurredAt: LocalDateTime,
    val fasted: Boolean? = null,
    val beforeTraining: Boolean? = null,
    val caliper: String? = null,
    val notes: String? = null,
    val measurements: List<BodyMeasurement> = emptyList(),
    /** Grip in kg by hand ("left"/"right") taken during this check-in. */
    val grip: Map<String, Double> = emptyMap(),
) {
    val date: LocalDate get() = occurredAt.toLocalDate()
    fun value(site: BodySite, side: BodySide = BodySide.NONE): Double? =
        measurements.firstOrNull { it.site == site && it.side == side }?.value
}

// ---- Cadence -----------------------------------------------------------------------------------------------------

const val BODY_CHECKIN_INTERVAL_DAYS = 28L

fun bodyCheckinDueOn(last: LocalDate?): LocalDate? = last?.plusDays(BODY_CHECKIN_INTERVAL_DAYS)

/** The reminder fires only once four weeks have passed since the last check-in (or before the first one). */
fun bodyCheckinNeeded(last: LocalDate?, today: LocalDate): Boolean = last == null || !today.isBefore(bodyCheckinDueOn(last))

// ---- Error model -------------------------------------------------------------------------------------------------

/** Floor on a value's typical error: finding the landmark again on another day, which repeats in one sitting miss. */
fun errorFloor(method: MeasureMethod): Double = if (method == MeasureMethod.TAPE) 0.5 else 1.0

/** The smallest real difference at 95%: two values each with this typical error. */
const val REAL_CHANGE_FACTOR = 2.77

/**
 * Typical error of one reading at this site: the pooled within-sitting standard deviation of the repeats across every
 * check-in (both sides together), or null with fewer than two repeated sittings.
 */
fun singleReadingError(history: List<BodyCheckinRecord>, site: BodySite): Double? {
    var ss = 0.0
    var df = 0
    var sittings = 0
    history.forEach { c ->
        c.measurements.filter { it.site == site && it.readings.size >= 2 }.forEach { m ->
            val mean = m.readings.average()
            ss += m.readings.sumOf { (it - mean) * (it - mean) }
            df += m.readings.size - 1
            sittings++
        }
    }
    return if (sittings >= 2 && df > 0) sqrt(ss / df) else null
}

/** Typical error of a site's value (a median of a few readings), never below the method's floor. */
fun valueError(history: List<BodyCheckinRecord>, site: BodySite): Double {
    val single = singleReadingError(history, site)
    val readings = minReadings(site.method)
    return maxOf(single?.let { it / sqrt(readings.toDouble()) } ?: 0.0, errorFloor(site.method))
}

enum class ChangeVerdict { UP, DOWN, WITHIN_ERROR }

data class SiteChange(val slot: BodySlot, val latest: Double, val previous: Double?, val change: Double?, val smallestReal: Double, val verdict: ChangeVerdict?)

fun judge(change: Double, smallestReal: Double): ChangeVerdict = when {
    change > smallestReal -> ChangeVerdict.UP
    change < -smallestReal -> ChangeVerdict.DOWN
    else -> ChangeVerdict.WITHIN_ERROR
}

/** Each slot measured at the newest check-in, against the newest earlier check-in that measured it. */
fun siteChanges(history: List<BodyCheckinRecord>): List<SiteChange> {
    val sorted = history.sortedBy { it.occurredAt }
    val latest = sorted.lastOrNull() ?: return emptyList()
    return BODY_CHECKIN_SLOTS.mapNotNull { slot ->
        val now = latest.value(slot.site, slot.side) ?: return@mapNotNull null
        val before = sorted.dropLast(1).lastOrNull { it.value(slot.site, slot.side) != null }?.value(slot.site, slot.side)
        val smallest = REAL_CHANGE_FACTOR * valueError(sorted, slot.site)
        val change = before?.let { now - it }
        SiteChange(slot, now, before, change, smallest, change?.let { judge(it, smallest) })
    }
}

data class Asymmetry(val site: BodySite, val right: Double, val left: Double, val difference: Double, val beyondError: Boolean)

/** Right minus left for each limb girth of the newest check-in, judged against the site's error. */
fun asymmetries(history: List<BodyCheckinRecord>): List<Asymmetry> {
    val latest = history.maxByOrNull { it.occurredAt } ?: return emptyList()
    return BodySite.entries.filter { it.bilateral }.mapNotNull { site ->
        val r = latest.value(site, BodySide.RIGHT) ?: return@mapNotNull null
        val l = latest.value(site, BodySide.LEFT) ?: return@mapNotNull null
        val d = r - l
        Asymmetry(site, r, l, d, abs(d) > REAL_CHANGE_FACTOR * valueError(history, site))
    }
}

// ---- Derived numbers ---------------------------------------------------------------------------------------------

val SKINFOLD_SUM_SITES = listOf(BodySite.SF_ABDOMINAL, BodySite.SF_SUPRAILIAC, BodySite.SF_THIGH)

/** The waist the ratios use: the navel, else (older check-ins) the WHO midpoint. */
fun ratioWaist(c: BodyCheckinRecord): Double? = c.value(BodySite.WAIST_NAVEL) ?: c.value(BodySite.WAIST_MIDPOINT)

fun waistToHeight(c: BodyCheckinRecord, heightCm: Double?): Double? =
    ratioWaist(c)?.let { w -> heightCm?.takeIf { it in 100.0..250.0 }?.let { w / it } }

fun waistToHip(c: BodyCheckinRecord): Double? = ratioWaist(c)?.let { w -> c.value(BodySite.HIPS)?.let { w / it } }

/** Sum of the three self-reachable skinfolds (mm): the primary skinfold number, with no population formula in it. */
fun skinfoldSum(c: BodyCheckinRecord): Double? =
    SKINFOLD_SUM_SITES.map { c.value(it, BodySide.RIGHT) ?: return null }.sum()

/**
 * US Navy circumference estimate of body fat (Hodgdon and Beckett 1984, metric form). Men: waist at the navel and neck;
 * women: waist (mid) plus hips minus neck. A population equation: a rough estimate, shown next to the scale's.
 */
fun navyBodyFat(c: BodyCheckinRecord, heightCm: Double?, sex: String?): Double? {
    val h = heightCm?.takeIf { it in 100.0..250.0 } ?: return null
    val neck = c.value(BodySite.NECK) ?: return null
    val pct = when (sex) {
        "male" -> {
            val waist = c.value(BodySite.WAIST_NAVEL) ?: return null
            if (waist - neck <= 0) return null
            495.0 / (1.0324 - 0.19077 * log10(waist - neck) + 0.15456 * log10(h)) - 450.0
        }
        "female" -> {
            val waist = ratioWaist(c) ?: return null
            val hips = c.value(BodySite.HIPS) ?: return null
            if (waist + hips - neck <= 0) return null
            495.0 / (1.29579 - 0.35004 * log10(waist + hips - neck) + 0.22100 * log10(h)) - 450.0
        }
        else -> return null
    }
    return pct.takeIf { it in 2.0..60.0 }
}

/**
 * Jackson and Pollock (1978) three-site estimate for men (chest, abdominal, thigh skinfolds and age), body density
 * turned into body fat with Siri's equation. Only for men who measured the chest fold; the women's three-site equation
 * needs the triceps, which one cannot pinch on oneself.
 */
fun jacksonPollockBodyFat(c: BodyCheckinRecord, ageYears: Int?, sex: String?): Double? {
    if (sex != "male" || ageYears == null) return null
    val s = listOf(BodySite.SF_CHEST, BodySite.SF_ABDOMINAL, BodySite.SF_THIGH).map { c.value(it, BodySide.RIGHT) ?: return null }.sum()
    val density = 1.10938 - 0.0008267 * s + 0.0000016 * s * s - 0.0002574 * ageYears
    return (495.0 / density - 450.0).takeIf { it in 2.0..60.0 }
}

/** Typical error of the skinfold sum: the sites' errors added in quadrature. */
fun skinfoldSumError(history: List<BodyCheckinRecord>): Double =
    sqrt(SKINFOLD_SUM_SITES.sumOf { valueError(history, it).let { e -> e * e } })

/** [caliperChanged]: the two sums were taken with different calipers, so their difference is not judged. */
data class SumChange(
    val latest: Double,
    val previous: Double?,
    val change: Double?,
    val smallestReal: Double,
    val verdict: ChangeVerdict?,
    val caliperChanged: Boolean = false,
)

private fun sameCaliper(a: String?, b: String?): Boolean {
    val x = a?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val y = b?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return x == null || y == null || x == y
}

fun skinfoldSumChange(history: List<BodyCheckinRecord>): SumChange? {
    val sorted = history.sortedBy { it.occurredAt }
    val latest = sorted.lastOrNull() ?: return null
    val now = skinfoldSum(latest) ?: return null
    val prior = sorted.dropLast(1).lastOrNull { skinfoldSum(it) != null }
    val before = prior?.let { skinfoldSum(it) }
    val smallest = REAL_CHANGE_FACTOR * skinfoldSumError(sorted)
    val change = before?.let { now - it }
    val changed = prior != null && !sameCaliper(prior.caliper, latest.caliper)
    return SumChange(now, before, change, smallest, if (changed) null else change?.let { judge(it, smallest) }, changed)
}

// ---- The sitting's order -----------------------------------------------------------------------------------------

/** The slots of this sitting: every site, minus the optional ones not chosen and the sections left out. */
fun activeSlots(tape: Boolean, calipers: Boolean, flexedArms: Boolean, chestSkinfold: Boolean): List<BodySlot> =
    BODY_CHECKIN_SLOTS.filter { slot ->
        when (slot.site.method) {
            MeasureMethod.TAPE -> tape && (slot.site != BodySite.BICEPS_FLEXED || flexedArms)
            MeasureMethod.CALIPER -> calipers && (slot.site != BodySite.SF_CHEST || chestSkinfold)
        }
    }

/**
 * The slot to measure after [current], or null when the sitting is done. Tape sites are finished one at a time, in
 * order. Skinfolds are taken in rounds -- one reading at each site, then the next round -- so a fold is never pinched
 * twice in a row; a site leaves the rounds once its readings agree.
 */
fun nextSlot(slots: List<BodySlot>, readings: Map<BodySlot, List<Double>>, skipped: Set<BodySlot>, current: BodySlot?): BodySlot? {
    fun open(s: BodySlot) = s !in skipped && needsAnotherReading(s.site.method, readings[s].orEmpty())
    val tape = slots.filter { it.site.method == MeasureMethod.TAPE }
    val calipers = slots.filter { it.site.method == MeasureMethod.CALIPER }
    if (current == null || current.site.method == MeasureMethod.TAPE) {
        if (current != null && open(current)) return current
        val from = current?.let { tape.indexOf(it) + 1 } ?: 0
        tape.drop(from).firstOrNull { open(it) }?.let { return it }
        // Anything earlier left open (after going back) comes before the skinfolds.
        tape.firstOrNull { open(it) }?.let { return it }
        return calipers.firstOrNull { open(it) }
    }
    val i = calipers.indexOf(current)
    for (k in 1..calipers.size) {
        val s = calipers[(i + k) % calipers.size]
        if (open(s)) return s
    }
    return null
}

/**
 * Body fat blended across methods (scale, tape, skinfolds): at each reading date, the mean of every estimate from the
 * [windowDays] days up to it. Each method is a few points off for one person, so the blend follows the trend, not the level.
 */
fun rollingBodyFat(readings: List<Pair<LocalDate, Double>>, windowDays: Long = 28): List<Pair<LocalDate, Double>> =
    readings.map { it.first }.distinct().sorted().map { d ->
        d to readings.filter { !it.first.isAfter(d) && it.first.isAfter(d.minusDays(windowDays)) }.map { it.second }.average()
    }
