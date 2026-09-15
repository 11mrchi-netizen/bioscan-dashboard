package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.RunRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Real computation over the `runs` table -- notably, this is data
// index.html's own fetchDashboardData() already pulls into DASHBOARD_DATA
// .runs, but its Training panel's render() never actually reads from it
// (confirmed by reading the function directly): every strength AND
// endurance number shown there ("17.9km this week", "+9.4% pace trend",
// all squat/deadlift/pull-up PRs) is a hardcoded literal from a one-off
// narrative pass, not a live query. Rather than port stale hardcoded
// numbers as if they were the "current web-dashboard source of truth"
// (Step 7's literal done-when wording), this computes real numbers from
// the same table the web dashboard already fetches but doesn't use.
// See ROADMAP.md P8 Step 7 for the full finding.

fun sumDistanceKmSince(runs: List<RunRow>, today: LocalDate, days: Long): Double =
    runs.filter { run ->
        val d = LocalDate.parse(run.date)
        ChronoUnit.DAYS.between(d, today) < days
    }.sumOf { it.distanceKm ?: 0.0 }

fun longestRunKm(runs: List<RunRow>): Double? = runs.mapNotNull { it.distanceKm }.maxOrNull()

fun averagePaceMinPerKmSince(runs: List<RunRow>, today: LocalDate, days: Long): Double? {
    val paces = runs.filter { run ->
        val d = LocalDate.parse(run.date)
        ChronoUnit.DAYS.between(d, today) < days
    }.mapNotNull { it.paceMinPerKm }
    return if (paces.isEmpty()) null else paces.average()
}

// Ported 1:1 from index.html's `[...wearable.vo2].reverse().find(v=>v!==null)`
// -- most recent non-null reading, not necessarily the very latest row.
fun latestNonNullVo2Max(rows: List<Vo2MaxRow>): Double? =
    rows.asReversed().firstNotNullOfOrNull { it.vo2max }
