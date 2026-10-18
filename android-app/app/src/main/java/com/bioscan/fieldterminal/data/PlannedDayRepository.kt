package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.CalendarSessionInput
import com.bioscan.fieldterminal.domain.fuel.DemandHistory
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.PlannedSessionInput
import com.bioscan.fieldterminal.domain.fuel.RecordedSessionInput
import com.bioscan.fieldterminal.domain.fuel.isTrainingSession
import com.bioscan.fieldterminal.domain.fuel.resolvePlannedDays
import com.bioscan.fieldterminal.domain.fuel.trimpPerMinute
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

// The reads behind domain/fuel/PlannedDay.kt: events, planned sessions, calendar training events and recorded sessions
// around the days asked for, plus what the person's history says about their sessions (kcal and heart-rate load per
// minute by type over the last 180 days, today's heart-rate anchors, the latest weight).

@Serializable
private data class PlannedSessionTimesRow(
    val id: Long,
    @SerialName("scheduled_date") val scheduledDate: String,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("duration_min") val durationMin: Double? = null,
    val domain: String? = null,
    val title: String,
    @SerialName("gcal_event_id") val gcalEventId: String? = null,
)

@Serializable
private data class CalendarTimesRow(
    @SerialName("event_id") val eventId: String,
    val title: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("color_id") val colorId: String? = null,
)

@Serializable
private data class RecordedRow(
    val id: Long,
    val type: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("calories_active") val caloriesActive: Double? = null,
    @SerialName("avg_hr") val avgHr: Double? = null,
    val rpe: Int? = null,
)

@Serializable
private data class LatestWeightRow(@SerialName("weight_kg") val weightKg: Double? = null)

private const val HISTORY_DAYS = 180L
private const val PAGE = 1000L

class PlannedDayRepository(private val supabase: SupabaseClient) {

    /** [days] planned days from [from] (today and the two after it by default). */
    suspend fun load(from: LocalDate = LocalDate.now(), days: Int = 3, now: ZonedDateTime = ZonedDateTime.now()): List<PlannedDay> = coroutineScope {
        val zone = now.zone
        val to = from.plusDays(days.toLong())
        val eventsD = async { runCatching { AthleteEventsRepository(supabase).loadAll().filter { !it.date.isBefore(from) && it.date.isBefore(to) } }.getOrDefault(emptyList()) }
        val plannedD = async { runCatching { planned(from, to) }.getOrDefault(emptyList()) }
        val calendarD = async { runCatching { calendar(from, to, zone) }.getOrDefault(emptyList()) }
        val recordedD = async { recorded(from.minusDays(HISTORY_DAYS), zone) }
        val anchorsD = async { runCatching { ZoneAnchorsRepository(supabase).load().anchorsFor(from) }.getOrNull() }
        val weightD = async { runCatching { latestWeight() }.getOrNull() }

        val recorded = recordedD.await()
        val anchors = anchorsD.await()
        val history = demandHistory(recorded, anchors?.restingHr, anchors?.maxHr, weightD.await())
        resolvePlannedDays(
            from, days, eventsD.await(), plannedD.await(), calendarD.await(),
            recorded.filter { !it.start.toLocalDate().isBefore(from) }, history, now,
        )
    }

    private suspend fun planned(from: LocalDate, to: LocalDate): List<PlannedSessionInput> =
        supabase.postgrest.from("planned_sessions")
            .select(Columns.list("id,scheduled_date,start_time,duration_min,domain,title,gcal_event_id")) {
                filter {
                    gte("scheduled_date", from.toString())
                    lt("scheduled_date", to.toString())
                    isIn("status", listOf("planned", "confirmed"))
                }
            }
            .decodeList<PlannedSessionTimesRow>()
            .map { r ->
                PlannedSessionInput(
                    r.id, LocalDate.parse(r.scheduledDate), r.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                    r.durationMin, r.domain, r.title, r.gcalEventId,
                )
            }

    private suspend fun calendar(from: LocalDate, to: LocalDate, zone: ZoneId): List<CalendarSessionInput> =
        supabase.postgrest.from("calendar_events")
            .select(Columns.list("event_id,title,start_time,end_time,color_id")) {
                filter {
                    eq("is_cancelled", false)
                    gte("start_time", from.atStartOfDay(zone).toInstant().toString())
                    lt("start_time", to.atStartOfDay(zone).toInstant().toString())
                }
            }
            .decodeList<CalendarTimesRow>()
            .mapNotNull { r ->
                val start = r.startTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() } ?: return@mapNotNull null
                CalendarSessionInput(r.eventId, r.title.orEmpty(), start, r.endTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }, r.colorId)
            }

    // Paged: 180 days of sessions without walks can pass the API's 1,000-row cap.
    private suspend fun recorded(since: LocalDate, zone: ZoneId): List<RecordedSessionInput> =
        ReadCache.get("fuel.recorded.$since", tables = setOf("exercise_sessions")) {
            val out = mutableListOf<RecordedRow>()
            var start = 0L
            while (true) {
                val page = supabase.postgrest.from("exercise_sessions")
                    .select(Columns.list("id,type,start_time,duration_min,calories_active,avg_hr,rpe")) {
                        filter {
                            gte("start_time", since.atStartOfDay(zone).toInstant().toString())
                            neq("type", "walk")
                        }
                        order("start_time", Order.ASCENDING)
                        range(start, start + PAGE - 1)
                    }
                    .decodeList<RecordedRow>()
                out += page
                if (page.size < PAGE) break
                start += PAGE
            }
            out.mapNotNull { r ->
                val s = runCatching { OffsetDateTime.parse(r.startTime).atZoneSameInstant(zone) }.getOrNull() ?: return@mapNotNull null
                RecordedSessionInput(r.id, r.type, s, r.durationMin ?: 0.0, r.caloriesActive, r.avgHr, r.rpe)
            }
        }

    private suspend fun latestWeight(): Double? =
        supabase.postgrest.from("body_metrics")
            .select(Columns.list("weight_kg")) {
                filter { filterNot("weight_kg", FilterOperator.IS, "null") }
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<LatestWeightRow>().firstOrNull()?.weightKg
}

/** kcal and TRIMP per minute by session type from recorded training sessions (walks and fragments left out). */
fun demandHistory(recorded: List<RecordedSessionInput>, restingHr: Double?, maxHr: Double?, weightKg: Double?): DemandHistory {
    val training = recorded.filter { isTrainingSession(it.type, it.durationMin) }
    val kcal = training.filter { (it.caloriesActive ?: 0.0) > 0 && it.durationMin > 0 }
        .groupBy { it.type }.mapValues { (_, s) -> s.map { it.caloriesActive!! / it.durationMin } }
    val trimp = if (restingHr != null && maxHr != null && maxHr > restingHr) {
        training.filter { it.avgHr != null }.groupBy { it.type }.mapValues { (_, s) ->
            s.map { trimpPerMinute(((it.avgHr!! - restingHr) / (maxHr - restingHr)).coerceIn(0.0, 1.0)) }
        }
    } else {
        emptyMap()
    }
    return DemandHistory(kcal, trimp, restingHr, maxHr, weightKg)
}
