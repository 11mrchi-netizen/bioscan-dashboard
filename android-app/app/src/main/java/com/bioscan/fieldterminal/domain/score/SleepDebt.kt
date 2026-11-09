@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class)
package com.bioscan.fieldterminal.domain.score

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.roundToInt

// Sleep debt as a planning estimate (after NOOP's SleepDebt, docs/scoring-system/README.md "SLEEP"). Each night carries
// 55 % of what was still owed into the next night's target, instead of summing every short hour of the week:
//
//   target(n)  = need + debt(n)
//   debt(n+1)  = 0.55 x max(0, target(n) - slept(n))
//
// under 10 minutes counts as none; meeting the whole target clears it and extra sleep builds no credit. The recurrence
// starts 14 nights back, so older nights fall away. The coefficient is NOOP's, fitted to one person's 853 WHOOP nights;
// controlled restriction studies (Van Dongen 2003; Banks 2010) show sleep loss neither banks nor recovers hour for hour,
// which a bounded, recency-weighted estimate respects better than a plain sum.

const val DEBT_CARRY = 0.55
const val DEBT_DEADBAND_H = 10.0 / 60
const val DEBT_NIGHTS = 14

/** The debt carried into the night after [day] (the night whose sleep row is dated the next morning). */
fun sleepDebtAfter(day: LocalDate, hours: Map<LocalDate, Double>, need: Double): Double? {
    val nights = (DEBT_NIGHTS - 1 downTo 0).map { day.minusDays(it.toLong()) }
    if (nights.count { it in hours } < 4) return null
    var debt = 0.0
    for (n in nights) {
        val slept = hours[n] ?: continue // an unrecorded night carries the debt as it was
        debt = DEBT_CARRY * max(0.0, need + debt - slept)
        if (debt < DEBT_DEADBAND_H) debt = 0.0
    }
    return debt
}

/** Tonight's sleep: what to aim for and when to be in bed for it. */
@Serializable
data class SleepTarget(val needH: Double, val debtH: Double, val targetH: Double, val usualWake: LocalTime?, val bedBy: LocalTime?) {
    /** "7 h 20 + 25 min owed". */
    val words: String get() = hm(needH) + if (debtH > 0) " + ${(debtH * 60).roundToInt()} min owed" else ""
}

private fun hm(h: Double): String { val m = (h * 60).roundToInt(); return "${m / 60} h ${"%02d".format(m % 60)}" }

/** Tonight's target from the morning's debt and the usual wake time of the last 14 mornings (median). */
fun sleepTarget(need: Double, debt: Double, wakeMinutes: List<Int>): SleepTarget {
    val target = need + debt
    val wake = wakeMinutes.takeIf { it.size >= 4 }?.sorted()?.let { it[it.size / 2] }
    val usual = wake?.let { LocalTime.MIDNIGHT.plusMinutes(it.toLong()) }
    // A sleep target is time asleep; about 15 minutes to fall asleep is added to the time in bed.
    val bedBy = usual?.minusMinutes((target * 60).roundToInt().toLong() + 15)
    return SleepTarget(need, debt, target, usual, bedBy)
}
