package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DAV-145. Local cache of Google's own event shape, kept fresh via
// Calendar's real incremental-sync protocol -- see data/CalendarSyncRepository.kt.

@Serializable
data class CalendarSyncStateRow(
    @SerialName("calendar_id") val calendarId: String = "primary",
    @SerialName("sync_token") val syncToken: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

@Serializable
data class CalendarAttachmentRow(
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val title: String? = null,
)

@Serializable
data class NewCalendarEventRow(
    @SerialName("calendar_id") val calendarId: String,
    @SerialName("event_id") val eventId: String,
    val title: String? = null,
    val description: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("color_id") val colorId: String? = null,
    val attachments: List<CalendarAttachmentRow>? = null,
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
    @SerialName("google_updated_at") val googleUpdatedAt: String? = null,
)

@Serializable
data class CalendarEventRow(
    val id: Long,
    @SerialName("calendar_id") val calendarId: String,
    @SerialName("event_id") val eventId: String,
    val title: String? = null,
    val description: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("color_id") val colorId: String? = null,
    val attachments: List<CalendarAttachmentRow>? = null,
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
)
