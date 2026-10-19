package com.bioscan.fieldterminal.ui.components

import androidx.compose.runtime.saveable.Saver
import com.bioscan.fieldterminal.domain.events.AidStation
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.events.EventSport
import com.bioscan.fieldterminal.domain.events.EventStatus
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZonedDateTime

// Savers for rememberSaveable state that is not Bundle-safe by itself. Sheets and forms that send the person to another
// app (the camera, the file picker) must survive the app being stopped, or killed and restored, while that app is open:
// their state is kept in the saved instance state as plain strings.

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** A Saver that keeps [T] as a string. */
fun <T> stringSaver(encode: (T) -> String, decode: (String) -> T): Saver<T, String> =
    Saver(save = { encode(it) }, restore = { runCatching { decode(it) }.getOrNull() })

/** Course points as "distance,elevation;…" (elevation empty when unknown). */
fun encodeCourse(points: List<CoursePoint>): String =
    points.joinToString(";") { "${it.distanceM},${it.elevationM ?: ""}" }

fun decodeCourse(s: String): List<CoursePoint> = if (s.isEmpty()) emptyList() else s.split(';').mapNotNull { p ->
    val parts = p.split(',')
    val d = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
    CoursePoint(d, parts.getOrNull(1)?.toDoubleOrNull())
}

val CourseSaver: Saver<List<CoursePoint>, String> = stringSaver(::encodeCourse, ::decodeCourse)

@Serializable
private data class StationJson(val name: String, val km: Double, val water: Boolean, val food: Boolean, val dropBag: Boolean, val cutoffMin: Double? = null)

@Serializable
private data class EventJson(
    val id: Long? = null,
    val name: String,
    val kind: String,
    val sport: String,
    val priority: String,
    val date: String,
    val start: String? = null,
    val status: String,
    val targetTimeMin: Double? = null,
    val expectedDurationMin: Double? = null,
    val distanceKm: Double? = null,
    val ascentM: Double? = null,
    val descentM: Double? = null,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val startElevationM: Double? = null,
    val locationName: String? = null,
    val course: String = "",
    val aidStations: List<StationJson> = emptyList(),
    val calendarEventId: String? = null,
    val exerciseSessionId: Long? = null,
    val notes: String? = null,
)

fun encodeEvent(e: AthleteEvent): String = json.encodeToString(
    EventJson(
        e.id, e.name, e.kind.name, e.sport.name, e.priority.name, e.date.toString(), e.start?.toString(), e.status.name,
        e.targetTimeMin, e.expectedDurationMin, e.distanceKm, e.ascentM, e.descentM, e.startLat, e.startLon, e.startElevationM,
        e.locationName, encodeCourse(e.course),
        e.aidStations.map { StationJson(it.name, it.km, it.water, it.food, it.dropBag, it.cutoffMin) },
        e.calendarEventId, e.exerciseSessionId, e.notes,
    ),
)

fun decodeEvent(s: String): AthleteEvent {
    val j = json.decodeFromString<EventJson>(s)
    return AthleteEvent(
        id = j.id, name = j.name, kind = EventKind.valueOf(j.kind), sport = EventSport.valueOf(j.sport),
        priority = EventPriority.valueOf(j.priority), date = LocalDate.parse(j.date), start = j.start?.let(ZonedDateTime::parse),
        status = EventStatus.valueOf(j.status), targetTimeMin = j.targetTimeMin, expectedDurationMin = j.expectedDurationMin,
        distanceKm = j.distanceKm, ascentM = j.ascentM, descentM = j.descentM, startLat = j.startLat, startLon = j.startLon,
        startElevationM = j.startElevationM, locationName = j.locationName, course = decodeCourse(j.course),
        aidStations = j.aidStations.map { AidStation(it.name, it.km, it.water, it.food, it.dropBag, it.cutoffMin) },
        calendarEventId = j.calendarEventId, exerciseSessionId = j.exerciseSessionId, notes = j.notes,
    )
}

/** An optional event (an open "add event" draft, a picked course) kept across a restore. */
val NullableEventSaver: Saver<AthleteEvent?, String> = Saver(
    save = { it?.let(::encodeEvent) ?: "" },
    restore = { s -> if (s.isEmpty()) null else runCatching { decodeEvent(s) }.getOrNull() },
)
