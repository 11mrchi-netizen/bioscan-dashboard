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
//
// DAV-283: trail runs (confirmed via SportType, i.e. details.routeType ==
// "trail") are excluded -- their pace reflects terrain/climbing, not road
// effort, and would drag a "road" average pace in a way that misrepresents
// fitness. An untagged run merely *suspected* of being a trail run (see
// suspectedTrailReason) is NOT excluded here -- same detection-only stance
// as everywhere else in this codebase until a person confirms it via MARK AS
// TRAIL. Unknown/other types never reach this function (callers already
// filter to running sessions).
fun averagePaceMinPerKmSince(sessions: List<ExerciseSessionRow>, today: LocalDate, days: Long): Double? {
    val paces = sessions.filter { s -> ChronoUnit.DAYS.between(localDateOf(s.startTime), today) < days }
        .filter { s -> SportType.from(s) != SportType.TRAIL_RUN }
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

// Shared timeframe selector for the PERFORMANCE card's two switchable
// metrics (VO2max and lactate threshold) -- both are slow-moving fitness
// estimates, not day-to-day figures, so a 7-day option doesn't belong here
// (contrast RunningPeriod below, which does want one).
enum class PerformanceTimeframe(val label: String, val months: Long) {
    OneMonth("1M", 1),
    ThreeMonths("3M", 3),
    SixMonths("6M", 6),
    OneYear("1Y", 12),
}

data class PerformanceTrendData(
    val raw: List<Pair<LocalDate, Double>>,
    val avg7d: List<Pair<LocalDate, Double>>,
    val avg28d: List<Pair<LocalDate, Double>>,
)

// Generic over any (date, value) series -- used for both VO2max and lactate
// threshold pace, since the rolling-average/cutoff logic never actually
// cared which metric it was smoothing.
fun preparePerformanceTrendData(
    series: List<Pair<LocalDate, Double>>,
    timeframe: PerformanceTimeframe = PerformanceTimeframe.OneMonth,
    today: LocalDate = LocalDate.now(),
): PerformanceTrendData {
    val cutoff = today.minusMonths(timeframe.months)
    val avg7 = vo2MaxRollingAverage(series, 7)
    val avg28 = vo2MaxRollingAverage(series, 28)
    fun filter(points: List<Pair<LocalDate, Double>>) = points.filter { it.first >= cutoff }

    return PerformanceTrendData(
        raw = filter(series),
        avg7d = filter(avg7),
        avg28d = filter(avg28),
    )
}

// DAV-115: lactate threshold's pace field arrives from Zepp as seconds/km
// (docs/zepp-integration/03-workout-detail-field-decode.md); minutes/km
// matches every other pace figure this app already shows.
fun lactateThresholdPaceMinPerKmSeries(rows: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>> =
    rows.map { (date, secPerKm) -> date to secPerKm / 60.0 }

// RUNNING card's timeframe -- separate from TotalsPeriod (shared with the
// Nutrition tab's own 1D/7D/30D/90D totals, which this rework doesn't touch)
// since 6M/1Y only make sense for a running-distance trend, not a nutrition one.
enum class RunningPeriod(val label: String, val days: Long) {
    Week("7D", 7),
    Month("1M", 30),
    Quarter("3M", 90),
    HalfYear("6M", 180),
    Year("1Y", 365),
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

// `preferred` picks the winner within a same-run cluster. Defaults to
// trusting a manual entry's distance over Health Connect's (this account's
// own pre-fix ground truth for 2026-08-14..09-15 -- see TrainingRepository's
// own comment). Live check found the default wrong for one purpose: a list
// meant to be tapped into Session Detail needs the row that actually has a
// health_connect_record_id, since only that one has any route/time-series
// data to show -- picking the manual row there is a dead end, not a fix.
// Callers building a tappable list pass a preference for source ==
// "health_connect" instead.
val PREFER_MANUAL_DISTANCE: Comparator<ExerciseSessionRow> =
    compareBy<ExerciseSessionRow> { it.source == "manual" }
        .thenBy { it.avgHr != null }
        .thenBy { it.distanceKm ?: 0.0 }
        .thenBy { it.durationMin ?: 0.0 }

fun dedupeRunSessions(
    sessions: List<ExerciseSessionRow>,
    preferred: Comparator<ExerciseSessionRow> = PREFER_MANUAL_DISTANCE,
): List<ExerciseSessionRow> {
    if (sessions.isEmpty()) return emptyList()

    val validSessions = sessions.filter { hasPlausiblePace(it.distanceKm, it.durationMin) }
    val sorted = validSessions.sortedBy { OffsetDateTime.parse(it.startTime).toInstant() }

    val clusters = mutableListOf<MutableList<ExerciseSessionRow>>()
    for (session in sorted) {
        val sessionStart = OffsetDateTime.parse(session.startTime)
        val sessionDuration = session.durationMin ?: 0.0

        val matchingCluster = clusters.find { cluster ->
            cluster.any { rep ->
                val repStart = OffsetDateTime.parse(rep.startTime)
                val repDuration = rep.durationMin ?: 0.0
                val sameDay = repStart.toLocalDate() == sessionStart.toLocalDate()
                val startDiffMin = kotlin.math.abs(java.time.Duration.between(repStart, sessionStart).toMinutes())
                val durationDiffMin = kotlin.math.abs(repDuration - sessionDuration)
                val durationTolerance = maxOf(SAME_RUN_DURATION_TOLERANCE_MIN, minOf(repDuration, sessionDuration) * 0.2)
                sameDay && startDiffMin <= SAME_RUN_START_TOLERANCE_MIN && durationDiffMin <= durationTolerance
            }
        }

        if (matchingCluster != null) {
            matchingCluster.add(session)
        } else {
            clusters.add(mutableListOf(session))
        }
    }

    return clusters.map { cluster ->
        val winner = cluster.maxWith(preferred)
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


// 25/9 rework: Health Connect carries no trail signal, so a trail run often sits
// in the log as an untagged "run". Flag (never auto-change) an untagged run as a
// suspected trail run when Zepp itself called it one (sport code 7) or it climbs
// like one. Explicit road/mixed/track tags are the user's word and stay alone.
const val TRAIL_CLIMB_M_PER_KM = 40.0
const val ZEPP_SPORT_TRAIL_RUN = "7"

// Null = not suspected; otherwise the short reason shown next to the run.
fun suspectedTrailReason(row: ExerciseSessionRow, zeppSportType: String?): String? {
    if (row.type != "run" || row.details.routeType != null) return null
    if (zeppSportType == ZEPP_SPORT_TRAIL_RUN) return "ZEPP TRAIL RUN"
    val distance = row.distanceKm
    val climb = row.elevationGainM
    if (distance != null && distance > 0 && climb != null && climb / distance >= TRAIL_CLIMB_M_PER_KM) {
        return "%.0f M/KM CLIMB".format(climb / distance)
    }
    return null
}

// DAV-272: Efficiency Factor is noisy run to run, so the trend shown is a 28-day
// rolling median, and only once enough aerobic runs exist to mean something
// (DAV-40's gate). Returns one point per run date that has >= EF_MIN_RUNS_28D
// runs in its trailing 28 days.
const val EF_MIN_RUNS_28D = 6

fun efficiencyRollingMedian28(series: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>> =
    series.sortedBy { it.first }.mapNotNull { (date, _) ->
        val window = series.filter { !it.first.isAfter(date) && it.first.isAfter(date.minusDays(28)) }.map { it.second }.sorted()
        if (window.size < EF_MIN_RUNS_28D) null
        else date to (if (window.size % 2 == 1) window[window.size / 2] else (window[window.size / 2 - 1] + window[window.size / 2]) / 2)
    }
