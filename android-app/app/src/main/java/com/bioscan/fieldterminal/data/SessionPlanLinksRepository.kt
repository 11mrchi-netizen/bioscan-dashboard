package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.StoredLink
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

// Which plan each recording was (supabase/migrations/20261110040502_session_plan_links.sql; domain/fuel/SessionMatching.kt).
// 'manual' rows are the person's answers; 'auto' rows keep the app's own match once its day is over, so it no longer
// changes under the fuel schedule, the debrief and the event that hang on it.

@Serializable
private data class LinkRow(
    @SerialName("exercise_session_id") val sessionId: Long,
    @SerialName("plan_ref") val planRef: String? = null,
    @SerialName("plan_title") val planTitle: String? = null,
    val source: String,
    val confidence: Double? = null,
)

@Serializable
private data class LinkWrite(
    @SerialName("exercise_session_id") val sessionId: Long,
    @SerialName("plan_ref") val planRef: String?,
    @SerialName("plan_title") val planTitle: String?,
    @SerialName("plan_date") val planDate: String?,
    val source: String,
    val confidence: Double?,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class EventSessionWrite(@SerialName("exercise_session_id") val sessionId: Long)

class SessionPlanLinksRepository(private val supabase: SupabaseClient) {

    /** Every stored link, by recording. Empty before the migration is applied. */
    suspend fun load(): Map<Long, StoredLink> = catching {
        ReadCache.kept("fuel.session_plan_links", tables = setOf(TABLE)) {
            supabase.postgrest.from(TABLE).select(Columns.list("exercise_session_id,plan_ref,plan_title,source,confidence"))
                .decodeList<LinkRow>()
                .map { StoredLink(it.sessionId, it.planRef, it.source == "manual", it.confidence, it.planTitle) }
        }
    }.getOrDefault(emptyList()).associateBy { it.sessionId }

    suspend fun linkFor(sessionId: Long): StoredLink? = load()[sessionId]

    /**
     * The person's answer: [sessionId] was [planRef] (null: it was not planned). A plan is one recording, so whatever
     * else was linked to that plan lets go of it first.
     */
    suspend fun setManual(sessionId: Long, planRef: String?, planTitle: String?, planDate: LocalDate?) {
        if (planRef != null) {
            supabase.postgrest.from(TABLE).delete { filter { eq("plan_ref", planRef); neq("exercise_session_id", sessionId) } }
        }
        supabase.postgrest.from(TABLE).upsert(
            LinkWrite(sessionId, planRef, planTitle?.take(300), planDate?.toString(), "manual", 1.0, Instant.now().toString()),
        ) { onConflict = "user_id,exercise_session_id" }
        if (planRef != null) linkEvent(sessionId, planRef)
    }

    /** Forgets the answer for [sessionId]: the app matches it again. */
    suspend fun clear(sessionId: Long) {
        supabase.postgrest.from(TABLE).delete { filter { eq("exercise_session_id", sessionId) } }
    }

    /**
     * Keeps the automatic matches of the days before [today] that are not stored yet. Each recording is tried once per
     * run of the app, so a refused write (the plan already taken by another recording) is not retried on every load.
     */
    suspend fun keepFinished(days: List<PlannedDay>, today: LocalDate) {
        days.filter { it.date.isBefore(today) }.flatMap { d -> d.items.map { d.date to it } }
            .filter { (_, it) -> it.recorded && it.planRef != null && !it.matchStored }
            .forEach { (date, item) ->
                val id = item.sessionId ?: return@forEach
                if (tried.putIfAbsent(id, true) != null) return@forEach
                catching {
                    supabase.postgrest.from(TABLE).upsert(
                        LinkWrite(id, item.planRef, item.planTitle?.take(300), date.toString(), "auto", item.matchConfidence, Instant.now().toString()),
                    ) { onConflict = "user_id,exercise_session_id"; ignoreDuplicates = true }
                    linkEvent(id, item.planRef!!)
                }
            }
    }

    /** An event matched to a recording gets it as its session when it has none yet (the event's debrief reads it). */
    private suspend fun linkEvent(sessionId: Long, planRef: String) {
        val eventId = planRef.removePrefix("event:").takeIf { planRef.startsWith("event:") }?.toLongOrNull() ?: return
        catching {
            supabase.postgrest.from("athlete_events").update(EventSessionWrite(sessionId)) {
                filter { eq("id", eventId); exact("exercise_session_id", null) }
            }
        }
    }

    private companion object {
        const val TABLE = "session_plan_links"
        val tried = ConcurrentHashMap<Long, Boolean>()
    }
}
