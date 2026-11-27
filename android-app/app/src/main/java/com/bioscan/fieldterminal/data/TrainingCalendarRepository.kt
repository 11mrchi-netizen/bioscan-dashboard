package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.LenientJson
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The plan -> Google Calendar projection. The app makes ONE calendar of its own ([CALENDAR_NAME]) and writes only there:
 * with the `calendar.app.created` scope Google lets it touch no other calendar, so an event of the person's own can never
 * be changed or deleted by this code. One event per planned session; `extendedProperties.private.planned_session_id`
 * and `planned_sessions.gcal_event_id / gcal_pushed_hash` say which event is which, an unchanged session is not sent again,
 * and a session that is superseded or skipped has its event deleted (only ever from this calendar).
 */
class TrainingCalendarRepository(private val accessToken: String, private val supabase: SupabaseClient) {
    companion object {
        const val CALENDAR_NAME = "Bioscan Training"
        private val client = HttpClient(OkHttp)
        private const val API = "https://www.googleapis.com/calendar/v3"
        private val json = LenientJson
    }

    @Serializable
    private data class Row(
        val id: Long,
        val title: String,
        @SerialName("scheduled_date") val scheduledDate: String,
        @SerialName("week_index") val weekIndex: Int = 0,
        @SerialName("start_time") val startTime: String? = null,
        @SerialName("duration_min") val durationMin: Int? = null,
        @SerialName("block_id") val blockId: Long,
        val status: String = "planned",
        @SerialName("gcal_calendar_id") val gcalCalendarId: String? = null,
        @SerialName("gcal_event_id") val gcalEventId: String? = null,
        @SerialName("gcal_pushed_hash") val gcalPushedHash: String? = null,
    )

    private fun HttpResponse.ok(what: String, body: String) {
        if (!status.isSuccess()) throw IllegalStateException("$what failed (${status.value}): ${body.take(200)}")
    }

    /** The id of the app's calendar: found by name among the calendars this app created, made when it is not there. */
    private suspend fun ensureCalendar(): String {
        val list = client.get("$API/users/me/calendarList") { header("Authorization", "Bearer $accessToken") }
        val body = list.bodyAsText().also { list.ok("Listing calendars", it) }
        json.parseToJsonElement(body).jsonObject["items"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["summary"]?.jsonPrimitive?.contentOrNull == CALENDAR_NAME }
            ?.get("id")?.jsonPrimitive?.contentOrNull?.let { return it }
        val made = client.post("$API/calendars") {
            header("Authorization", "Bearer $accessToken"); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("summary", CALENDAR_NAME); put("description", "Planned training sessions, written by the Bioscan app. Safe to delete."); put("timeZone", ZoneId.systemDefault().id) }.toString())
        }
        val madeBody = made.bodyAsText().also { made.ok("Creating the calendar", it) }
        return json.parseToJsonElement(madeBody).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun Row.eventBody(zone: String): JsonObject {
        val day = LocalDate.parse(scheduledDate)
        val start = startTime?.let { LocalTime.parse(it.take(5)) } ?: LocalTime.of(7, 0)
        val from = day.atTime(start)
        val to = from.plusMinutes((durationMin ?: 60).toLong())
        return buildJsonObject {
            put("summary", "🏋️ ${title.take(80)}")
            put("description", "Week $weekIndex · planned session $id (Bioscan). The app owns this calendar: changes made here are overwritten.")
            put("start", buildJsonObject { put("dateTime", from.toString() + ":00"); put("timeZone", zone) })
            put("end", buildJsonObject { put("dateTime", to.toString() + ":00"); put("timeZone", zone) })
            put("extendedProperties", buildJsonObject { put("private", buildJsonObject { put("planned_session_id", id.toString()); put("block_id", blockId.toString()) }) })
        }
    }

    /** Sends every planned session of the next [days] days; answers a one-line summary. */
    suspend fun push(days: Long = 60): String {
        val zone = ZoneId.systemDefault().id
        val today = LocalDate.now()
        val cols = Columns.list("id,title,scheduled_date,week_index,start_time,duration_min,block_id,status,gcal_calendar_id,gcal_event_id,gcal_pushed_hash")
        val table = supabase.postgrest.from("planned_sessions")
        val rows = table.select(columns = cols) {
            filter { gte("scheduled_date", today.toString()); lte("scheduled_date", today.plusDays(days).toString()) }
            order("scheduled_date", Order.ASCENDING)
        }.decodeList<Row>()
        // Sessions of the past or beyond the window that still carry an event are left alone: the calendar keeps its history.
        val calendarId = ensureCalendar()
        var created = 0; var updated = 0; var removed = 0
        val now = Instant.now().toString()
        for (r in rows) {
            val live = r.status == "planned" || r.status == "confirmed"
            val eventId = r.gcalEventId.takeIf { r.gcalCalendarId == calendarId }
            if (!live) {
                if (eventId != null) {
                    val d = client.delete("$API/calendars/$calendarId/events/$eventId") { header("Authorization", "Bearer $accessToken") }
                    if (d.status.isSuccess() || d.status == HttpStatusCode.NotFound || d.status == HttpStatusCode.Gone) {
                        table.update({ set("gcal_event_id", null as String?); set("gcal_pushed_hash", null as String?); set("gcal_calendar_id", null as String?) }) { filter { eq("id", r.id) } }
                        removed++
                    }
                }
                continue
            }
            val ev = r.eventBody(zone)
            val hash = ev.toString().hashCode().toString()
            if (eventId != null && r.gcalPushedHash == hash) continue
            val res = if (eventId != null) client.patch("$API/calendars/$calendarId/events/$eventId") {
                header("Authorization", "Bearer $accessToken"); contentType(ContentType.Application.Json); setBody(ev.toString())
            } else client.post("$API/calendars/$calendarId/events") {
                header("Authorization", "Bearer $accessToken"); contentType(ContentType.Application.Json); setBody(ev.toString())
            }
            val body = res.bodyAsText()
            if (res.status == HttpStatusCode.NotFound && eventId != null) { // deleted by hand: made again next time
                table.update({ set("gcal_event_id", null as String?); set("gcal_pushed_hash", null as String?) }) { filter { eq("id", r.id) } }
                continue
            }
            res.ok("Sending session ${r.id}", body)
            val newId = json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content
            table.update({ set("gcal_calendar_id", calendarId); set("gcal_event_id", newId); set("gcal_pushed_hash", hash); set("gcal_pushed_at", now) }) { filter { eq("id", r.id) } }
            if (eventId == null) created++ else updated++
        }
        return "Google Calendar \"$CALENDAR_NAME\": $created added, $updated changed, $removed removed"
    }
}
