package com.bioscan.fieldterminal.domain.events

import com.bioscan.fieldterminal.domain.trail.CoursePoint
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs

// Events outside the training program (supabase/migrations/20261024_athlete_events.sql): races, long sessions, matches,
// hiking days and other hard days. The training program only knows the sessions of a block, and the calendar only a
// title; an event says what the day is (kind, sport, priority), how long it should take, and over which course, which
// is what fuel planning, hydration and a taper need.

enum class EventKind(val key: String, val label: String) {
    RACE("race", "RACE"),
    LONG_SESSION("long_session", "LONG SESSION"),
    MATCH("match", "MATCH"),
    HIKE_DAY("hike_day", "HIKE DAY"),
    OTHER_HARD_DAY("other_hard_day", "OTHER HARD DAY"),
    ;

    companion object {
        fun of(key: String?): EventKind = entries.firstOrNull { it.key == key } ?: OTHER_HARD_DAY
    }
}

enum class EventSport(val key: String, val label: String, val sessionTypes: Set<String>) {
    RUN("run", "RUN", setOf("run")),
    TRAIL_RUN("trail_run", "TRAIL RUN", setOf("run", "hike")),
    RIDE("ride", "RIDE", setOf("ride")),
    HIKE("hike", "HIKE", setOf("hike", "walk")),
    SWIM("swim", "SWIM", setOf("swim", "other")),
    TRIATHLON("triathlon", "TRIATHLON", setOf("run", "ride", "swim", "other")),
    TEAM_SPORT("team_sport", "TEAM SPORT", setOf("other")),
    OTHER("other", "OTHER", setOf("other", "run", "ride", "hike")),
    ;

    companion object {
        fun of(key: String?): EventSport = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** A: the goal of a season; B: important; C: a training race or a hard day that fits around the plan. */
enum class EventPriority(val key: String) {
    A("A"), B("B"), C("C");

    companion object {
        fun of(key: String?): EventPriority = entries.firstOrNull { it.key == key } ?: B
    }
}

enum class EventStatus(val key: String, val label: String) {
    PLANNED("planned", "PLANNED"),
    DONE("done", "DONE"),
    DNS("dns", "DID NOT START"),
    CANCELLED("cancelled", "CANCELLED"),
    ;

    companion object {
        fun of(key: String?): EventStatus = entries.firstOrNull { it.key == key } ?: PLANNED
    }
}

data class AidStation(
    val name: String,
    val km: Double,
    val water: Boolean = true,
    val food: Boolean = false,
    val dropBag: Boolean = false,
    /** Elapsed time from the start by which the station must be left, in minutes. */
    val cutoffMin: Double? = null,
)

data class AthleteEvent(
    val id: Long? = null,
    val name: String,
    val kind: EventKind,
    val sport: EventSport,
    val priority: EventPriority = EventPriority.B,
    val date: LocalDate,
    val start: ZonedDateTime? = null,
    val status: EventStatus = EventStatus.PLANNED,
    val targetTimeMin: Double? = null,
    val expectedDurationMin: Double? = null,
    val distanceKm: Double? = null,
    val ascentM: Double? = null,
    val descentM: Double? = null,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val startElevationM: Double? = null,
    val locationName: String? = null,
    val course: List<CoursePoint> = emptyList(),
    val aidStations: List<AidStation> = emptyList(),
    val calendarEventId: String? = null,
    val exerciseSessionId: Long? = null,
    val notes: String? = null,
) {
    /** The duration the day is planned around: the target time when there is one, else the expected duration. */
    val plannedDurationMin: Double? get() = targetTimeMin ?: expectedDurationMin

    val isOpen: Boolean get() = status == EventStatus.PLANNED
}

/** What is wrong with an event as entered; empty when it can be saved. */
fun validateEvent(event: AthleteEvent): List<String> {
    val problems = mutableListOf<String>()
    if (event.name.isBlank()) problems += "Give the event a name."
    if (event.name.length > 120) problems += "Keep the name under 120 characters."
    if (event.plannedDurationMin == null) problems += "Set a target time or an expected duration: plans are built around it."
    listOfNotNull(event.targetTimeMin, event.expectedDurationMin).forEach {
        if (it !in 1.0..10080.0) problems += "Durations must be between 1 minute and 7 days."
    }
    event.distanceKm?.let { if (it < 0 || it > 2000) problems += "Distance must be between 0 and 2,000 km." }
    event.ascentM?.let { if (it < 0) problems += "Ascent cannot be negative." }
    event.start?.let { if (it.toLocalDate() != event.date) problems += "The start time must fall on the event's date." }
    val distance = event.distanceKm ?: event.course.lastOrNull()?.distanceM?.div(1000)
    event.aidStations.forEach { station ->
        if (station.name.isBlank()) problems += "Every aid station needs a name."
        if (station.km < 0 || (distance != null && station.km > distance + 0.5)) {
            problems += "Aid station ${station.name.ifBlank { "?" }} at ${"%.1f".format(station.km)} km is outside the course."
        }
    }
    return problems.distinct()
}

/** A recorded session that might be the event (matching only needs its type and times). */
data class EventSessionCandidate(val id: Long, val type: String, val start: ZonedDateTime, val durationMin: Double)

/**
 * The recorded session that is this event: on the event's date, of a type the sport is done as, and, when the event
 * has a start time, starting within two hours of it (the closest start wins); without a start time, the longest one.
 * Sessions already linked to another event are left out by the caller. Null when none fits.
 */
fun matchEventToSession(event: AthleteEvent, sessions: List<EventSessionCandidate>): EventSessionCandidate? {
    val sameDay = sessions.filter { it.start.withZoneSameInstant(event.start?.zone ?: it.start.zone).toLocalDate() == event.date }
        .filter { it.type in event.sport.sessionTypes }
    val start = event.start
    return if (start != null) {
        sameDay.map { it to abs(Duration.between(start, it.start).toMinutes()) }
            .filter { it.second <= 120 }
            .minByOrNull { it.second }?.first
    } else {
        sameDay.maxByOrNull { it.durationMin }
    }
}

private val RACE_WORDS = listOf(
    // Not "trail" or "vertical": training runs are named that way too.
    "race", "gara", "marathon", "maratona", "mezza", "half", "ultra", "skyrace", "sky race",
    "granfondo", "gran fondo", "triathlon", "duathlon", "parkrun", "10k", "21k", "42k", "5k ", "competition", "crono",
)
private val RACE_EMOJI = listOf("🏅", "🏁", "🎽", "🥇", "🏆")

/**
 * Whether a calendar title reads like a race, so the app can offer to make it an event. Conservative on purpose: a
 * suggestion the person dismisses costs more trust than one they never see.
 */
fun looksLikeRace(title: String?): Boolean {
    val t = title?.lowercase()?.let { " $it " } ?: return false
    return RACE_EMOJI.any { it in t } || RACE_WORDS.any { word -> Regex("(^|[^a-z])${Regex.escape(word.trim())}([^a-z]|$)").containsMatchIn(t) }
}

/** Upcoming open events first, then the rest newest first. */
fun sortEvents(events: List<AthleteEvent>, today: LocalDate): List<AthleteEvent> {
    val (upcoming, rest) = events.partition { it.isOpen && !it.date.isBefore(today) }
    return upcoming.sortedBy { it.date } + rest.sortedByDescending { it.date }
}

/** "1:45", "1h45", "105" (minutes) or "2h" to minutes; null when blank or unreadable. */
fun parseHoursMinutes(text: String): Double? {
    val t = text.trim().lowercase().replace(" ", "")
    if (t.isEmpty()) return null
    Regex("""^(\d{1,3}):([0-5]?\d)(:([0-5]\d))?$""").matchEntire(t)?.let { m ->
        val h = m.groupValues[1].toDouble()
        val min = m.groupValues[2].toDouble()
        val s = m.groupValues[4].toDoubleOrNull() ?: 0.0
        return h * 60 + min + s / 60
    }
    Regex("""^(\d{1,3})h([0-5]?\d)?m?$""").matchEntire(t)?.let { m ->
        return m.groupValues[1].toDouble() * 60 + (m.groupValues[2].toDoubleOrNull() ?: 0.0)
    }
    Regex("""^(\d{1,5})(m|min)?$""").matchEntire(t)?.let { m -> return m.groupValues[1].toDouble() }
    return null
}

/** Minutes as "1:45" (hours:minutes), rounded to the minute. */
fun formatHoursMinutes(minutes: Double): String {
    val total = kotlin.math.round(minutes).toLong()
    return "%d:%02d".format(total / 60, total % 60)
}

/** When an event starts, for ordering: its start time, else the end of its day (it is "on" all day). */
fun AthleteEvent.effectiveStart(zone: java.time.ZoneId): ZonedDateTime = start?.withZoneSameInstant(zone) ?: date.atTime(23, 59).atZone(zone)

/** The soonest open event that has not started yet and falls within [horizonDays]. */
fun nextUpcomingEvent(events: List<AthleteEvent>, now: ZonedDateTime, horizonDays: Long = 14): AthleteEvent? =
    events.filter { it.isOpen && !it.effectiveStart(now.zone).isBefore(now) && !it.date.isAfter(now.toLocalDate().plusDays(horizonDays)) }
        .minByOrNull { it.effectiveStart(now.zone) }

/**
 * Status's NEXT UP line shows the next 24 hours of the calendar; an event takes the line when it comes first within
 * those 24 hours, or when the calendar has nothing in them (then up to [nextUpcomingEvent]'s horizon, as "IN N DAYS").
 */
fun eventTakesNextUp(event: AthleteEvent?, calendarStart: ZonedDateTime?, now: ZonedDateTime): Boolean {
    event ?: return false
    if (calendarStart == null) return true
    val eventStart = event.effectiveStart(now.zone)
    return eventStart.isBefore(now.plusHours(24)) && !eventStart.isAfter(calendarStart)
}
