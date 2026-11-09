package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.DrawEventLink
import com.bioscan.fieldterminal.data.model.DrawRow
import com.bioscan.fieldterminal.data.model.EpisodicIntervention
import com.bioscan.fieldterminal.data.model.EventEvidenceRow
import com.bioscan.fieldterminal.data.model.EventRatingPatch
import com.bioscan.fieldterminal.data.model.EventRow
import com.bioscan.fieldterminal.data.model.ExpectationRow
import com.bioscan.fieldterminal.data.model.NewEpisodicIntervention
import com.bioscan.fieldterminal.data.model.NewDraw
import com.bioscan.fieldterminal.data.model.NewEvent
import com.bioscan.fieldterminal.data.model.NewExpectation
import com.bioscan.fieldterminal.data.model.NewTrackedOutcome
import com.bioscan.fieldterminal.data.model.NewTrackedValue
import com.bioscan.fieldterminal.data.model.TrackedOutcomeRow
import com.bioscan.fieldterminal.data.model.TrackedValueTime
import com.bioscan.fieldterminal.domain.DrawResult
import com.bioscan.fieldterminal.domain.ExpectationDraft
import com.bioscan.fieldterminal.domain.LocalStamp
import com.bioscan.fieldterminal.domain.validateExpectations
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

// Repeatable, timed interventions: definitions (interventions with mode 'episodic'), the expectations declared for them,
// individual events, user-defined tracked outcomes and their values, and the evidence analysis-run publishes. Every read
// is optional enrichment: a failed one returns an empty result rather than failing the screen.
class InterventionEventsRepository(private val supabase: SupabaseClient) {

    suspend fun loadInterventions(): List<EpisodicIntervention> = try {
        supabase.postgrest.from("interventions")
            .select(columns = Columns.list("id,name,instructions,domain,lifecycle_state,version,draw_enabled")) { filter { eq("mode", "episodic") } }
            .decodeList<EpisodicIntervention>()
            .filter { it.lifecycleState != "stopped" }
            .sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun loadExpectations(): List<ExpectationRow> = try {
        supabase.postgrest.from("intervention_expectations").select().decodeList<ExpectationRow>()
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun loadRecentEvents(limit: Long = 60): List<EventRow> = try {
        supabase.postgrest.from("intervention_events")
            .select(columns = Columns.list("id,intervention_id,occurred_at,local_date,adhered,feeling,note,rated_at")) {
                order("occurred_at", Order.DESCENDING)
                limit(limit)
            }
            .decodeList<EventRow>()
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun loadEvidence(): Map<String, EventEvidenceRow> = try {
        supabase.postgrest.from("analysis_results")
            .select(columns = Columns.list("key,state,payload")) { filter { eq("kind", "event_evidence") } }
            .decodeList<EventEvidenceRow>()
            .associateBy { it.key }
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * Declares the intervention and its expectations. The database locks an expectation once an event exists, so this is
     * the pre-registration of the windows. If the expectations can't be written the intervention is removed again.
     */
    suspend fun createIntervention(name: String, instructions: String?, domain: String, drafts: List<ExpectationDraft>, drawEnabled: Boolean = false): String {
        validateExpectations(drafts)?.let { throw IllegalArgumentException(it) }
        val id = UUID.randomUUID().toString()
        supabase.postgrest.from("interventions").insert(
            NewEpisodicIntervention(
                id = id, domain = domain, subjectType = "event_series", subjectKey = id, name = name.trim().take(80),
                instructions = instructions?.trim()?.takeIf { it.isNotEmpty() }, mode = "episodic", lifecycleState = "active", source = "events", drawEnabled = drawEnabled,
            ),
        )
        try {
            supabase.postgrest.from("intervention_expectations").insert(
                drafts.map {
                    NewExpectation(id, it.outcomeKind, it.outcomeKey, it.role.key, it.direction.key, it.startH, it.endH, it.aggregation)
                },
            )
        } catch (e: Exception) {
            runCatching { supabase.postgrest.from("interventions").delete { filter { eq("id", id) } } }
            throw e
        }
        return id
    }

    /** Parks an intervention (lifecycle 'stopped'); its events and evidence stay. */
    suspend fun park(interventionId: String) {
        supabase.postgrest.from("interventions").update(mapOf("lifecycle_state" to "stopped")) { filter { eq("id", interventionId) } }
    }

    suspend fun logEvent(
        intervention: EpisodicIntervention,
        expectations: List<ExpectationRow>,
        occurredAt: Instant,
        stamp: LocalStamp,
        adhered: String,
        confounders: List<String>,
        doseNote: String?,
        note: String?,
        assignedBy: String = "self",
    ): String {
        val id = UUID.randomUUID().toString()
        val snapshot = JsonArray(
            expectations.filter { it.interventionId == intervention.id }.map {
                JsonObject(
                    mapOf(
                        "outcome_kind" to JsonPrimitive(it.outcomeKind), "outcome_key" to JsonPrimitive(it.outcomeKey), "role" to JsonPrimitive(it.role),
                        "direction" to JsonPrimitive(it.direction), "window_start_h" to JsonPrimitive(it.windowStartH), "window_end_h" to JsonPrimitive(it.windowEndH),
                    ),
                )
            },
        )
        supabase.postgrest.from("intervention_events").insert(
            NewEvent(
                id = id, interventionId = intervention.id, interventionVersion = intervention.version, occurredAt = occurredAt.toString(),
                localDate = stamp.date, localMinute = stamp.minute, adhered = adhered, doseNote = doseNote?.trim()?.takeIf { it.isNotEmpty() },
                confounders = confounders, note = note?.trim()?.takeIf { it.isNotEmpty() }, assignedBy = assignedBy, expectationSnapshot = snapshot,
            ),
        )
        return id
    }

    /** Recent draws, newest first; a failed read is an empty list (the draw button then simply shows). */
    suspend fun loadDraws(limit: Long = 60): List<DrawRow> = try {
        supabase.postgrest.from("intervention_draws").select { order("local_date", Order.DESCENDING); limit(limit) }.decodeList<DrawRow>()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Stores today's draw before anything is shown or logged. The (intervention, day) pair is unique in the database, so a
     * second attempt fails rather than redrawing; the caller reloads and shows the stored result.
     */
    suspend fun saveDraw(interventionId: String, localDate: String, result: DrawResult): String {
        val id = UUID.randomUUID().toString()
        supabase.postgrest.from("intervention_draws").insert(NewDraw(id, interventionId, localDate, result.key))
        return id
    }

    suspend fun linkDrawEvent(drawId: String, eventId: String) {
        supabase.postgrest.from("intervention_draws").update(DrawEventLink(eventId)) { filter { eq("id", drawId) } }
    }

    /** Recent logged values (outcome id and time), for the follow-up reminder. */
    suspend fun loadRecentTrackedValueTimes(limit: Long = 200): List<TrackedValueTime> = try {
        supabase.postgrest.from("tracked_outcome_values")
            .select(columns = Columns.list("outcome_id,observed_at")) { order("observed_at", Order.DESCENDING); limit(limit) }
            .decodeList<TrackedValueTime>()
    } catch (e: Exception) {
        emptyList()
    }

    /** The morning rating, saved before any numbers are shown. */
    suspend fun rateEvent(eventId: String, feeling: Int, note: String?) {
        supabase.postgrest.from("intervention_events").update(EventRatingPatch(feeling.coerceIn(0, 10), Instant.now().toString(), note?.trim()?.takeIf { it.isNotEmpty() })) {
            filter { eq("id", eventId) }
        }
    }

    suspend fun loadTrackedOutcomes(): List<TrackedOutcomeRow> = try {
        supabase.postgrest.from("tracked_outcomes").select().decodeList<TrackedOutcomeRow>().filter { it.archivedAt == null }.sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun createTrackedOutcome(name: String, scale: String, betterDirection: String, unit: String?) {
        supabase.postgrest.from("tracked_outcomes").insert(NewTrackedOutcome(name.trim().take(60), scale, betterDirection, unit?.trim()?.takeIf { it.isNotEmpty() }))
    }

    suspend fun logTrackedValue(outcomeId: String, observedAt: Instant, stamp: LocalStamp, value: Double, note: String?) {
        supabase.postgrest.from("tracked_outcome_values").insert(
            NewTrackedValue(outcomeId, observedAt.toString(), stamp.date, stamp.minute, value, note?.trim()?.takeIf { it.isNotEmpty() }),
        )
    }
}
