package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// How much of a substance is in the body through a day, estimated from when it was logged (TODAY's IN THE BODY card;
// the web Today's Substances.tsx and console/src/lib/substanceCurves.ts are the same model). Times are minutes from the
// local midnight that starts the day shown; a dose the evening before is negative.
//
// CAFFEINE: one compartment, first-order absorption (ka 4 per hour: the peak comes about 52 minutes after the dose) and
//   elimination (half-life 5 h: the textbook mean for healthy adults, range about 3 to 7 h, shorter with smoking, longer
//   with the pill or in pregnancy). The bedtime line is 50 mg, about half a cup of coffee: a working line, not a measured
//   one (400 mg 6 h before bed cost about an hour of sleep in Drake 2013).
// ALCOHOL: grams absorbed from the stomach (ka 2 per hour), cleared at a constant 7 g an hour (about 0.015 % blood alcohol
//   an hour in a 75 kg man, Jones 2010), so the curve falls as a straight line.
// SUPPLEMENTS: only the fast ones (melatonin, glycine, L-theanine, tyrosine) have an intraday curve; their half-lives are
//   rounded from small studies and are rougher than caffeine's. Magnesium, zinc, vitamin D, omega-3 and creatine do not
//   move within a day, so they are left out on purpose.
object SubstanceCurves {
    const val CAFFEINE_HALF_LIFE_H = 5.0
    const val CAFFEINE_KA_PER_H = 4.0
    const val CAFFEINE_BEDTIME_MG = 50.0
    const val ALCOHOL_KA_PER_H = 2.0
    const val ALCOHOL_CLEARANCE_G_PER_H = 7.0

    data class Dose(val atMin: Double, val amount: Double)
    data class Point(val t: Double, val v: Double)
    data class Compound(val key: String, val label: String, val unit: String, val halfLifeH: Double, val kaPerH: Double)

    val COMPOUNDS = listOf(
        Compound("melatonin", "MELATONIN", "mg", 0.75, 4.0),
        Compound("glycine", "GLYCINE", "g", 1.0, 3.0),
        Compound("l-theanine", "L-THEANINE", "mg", 1.0, 3.0),
        Compound("tyrosine", "TYROSINE", "mg", 2.5, 3.0),
    )

    /** The compound a logged nutrient key stands for ('theanine' and 'l-theanine' alike), or null. */
    fun compoundFor(nutrient: String): Compound? {
        val k = nutrient.trim().lowercase().replace('_', '-')
        return COMPOUNDS.firstOrNull { it.key == k || (it.key == "l-theanine" && k == "theanine") }
    }

    /** A logged amount in the compound's unit (mcg, mg, g, "Gram(s)"), or null for a unit that is not a mass. */
    fun amountIn(c: Compound, amount: Double, unit: String?): Double? {
        val u = (unit ?: c.unit).trim().lowercase()
        val mg = when {
            u == "mg" -> amount
            u == "mcg" || u == "µg" || u == "ug" -> amount / 1000
            u == "g" || u.startsWith("gram") -> amount * 1000
            else -> return null
        }
        return if (c.unit == "g") mg / 1000 else mg
    }

    // MEDICATIONS (a medication on the regimen with a label half-life, e.g. tadalafil 17.5 h): the same model with the half-life
    // from the FDA label (regimen_references.half_life_hours) and ka 2 per hour, which puts the peak at 2 h, the label's median
    // for tadalafil. Doses back to five half-lives count, so a daily 17.5 h drug shows its carry-over.
    const val MEDICATION_KA_PER_H = 2.0

    /** Days of doses that still matter for a half-life: five half-lives, at least one day. */
    fun lookbackDays(halfLifeH: Double): Long = maxOf(1L, Math.ceil(halfLifeH * 5 / 24).toLong())

    /** A mass in mg (mg, mcg, g), or null for a unit that is not one (tablet, pill). */
    fun massMg(amount: Double, unit: String?): Double? = when (unit?.trim()?.lowercase()) {
        "mg" -> amount
        "mcg", "µg", "ug" -> amount / 1000
        "g" -> amount * 1000
        else -> null
    }

    /** One logged dose in mg: its own mass when logged in one, else the servings taken times the mg in a serving. */
    fun doseMg(doseValue: Double?, doseUnit: String?, servingCount: Double?, mgPerServing: Double?): Double? {
        doseValue?.let { v -> massMg(v, doseUnit)?.let { return it } }
        return if (servingCount != null && mgPerServing != null) servingCount * mgPerServing else null
    }

    /** Amount in the body at minute [t], first-order absorption and elimination. */
    fun oneCompartmentAt(doses: List<Dose>, t: Double, halfLifeH: Double, kaPerH: Double): Double {
        val ke = ln(2.0) / halfLifeH / 60
        val ka = kaPerH / 60
        var sum = 0.0
        for (d in doses) {
            val dt = t - d.atMin
            if (dt <= 0 || d.amount <= 0) continue
            sum += d.amount * (ka / (ka - ke)) * (exp(-ke * dt) - exp(-ka * dt))
        }
        return sum
    }

    fun caffeineAt(doses: List<Dose>, t: Double) = oneCompartmentAt(doses, t, CAFFEINE_HALF_LIFE_H, CAFFEINE_KA_PER_H)

    fun caffeinePeakMin(): Double {
        val ke = ln(2.0) / CAFFEINE_HALF_LIFE_H / 60
        val ka = CAFFEINE_KA_PER_H / 60
        return ln(ka / ke) / (ka - ke)
    }

    /** The first minute at which caffeine is at or below [mg], never before the last dose has peaked; null within two days. */
    fun caffeineBelowAt(doses: List<Dose>, mg: Double, from: Double): Double? {
        val settled = maxOf(from, doses.maxOfOrNull { if (it.amount > 0) it.atMin + caffeinePeakMin() else it.atMin } ?: from)
        var t = settled
        while (t <= settled + 48 * 60) {
            if (caffeineAt(doses, t) <= mg) return t
            t += 5
        }
        return null
    }

    fun series(from: Double, to: Double, step: Double = 5.0, value: (Double) -> Double): List<Point> {
        val out = ArrayList<Point>()
        var t = from
        while (t <= to + 1e-9) { out += Point(t, value(t)); t += step }
        return out
    }

    fun caffeineSeries(doses: List<Dose>, from: Double, to: Double) = series(from, to) { caffeineAt(doses, it) }
    fun compoundSeries(c: Compound, doses: List<Dose>, from: Double, to: Double) = series(from, to) { oneCompartmentAt(doses, it, c.halfLifeH, c.kaPerH) }

    /** Alcohol (g) in the body from [from] to [to] every 5 minutes: absorption from the stomach, constant clearance. */
    fun alcoholSeries(doses: List<Dose>, from: Double, to: Double): List<Point> {
        val ka = ALCOHOL_KA_PER_H / 60
        val clear = ALCOHOL_CLEARANCE_G_PER_H / 60
        val sorted = doses.filter { it.amount > 0 }.sortedBy { it.atMin }
        var t = minOf(sorted.firstOrNull()?.atMin ?: from, from)
        var gut = 0.0
        var body = 0.0
        var next = 0
        val out = ArrayList<Point>()
        while (t <= to + 1e-9) {
            while (next < sorted.size && sorted[next].atMin <= t) gut += sorted[next++].amount
            val absorbed = gut * (1 - exp(-ka))
            gut -= absorbed
            body = maxOf(0.0, body + absorbed - clear)
            if (t >= from && ((t - from).roundToInt() % 5 == 0)) out += Point(t, body)
            t += 1
        }
        return out
    }

    /** Minutes from the local midnight of [day] for a logged wall-clock time; negative the evening before. */
    fun minutesFromDay(loggedAt: String, day: LocalDate): Double? {
        val ldt = WallClock.read(loggedAt) ?: return null
        return ChronoUnit.MINUTES.between(day.atStartOfDay(), ldt).toDouble()
    }

    /**
     * The usual bedtime as minutes after midnight (24:00 to 30:00 for one after midnight): the median of recent nights.
     * `bedtime` is a real instant, read in the night's own zone (sleep_daily.tz_offset_min).
     */
    fun usualBedtimeMin(nights: List<Pair<String?, Int?>>): Double? {
        val mins = nights.mapNotNull { (bedtime, tz) ->
            val at = bedtime?.trim()?.replace(' ', 'T')?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val local = at.withOffsetSameInstant(ZoneOffset.ofTotalSeconds((tz ?: 0) * 60))
            var m = local.hour * 60 + local.minute
            if (m < 12 * 60) m += 1440
            m.toDouble()
        }.sorted()
        if (mins.size < 3) return null
        val mid = mins.size / 2
        return if (mins.size % 2 == 1) mins[mid] else ((mins[mid - 1] + mins[mid]) / 2).let { Math.round(it).toDouble() }
    }

    fun clockLabel(min: Double): String {
        val m = ((min.roundToInt() % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** The next 1, 2 or 5 times a power of ten above [v], so an axis tops out just over the curve. */
    fun niceMax(v: Double): Double {
        if (v <= 0) return 1.0
        val p = 10.0.pow(kotlin.math.floor(log10(v)))
        return (listOf(1.0, 2.0, 5.0, 10.0).firstOrNull { it * p >= v } ?: 10.0) * p
    }
}
