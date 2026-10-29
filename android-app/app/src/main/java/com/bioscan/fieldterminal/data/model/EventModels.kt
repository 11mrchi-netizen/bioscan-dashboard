package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Repeatable, timed interventions (20261113_intervention_events.sql). Read models default every field so an older or
// partial payload still decodes; insert models carry every column explicitly.

@Serializable
data class EpisodicIntervention(
    val id: String,
    val name: String = "",
    val instructions: String? = null,
    val domain: String = "other",
    @SerialName("lifecycle_state") val lifecycleState: String = "active",
    val version: Int = 1,
    @SerialName("draw_enabled") val drawEnabled: Boolean = false,
)

@Serializable
data class ExpectationRow(
    val id: String,
    @SerialName("intervention_id") val interventionId: String,
    @SerialName("outcome_kind") val outcomeKind: String = "series",
    @SerialName("outcome_key") val outcomeKey: String = "",
    val role: String = "primary",
    val direction: String = "none",
    @SerialName("window_start_h") val windowStartH: Double = 0.0,
    @SerialName("window_end_h") val windowEndH: Double = 24.0,
    val aggregation: String = "mean",
    val exploratory: Boolean = false,
)

@Serializable
data class EventRow(
    val id: String,
    @SerialName("intervention_id") val interventionId: String,
    @SerialName("occurred_at") val occurredAt: String = "",
    @SerialName("local_date") val localDate: String = "",
    val adhered: String = "yes",
    val feeling: Int? = null,
    val note: String? = null,
    @SerialName("rated_at") val ratedAt: String? = null,
)

@Serializable
data class TrackedOutcomeRow(
    val id: String,
    val name: String = "",
    val scale: String = "rating_0_10",
    @SerialName("better_direction") val betterDirection: String = "none",
    val unit: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
)

@Serializable
data class NewEpisodicIntervention(
    val id: String,
    val domain: String,
    @SerialName("subject_type") val subjectType: String,
    @SerialName("subject_key") val subjectKey: String,
    val name: String,
    val instructions: String?,
    val mode: String,
    @SerialName("lifecycle_state") val lifecycleState: String,
    val source: String,
    @SerialName("draw_enabled") val drawEnabled: Boolean = false,
)

// The daily coin flip (20261115): one draw per intervention and local day, never redrawn.
@Serializable
data class DrawRow(
    val id: String,
    @SerialName("intervention_id") val interventionId: String,
    @SerialName("local_date") val localDate: String = "",
    val result: String = "skip",
    @SerialName("event_id") val eventId: String? = null,
)

@Serializable
data class NewDraw(
    val id: String,
    @SerialName("intervention_id") val interventionId: String,
    @SerialName("local_date") val localDate: String,
    val result: String,
)

@Serializable
data class DrawEventLink(@SerialName("event_id") val eventId: String)

@Serializable
data class NewExpectation(
    @SerialName("intervention_id") val interventionId: String,
    @SerialName("outcome_kind") val outcomeKind: String,
    @SerialName("outcome_key") val outcomeKey: String,
    val role: String,
    val direction: String,
    @SerialName("window_start_h") val windowStartH: Double,
    @SerialName("window_end_h") val windowEndH: Double,
    val aggregation: String,
)

@Serializable
data class NewEvent(
    val id: String,
    @SerialName("intervention_id") val interventionId: String,
    @SerialName("intervention_version") val interventionVersion: Int,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("local_minute") val localMinute: Int,
    val adhered: String,
    @SerialName("dose_note") val doseNote: String?,
    val confounders: List<String>,
    val note: String?,
    @SerialName("assigned_by") val assignedBy: String,
    @SerialName("expectation_snapshot") val expectationSnapshot: JsonElement,
)

@Serializable
data class NewTrackedOutcome(
    val name: String,
    val scale: String,
    @SerialName("better_direction") val betterDirection: String,
    val unit: String?,
)

@Serializable
data class NewTrackedValue(
    @SerialName("outcome_id") val outcomeId: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("local_minute") val localMinute: Int,
    val value: Double,
    val note: String?,
)

@Serializable
data class EventRatingPatch(
    val feeling: Int,
    @SerialName("rated_at") val ratedAt: String,
    val note: String?,
)

// What analysis-run publishes (analysis_results kind 'event_evidence', key = intervention id).
@Serializable
data class EventRandomised(
    val nDo: Int = 0,
    val nSkip: Int = 0,
    val diff: Double? = null,
    val lo: Double? = null,
    val hi: Double? = null,
    val observed: String = "too_few",
)

@Serializable
data class EventExpectationResult(
    val label: String = "",
    val role: String = "primary",
    val exploratory: Boolean = false,
    val direction: String = "none",
    val windowStartH: Double = 0.0,
    val windowEndH: Double = 24.0,
    val nWithData: Int = 0,
    val nReference: Int = 0,
    val diff: Double? = null,
    val lo: Double? = null,
    val hi: Double? = null,
    val unit: String? = null,
    val observed: String = "too_few",
    val summary: String = "",
    val randomised: EventRandomised? = null,
)

@Serializable
data class EventDraws(val nDo: Int = 0, val nSkip: Int = 0, val kept: Int = 0, val missed: Int = 0, val broken: Int = 0)

@Serializable
data class EventFeeling(val n: Int = 0, val mean: Double? = null)

@Serializable
data class EventEvidencePayload(
    val name: String = "",
    val nEvents: Int = 0,
    val nUsed: Int = 0,
    val ladder: String = "anecdote",
    val assignment: String = "self",
    val feeling: EventFeeling = EventFeeling(),
    val draws: EventDraws? = null,
    val expectations: List<EventExpectationResult> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class EventEvidenceRow(val key: String, val state: String = "anecdote", val payload: EventEvidencePayload = EventEvidencePayload())
