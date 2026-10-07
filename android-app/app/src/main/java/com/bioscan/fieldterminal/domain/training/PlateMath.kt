package com.bioscan.fieldterminal.domain.training

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

// DAV-343. Equipment-aware load rounding. Plates are per side and assumed available
// in any quantity (a home or gym rack with a stack of each size). Work is done in
// centi-kg integers so 1.25 kg plates never accumulate floating-point error.

const val KG_PER_LB = 0.45359237

fun lbToKg(lb: Double): Double = lb * KG_PER_LB

fun kgToLb(kg: Double): Double = kg / KG_PER_LB

data class Loadable(val barKg: Double, val platesPerSideKg: List<Double>) {
    val totalKg: Double get() = barKg + 2 * platesPerSideKg.sum()
}

enum class Rounding { Nearest, Down, Up }

private const val CENTI = 100.0

private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

private fun centi(kg: Double): Long = (kg * CENTI).roundToLong()

// Smallest total-weight step the plates allow: twice the greatest common divisor of
// the plate sizes (1.25, 2.5, 5, 10, 15, 20 -> 2.5 kg).
fun loadStepKg(platesKg: List<Double>): Double {
    require(platesKg.isNotEmpty() && platesKg.all { it > 0 }) { "plates must be positive" }
    return 2 * platesKg.map(::centi).reduce(::gcd) / CENTI
}

// The nearest loadable total to `targetKg` (ties go down, so a prescription is never
// silently overloaded), with the fewest plates per side. Below the bare bar the
// answer is the bar alone.
fun loadableFor(targetKg: Double, barKg: Double, platesKg: List<Double>, rounding: Rounding = Rounding.Nearest): Loadable {
    require(platesKg.isNotEmpty() && platesKg.all { it > 0 }) { "plates must be positive" }
    if (targetKg <= barKg) return Loadable(barKg, emptyList())

    val sizes = platesKg.map(::centi).distinct().sortedDescending()
    val unit = sizes.reduce(::gcd)
    val perSideUnits = (targetKg - barKg) / 2 * CENTI / unit
    val lower = floor(perSideUnits + 1e-9).toLong()
    val upper = ceil(perSideUnits - 1e-9).toLong()
    val units = when (rounding) {
        Rounding.Down -> lower
        Rounding.Up -> upper
        Rounding.Nearest -> if (perSideUnits - lower <= upper - perSideUnits) lower else upper
    }
    val plates = fewestPlates(units.toInt(), sizes.map { (it / unit).toInt() })
        .map { it * unit / CENTI }
        .sortedDescending()
    return Loadable(barKg, plates)
}

// Minimum-plate decomposition (coin change). Greedy is not safe here: 15 is not a
// multiple of 10, so largest-first can pick a worse set for some totals.
private fun fewestPlates(units: Int, denominations: List<Int>): List<Int> {
    if (units <= 0) return emptyList()
    val best = IntArray(units + 1) { Int.MAX_VALUE }
    val last = IntArray(units + 1)
    best[0] = 0
    for (u in 1..units) {
        for (d in denominations) {
            if (d <= u && best[u - d] != Int.MAX_VALUE && best[u - d] + 1 < best[u]) {
                best[u] = best[u - d] + 1
                last[u] = d
            }
        }
    }
    check(best[units] != Int.MAX_VALUE) { "unreachable plate total" }
    val out = mutableListOf<Int>()
    var u = units
    while (u > 0) { out += last[u]; u -= last[u] }
    return out
}

// Rounds an added (belt/vest) load to a single-plate step, nearest, ties down.
fun roundToStep(valueKg: Double, stepKg: Double): Double {
    require(stepKg > 0)
    val steps = valueKg / stepKg
    val lower = floor(steps + 1e-9)
    val upper = ceil(steps - 1e-9)
    return (if (steps - lower <= upper - steps) lower else upper) * stepKg
}
