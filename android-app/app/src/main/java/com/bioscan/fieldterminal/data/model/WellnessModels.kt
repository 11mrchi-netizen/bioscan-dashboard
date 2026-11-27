package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// wellness_sessions (supabase/migrations/20261127131608_wellness_sessions.sql): one guided wellness session. Counts and
// ratings only; there is no free-text column.

@Serializable
data class NewWellnessRow(
    @SerialName("started_at") val startedAt: String,
    @SerialName("programme_key") val programmeKey: String,
    @SerialName("stage_key") val stageKey: String,
    @SerialName("script_version") val scriptVersion: String,
    @SerialName("duration_s") val durationS: Int,
    val completed: Boolean,
    @SerialName("cycles_planned") val cyclesPlanned: Int,
    @SerialName("cycles_done") val cyclesDone: Int,
    @SerialName("peak_arousal") val peakArousal: Int? = null,
    @SerialName("late_stops") val lateStops: Int = 0,
    val felt: Int? = null,
)

@Serializable
data class WellnessRow(
    val id: Long,
    @SerialName("started_at") val startedAt: String,
    @SerialName("programme_key") val programmeKey: String,
    @SerialName("stage_key") val stageKey: String,
    @SerialName("duration_s") val durationS: Int,
    val completed: Boolean,
    @SerialName("cycles_planned") val cyclesPlanned: Int,
    @SerialName("cycles_done") val cyclesDone: Int,
    @SerialName("peak_arousal") val peakArousal: Int? = null,
    @SerialName("late_stops") val lateStops: Int = 0,
    val felt: Int? = null,
)
