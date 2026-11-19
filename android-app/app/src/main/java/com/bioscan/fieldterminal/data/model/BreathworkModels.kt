package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// breathwork_sessions (supabase/migrations/20261118160000_breathwork_sessions.sql): one guided breathing session.

/** One step of the pattern as it was run; `kind` is a BreathStepKind name. */
@Serializable
data class BreathStepJson(val kind: String, val seconds: Double)

@Serializable
data class NewBreathworkRow(
    @SerialName("started_at") val startedAt: String,
    @SerialName("pattern_key") val patternKey: String,
    @SerialName("pattern_name") val patternName: String,
    val steps: List<BreathStepJson>,
    @SerialName("rounds_planned") val roundsPlanned: Int,
    @SerialName("rounds_done") val roundsDone: Int,
    @SerialName("duration_s") val durationS: Int,
    val completed: Boolean,
)

@Serializable
data class LogBreathworkRow(
    val id: Long,
    @SerialName("started_at") val startedAt: String,
    @SerialName("pattern_name") val patternName: String,
    val steps: List<BreathStepJson> = emptyList(),
    @SerialName("rounds_planned") val roundsPlanned: Int,
    @SerialName("rounds_done") val roundsDone: Int,
    @SerialName("duration_s") val durationS: Int,
    val completed: Boolean,
)
