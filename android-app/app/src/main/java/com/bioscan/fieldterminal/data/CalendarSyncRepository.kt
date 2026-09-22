package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.CalendarAttachmentRow
import com.bioscan.fieldterminal.data.model.CalendarSyncStateRow
import com.bioscan.fieldterminal.data.model.NewCalendarEventRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

// DAV-145. Distinct from data/MapRepository.kt's fetchUpcomingEvents() on
// purpose (doc 01): that one is a live, unpersisted 24h-window read for the
// Map tab's own preview, rebuilt from scratch every screen load. This is a
// genuine incremental sync -- Google's own syncToken protocol, a local
// Supabase cache, dedup by event_id, cancellations reflected -- feeding
// DAV-146/148's GPX-preview pipeline, which runs independently of whether
// the Map tab is even open.

sealed interface CalendarSyncResult {
    data class Success(val eventCount: Int) : CalendarSyncResult
    data class Failure(val message: String) : CalendarSyncResult
}

private class SyncTokenExpiredException : Exception("Calendar sync token expired, full resync required")

private val json = Json { ignoreUnknownKeys = true }

class CalendarSyncRepository(
    private val accessToken: String,
    private val supabase: SupabaseClient,
) {
    private val client = HttpClient(Android)

    suspend fun sync(calendarId: String = "primary"): CalendarSyncResult = try {
        runSync(calendarId, useStoredToken = true)
    } catch (e: SyncTokenExpiredException) {
        // Google's own documented recovery: an expired/invalid syncToken
        // can't resolve to a delta anymore, so the cached side must be
        // dropped and reseeded from a full sync -- not treated as a
        // reportable failure, since this is Google's normal, expected
        // token-lifecycle behavior, not an error condition.
        try {
            clearEvents(calendarId)
            runSync(calendarId, useStoredToken = false)
        } catch (inner: Exception) {
            CalendarSyncResult.Failure(inner.message ?: "Full resync failed")
        }
    } catch (e: Exception) {
        CalendarSyncResult.Failure(e.message ?: "Calendar sync failed")
    }

    private suspend fun runSync(calendarId: String, useStoredToken: Boolean): CalendarSyncResult {
        val syncToken = if (useStoredToken) loadSyncState(calendarId)?.syncToken else null
        val result = fetchAllPages(calendarId, syncToken)
        applyEvents(calendarId, result.events)
        saveSyncState(calendarId, result.nextSyncToken)
        return CalendarSyncResult.Success(result.events.size)
    }

    private suspend fun loadSyncState(calendarId: String): CalendarSyncStateRow? =
        supabase.postgrest.from("calendar_sync_state")
            .select { filter { eq("calendar_id", calendarId) } }
            .decodeList<CalendarSyncStateRow>()
            .firstOrNull()

    private suspend fun saveSyncState(calendarId: String, syncToken: String?) {
        supabase.postgrest.from("calendar_sync_state").upsert(
            CalendarSyncStateRow(calendarId = calendarId, syncToken = syncToken, lastSyncedAt = Instant.now().toString()),
        ) { onConflict = "user_id,calendar_id" }
    }

    private suspend fun clearEvents(calendarId: String) {
        supabase.postgrest.from("calendar_events").delete { filter { eq("calendar_id", calendarId) } }
    }

    private suspend fun applyEvents(calendarId: String, events: List<GoogleCalendarEvent>) {
        if (events.isEmpty()) return
        supabase.postgrest.from("calendar_events")
            .upsert(events.map { it.toRow(calendarId) }) { onConflict = "user_id,calendar_id,event_id" }
    }

    private data class FetchResult(val events: List<GoogleCalendarEvent>, val nextSyncToken: String?)

    // Full sync (no syncToken): every non-cancelled upcoming event, with
    // showDeleted=true so this same request shape also works as the
    // baseline a later incremental sync can build on. Incremental sync
    // (with syncToken): only what changed since then, deletions included --
    // singleEvents must stay the same true value on both request kinds,
    // Google's own requirement, not an accidental default.
    private suspend fun fetchAllPages(calendarId: String, syncToken: String?): FetchResult {
        val events = mutableListOf<GoogleCalendarEvent>()
        var pageToken: String? = null
        var nextSyncToken: String? = null

        do {
            val response = client.get("https://www.googleapis.com/calendar/v3/calendars/$calendarId/events") {
                header("Authorization", "Bearer $accessToken")
                url {
                    if (syncToken != null) {
                        parameters.append("syncToken", syncToken)
                    } else {
                        parameters.append("timeMin", Instant.now().toString())
                        parameters.append("showDeleted", "true")
                    }
                    parameters.append("singleEvents", "true")
                    parameters.append("maxResults", "250")
                    pageToken?.let { parameters.append("pageToken", it) }
                }
            }

            val bodyText = response.bodyAsText()
            if (response.status == HttpStatusCode.Gone) throw SyncTokenExpiredException()
            if (!response.status.isSuccess()) {
                throw MapFetchException(extractErrorMessage(bodyText) ?: "Calendar sync request failed (${response.status.value})")
            }

            val page = json.decodeFromString<GoogleCalendarEventsPage>(bodyText)
            events += page.items
            pageToken = page.nextPageToken
            page.nextSyncToken?.let { nextSyncToken = it }
        } while (pageToken != null)

        return FetchResult(events, nextSyncToken)
    }

    private fun extractErrorMessage(body: String): String? = try {
        json.decodeFromString<GoogleCalendarErrorResponse>(body).error?.message
    } catch (e: Exception) {
        null
    }
}

// Same shape as MapRepository.kt's own error-response classes, but a
// distinct name -- Kotlin's top-level `private` scopes *visibility* to the
// file, not the declared name itself, so two files in one package still
// can't each declare their own "GoogleErrorResponse" (confirmed live: this
// was a real "Redeclaration" compile error, not a hypothetical one).
@Serializable
private data class GoogleCalendarErrorResponse(val error: GoogleCalendarErrorDetail? = null)

@Serializable
private data class GoogleCalendarErrorDetail(val message: String? = null)

@Serializable
private data class GoogleCalendarEventsPage(
    val items: List<GoogleCalendarEvent> = emptyList(),
    val nextPageToken: String? = null,
    val nextSyncToken: String? = null,
)

@Serializable
private data class GoogleCalendarEvent(
    val id: String,
    val status: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val colorId: String? = null,
    val updated: String? = null,
    val start: GoogleCalendarEventDateTime = GoogleCalendarEventDateTime(),
    val end: GoogleCalendarEventDateTime = GoogleCalendarEventDateTime(),
    val attachments: List<GoogleCalendarAttachment>? = null,
) {
    fun toRow(calendarId: String): NewCalendarEventRow = NewCalendarEventRow(
        calendarId = calendarId,
        eventId = id,
        title = summary,
        description = description,
        startTime = start.dateTime ?: start.date,
        endTime = end.dateTime ?: end.date,
        colorId = colorId,
        attachments = attachments?.map { CalendarAttachmentRow(it.fileId, it.fileUrl, it.mimeType, it.title) },
        isCancelled = status == "cancelled",
        googleUpdatedAt = updated,
    )
}

@Serializable
private data class GoogleCalendarEventDateTime(val dateTime: String? = null, val date: String? = null)

@Serializable
private data class GoogleCalendarAttachment(
    val fileId: String? = null,
    val fileUrl: String? = null,
    val mimeType: String? = null,
    val title: String? = null,
)
