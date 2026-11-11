package com.bioscan.fieldterminal.domain.stress

import java.time.ZoneId
import kotlin.math.roundToInt

// Zepp's all_day_stress items are UTC days (zepp-extract asks for one per UTC day, and each item's samples run
// 00:00-24:00 UTC, which is 08:00-08:00 here). Filed under the query date, every reading from midnight to 08:00 landed
// on the day before. The samples are real instants, so they are regrouped by the local calendar day in [zone]; a
// sample two items both carry is kept once. Zepp's own daily roll-up describes the UTC day, so each local day's
// average, range and band shares are recomputed from its own samples, with the bands Zepp uses (1-39 relaxed,
// 40-59 normal, 60-79 medium, 80-100 high: recomputing them this way reproduces Zepp's figures to within half a
// point, per ZeppBridge's check). Newest day first, as the repository has always returned them.
fun stressDaysByLocalDate(samples: List<PhysiologicalStressReading>, zone: ZoneId): List<StressDay> =
    samples.filter { it.value in 1..100 }
        .distinctBy { it.timestamp }
        .groupBy { it.timestamp.atZone(zone).toLocalDate() }
        .map { (date, day) -> StressDay(date, day.sortedBy { it.timestamp }, summaryOf(day)) }
        .sortedByDescending { it.date }

fun summaryOf(samples: List<PhysiologicalStressReading>): StressDailySummary {
    if (samples.isEmpty()) return StressDailySummary(null, null, null, null, null, null, null)
    val v = samples.map { it.value }
    fun share(r: IntRange) = (v.count { it in r } * 100.0 / v.size).roundToInt()
    return StressDailySummary(
        avg = v.average().roundToInt(), max = v.max(), min = v.min(),
        relaxProportion = share(1..39), normalProportion = share(40..59),
        mediumProportion = share(60..79), highProportion = share(80..100),
    )
}
