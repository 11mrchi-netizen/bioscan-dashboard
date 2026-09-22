package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DAV-147/148. Course-demand-only fields (no performance metrics, no
// reconciliation columns) -- see docs/trail-intelligence/07-planned-route-data-model.md.
@Serializable
data class NewPlannedRouteRow(
    @SerialName("calendar_id") val calendarId: String,
    @SerialName("calendar_event_id") val calendarEventId: String,
    @SerialName("event_title") val eventTitle: String? = null,
    @SerialName("event_start_time") val eventStartTime: String,
    @SerialName("drive_file_id") val driveFileId: String? = null,
    @SerialName("drive_source_url") val driveSourceUrl: String? = null,
    @SerialName("gpx_checksum") val gpxChecksum: String? = null,
    @SerialName("gpx_storage_path") val gpxStoragePath: String? = null,
    @SerialName("parser_version") val parserVersion: String? = null,
    @SerialName("terrain_analysis_version") val terrainAnalysisVersion: String? = null,
    @SerialName("is_stale") val isStale: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("elevation_loss_m") val elevationLossM: Double? = null,
    @SerialName("mountain_index") val mountainIndex: Double? = null,
    @SerialName("km_effort") val kmEffort: Double? = null,
    @SerialName("grade_distribution") val gradeDistribution: Map<String, Double>? = null,
    @SerialName("confidence_tier") val confidenceTier: String? = null,
    @SerialName("computed_at") val computedAt: String? = null,
)

@Serializable
data class PlannedRouteFailureUpdate(
    @SerialName("is_stale") val isStale: Boolean = true,
    @SerialName("last_error") val lastError: String,
)

// DAV-151. Confirms an existing preview is still current (checksum + both
// algorithm versions matched) without touching any of its real numeric
// fields -- nothing was recomputed, so nothing about the course should
// change, only that it's known-fresh again.
@Serializable
data class PlannedRouteFreshUpdate(
    @SerialName("is_stale") val isStale: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("computed_at") val computedAt: String,
)

@Serializable
data class PlannedRouteRow(
    val id: Long,
    @SerialName("calendar_event_id") val calendarEventId: String,
    @SerialName("event_title") val eventTitle: String? = null,
    @SerialName("event_start_time") val eventStartTime: String,
    @SerialName("drive_file_id") val driveFileId: String? = null,
    @SerialName("gpx_checksum") val gpxChecksum: String? = null,
    @SerialName("parser_version") val parserVersion: String? = null,
    @SerialName("terrain_analysis_version") val terrainAnalysisVersion: String? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("elevation_loss_m") val elevationLossM: Double? = null,
    @SerialName("mountain_index") val mountainIndex: Double? = null,
    @SerialName("km_effort") val kmEffort: Double? = null,
    @SerialName("grade_distribution") val gradeDistribution: Map<String, Double>? = null,
    @SerialName("confidence_tier") val confidenceTier: String? = null,
    @SerialName("is_stale") val isStale: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("computed_at") val computedAt: String? = null,
)
