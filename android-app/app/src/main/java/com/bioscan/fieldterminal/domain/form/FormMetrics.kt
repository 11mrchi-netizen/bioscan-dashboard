package com.bioscan.fieldterminal.domain.form

import com.bioscan.fieldterminal.domain.interquartileRange
import com.bioscan.fieldterminal.domain.mean
import com.bioscan.fieldterminal.domain.median
import com.bioscan.fieldterminal.domain.populationStdDev
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

// Side-view metrics (docs/running-form/README.md, "What is measured"). Angles are in the picture plane, with forward the
// direction of running and up the top of the picture. Lengths have no scale in one video, so they are shares of the
// runner's leg length.

/**
 * Frame rate needed for each metric (below it the metric is left out and the report says why). Anything read at the
 * moment of landing or toe-off needs 50 fps: at 30 fps a landing is placed half a frame late and the knee, which bends fast
 * just after landing, reads about 8° high on the synthetic runner (3° at 60 fps, 2° at 120).
 */
const val MIN_FPS_ANGLES = 25.0
const val MIN_FPS_FOOT = 50.0
const val MIN_FPS_CONTACT = 100.0

/** Foot strike from the foot's angle at landing, Altman & Davis (2012). */
enum class FootStrike(val label: String) { REARFOOT("REARFOOT"), MIDFOOT("MIDFOOT"), FOREFOOT("FOREFOOT") }
const val REARFOOT_ABOVE_DEG = 8.1
const val FOREFOOT_BELOW_DEG = -1.6

fun footStrikeOf(angleDeg: Double): FootStrike = when {
    angleDeg > REARFOOT_ABOVE_DEG -> FootStrike.REARFOOT
    angleDeg < FOREFOOT_BELOW_DEG -> FootStrike.FOREFOOT
    else -> FootStrike.MIDFOOT
}

/** One contact of the measured foot. */
data class StepMetrics(
    val landingMs: Double,
    val contactMs: Double?,
    val kneeFlexionAtLandingDeg: Double?,
    val peakKneeFlexionDeg: Double?,
    val shinAngleAtLandingDeg: Double?,
    val ankleAheadOfHip: Double?,
    val footStrikeAngleDeg: Double?,
    val hipExtensionAtToeOffDeg: Double?,
)

/** The middle value over the clip's steps, their spread (interquartile range) and how many there were. */
data class MetricSummary(val median: Double, val spread: Double, val n: Int)

fun summarise(values: List<Double?>): MetricSummary? {
    val v = values.filterNotNull().filter { !it.isNaN() }
    return if (v.isEmpty()) null else MetricSummary(median(v), interquartileRange(v), v.size)
}

data class FormMetrics(
    val cadenceSpm: Double?,
    val contactTimeMs: MetricSummary?,
    val flightTimeMs: MetricSummary?,
    val dutyFactor: MetricSummary?,
    val kneeFlexionAtLanding: MetricSummary?,
    val peakKneeFlexion: MetricSummary?,
    val shinAngleAtLanding: MetricSummary?,
    val ankleAheadOfHip: MetricSummary?,
    val footStrikeAngle: MetricSummary?,
    val footStrike: FootStrike?,
    val hipExtensionAtToeOff: MetricSummary?,
    val trunkLean: MetricSummary?,
    /** Rise and fall of the hips per stride, as a share of leg length. */
    val verticalOscillation: MetricSummary?,
    /** Spread of step times (SD / mean). */
    val stepTimeVariation: Double?,
    val steps: List<StepMetrics>,
)

private const val DEG = 180.0 / Math.PI

private fun ok(vararg v: Double) = v.none { it.isNaN() }

/** Angle at the knee between thigh and shin, as flexion: 0 straight. */
fun kneeFlexionDeg(t: PoseTracks, leg: LegIndices, i: Int): Double {
    val hx = t.x[leg.hip][i]; val hy = t.y[leg.hip][i]
    val kx = t.x[leg.knee][i]; val ky = t.y[leg.knee][i]
    val ax = t.x[leg.ankle][i]; val ay = t.y[leg.ankle][i]
    if (!ok(hx, hy, kx, ky, ax, ay)) return Double.NaN
    val ux = hx - kx; val uy = hy - ky; val vx = ax - kx; val vy = ay - ky
    val c = (ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))
    return 180.0 - acos(c.coerceIn(-1.0, 1.0)) * DEG
}

/** A segment's angle from straight down, positive when its lower end is ahead (shin: ankle ahead of the knee). */
private fun fromVerticalDeg(t: PoseTracks, upper: Int, lower: Int, i: Int, direction: Int): Double {
    val dx = (t.x[lower][i] - t.x[upper][i]) * direction
    val dy = t.y[lower][i] - t.y[upper][i]
    if (!ok(dx, dy)) return Double.NaN
    return atan2(dx, dy) * DEG
}

/** The foot's angle from level, positive with the toe up. */
fun footAngleDeg(t: PoseTracks, leg: LegIndices, i: Int, direction: Int): Double {
    val dx = (t.x[leg.toe][i] - t.x[leg.heel][i]) * direction
    val dy = t.y[leg.heel][i] - t.y[leg.toe][i]
    if (!ok(dx, dy)) return Double.NaN
    return atan2(dy, dx) * DEG
}

/** Trunk lean: hip midpoint to shoulder midpoint, positive leaning forward. */
fun trunkLeanDeg(t: PoseTracks, i: Int, direction: Int): Double {
    val sx = (t.x[Landmark.LEFT_SHOULDER][i] + t.x[Landmark.RIGHT_SHOULDER][i]) / 2
    val sy = (t.y[Landmark.LEFT_SHOULDER][i] + t.y[Landmark.RIGHT_SHOULDER][i]) / 2
    val dx = (sx - hipMidX(t, i)) * direction
    val dy = hipMidY(t, i) - sy
    if (!ok(dx, dy)) return Double.NaN
    return atan2(dx, dy) * DEG
}

/**
 * The clip's metrics. `measured` are the contacts of the foot measured in detail (the near one); `allLandings` are every
 * landing of both feet when the far foot was seen well enough, else null (cadence then comes from the measured foot's
 * strides).
 */
fun computeFormMetrics(
    t: PoseTracks,
    measuredSide: BodySide,
    measured: List<FootContact>,
    allLandings: List<Double>?,
    direction: Int,
): FormMetrics {
    val leg = legOf(measuredSide)
    val angles = t.fps >= MIN_FPS_ANGLES
    val foot = t.fps >= MIN_FPS_FOOT
    val timing = t.fps >= MIN_FPS_CONTACT
    val strideMs = measured.zipWithNext { a, b -> b.landingMs - a.landingMs }

    // Angles at landing and toe-off are read at the interpolated moment, not the nearest frame: the knee bends quickly
    // after landing, a frame later reads several degrees more at 30 fps.
    fun at(ms: Double, f: (Int) -> Double): Double = valueAt(t.timesMs, ms, f)
    val steps = measured.map { c ->
        val land = c.landingMs
        val stance = c.landingIndex..c.toeOffIndex
        // The foot is flat in the middle third of the contact; its angle there is the zero foot strike is read against.
        val third = stance.count() / 3
        val flatAngles = (stance.first + third..stance.last - third).map { footAngleDeg(t, leg, it, direction) }.filter { !it.isNaN() }
        val strike = if (foot && flatAngles.isNotEmpty()) at(land) { footAngleDeg(t, leg, it, direction) } - median(flatAngles) else null
        StepMetrics(
            landingMs = c.landingMs,
            contactMs = if (timing) c.contactMs else null,
            kneeFlexionAtLandingDeg = if (foot) at(land) { kneeFlexionDeg(t, leg, it) }.takeIf { !it.isNaN() } else null,
            peakKneeFlexionDeg = if (angles) stance.map { kneeFlexionDeg(t, leg, it) }.filter { !it.isNaN() }.maxOrNull() else null,
            shinAngleAtLandingDeg = if (foot) at(land) { fromVerticalDeg(t, leg.knee, leg.ankle, it, direction) }.takeIf { !it.isNaN() } else null,
            ankleAheadOfHip = if (foot && t.legLengthPx > 0) at(land) { (t.x[leg.ankle][it] - hipMidX(t, it)) * direction / t.legLengthPx }.takeIf { !it.isNaN() } else null,
            footStrikeAngleDeg = strike?.takeIf { !it.isNaN() },
            // Thigh behind vertical at toe-off: hip to knee, positive with the knee behind the hip.
            hipExtensionAtToeOffDeg = if (foot) (-at(c.toeOffMs) { fromVerticalDeg(t, leg.hip, leg.knee, it, direction) }).takeIf { !it.isNaN() } else null,
        )
    }

    val stepIntervals = allLandings?.sorted()?.zipWithNext { a, b -> b - a }
    val cadence = when {
        !stepIntervals.isNullOrEmpty() -> 60_000.0 / median(stepIntervals)
        strideMs.isNotEmpty() -> 120_000.0 / median(strideMs)
        else -> null
    }
    val stepMs = if (!stepIntervals.isNullOrEmpty()) median(stepIntervals) else strideMs.takeIf { it.isNotEmpty() }?.let { median(it) / 2 }
    val flight = if (timing && stepMs != null) measured.map { stepMs - it.contactMs } else emptyList()
    val duty = if (timing && strideMs.isNotEmpty()) measured.map { it.contactMs / median(strideMs) } else emptyList()

    // Hips' rise and fall over each stride of the measured foot.
    val oscillation = if (angles && t.legLengthPx > 0) measured.zipWithNext { a, b ->
        val ys = (a.landingIndex..b.landingIndex).map { hipMidY(t, it) }.filter { !it.isNaN() }
        if (ys.size < 3) null else (ys.max() - ys.min()) / t.legLengthPx
    } else emptyList()

    val lean = if (angles) (0 until t.size).filter { t.detected[it] }.map { trunkLeanDeg(t, it, direction) }.filter { !it.isNaN() } else emptyList()
    val variationSource = stepIntervals ?: strideMs
    val variation = if (variationSource.size >= 3 && mean(variationSource) > 0) populationStdDev(variationSource) / mean(variationSource) else null

    val strikeSummary = summarise(steps.map { it.footStrikeAngleDeg })
    return FormMetrics(
        cadenceSpm = cadence,
        contactTimeMs = summarise(steps.map { it.contactMs }),
        flightTimeMs = summarise(flight.map { max(0.0, it) }),
        dutyFactor = summarise(duty),
        kneeFlexionAtLanding = summarise(steps.map { it.kneeFlexionAtLandingDeg }),
        peakKneeFlexion = summarise(steps.map { it.peakKneeFlexionDeg }),
        shinAngleAtLanding = summarise(steps.map { it.shinAngleAtLandingDeg }),
        ankleAheadOfHip = summarise(steps.map { it.ankleAheadOfHip }),
        footStrikeAngle = strikeSummary,
        footStrike = strikeSummary?.let { footStrikeOf(it.median) },
        hipExtensionAtToeOff = summarise(steps.map { it.hipExtensionAtToeOffDeg }),
        trunkLean = summarise(lean),
        verticalOscillation = summarise(oscillation),
        stepTimeVariation = variation,
        steps = steps,
    )
}

/** A per-frame value at a time between frames, by linear interpolation; the end frames' values outside the clip. */
fun valueAt(timesMs: DoubleArray, ms: Double, value: (Int) -> Double): Double {
    if (timesMs.isEmpty()) return Double.NaN
    val k = timesMs.indexOfLast { it <= ms }
    if (k < 0) return value(0)
    if (k >= timesMs.lastIndex) return value(timesMs.lastIndex)
    val a = value(k); val b = value(k + 1)
    if (a.isNaN() || b.isNaN()) return if (ms - timesMs[k] <= timesMs[k + 1] - ms) a else b
    val f = (ms - timesMs[k]) / (timesMs[k + 1] - timesMs[k])
    return a + (b - a) * f
}
