package com.bioscan.fieldterminal.ui.components

// Nutrition bars all read the same way (owner request): the first 70 % of the bar is under target, the next 20 % is the
// target range, the last 10 % is over. A value maps piecewise linearly onto that, so the target band always sits at
// 70-90 % however wide it is.
//   lower and upper: 0 -> 0, lower -> 0.70, upper -> 0.90, upper + half the range's width -> 1.
//   lower only (an RDA with no upper limit): lower -> 0.70, 2 x lower -> 1; the last 30 % is the good range.
//   upper only (an upper limit with no RDA): upper -> 0.90, 1.5 x upper -> 1; below the limit is good.
// Past the end it stays at the end. Null when there is neither bound (no target to zone).
const val ZONE_TARGET_START = 0.70f
const val ZONE_TARGET_END = 0.90f

fun zonedFraction(value: Double, lower: Double?, upper: Double?): Float? {
    val lo = lower?.takeIf { it > 0.0 }
    val hi = upper?.takeIf { it > 0.0 && (lo == null || it > lo) }
    fun seg(v: Double, from: Double, to: Double, f0: Float, f1: Float): Float =
        if (to <= from) f1 else (f0 + (f1 - f0) * ((v - from) / (to - from))).toFloat()
    val v = value.coerceAtLeast(0.0)
    val f = when {
        lo != null && hi != null -> when {
            v <= lo -> seg(v, 0.0, lo, 0f, ZONE_TARGET_START)
            v <= hi -> seg(v, lo, hi, ZONE_TARGET_START, ZONE_TARGET_END)
            else -> seg(v, hi, hi + (hi - lo) / 2, ZONE_TARGET_END, 1f)
        }
        lo != null -> if (v <= lo) seg(v, 0.0, lo, 0f, ZONE_TARGET_START) else seg(v, lo, 2 * lo, ZONE_TARGET_START, 1f)
        hi != null -> if (v <= hi) seg(v, 0.0, hi, 0f, ZONE_TARGET_END) else seg(v, hi, 1.5 * hi, ZONE_TARGET_END, 1f)
        else -> return null
    }
    return f.coerceIn(0f, 1f)
}

// Where the zones sit for these bounds: (target start, target end) as bar fractions.
fun zoneEdges(lower: Double?, upper: Double?): Pair<Float, Float>? {
    val lo = lower?.takeIf { it > 0.0 }
    val hi = upper?.takeIf { it > 0.0 && (lo == null || it > lo) }
    return when {
        lo != null && hi != null -> ZONE_TARGET_START to ZONE_TARGET_END
        lo != null -> ZONE_TARGET_START to 1f
        hi != null -> 0f to ZONE_TARGET_END
        else -> null
    }
}
