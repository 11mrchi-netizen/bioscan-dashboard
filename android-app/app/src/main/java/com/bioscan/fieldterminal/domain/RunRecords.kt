package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Running page, RECORDS, following the page's ROAD | TRAIL switch. Road: the longest run, the fastest run of 5 km or more
// and the biggest week. Trail: the longest trail run, the biggest climb, the fastest grade-adjusted pace over 10 km or
// more (raw pace on a trail says more about the ground than the runner) and the biggest trail week.

/** The fields a record needs from one run. */
data class RecordRun(
    val date: LocalDate,
    val distanceKm: Double,
    val paceMinPerKm: Double,
    val elevationGainM: Double?,
    val gapMinPerKm: Double?,
)

data class RunRecord(val label: String, val date: LocalDate, val value: Double)

data class RunRecords(
    val longest: RunRecord?,
    val fastest: RunRecord?,
    val biggestClimb: RunRecord?,
    val biggestWeek: RunRecord?,
)

const val ROAD_FASTEST_MIN_KM = 5.0
const val TRAIL_FASTEST_MIN_KM = 10.0

/** Records over [runs] (already the road or the trail ones); weeks run from the first run's week to [today]. */
fun runRecords(runs: List<RecordRun>, trail: Boolean, today: LocalDate): RunRecords {
    if (runs.isEmpty()) return RunRecords(null, null, null, null)
    val longest = runs.maxBy { it.distanceKm }.let { RunRecord("Longest", it.date, it.distanceKm) }
    val fastest = if (trail) {
        runs.filter { it.distanceKm >= TRAIL_FASTEST_MIN_KM && it.gapMinPerKm != null }.minByOrNull { it.gapMinPerKm!! }
            ?.let { RunRecord("Fastest grade-adjusted", it.date, it.gapMinPerKm!!) }
    } else {
        runs.filter { it.distanceKm >= ROAD_FASTEST_MIN_KM }.minByOrNull { it.paceMinPerKm }?.let { RunRecord("Fastest", it.date, it.paceMinPerKm) }
    }
    val climb = if (trail) runs.filter { (it.elevationGainM ?: 0.0) > 0 }.maxByOrNull { it.elevationGainM!! }?.let { RunRecord("Biggest climb", it.date, it.elevationGainM!!) } else null
    val week = bucketWeekly(runs, { it.date }, { it.distanceKm }, runs.minOf { it.date }, today)
        .filter { it.value > 0 }.maxByOrNull { it.value }?.let { RunRecord("Biggest week", it.weekStart, it.value) }
    return RunRecords(longest, fastest, climb, week)
}
