package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.BandPoint
import com.bioscan.fieldterminal.data.model.ExpectedBandPayload
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

// Analysis review phase C: the phone side of the expected band (supabase/functions/_shared/analysis-core/expectedBand.ts).
// The nightly run publishes, per metric, the band each recent morning was expected to fall in and the band for the
// morning after the run. This places this morning's reading against it, words the result (below, as or above expected,
// by how much, and how often a morning this far out comes when nothing has changed), and keeps the flags the server set.
// A reading that arrived after the run is placed with the published model-space centre and width, and steps the run
// chart once, exactly as the server would have.

enum class BandPosition { Below, Within, Above }
enum class BandFlagKind { Extreme, Run }

// `low` = the flag is on the low side (a low reading, or a run of low readings).
data class BandFlag(val kind: BandFlagKind, val low: Boolean)

data class MorningReadout(
    val metric: String,
    val date: LocalDate,
    val value: Double,
    val unit: String,
    val expected: Double,
    val lo80: Double,
    val hi80: Double,
    val position: BandPosition,
    // Difference from the expected value: a percentage for log-modelled metrics (HRV), units otherwise.
    val difference: Double,
    val differenceIsPercent: Boolean,
    val flag: BandFlag?,
    // One-sided chance of a morning at least this far out when nothing has changed; null inside the 80% band.
    val tailProbability: Double?,
    val provisional: Boolean,
    // "higher" | "lower" | "range"
    val better: String,
    // A logged illness covers the day: shown, never judged.
    val excluded: Boolean,
)

fun bandPointFor(payload: ExpectedBandPayload, day: LocalDate): BandPoint? {
    val key = day.toString()
    return payload.recent.firstOrNull { it.date == key } ?: payload.next?.takeIf { it.date == key }
}

// `latest` is the phone's own newest reading (date, value in natural units); it is used only when the published band
// for `today` has no reading yet.
fun morningReadout(payload: ExpectedBandPayload, today: LocalDate, latest: Pair<LocalDate, Double>?): MorningReadout? {
    if (payload.state == "building") return null
    val point = bandPointFor(payload, today) ?: return null
    val expected = point.expected ?: return null
    val lo80 = point.lo80 ?: return null
    val hi80 = point.hi80 ?: return null
    val lo99 = point.lo99 ?: return null
    val hi99 = point.hi99 ?: return null
    val mu = point.mu ?: return null
    val sigma = point.sigma?.takeIf { it > 0 } ?: return null
    val logScale = payload.transform == "ln"
    val published = point.value
    val value = published ?: latest?.takeIf { it.first == today }?.second ?: return null
    if (logScale && value <= 0) return null
    val toModel = { v: Double -> if (logScale) ln(v) else v }
    val z = (if (published != null) point.z else null) ?: ((toModel(value) - mu) / sigma)

    val flag = when {
        point.excluded -> null
        published != null -> when (point.flag) {
            "extreme" -> BandFlag(BandFlagKind.Extreme, point.side == "low")
            "run" -> BandFlag(BandFlagKind.Run, point.side == "low")
            else -> null
        }
        value < lo99 || value > hi99 -> BandFlag(BandFlagKind.Extreme, value < lo99)
        else -> {
            val ewma = payload.ewma
            val q99 = (toModel(hi99) - mu) / sigma
            val stepped = ewma.lambda * z.coerceIn(-q99, q99) + (1 - ewma.lambda) * ewma.value
            val fresh = ewma.date == null || ewma.date < today.toString()
            if (ewma.runsActive && fresh && abs(stepped) > ewma.limit) BandFlag(BandFlagKind.Run, stepped < 0) else null
        }
    }
    val position = when {
        value < lo80 -> BandPosition.Below
        value > hi80 -> BandPosition.Above
        else -> BandPosition.Within
    }
    val df = payload.params.df.coerceAtLeast(1).toDouble()
    val tail = when (position) {
        BandPosition.Below -> studentTCdf(z, df)
        BandPosition.Above -> 1 - studentTCdf(z, df)
        BandPosition.Within -> null
    }
    return MorningReadout(
        metric = payload.metric,
        date = today,
        value = value,
        unit = payload.unit,
        expected = expected,
        lo80 = lo80,
        hi80 = hi80,
        position = position,
        difference = if (logScale) (value / expected - 1) * 100 else value - expected,
        differenceIsPercent = logScale,
        flag = flag,
        tailProbability = tail,
        provisional = payload.state == "provisional",
        better = payload.better,
        excluded = point.excluded,
    )
}

// The pill. Inside the band reads AS EXPECTED; one morning outside it is ordinary and stays neutral. Only a flag on the
// unfavorable side asks for attention (for sleep duration, a flag that also leaves the 7-9 h range). Optimal is never
// used: one morning is not a change.
fun expectedStateWord(r: MorningReadout): StateWord {
    val flag = r.flag
    val word = when {
        r.excluded -> "ILLNESS LOGGED"
        flag?.kind == BandFlagKind.Extreme -> if (flag.low) "UNUSUALLY LOW" else "UNUSUALLY HIGH"
        flag?.kind == BandFlagKind.Run -> if (flag.low) "LOW LATELY" else "HIGH LATELY"
        r.position == BandPosition.Below -> "BELOW EXPECTED"
        r.position == BandPosition.Above -> "ABOVE EXPECTED"
        else -> "AS EXPECTED"
    }
    val unfavorable = flag != null && !r.excluded && when (r.better) {
        "higher" -> flag.low
        "lower" -> !flag.low
        else -> r.metric == "sleep_hours" && r.value !in SLEEP_HOURS_RANGE
    }
    return StateWord(if (unfavorable) MetricState.Warning else MetricState.Neutral, word)
}

private fun formatValue(v: Double, unit: String): String = when (unit) {
    "h" -> "%.1f h".format(v)
    else -> "%.0f %s".format(v, unit)
}

// "41 ms, 9% below what was expected for a Tuesday."
fun MorningReadout.headline(): String {
    val day = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val v = formatValue(value, unit)
    val sleep = metric == "sleep_hours"
    if (position == BandPosition.Within) return "$v, as expected for a $day."
    val amount = if (differenceIsPercent) "%.0f%%".format(abs(difference)) else formatValue(abs(difference), unit)
    val side = when {
        sleep && position == BandPosition.Below -> "shorter than"
        sleep -> "longer than"
        position == BandPosition.Below -> "below"
        else -> "above"
    }
    return "$v, $amount $side what was expected for a $day."
}

// Every interval says what it means (contract: labelled intervals).
fun MorningReadout.bandLine(): String {
    val range = if (unit == "h") "%.1f–%.1f h".format(lo80, hi80) else "%.0f–%.0f %s".format(lo80, hi80, unit)
    return "Expected $range: 8 mornings in 10 fall inside." + if (provisional) " Provisional while the band learns." else ""
}

// "A morning this low comes about once a month when nothing has changed." Null inside the band.
fun MorningReadout.rarityLine(): String? {
    val p = tailProbability ?: return null
    val freq = naturalFrequency(p) ?: return null
    val what = when {
        metric == "sleep_hours" && position == BandPosition.Below -> "A night this short"
        metric == "sleep_hours" -> "A night this long"
        position == BandPosition.Below -> "A morning this low"
        else -> "A morning this high"
    }
    return "$what comes $freq when nothing has changed."
}

fun MorningReadout.flagLine(): String? {
    val f = flag ?: return null
    if (excluded) return null
    return when (f.kind) {
        BandFlagKind.Extreme -> "Flagged: beyond the band 99 mornings in 100 fall inside."
        BandFlagKind.Run -> "Flagged: several " + (if (f.low) "low" else "high") + " mornings in a row, more than day-to-day noise usually adds up to."
    }
}

// Rarity as a natural frequency, which people read more accurately than percentages or percentiles. Only for a reading
// outside the 80% band (p <= 0.1 one-sided; a little above for rounding).
fun naturalFrequency(p: Double): String? {
    if (!(p > 0) || p > 0.12) return null
    val n = 1 / p
    return when {
        n < 13.5 -> "about once every ${n.roundToInt()} mornings"
        n < 24.5 -> "about once every ${(n / 7).roundToInt()} weeks"
        n < 45 -> "about once a month"
        n < 75 -> "about once every two months"
        n < 330 -> "about once every ${(n / 30.4).roundToInt().coerceAtLeast(3)} months"
        else -> "less than once a year"
    }
}

// Status hero and Today header: which metric leads. A flagged unfavorable metric first, then HRV.
fun leadReadout(readouts: List<MorningReadout>): MorningReadout? =
    readouts.firstOrNull { expectedStateWord(it).state == MetricState.Warning } ?: readouts.firstOrNull { it.metric == "hrv" } ?: readouts.firstOrNull()

// ---- Student t distribution (regularised incomplete beta; Numerical Recipes, as in the server's stats.ts) ----

private fun lnGamma(z: Double): Double {
    val c = doubleArrayOf(76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5)
    var y = z
    var tmp = z + 5.5
    tmp -= (z + 0.5) * ln(tmp)
    var ser = 1.000000000190015
    for (j in 0 until 6) {
        y += 1
        ser += c[j] / y
    }
    return -tmp + ln(2.5066282746310005 * ser / z)
}

private fun betaContinuedFraction(a: Double, b: Double, x: Double): Double {
    val tiny = 1e-300
    val qab = a + b
    val qap = a + 1
    val qam = a - 1
    var c = 1.0
    var d = 1 - qab * x / qap
    if (abs(d) < tiny) d = tiny
    d = 1 / d
    var h = d
    for (m in 1 until 300) {
        val m2 = 2 * m
        var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1 + aa * d
        if (abs(d) < tiny) d = tiny
        c = 1 + aa / c
        if (abs(c) < tiny) c = tiny
        d = 1 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1 + aa * d
        if (abs(d) < tiny) d = tiny
        c = 1 + aa / c
        if (abs(c) < tiny) c = tiny
        d = 1 / d
        val del = d * c
        h *= del
        if (abs(del - 1) < 3e-15) break
    }
    return h
}

private fun regularizedBeta(a: Double, b: Double, x: Double): Double {
    if (x <= 0) return 0.0
    if (x >= 1) return 1.0
    val bt = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1 - x))
    return if (x < (a + 1) / (a + b + 2)) bt * betaContinuedFraction(a, b, x) / a else 1 - bt * betaContinuedFraction(b, a, 1 - x) / b
}

// P(T <= t) for Student's t with `df` degrees of freedom.
fun studentTCdf(t: Double, df: Double): Double {
    if (!t.isFinite()) return if (t > 0) 1.0 else 0.0
    val tail = 0.5 * regularizedBeta(df / 2, 0.5, df / (df + t * t))
    return if (t >= 0) 1 - tail else tail
}
