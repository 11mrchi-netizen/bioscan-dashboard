package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.trail.CoursePoint
import com.bioscan.fieldterminal.domain.trail.minettiCostOfTransport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// When the person will be where on a course (docs/fuel-planning/README.md, phase 5), so fuel and fluid can be planned by
// time and by aid station. Deliberately simple and labelled as an estimate (the predictive TRAP model was not built).
//
// Each 100 m of the course costs its distance times the Minetti et al. (2002) energy cost of running at that grade,
// relative to flat ground. Downhill gains are capped: nobody descends faster than 1.25 times their flat pace for long,
// whatever the energy cost says; steep climbs are capped at 5 times the flat time (walking). The planned duration (the
// target time, or the expected one) is spread along the course in proportion to that cost, which gives the elapsed time
// at every point and every aid station.
//
// For a run with no planned duration the person's own history gives one: their recent runs' minutes per km-effort
// (distance plus ascent / 100, the ITRA convention), the median and the interquartile range, scaled up for longer
// efforts with Riegel's exponent (1.06).

private const val DOWNHILL_FLOOR = 0.8
private const val UPHILL_CEILING = 5.0
const val RIEGEL_EXPONENT = 1.06

/** Relative cost of each step of the course (1.0 = flat), aligned with points[1..]. */
fun stepCosts(points: List<CoursePoint>): List<Double> {
    val flat = minettiCostOfTransport(0.0)
    return points.zipWithNext { a, b ->
        val d = b.distanceM - a.distanceM
        val grade = if (d > 0 && a.elevationM != null && b.elevationM != null) ((b.elevationM - a.elevationM) / d).coerceIn(-0.45, 0.45) else 0.0
        (minettiCostOfTransport(grade) / flat).coerceIn(DOWNHILL_FLOOR, UPHILL_CEILING) * max(d, 0.0)
    }
}

/** Elapsed minutes at each course point when the whole course takes [totalMin], spread by cost. */
fun timeAlongCourse(points: List<CoursePoint>, totalMin: Double): List<Double> {
    if (points.size < 2) return points.map { 0.0 }
    val costs = stepCosts(points)
    val total = costs.sum().takeIf { it > 0 } ?: return points.indices.map { it * totalMin / (points.size - 1) }
    var acc = 0.0
    return listOf(0.0) + costs.map { acc += it; acc / total * totalMin }
}

/** Elapsed minutes at [km] along a course, by interpolation; a straight line in distance without a course. */
fun elapsedAtKm(points: List<CoursePoint>, times: List<Double>, km: Double, totalMin: Double, totalKm: Double?): Double {
    if (points.size >= 2 && times.size == points.size) {
        val d = km * 1000
        val i = points.indexOfFirst { it.distanceM >= d }
        if (i <= 0) return if (i == 0) 0.0 else times.last()
        val a = points[i - 1]
        val b = points[i]
        val f = ((d - a.distanceM) / (b.distanceM - a.distanceM)).coerceIn(0.0, 1.0)
        return times[i - 1] + (times[i] - times[i - 1]) * f
    }
    val dist = totalKm?.takeIf { it > 0 } ?: return totalMin
    return min(1.0, km / dist) * totalMin
}

/** Where the person is (km) at [elapsedMin]; null without a course or a distance. */
fun kmAtElapsed(points: List<CoursePoint>, times: List<Double>, elapsedMin: Double, totalMin: Double, totalKm: Double?): Double? {
    if (points.size >= 2 && times.size == points.size) {
        val i = times.indexOfFirst { it >= elapsedMin }
        if (i <= 0) return if (i == 0) 0.0 else points.last().distanceM / 1000
        val f = ((elapsedMin - times[i - 1]) / (times[i] - times[i - 1])).coerceIn(0.0, 1.0)
        return (points[i - 1].distanceM + (points[i].distanceM - points[i - 1].distanceM) * f) / 1000
    }
    return totalKm?.let { min(1.0, elapsedMin / totalMin) * it }
}

/** Elevation (m) at [km] along the course; null without one. */
fun elevationAtKm(points: List<CoursePoint>, km: Double): Double? {
    val d = km * 1000
    val i = points.indexOfFirst { it.distanceM >= d }
    if (i < 0) return points.lastOrNull()?.elevationM
    if (i == 0) return points[0].elevationM
    val a = points[i - 1]
    val b = points[i]
    val ea = a.elevationM ?: return b.elevationM
    val eb = b.elevationM ?: return ea
    return ea + (eb - ea) * ((d - a.distanceM) / (b.distanceM - a.distanceM)).coerceIn(0.0, 1.0)
}

data class RecentRun(val distanceKm: Double, val ascentM: Double, val durationMin: Double)

data class TimeEstimate(val minutes: Double, val lowMinutes: Double, val highMinutes: Double, val basis: String)

/** km-effort (ITRA): distance plus ascent / 100. */
fun kmEffort(distanceKm: Double, ascentM: Double): Double = distanceKm + ascentM / 100.0

/**
 * The person's likely time for [distanceKm] with [ascentM] of climbing, from their recent runs of at least 30 minutes
 * (5 or more): median and interquartile minutes per km-effort, scaled for distance with Riegel from their median run.
 */
fun estimateRunTime(distanceKm: Double, ascentM: Double, runs: List<RecentRun>): TimeEstimate? {
    val usable = runs.filter { it.durationMin >= 30 && it.distanceKm >= 3 }
    if (usable.size < 5) return null
    val paces = usable.map { it.durationMin / kmEffort(it.distanceKm, it.ascentM) }.sorted()
    fun q(p: Double) = paces[((paces.size - 1) * p).toInt()]
    val typicalEffort = usable.map { kmEffort(it.distanceKm, it.ascentM) }.sorted()[usable.size / 2]
    val effort = kmEffort(distanceKm, ascentM)
    val riegel = (effort / typicalEffort).coerceAtLeast(1.0).pow(RIEGEL_EXPONENT - 1)
    return TimeEstimate(
        minutes = q(0.5) * effort * riegel,
        lowMinutes = q(0.25) * effort * riegel,
        highMinutes = q(0.75) * effort * riegel,
        basis = "your last ${usable.size} runs (median %.1f min per km-effort), longer efforts slowed by Riegel's 1.06".format(q(0.5)),
    )
}
