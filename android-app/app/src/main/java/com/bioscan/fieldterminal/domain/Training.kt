package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// Phase G3: generalized from run-only (the old `runs` table, one RunRow per
// row) to any exercise type (`exercise_sessions`). These functions only
// ever cared about distance/duration, never "is this specifically a run" --
// the endurance-vs-strength type filtering now happens once in
// TrainingRepository when it builds the lists passed in here, so this file
// stays exactly as generic as it always effectively was.
//
// `exercise_sessions.start_time` is a real timestamptz (even migrated
// pre-Health-Connect rows carry a nominal-but-real one), so "which day did
// this happen" is a real OffsetDateTime parse now, not a bare LocalDate.

private fun localDateOf(startTime: String): LocalDate = OffsetDateTime.parse(startTime).toLocalDate()

fun sumDistanceKmSince(sessions: List<ExerciseSessionRow>, today: LocalDate, days: Long): Double =
    sessions.filter { s -> ChronoUnit.DAYS.between(localDateOf(s.startTime), today) < days }
        .sumOf { it.distanceKm ?: 0.0 }

fun longestRunKm(sessions: List<ExerciseSessionRow>): Double? = sessions.mapNotNull { it.distanceKm }.maxOrNull()

// Health Connect gives distance + duration, not a stored pace -- derived
// here the same way the old `runs.pace_min_per_km` column was presumably
// computed in the first place, rather than carrying a redundant column.
fun averagePaceMinPerKmSince(sessions: List<ExerciseSessionRow>, today: LocalDate, days: Long): Double? {
    val paces = sessions.filter { s -> ChronoUnit.DAYS.between(localDateOf(s.startTime), today) < days }
        .mapNotNull { s ->
            val distance = s.distanceKm
            val duration = s.durationMin
            if (distance != null && distance > 0 && duration != null) duration / distance else null
        }
    return if (paces.isEmpty()) null else paces.average()
}

// Ported 1:1 from index.html's `[...wearable.vo2].reverse().find(v=>v!==null)`
// -- most recent non-null reading, not necessarily the very latest row.
fun latestNonNullVo2Max(rows: List<Vo2MaxRow>): Double? =
    rows.asReversed().firstNotNullOfOrNull { it.vo2max }

// DAV-80: the raw (date, value) series behind latestNonNullVo2Max, for the
// Training tile's chart -- same rows, just not collapsed to one number.
fun vo2MaxSeries(rows: List<Vo2MaxRow>): List<Pair<LocalDate, Double>> =
    rows.mapNotNull { row -> row.vo2max?.let { LocalDate.parse(row.date) to it } }.sortedBy { it.first }

// A calendar-day window average (not "last N readings") -- Health Connect's
// own vo2max estimates land irregularly, so a reading-count window would
// silently span a much wider or narrower real time range depending on how
// often the watch happened to estimate. Matches this app's own established
// day-aware smoothing approach (BodyCompositionEvaluation.kt's weight EMA).
fun vo2MaxRollingAverage(series: List<Pair<LocalDate, Double>>, windowDays: Long): List<Pair<LocalDate, Double>> =
    series.map { (date, _) ->
        val windowStart = date.minusDays(windowDays - 1)
        val inWindow = series.filter { it.first >= windowStart && it.first <= date }.map { it.second }
        date to inWindow.average()
    }

// DAV-152: Timeframe selector for VO2max chart. 2 months is the standard/default timeframe.
enum class Vo2MaxTimeframe(val label: String, val months: Long?) {
    TwoMonths("2M", 2),
    SixMonths("6M", 6),
    All("ALL", null),
}

data class Vo2MaxTrendData(
    val raw: List<Pair<LocalDate, Double>>,
    val avg7d: List<Pair<LocalDate, Double>>,
    val avg28d: List<Pair<LocalDate, Double>>,
)

fun prepareVo2MaxTrendData(
    series: List<Pair<LocalDate, Double>>,
    timeframe: Vo2MaxTimeframe = Vo2MaxTimeframe.TwoMonths,
    today: LocalDate = LocalDate.now(),
): Vo2MaxTrendData {
    val cutoff = timeframe.months?.let { today.minusMonths(it) }
    val avg7 = vo2MaxRollingAverage(series, 7)
    val avg28 = vo2MaxRollingAverage(series, 28)
    fun filter(points: List<Pair<LocalDate, Double>>) =
        if (cutoff != null) points.filter { it.first >= cutoff } else points

    return Vo2MaxTrendData(
        raw = filter(series),
        avg7d = filter(avg7),
        avg28d = filter(avg28),
    )
}

// DAV-153 follow-up: real 2026-09-20 case found live on-device -- a single
// Health Connect run row (66.87km / 249min = 16.1km/h implied pace) whose own
// avg_speed_kmh (4.96km/h) implied a real distance of only ~20.6km. Initially
// wired this in as an automatic write-time correction, but a broader scan of
// the account's full history found 62 real rows past this same divergence
// factor -- most of them real, plausible, heavily-technical trail runs
// (climbs/switchbacks/elevation legitimately drag average GPS speed well
// below distance/duration). Speed-vs-distance disagreement alone can't
// safely tell a real slow trail effort from a genuine sensor duplication
// artifact, so this is detection-only now (DataIntegrityValidator's matching
// check) -- never wired back into what actually gets stored without a
// person confirming the specific case first.
const val DISTANCE_SPEED_DIVERGENCE_FACTOR = 1.5

// Kept as a pure, tested utility for a future manual/one-off correction
// tool or human-in-the-loop review -- not called from ingestion.
fun reconcileDistanceWithSpeed(distanceKm: Double, durationMin: Double, avgSpeedKmh: Double?): Double {
    if (avgSpeedKmh == null || avgSpeedKmh <= 0 || distanceKm <= 0 || durationMin <= 0) return distanceKm
    val speedImpliedKm = avgSpeedKmh * (durationMin / 60.0)
    if (speedImpliedKm <= 0) return distanceKm
    return if (distanceKm / speedImpliedKm > DISTANCE_SPEED_DIVERGENCE_FACTOR) speedImpliedKm else distanceKm
}

// DAV-153: Fix doubled weekly distance aggregation.
// Multiple recording sources (e.g. watch + phone, multi-app Health Connect sync, or
// manual + Health Connect) can create duplicate records for the same physical run.
// Clusters runs occurring on the same day within 15 minutes of each other and with
// comparable durations (within 5 minutes or 20%), keeping exactly one representative
// session rather than summing them. Manual entries are prioritized; otherwise the session
// with richer telemetry / highest plausible distance is retained.
const val SAME_RUN_DURATION_TOLERANCE_MIN = 5.0
const val SAME_RUN_START_TOLERANCE_MIN = 15L
const val MAX_FOOT_SPEED_KMH = 22.0

fun hasPlausiblePace(distanceKm: Double?, durationMin: Double?): Boolean {
    val distance = distanceKm ?: return true
    val durationHours = (durationMin ?: return true) / 60.0
    if (durationHours <= 0) return true
    return distance / durationHours <= MAX_FOOT_SPEED_KMH
}

fun dedupeRunSessions(sessions: List<ExerciseSessionRow>): List<ExerciseSessionRow> {
    if (sessions.isEmpty()) return emptyList()

    val validSessions = sessions.filter { hasPlausiblePace(it.distanceKm, it.durationMin) }
    val sorted = validSessions.sortedBy { OffsetDateTime.parse(it.startTime).toInstant() }

    val clusters = mutableListOf<MutableList<ExerciseSessionRow>>()
    for (session in sorted) {
        val sessionStart = OffsetDateTime.parse(session.startTime)
        val sessionDuration = session.durationMin ?: 0.0

        val matchingCluster = clusters.find { cluster ->
            val rep = cluster.first()
            val repStart = OffsetDateTime.parse(rep.startTime)
            val repDuration = rep.durationMin ?: 0.0

            val sameDay = repStart.toLocalDate() == sessionStart.toLocalDate()
            val startDiffMin = kotlin.math.abs(java.time.Duration.between(repStart, sessionStart).toMinutes())
            val durationDiffMin = kotlin.math.abs(repDuration - sessionDuration)
            val durationTolerance = maxOf(SAME_RUN_DURATION_TOLERANCE_MIN, minOf(repDuration, sessionDuration) * 0.2)

            sameDay && startDiffMin <= SAME_RUN_START_TOLERANCE_MIN && durationDiffMin <= durationTolerance
        }

        if (matchingCluster != null) {
            matchingCluster.add(session)
        } else {
            clusters.add(mutableListOf(session))
        }
    }

    return clusters.map { cluster ->
        val winner = cluster.maxWith(
            compareBy<ExerciseSessionRow> { it.source == "manual" }
                .thenBy { it.avgHr != null }
                .thenBy { it.distanceKm ?: 0.0 }
                .thenBy { it.durationMin ?: 0.0 }
        )
        // Within a confirmed duplicate cluster the winner's distance may still be
        // inflated by multi-source HC summing. The trail-run concern that blocked
        // auto-reconciliation at ingest time doesn't apply here: the cluster already
        // proves a duplicate exists, so distance/speed disagreement more plausibly
        // reflects double-counting than a legitimately slow trail effort.
        if (cluster.size > 1 && winner.distanceKm != null) {
            val reconciled = reconcileDistanceWithSpeed(winner.distanceKm, winner.durationMin ?: 0.0, winner.avgSpeedKmh)
            winner.copy(distanceKm = reconciled)
        } else winner
    }
}

