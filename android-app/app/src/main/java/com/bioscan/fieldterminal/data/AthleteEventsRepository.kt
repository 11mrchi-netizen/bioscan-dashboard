package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.CalendarEventRow
import com.bioscan.fieldterminal.domain.events.AidStation
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.events.EventSessionCandidate
import com.bioscan.fieldterminal.domain.events.EventSport
import com.bioscan.fieldterminal.domain.events.EventStatus
import com.bioscan.fieldterminal.domain.events.looksLikeRace
import com.bioscan.fieldterminal.domain.events.matchEventToSession
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import com.bioscan.fieldterminal.domain.trail.CourseProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Events outside the training program (supabase/migrations/20261024_athlete_events.sql; domain/events/AthleteEvent.kt).
// Start times are local wall-clock labelled with an offset, like the other timed rows.

@Serializable
data class AidStationJson(
    val name: String,
    val km: Double,
    val water: Boolean = true,
    val food: Boolean = false,
    @SerialName("drop_bag") val dropBag: Boolean = false,
    @SerialName("cutoff_min") val cutoffMin: Double? = null,
)

@Serializable
data class AthleteEventRow(
    val id: Long,
    val name: String,
    val kind: String,
    val sport: String,
    val priority: String = "B",
    @SerialName("event_date") val eventDate: String,
    @SerialName("start_time") val startTime: String? = null,
    val status: String = "planned",
    @SerialName("target_time_min") val targetTimeMin: Double? = null,
    @SerialName("expected_duration_min") val expectedDurationMin: Double? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("ascent_m") val ascentM: Double? = null,
    @SerialName("descent_m") val descentM: Double? = null,
    @SerialName("start_lat") val startLat: Double? = null,
    @SerialName("start_lon") val startLon: Double? = null,
    @SerialName("start_elevation_m") val startElevationM: Double? = null,
    @SerialName("location_name") val locationName: String? = null,
    @SerialName("course_profile") val courseProfile: List<List<Double?>>? = null,
    @SerialName("course_source") val courseSource: String? = null,
    @SerialName("aid_stations") val aidStations: List<AidStationJson> = emptyList(),
    @SerialName("calendar_event_id") val calendarEventId: String? = null,
    @SerialName("exercise_session_id") val exerciseSessionId: Long? = null,
    val notes: String? = null,
)

@Serializable
private data class AthleteEventWrite(
    val name: String,
    val kind: String,
    val sport: String,
    val priority: String,
    @SerialName("event_date") val eventDate: String,
    @SerialName("start_time") val startTime: String?,
    val status: String,
    @SerialName("target_time_min") val targetTimeMin: Double?,
    @SerialName("expected_duration_min") val expectedDurationMin: Double?,
    @SerialName("distance_km") val distanceKm: Double?,
    @SerialName("ascent_m") val ascentM: Double?,
    @SerialName("descent_m") val descentM: Double?,
    @SerialName("start_lat") val startLat: Double?,
    @SerialName("start_lon") val startLon: Double?,
    @SerialName("start_elevation_m") val startElevationM: Double?,
    @SerialName("location_name") val locationName: String?,
    @SerialName("course_profile") val courseProfile: List<List<Double?>>?,
    @SerialName("course_source") val courseSource: String?,
    @SerialName("aid_stations") val aidStations: List<AidStationJson>,
    @SerialName("calendar_event_id") val calendarEventId: String?,
    val notes: String?,
)

@Serializable
private data class EventSessionLink(@SerialName("exercise_session_id") val exerciseSessionId: Long, val status: String = "done")

@Serializable
private data class EventStatusUpdate(val status: String)

@Serializable
private data class SessionTimesRow(
    val id: Long,
    val type: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
)

fun AthleteEventRow.toDomain(): AthleteEvent = AthleteEvent(
    id = id,
    name = name,
    kind = EventKind.of(kind),
    sport = EventSport.of(sport),
    priority = EventPriority.of(priority),
    date = LocalDate.parse(eventDate),
    start = startTime?.let { runCatching { OffsetDateTime.parse(it).toZonedDateTime() }.getOrNull() },
    status = EventStatus.of(status),
    targetTimeMin = targetTimeMin,
    expectedDurationMin = expectedDurationMin,
    distanceKm = distanceKm,
    ascentM = ascentM,
    descentM = descentM,
    startLat = startLat,
    startLon = startLon,
    startElevationM = startElevationM,
    locationName = locationName,
    course = courseProfile.orEmpty().mapNotNull { p -> p.getOrNull(0)?.let { CoursePoint(it, p.getOrNull(1)) } },
    aidStations = aidStations.map { AidStation(it.name, it.km, it.water, it.food, it.dropBag, it.cutoffMin) },
    calendarEventId = calendarEventId,
    exerciseSessionId = exerciseSessionId,
    notes = notes,
)

private fun AthleteEvent.toWrite(courseSource: String?): AthleteEventWrite = AthleteEventWrite(
    name = name.trim(),
    kind = kind.key,
    sport = sport.key,
    priority = priority.key,
    eventDate = date.toString(),
    startTime = start?.toOffsetDateTime()?.toString(),
    status = status.key,
    targetTimeMin = targetTimeMin,
    expectedDurationMin = expectedDurationMin,
    distanceKm = distanceKm,
    ascentM = ascentM,
    descentM = descentM,
    startLat = startLat,
    startLon = startLon,
    startElevationM = startElevationM,
    locationName = locationName?.trim()?.ifBlank { null },
    courseProfile = course.takeIf { it.isNotEmpty() }?.map { listOf(it.distanceM, it.elevationM) },
    courseSource = courseSource.takeIf { course.isNotEmpty() },
    aidStations = aidStations.sortedBy { it.km }.map { AidStationJson(it.name.trim(), it.km, it.water, it.food, it.dropBag, it.cutoffMin) },
    calendarEventId = calendarEventId,
    notes = notes?.trim()?.ifBlank { null },
)

/** An event with a course profile applied: distance, ascent, descent and start point come from the course. */
fun AthleteEvent.withCourse(profile: CourseProfile): AthleteEvent = copy(
    course = profile.points,
    distanceKm = profile.distanceM / 1000.0,
    ascentM = profile.gainM ?: ascentM,
    descentM = profile.lossM ?: descentM,
    startLat = profile.startLat ?: startLat,
    startLon = profile.startLon ?: startLon,
    startElevationM = profile.startElevationM ?: startElevationM,
)

/** A calendar entry that reads like a race and is not an event yet: the app offers MAKE THIS AN EVENT. */
data class EventSuggestion(val calendarEvent: CalendarEventRow, val start: ZonedDateTime)

class AthleteEventsRepository(private val supabase: SupabaseClient) {

    suspend fun loadAll(): List<AthleteEvent> =
        supabase.postgrest.from("athlete_events")
            .select { order("event_date", Order.DESCENDING); limit(500) }
            .decodeList<AthleteEventRow>().map { it.toDomain() }

    /** Open events from [from] for [days] days, soonest first. */
    suspend fun loadUpcoming(from: LocalDate, days: Long = 400): List<AthleteEvent> =
        supabase.postgrest.from("athlete_events")
            .select {
                filter {
                    gte("event_date", from.toString())
                    lte("event_date", from.plusDays(days).toString())
                    eq("status", EventStatus.PLANNED.key)
                }
                order("event_date", Order.ASCENDING)
            }
            .decodeList<AthleteEventRow>().map { it.toDomain() }

    suspend fun load(id: Long): AthleteEvent? =
        supabase.postgrest.from("athlete_events").select { filter { eq("id", id) } }
            .decodeList<AthleteEventRow>().firstOrNull()?.toDomain()

    /** Inserts a new event or updates an existing one; returns it as stored. */
    suspend fun save(event: AthleteEvent, courseSource: String? = null): AthleteEvent {
        val write = event.toWrite(courseSource ?: if (event.course.isNotEmpty()) "gpx_file" else null)
        val row = if (event.id == null) {
            supabase.postgrest.from("athlete_events").insert(write) { select() }.decodeSingle<AthleteEventRow>()
        } else {
            supabase.postgrest.from("athlete_events").update(write) {
                filter { eq("id", event.id) }
                select()
            }.decodeSingle<AthleteEventRow>()
        }
        return row.toDomain()
    }

    suspend fun setStatus(id: Long, status: EventStatus) {
        supabase.postgrest.from("athlete_events").update(EventStatusUpdate(status.key)) { filter { eq("id", id) } }
    }

    suspend fun delete(id: Long) {
        supabase.postgrest.from("athlete_events").delete { filter { eq("id", id) } }
    }

    /**
     * Links open events of the last [lookbackDays] days to the session that recorded them (domain matchEventToSession)
     * and marks them done. Returns how many were linked. A session already linked to another event is not reused.
     */
    suspend fun linkCompletedEvents(today: LocalDate = LocalDate.now(), lookbackDays: Long = 7): Int {
        val open = supabase.postgrest.from("athlete_events")
            .select {
                filter {
                    gte("event_date", today.minusDays(lookbackDays).toString())
                    lte("event_date", today.toString())
                    eq("status", EventStatus.PLANNED.key)
                }
            }
            .decodeList<AthleteEventRow>().map { it.toDomain() }
        if (open.isEmpty()) return 0
        val taken = supabase.postgrest.from("athlete_events")
            .select(Columns.list("id,exercise_session_id")) { filter { gte("event_date", today.minusDays(lookbackDays + 1).toString()) } }
            .decodeList<AthleteEventRow>().mapNotNull { it.exerciseSessionId }.toMutableSet()
        val zone = ZoneId.systemDefault()
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(Columns.list("id,type,start_time,duration_min")) {
                filter { gte("start_time", today.minusDays(lookbackDays + 1).atStartOfDay(zone).toInstant().toString()) }
            }
            .decodeList<SessionTimesRow>()
            .mapNotNull { s ->
                runCatching { OffsetDateTime.parse(s.startTime).atZoneSameInstant(zone) }.getOrNull()
                    ?.let { EventSessionCandidate(s.id, s.type, it, s.durationMin ?: 0.0) }
            }
        var linked = 0
        for (event in open.sortedBy { it.date }) {
            val match = matchEventToSession(event, sessions.filter { it.id !in taken }) ?: continue
            supabase.postgrest.from("athlete_events").update(EventSessionLink(match.id)) { filter { eq("id", event.id!!) } }
            taken += match.id
            linked++
        }
        return linked
    }

    /**
     * Calendar entries in the next [days] days that read like a race and are not events yet. Reads the synced
     * calendar_events table (CalendarSyncRepository); empty until the calendar has been synced once.
     */
    suspend fun loadSuggestions(days: Long = 365): List<EventSuggestion> {
        val now = Instant.now()
        val calendar = supabase.postgrest.from("calendar_events")
            .select {
                filter {
                    eq("is_cancelled", false)
                    gte("start_time", now.toString())
                    lte("start_time", now.plusSeconds(days * 86_400).toString())
                }
                order("start_time", Order.ASCENDING)
            }
            .decodeList<CalendarEventRow>()
        val linked = supabase.postgrest.from("athlete_events")
            .select(Columns.list("id,calendar_event_id")) { filter { gte("event_date", LocalDate.now().minusDays(1).toString()) } }
            .decodeList<AthleteEventRow>().mapNotNull { it.calendarEventId }.toSet()
        val zone = ZoneId.systemDefault()
        return calendar.filter { it.eventId !in linked && looksLikeRace(it.title) }
            .mapNotNull { row ->
                val start = row.startTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }
                    ?: return@mapNotNull null
                EventSuggestion(row, start)
            }
    }
}

/** A draft event from a calendar entry: its title, date, start and length; the person fills in the rest. */
fun draftEventFrom(suggestion: EventSuggestion): AthleteEvent {
    val row = suggestion.calendarEvent
    val end = row.endTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(suggestion.start.zone) }.getOrNull() }
    val minutes = end?.let { java.time.Duration.between(suggestion.start, it).toMinutes().toDouble() }?.takeIf { it > 0 }
    return AthleteEvent(
        name = row.title?.trim().orEmpty().ifBlank { "Race" },
        kind = EventKind.RACE,
        sport = EventSport.RUN,
        date = suggestion.start.toLocalDate(),
        start = suggestion.start,
        expectedDurationMin = minutes,
        calendarEventId = row.eventId,
        notes = row.description?.take(500),
    )
}
