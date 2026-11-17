package com.bioscan.fieldterminal.data.routine

import com.bioscan.fieldterminal.data.ReadCache
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.routine.ActivityCategory
import com.bioscan.fieldterminal.domain.routine.ActivityKind
import com.bioscan.fieldterminal.domain.routine.InferredInputs
import com.bioscan.fieldterminal.domain.routine.LogSource
import com.bioscan.fieldterminal.domain.routine.LogStatus
import com.bioscan.fieldterminal.domain.routine.RoutineActivity
import com.bioscan.fieldterminal.domain.routine.RoutineLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

// Practices and logged time (supabase/migrations/20261110044303_routine_practices.sql; domain/routine/Practices.kt), and
// the reads of what other data already shows for the week's time: sessions, trips, mobility, phone use and sleep.

@Serializable
private data class RoutineActivityRow(
    val id: Long,
    val name: String,
    val kind: String,
    val category: String = "other",
    @SerialName("target_min") val targetMin: Int? = null,
    val days: List<Int>? = null,
    @SerialName("time_of_day") val timeOfDay: String? = null,
    val remind: Boolean = false,
    @SerialName("archived_at") val archivedAt: String? = null,
)

@Serializable
private data class RoutineActivityWrite(
    val name: String,
    val kind: String,
    val category: String,
    @SerialName("target_min") val targetMin: Int?,
    val days: List<Int>?,
    @SerialName("time_of_day") val timeOfDay: String?,
    val remind: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class RoutineArchiveWrite(@SerialName("archived_at") val archivedAt: String?, @SerialName("updated_at") val updatedAt: String)

@Serializable
private data class RoutineLogRow(
    val id: Long,
    @SerialName("activity_id") val activityId: Long,
    val date: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val minutes: Double? = null,
    val status: String = "done",
    val source: String = "manual",
    val note: String? = null,
)

@Serializable
private data class RoutineLogWrite(
    @SerialName("activity_id") val activityId: Long,
    val date: String,
    @SerialName("started_at") val startedAt: String?,
    @SerialName("ended_at") val endedAt: String?,
    val minutes: Double?,
    val status: String,
    val source: String,
    val note: String?,
    @SerialName("client_key") val clientKey: String,
)

@Serializable
private data class RoutineLogEdit(
    @SerialName("activity_id") val activityId: Long,
    val date: String,
    @SerialName("started_at") val startedAt: String?,
    @SerialName("ended_at") val endedAt: String?,
    val minutes: Double?,
    val status: String,
    val note: String?,
)

@Serializable
private data class SessionTimeRow(val type: String, @SerialName("start_time") val startTime: String, @SerialName("duration_min") val durationMin: Double? = null)

@Serializable
private data class TripLegsRow(@SerialName("start_at") val startAt: String, val legs: JsonArray? = null)

@Serializable
private data class MobilityLightRow(val date: String, @SerialName("outdoor_daylight_min") val outdoorDaylightMin: Double? = null)

@Serializable
private data class PhoneCategoryRow(val date: String, @SerialName("by_category") val byCategory: JsonObject? = null)

@Serializable
private data class SleepHoursRow(val date: String, val hours: Double? = null)

/** A new block of time or a practice's answer, before it is saved. */
data class NewLog(
    val activityId: Long,
    val date: LocalDate,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val minutes: Double?,
    val status: LogStatus,
    val source: LogSource,
    val note: String?,
    /** Makes the save idempotent: the same key updates the one row. */
    val clientKey: String,
)

class PracticesRepository(private val supabase: SupabaseClient) {

    suspend fun activities(includeArchived: Boolean = false): List<RoutineActivity> =
        ReadCache.kept("routine.activities", tables = setOf(ACTIVITIES)) {
            supabase.postgrest.from(ACTIVITIES).select(Columns.list("id,name,kind,category,target_min,days,time_of_day,remind,archived_at"))
                .decodeList<RoutineActivityRow>().map { it.toDomain() }
        }.filter { includeArchived || !it.archived }.sortedBy { it.name.lowercase() }

    /** Creates ([id] null) or updates an activity; returns its id. */
    suspend fun saveActivity(id: Long?, a: RoutineActivity): Long {
        val write = RoutineActivityWrite(
            a.name.trim().take(80), a.kind.key, a.category.key, a.targetMin,
            a.days?.sorted()?.takeIf { a.kind == ActivityKind.PRACTICE && it.isNotEmpty() },
            a.time?.takeIf { a.kind == ActivityKind.PRACTICE }?.toString(), a.remind && a.kind == ActivityKind.PRACTICE && a.time != null,
            Instant.now().toString(),
        )
        return if (id == null) {
            supabase.postgrest.from(ACTIVITIES).insert(write) { select(Columns.list("id,name,kind")) }.decodeSingle<RoutineActivityRow>().id
        } else {
            supabase.postgrest.from(ACTIVITIES).update(write) { filter { eq("id", id) } }
            id
        }
    }

    /** Archived activities keep their history but leave the lists and the reminders. */
    suspend fun archive(id: Long, archived: Boolean = true) {
        supabase.postgrest.from(ACTIVITIES).update(RoutineArchiveWrite(if (archived) Instant.now().toString() else null, Instant.now().toString())) { filter { eq("id", id) } }
    }

    /** The logs from [from] on (local days). */
    suspend fun logsSince(from: LocalDate): List<RoutineLog> =
        ReadCache.kept("routine.logs.$from", tables = setOf(LOGS)) {
            supabase.postgrest.from(LOGS).select(Columns.list("id,activity_id,date,started_at,ended_at,minutes,status,source,note")) {
                filter { gte("date", from.toString()) }
            }.decodeList<RoutineLogRow>().mapNotNull { it.toDomain() }
        }

    suspend fun saveLog(l: NewLog) {
        supabase.postgrest.from(LOGS).upsert(
            RoutineLogWrite(
                l.activityId, l.date.toString(), l.start?.let(WallClock::write), l.end?.let(WallClock::write), l.minutes,
                l.status.key, l.source.key, l.note?.trim()?.take(500)?.ifBlank { null }, l.clientKey,
            ),
        ) { onConflict = "user_id,client_key" }
    }

    /** Changes a block in place; its key stays, so the reminder's DONE for that day still finds it. */
    suspend fun updateLog(id: Long, l: NewLog) {
        supabase.postgrest.from(LOGS).update(
            RoutineLogEdit(
                l.activityId, l.date.toString(), l.start?.let(WallClock::write), l.end?.let(WallClock::write), l.minutes,
                l.status.key, l.note?.trim()?.take(500)?.ifBlank { null },
            ),
        ) { filter { eq("id", id) } }
    }

    suspend fun deleteLog(id: Long) {
        supabase.postgrest.from(LOGS).delete { filter { eq("id", id) } }
    }

    /**
     * What other data shows for the days from [from]: training (logged times), trip legs and their local day (trips are
     * real instants), outdoor daylight, phone use by category and sleep. Each read is soft: a stream that is off or a
     * table that fails gives nothing, not an error.
     */
    suspend fun inferred(from: LocalDate, zone: ZoneId = ZoneId.systemDefault()): InferredInputs = coroutineScope {
        val sessions = async {
            catching {
                supabase.postgrest.from("exercise_sessions").select(Columns.list("type,start_time,duration_min")) {
                    filter { gte("start_time", from.toString()) }
                }.decodeList<SessionTimeRow>().mapNotNull { r ->
                    val d = WallClock.date(r.startTime) ?: return@mapNotNull null
                    val m = r.durationMin?.takeIf { it >= 10 } ?: return@mapNotNull null
                    Triple(r.type, d, m)
                }
            }.getOrDefault(emptyList())
        }
        val legs = async {
            catching {
                supabase.postgrest.from("trips").select(Columns.list("start_at,legs")) {
                    filter { gte("start_at", from.atStartOfDay(zone).toInstant().toString()) }
                }.decodeList<TripLegsRow>().flatMap { t ->
                    val d = runCatching { OffsetDateTime.parse(t.startAt).atZoneSameInstant(zone).toLocalDate() }.getOrNull() ?: return@flatMap emptyList()
                    t.legs.orEmpty().mapNotNull { leg ->
                        val o = leg as? JsonObject ?: return@mapNotNull null
                        val mode = o["mode"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val min = o["minutes"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                        Triple(mode, d, min)
                    }
                }
            }.getOrDefault(emptyList())
        }
        val mobility = async {
            catching {
                supabase.postgrest.from("mobility_daily").select(Columns.list("date,outdoor_daylight_min")) { filter { gte("date", from.toString()) } }
                    .decodeList<MobilityLightRow>().mapNotNull { r -> r.outdoorDaylightMin?.let { LocalDate.parse(r.date) to it } }.toMap()
            }.getOrDefault(emptyMap())
        }
        val phone = async {
            catching {
                supabase.postgrest.from("phone_usage_daily").select(Columns.list("date,by_category")) { filter { gte("date", from.toString()) } }
                    .decodeList<PhoneCategoryRow>().associate { r ->
                        LocalDate.parse(r.date) to r.byCategory.orEmpty().mapNotNull { (k, v) -> runCatching { v.jsonPrimitive.doubleOrNull }.getOrNull()?.let { k to it } }.toMap()
                    }
            }.getOrDefault(emptyMap())
        }
        val sleep = async {
            catching {
                supabase.postgrest.from("sleep_daily").select(Columns.list("date,hours")) { filter { gte("date", from.toString()) } }
                    .decodeList<SleepHoursRow>().mapNotNull { r -> r.hours?.let { LocalDate.parse(r.date) to it } }
                    .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.max() }
            }.getOrDefault(emptyMap())
        }
        InferredInputs(sessions.await(), legs.await(), mobility.await(), phone.await(), sleep.await())
    }

    private companion object {
        const val ACTIVITIES = "routine_activities"
        const val LOGS = "routine_logs"
    }
}

private fun RoutineActivityRow.toDomain() = RoutineActivity(
    id, name, ActivityKind.of(kind), ActivityCategory.of(category), targetMin, days?.toSet(),
    timeOfDay?.let { runCatching { LocalTime.parse(it) }.getOrNull() }, remind, archivedAt != null,
)

private fun RoutineLogRow.toDomain(): RoutineLog? = runCatching {
    RoutineLog(
        id, activityId, LocalDate.parse(date), WallClock.read(startedAt), WallClock.read(endedAt), minutes,
        LogStatus.of(status), LogSource.entries.firstOrNull { it.key == source } ?: LogSource.MANUAL, note,
    )
}.getOrNull()
