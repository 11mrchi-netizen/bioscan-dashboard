@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.InstantSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class, com.bioscan.fieldterminal.domain.LocalDateTimeSerializer::class, com.bioscan.fieldterminal.domain.ZonedDateTimeSerializer::class, com.bioscan.fieldterminal.domain.OffsetDateTimeSerializer::class, com.bioscan.fieldterminal.domain.DurationSerializer::class, com.bioscan.fieldterminal.domain.YearMonthSerializer::class, com.bioscan.fieldterminal.domain.ZoneIdSerializer::class)
package com.bioscan.fieldterminal.domain.fuel

import kotlinx.serialization.UseSerializers
import kotlinx.serialization.Serializable
import com.bioscan.fieldterminal.domain.TRAINING_COLOR_ID
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.events.EventStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

// The training of a day as fuel planning sees it (docs/fuel-planning/README.md, phase 2). Four sources, merged:
//   1. events (races, long days: domain/events), which win over anything else at the same time;
//   2. planned sessions of a training block;
//   3. calendar training events (colour 8) that neither of those already covers;
//   4. recorded sessions, which replace the planned item they are (same type family, any hour of the day: closest first)
//      once they exist, are an extra session only when nothing planned is left for them, and are all a past day keeps.
// Each item gets an intensity class and an energy estimate (SessionDemand.kt). Walks are left out: they are part of the
// everyday baseline the activity factor already counts.

enum class PlanSource { EVENT, PLANNED_SESSION, CALENDAR, RECORDED }

@Serializable
data class PlannedItem(
    val source: PlanSource,
    val sourceId: String,
    val title: String,
    /** exercise_sessions type: run, ride, hike, strength, swim, other. */
    val type: String,
    /** Null when only the day is known. */
    val start: ZonedDateTime?,
    val durationMin: Double,
    val intensity: Intensity,
    val intensityReason: String,
    val kcal: Double?,
    val recorded: Boolean = false,
    val eventId: Long? = null,
    val eventKind: EventKind? = null,
    val eventPriority: EventPriority? = null,
) {
    val end: ZonedDateTime? get() = start?.plusSeconds((durationMin * 60).toLong())
}

/**
 * A training-colour calendar entry the rules cannot read that runs over [REVIEW_OVER_MIN] minutes ("Resurrected",
 * 13:00-06:00): left out of the day's training until the person says what it is.
 */
@Serializable
data class CalendarReview(val eventId: String, val title: String, val description: String?, val start: ZonedDateTime, val end: ZonedDateTime?, val clockMin: Double)

enum class OverrideKind(val key: String) {
    IGNORE("ignore"), LONG_SESSION("long_session");

    companion object {
        fun of(key: String?): OverrideKind? = entries.firstOrNull { it.key == key }
    }
}

/** The person's answer for one calendar entry: leave it out, or count it as a session of this length and class. */
@kotlinx.serialization.Serializable
data class CalendarOverride(val eventId: String, val kind: OverrideKind, val durationMin: Double? = null, val intensity: Intensity? = null)

const val REVIEW_OVER_MIN = 240.0

@Serializable
data class PlannedDay(val date: LocalDate, val items: List<PlannedItem>, val reviews: List<CalendarReview> = emptyList()) {
    val trainingMin: Double get() = items.sumOf { it.durationMin }
    val sessionKcal: Double get() = items.sumOf { it.kcal ?: 0.0 }
    val hardest: Intensity? get() = items.maxByOrNull { it.intensity.ordinal }?.intensity
    val isRest: Boolean get() = items.isEmpty()
}

data class PlannedSessionInput(
    val id: Long,
    val date: LocalDate,
    val startTime: LocalTime?,
    val durationMin: Double?,
    val domain: String?,
    val title: String,
    val gcalEventId: String? = null,
)

data class CalendarSessionInput(
    val eventId: String,
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime?,
    val colorId: String?,
    val description: String? = null,
)

data class RecordedSessionInput(
    val id: Long,
    val type: String,
    val start: ZonedDateTime,
    val durationMin: Double,
    val caloriesActive: Double? = null,
    val avgHr: Double? = null,
    val rpe: Int? = null,
    val distanceKm: Double? = null,
    val ascentM: Double? = null,
)

/** What the person's history says about their sessions: kcal and TRIMP per minute by type, and heart-rate anchors. */
data class DemandHistory(
    val kcalPerMinByType: Map<String, List<Double>> = emptyMap(),
    val trimpPerMinByType: Map<String, List<Double>> = emptyMap(),
    val restingHr: Double? = null,
    val maxHr: Double? = null,
    val weightKg: Double? = null,
    /** Recent runs with a distance, for the pace calendar entries are timed at (CalendarWorkout.kt). */
    val recentRuns: List<RecentRun> = emptyList(),
)

/** The sessions of a day of training: everything except walks and very short entries. */
fun isTrainingSession(type: String, durationMin: Double): Boolean = type != "walk" && durationMin >= 10

private const val DEFAULT_PLANNED_MIN = 60.0
private const val SAME_SESSION_MIN = 120L
/** Matching a recording to a plan: a planned item with no time counts as this far away, and a compatible but different
 *  type (a planned run recorded as a hike) as this much further than the same type. */
private const val UNTIMED_GAP_MIN = 12 * 60L
private const val TYPE_MISMATCH_MIN = 24 * 60L

/** The session type a calendar title implies (the app's own emoji convention, domain/NextSession.kt), "run" by default. */
fun typeFromCalendarTitle(title: String): String = when {
    "🏋" in title -> "strength"
    "🚴" in title || Regex("\\b(ride|bike|bici|cycling)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> "ride"
    "🏊" in title || Regex("\\b(swim|nuoto)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> "swim"
    Regex("\\b(hike|escursion)", RegexOption.IGNORE_CASE).containsMatchIn(title) -> "hike"
    else -> "run"
}

/** The planned-session type a training domain implies. */
fun typeFromDomain(domain: String?): String = when (domain) {
    "max_strength", "hypertrophy", "strength_endurance", "power" -> "strength"
    "work_capacity", "anaerobic_capacity" -> "other"
    else -> "run"
}

private fun startsClose(a: ZonedDateTime?, b: ZonedDateTime?, minutes: Long = SAME_SESSION_MIN): Boolean =
    a == null || b == null || abs(Duration.between(a, b).toMinutes()) <= minutes

/**
 * The planned days from [from] for [days] days, merged as described at the top of this file. [now] decides what is past:
 * a day before today keeps only what was recorded; today keeps what is planned until a recording replaces it.
 */
fun resolvePlannedDays(
    from: LocalDate,
    days: Int,
    events: List<AthleteEvent>,
    planned: List<PlannedSessionInput>,
    calendar: List<CalendarSessionInput>,
    recorded: List<RecordedSessionInput>,
    history: DemandHistory,
    now: ZonedDateTime,
    overrides: Map<String, CalendarOverride> = emptyMap(),
): List<PlannedDay> {
    val zone = now.zone
    val today = now.toLocalDate()
    return (0 until days).map { offset ->
        val date = from.plusDays(offset.toLong())
        resolveDay(date, today, zone, events, planned, calendar, recorded, history, overrides)
    }
}

private fun resolveDay(
    date: LocalDate,
    today: LocalDate,
    zone: ZoneId,
    events: List<AthleteEvent>,
    planned: List<PlannedSessionInput>,
    calendar: List<CalendarSessionInput>,
    recorded: List<RecordedSessionInput>,
    history: DemandHistory,
    overrides: Map<String, CalendarOverride>,
): PlannedDay {
    val items = mutableListOf<PlannedItem>()
    val reviews = mutableListOf<CalendarReview>()

    events.filter { it.date == date && it.status != EventStatus.CANCELLED && it.status != EventStatus.DNS }.forEach { e ->
        val duration = e.plannedDurationMin ?: DEFAULT_PLANNED_MIN
        val type = e.sport.sessionTypes.first()
        val c = intensityForEvent(e.kind, e.priority, duration)
        items += PlannedItem(
            PlanSource.EVENT, "event:${e.id}", e.name, type, e.start?.withZoneSameInstant(zone), duration, c.intensity, c.reason,
            estimateSessionKcal(type, duration, c.intensity, history.weightKg, history.kcalPerMinByType),
            eventId = e.id, eventKind = e.kind, eventPriority = e.priority,
        )
    }

    planned.filter { it.date == date }.forEach { p ->
        val start = p.startTime?.let { date.atTime(it).atZone(zone) }
        val type = typeFromDomain(p.domain)
        if (items.any { it.source == PlanSource.EVENT && startsClose(it.start, start) && it.type == type }) return@forEach
        val duration = p.durationMin ?: DEFAULT_PLANNED_MIN
        val c = finalIntensity(type, duration, intensityForDomain(p.domain), intensityForTitle(p.title), intensityForTypical(type, history.trimpPerMinByType))
        items += PlannedItem(
            PlanSource.PLANNED_SESSION, "planned:${p.id}", p.title, type, start, duration, c.intensity, c.reason,
            estimateSessionKcal(type, duration, c.intensity, history.weightKg, history.kcalPerMinByType),
        )
    }

    val coveredGcal = planned.mapNotNull { it.gcalEventId }.toSet() + events.mapNotNull { it.calendarEventId }.toSet()
    calendar.filter { it.colorId == TRAINING_COLOR_ID && it.start.withZoneSameInstant(zone).toLocalDate() == date && it.eventId !in coveredGcal }
        .forEach { c ->
            val start = c.start.withZoneSameInstant(zone)
            if (items.any { startsClose(it.start, start, 60) && it.start != null }) return@forEach
            val override = overrides[c.eventId]
            if (override?.kind == OverrideKind.IGNORE) return@forEach
            val type = typeFromCalendarTitle(c.title)
            val clockMin = c.end?.let { Duration.between(c.start, it).toMinutes().toDouble() }
            if (override?.kind == OverrideKind.LONG_SESSION) {
                val minutes = override.durationMin ?: clockMin?.coerceAtMost(600.0) ?: DEFAULT_PLANNED_MIN
                val intensity = override.intensity ?: Intensity.LONG
                items += PlannedItem(
                    PlanSource.CALENDAR, "calendar:${c.eventId}", c.title.trim().ifBlank { "Training" }, type, start, minutes, intensity,
                    "you set it as ${minutes.toInt()} min, ${intensity.label.lowercase()}",
                    estimateSessionKcal(type, minutes, intensity, history.weightKg, history.kcalPerMinByType),
                )
                return@forEach
            }
            // The entry's text (distance, intervals, sets) beats its clock length: training entries are often placeholders.
            val read = estimateCalendarWorkout(parseCalendarWorkout(c.title, c.description, type), type, history.recentRuns)
            val duration = read?.durationMin
                ?: c.end?.let { Duration.between(c.start, it).toMinutes().toDouble() }?.takeIf { it in 5.0..1440.0 } ?: DEFAULT_PLANNED_MIN
            val titled = intensityForTitle(c.title)
            // Over four hours with nothing the rules can read: asked about, not counted as hours of training.
            if (read == null && titled == null && (clockMin ?: 0.0) > REVIEW_OVER_MIN) {
                reviews += CalendarReview(c.eventId, c.title.trim().ifBlank { "Untitled" }, c.description, start, c.end?.withZoneSameInstant(zone), clockMin!!)
                return@forEach
            }
            val k = finalIntensity(type, duration, read?.classified, titled, intensityForTypical(type, history.trimpPerMinByType))
            items += PlannedItem(
                PlanSource.CALENDAR, "calendar:${c.eventId}", c.title.trim().ifBlank { "Training" }, type, start, duration, k.intensity, k.reason,
                estimateSessionKcal(type, duration, k.intensity, history.weightKg, history.kcalPerMinByType),
            )
        }

    if (!date.isAfter(today)) {
        val done = recorded.filter { it.start.withZoneSameInstant(zone).toLocalDate() == date && isTrainingSession(it.type, it.durationMin) }
            .sortedBy { it.start }
        // Which planned item each recording is. A session done later than planned (a morning run done at lunch, or in
        // the evening) is still that session: any compatible planned item of the day can be matched, whatever the hour.
        // Pairs are taken closest first across the whole day (exact type before a compatible one, then the nearer start),
        // so two recordings never swap plans; a recording becomes an extra session only when no planned item is left for it.
        val open = items.filter { !it.recorded }
        val pairs = done.flatMap { r ->
            val start = r.start.withZoneSameInstant(zone)
            open.filter { compatible(it.type, r.type) }.map { p ->
                val gap = p.start?.let { abs(Duration.between(it, start).toMinutes()) } ?: UNTIMED_GAP_MIN
                Triple(r, p, (if (p.type == r.type) 0L else TYPE_MISMATCH_MIN) + gap)
            }
        }.sortedBy { it.third }
        val matchOf = mutableMapOf<Long, PlannedItem>()
        val taken = mutableSetOf<PlannedItem>()
        for ((r, p, _) in pairs) {
            if (r.id in matchOf || p in taken) continue
            matchOf[r.id] = p
            taken += p
        }
        done.forEach { r ->
            val start = r.start.withZoneSameInstant(zone)
            val match = matchOf[r.id]
            val own = intensityForRecorded(r.rpe, r.avgHr, history.restingHr, history.maxHr)
            val base = match?.let {
                // An event keeps its class (a race is still a race); otherwise the recording's own evidence wins.
                if (it.source == PlanSource.EVENT) Classified(it.intensity, it.intensityReason) else null
            }
            val c = finalIntensity(r.type, r.durationMin, base, own, match?.let { Classified(it.intensity, it.intensityReason) }, intensityForTypical(r.type, history.trimpPerMinByType))
            val kcal = r.caloriesActive?.takeIf { it > 0 } ?: estimateSessionKcal(r.type, r.durationMin, c.intensity, history.weightKg, history.kcalPerMinByType)
            val item = PlannedItem(
                PlanSource.RECORDED, "session:${r.id}", match?.title ?: r.type.replaceFirstChar { it.uppercase() }, r.type, start, r.durationMin,
                c.intensity, c.reason, kcal, recorded = true,
                eventId = match?.eventId, eventKind = match?.eventKind, eventPriority = match?.eventPriority,
            )
            if (match != null) items[items.indexOf(match)] = item else items += item
        }
        // A past day is what happened: planned items that were never recorded drop out.
        if (date.isBefore(today)) items.removeAll { !it.recorded }
    }

    return PlannedDay(date, items.sortedBy { it.start?.toEpochSecond() ?: Long.MAX_VALUE }, reviews)
}

private fun compatible(plannedType: String, recordedType: String): Boolean = when (plannedType) {
    recordedType -> true
    "run" -> recordedType in setOf("run", "hike", "other")
    "hike" -> recordedType in setOf("hike", "walk", "run")
    "other" -> recordedType != "walk"
    "strength" -> recordedType in setOf("strength", "other")
    else -> false
}
