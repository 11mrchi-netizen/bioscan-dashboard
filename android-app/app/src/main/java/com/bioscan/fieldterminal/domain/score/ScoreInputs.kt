@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.InstantSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class)
package com.bioscan.fieldterminal.domain.score

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign

// Scoring system: how one input becomes a 0-150 sub-score for one morning.
//
// Every series the engine reads is keyed by the MORNING its score is published on. Night-time readings (sleep, HRV,
// resting HR) already carry that date; anything about the day before (load, food, gut, the check-in, daytime stress) is
// shifted forward one day when the series are assembled (ScoreAssembly.kt), so the engine itself never shifts dates.

typealias Series = Map<LocalDate, Double>

/** How an input is judged. */
@Serializable
sealed interface InputKind {
    /**
     * Against the person's own recent days: robust z of the value against the median and spread of the [window] days
     * before it. [betterHigher] sets the direction. Changes inside [deadZone] standard deviations count as none.
     */
    @Serializable
    data class Baseline(
        val betterHigher: Boolean,
        val log: Boolean = false,
        val upside: Boolean = true,
        val minSd: Double = 0.0,
        val deadZone: Double = 0.2,
        val window: Int = 60,
        val minHistory: Int = 14,
    ) : InputKind

    /**
     * Against the person's own recent days, where any change from usual is worse (breathing rate, sleep timing):
     * a day at the usual value scores the top of normal, and each standard deviation away costs one.
     */
    @Serializable
    data class Stability(
        val log: Boolean = false,
        val minSd: Double = 0.0,
        val deadZone: Double = 0.3,
        val window: Int = 60,
        val minHistory: Int = 14,
    ) : InputKind

    /** At least [target]: on or above scores the top of normal; each [scale] short costs one standard deviation. */
    @Serializable
    data class AtLeast(val target: Double, val scale: Double) : InputKind

    /** At most [limit]: on or below scores the top of normal; each [scale] over costs one standard deviation. */
    @Serializable
    data class AtMost(val limit: Double, val scale: Double) : InputKind

    /** Inside [lo]..[hi]: inside scores the top of normal; each [scale] outside (either side) costs one. */
    @Serializable
    data class Band(val lo: Double, val hi: Double, val scale: Double) : InputKind
}

@Serializable
data class InputSpec(
    val key: String,
    val label: String,
    val weight: Double,
    val kind: InputKind,
    /** A critical input caps its domain: the domain can't read more than [CRITICAL_HEADROOM] above it. */
    val critical: Boolean = false,
)

/** Where an input's value sits against its reference: shown as ▲ / ● / ▼ (or ✓ / ↓ / ↑ for targets). */
enum class InputDirection { ABOVE, NORMAL, BELOW, ON_TARGET, SHORT, OVER }

@Serializable
data class InputScore(
    val spec: InputSpec,
    val value: Double,
    /** The reference the value was judged against: the baseline median, or the target / limit / band edge. */
    val reference: Double?,
    /** Baseline kinds: the robust spread used. */
    val spread: Double?,
    val z: Double,
    val score: Double,
    val direction: InputDirection,
)

const val CRITICAL_HEADROOM = 25.0
private const val ROBUST_SD_PER_MAD = 1.4826

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/** Median and robust SD of the [window] days before [day] (not including it), or null below [minHistory] readings. */
internal fun baselineOf(series: Series, day: LocalDate, window: Int, minHistory: Int, log: Boolean, minSd: Double): Pair<Double, Double>? {
    val prior = (1..window).mapNotNull { series[day.minusDays(it.toLong())] }
        .let { v -> if (log) v.filter { it > 0 }.map { ln(it) } else v }
    if (prior.size < minHistory) return null
    val med = median(prior)
    val mad = median(prior.map { abs(it - med) })
    val sd = max(ROBUST_SD_PER_MAD * mad, max(minSd, 1e-9))
    return med to sd
}

private fun softDeadZone(z: Double, deadZone: Double): Double = sign(z) * max(0.0, abs(z) - deadZone)

/** One input's sub-score for [day], or null when its value or its baseline is missing. */
fun scoreInput(spec: InputSpec, series: Series, day: LocalDate): InputScore? {
    val value = series[day] ?: return null
    if (!value.isFinite()) return null
    val top = ScoreScale.Z_TOP_OF_NORMAL
    return when (val kind = spec.kind) {
        is InputKind.Baseline -> {
            if (kind.log && value <= 0) return null
            val (med, sd) = baselineOf(series, day, kind.window, kind.minHistory, kind.log, kind.minSd) ?: return null
            val x = if (kind.log) ln(value) else value
            val raw = (x - med) / sd
            val z = softDeadZone(if (kind.betterHigher) raw else -raw, kind.deadZone)
            val direction = when {
                softDeadZone(raw, kind.deadZone) > 0 -> InputDirection.ABOVE
                softDeadZone(raw, kind.deadZone) < 0 -> InputDirection.BELOW
                else -> InputDirection.NORMAL
            }
            val reference = if (kind.log) exp(med) else med
            InputScore(spec, value, reference, sd, z, ScoreScale.fromZ(z, kind.upside), direction)
        }
        is InputKind.Stability -> {
            if (kind.log && value <= 0) return null
            val (med, sd) = baselineOf(series, day, kind.window, kind.minHistory, kind.log, kind.minSd) ?: return null
            val x = if (kind.log) ln(value) else value
            val raw = (x - med) / sd
            val off = max(0.0, abs(raw) - kind.deadZone)
            val direction = when {
                off == 0.0 -> InputDirection.NORMAL
                raw > 0 -> InputDirection.ABOVE
                else -> InputDirection.BELOW
            }
            val reference = if (kind.log) exp(med) else med
            InputScore(spec, value, reference, sd, top - off, ScoreScale.fromZ(top - off, upside = false), direction)
        }
        is InputKind.AtLeast -> {
            val short = max(0.0, (kind.target - value) / kind.scale)
            InputScore(spec, value, kind.target, null, top - short, ScoreScale.fromZ(top - short, upside = false),
                if (short > 0) InputDirection.SHORT else InputDirection.ON_TARGET)
        }
        is InputKind.AtMost -> {
            val over = max(0.0, (value - kind.limit) / kind.scale)
            InputScore(spec, value, kind.limit, null, top - over, ScoreScale.fromZ(top - over, upside = false),
                if (over > 0) InputDirection.OVER else InputDirection.ON_TARGET)
        }
        is InputKind.Band -> {
            val outside = when {
                value < kind.lo -> (kind.lo - value) / kind.scale
                value > kind.hi -> (value - kind.hi) / kind.scale
                else -> 0.0
            }
            val direction = when {
                value < kind.lo -> InputDirection.SHORT
                value > kind.hi -> InputDirection.OVER
                else -> InputDirection.ON_TARGET
            }
            val reference = if (value < kind.lo) kind.lo else if (value > kind.hi) kind.hi else null
            InputScore(spec, value, reference, null, top - outside, ScoreScale.fromZ(top - outside, upside = false), direction)
        }
    }
}
