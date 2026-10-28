package com.bioscan.fieldterminal.domain.routine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Routine intelligence R5 (docs/routine-intelligence/01-research-and-plan.md, section 10): what Lab › ROUTINE, Today and
// the Log draw, from rows R1-R3 published. Pure.

// ---- The week by place category ----

enum class StripGroup(val label: String) { HOME("Home"), WORK("Work"), TRANSIT("Transit"), OTHER("Elsewhere"), UNKNOWN("Unknown") }

data class StripDay(val date: LocalDate, val minutes: Map<StripGroup, Double>, val coveragePct: Int?)

// The last [days] days ending yesterday, oldest first; a day without a mobility row is all unknown.
fun weekStrip(byDate: Map<LocalDate, Pair<Map<String, Double>, Int>>, yesterday: LocalDate, days: Int = 7): List<StripDay> =
    (days - 1 downTo 0).map { yesterday.minusDays(it.toLong()) }.map { d ->
        val row = byDate[d] ?: return@map StripDay(d, mapOf(StripGroup.UNKNOWN to 1440.0), null)
        val m = mutableMapOf<StripGroup, Double>()
        for ((key, min) in row.first) {
            val g = when (key) {
                PlaceCategory.HOME.key -> StripGroup.HOME
                PlaceCategory.WORK.key -> StripGroup.WORK
                "transit" -> StripGroup.TRANSIT
                "unknown" -> StripGroup.UNKNOWN
                else -> StripGroup.OTHER
            }
            m[g] = (m[g] ?: 0.0) + min
        }
        StripDay(d, m, row.second)
    }

// ---- Commute ----

data class CommuteDirection(val n: Int, val medianMin: Double, val minMin: Double, val maxMin: Double, val medianActiveMin: Double)

data class CommuteSummary(val toWork: CommuteDirection?, val toHome: CommuteDirection?)

private fun median(xs: List<Double>): Double {
    val s = xs.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

private fun direction(trips: List<Trip>): CommuteDirection? {
    if (trips.isEmpty()) return null
    val mins = trips.map { it.minutes }
    return CommuteDirection(trips.size, median(mins), mins.min(), mins.max(), median(trips.map { it.activeMinutes }))
}

// Commutes split by direction: from a home place is to work, to a home place is to home.
fun commuteSummary(commutes: List<Trip>, homeIds: Set<String>): CommuteSummary = CommuteSummary(
    toWork = direction(commutes.filter { it.fromPlaceId in homeIds }),
    toHome = direction(commutes.filter { it.toPlaceId in homeIds }),
)

// ---- Phone week ----

data class PhoneDaySlice(val date: LocalDate, val screenMin: Double, val bedWindowMin: Double?, val nightUnlocks: Int?)

data class PhoneWeek(
    val days: Int,
    val meanScreenMin: Double,
    val meanBedWindowMin: Double?,
    val nightsMeasured: Int,
    val nightsUnlocked: Int,
)

fun phoneWeek(slices: List<PhoneDaySlice>): PhoneWeek? {
    if (slices.isEmpty()) return null
    val bed = slices.mapNotNull { it.bedWindowMin }
    val nights = slices.mapNotNull { it.nightUnlocks }
    return PhoneWeek(
        days = slices.size,
        meanScreenMin = slices.map { it.screenMin }.average(),
        meanBedWindowMin = bed.takeIf { it.isNotEmpty() }?.average(),
        nightsMeasured = nights.size,
        nightsUnlocked = nights.count { it > 0 },
    )
}

// ---- Unlogged visits ----

data class PlacedVisit(val startMs: Long, val endMs: Long, val placeId: String?, val label: String?, val category: PlaceCategory?)

// Visits of at least [minMinutes] to an Exercise place with no recorded session starting within the visit (give or take
// [slackMs]), finished, newest first. The Log shows them so the session can be added from the watch; no notification.
fun unloggedExerciseVisits(
    visits: List<PlacedVisit>,
    sessionStartsMs: List<Long>,
    nowMs: Long,
    minMinutes: Long = 30,
    slackMs: Long = 30 * 60_000L,
): List<PlacedVisit> = visits
    .filter { it.category == PlaceCategory.EXERCISE && it.endMs < nowMs && it.endMs - it.startMs >= minMinutes * 60_000L }
    .filter { v -> sessionStartsMs.none { it in (v.startMs - slackMs)..(v.endMs + slackMs) } }
    .sortedByDescending { it.startMs }

// ---- Today's line ----

data class RoutineYesterday(
    val date: LocalDate,
    val coveragePct: Int?,
    val homeMin: Double?,
    val workMin: Double?,
    val transitMin: Double?,
    val commuteMin: Double?,
    val usualCommuteMin: Double?,
    val bedWindowMin: Double?,
)

// Yesterday in three short facts for Today; null facts are left out. Sentences, not verdicts (the contract's language).
fun routineLines(y: RoutineYesterday): List<String> {
    fun h(m: Double) = if (m >= 60) "%.1f h".format(m / 60) else "%.0f min".format(m)
    val lines = mutableListOf<String>()
    if (y.coveragePct != null && y.coveragePct >= 50) {
        val parts = listOfNotNull(
            y.homeMin?.takeIf { it > 0 }?.let { "home ${h(it)}" },
            y.workMin?.takeIf { it > 0 }?.let { "work ${h(it)}" },
            y.transitMin?.takeIf { it > 0 }?.let { "on the move ${h(it)}" },
        )
        if (parts.isNotEmpty()) lines += parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
    if (y.commuteMin != null) {
        lines += if (y.usualCommuteMin != null) "Commute ${h(y.commuteMin)}, usual ${h(y.usualCommuteMin)}" else "Commute ${h(y.commuteMin)}"
    }
    if (y.bedWindowMin != null) lines += "Phone in the hour before sleep: ${h(y.bedWindowMin)}"
    return lines
}

fun localDateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
