package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.GpxPoint
import com.bioscan.fieldterminal.domain.MapEvent
import com.bioscan.fieldterminal.domain.parseGpxLink
import com.bioscan.fieldterminal.domain.parseGpxPoints
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

class MapFetchException(message: String) : Exception(message)

private val json = Json { ignoreUnknownKeys = true }

// Takes a live Google access token (from GoogleAuthorizationManager, minted
// fresh per screen load -- see that file for why this app needs no stored
// refresh token the way the web dashboard does).
class MapRepository(private val accessToken: String) {
    private val client = HttpClient(Android)

    // Phase M1: every event in a rolling 24h window, not just the first
    // training-colored one -- replaces the old fetchNextSession(), which is
    // unused anywhere else in this app (confirmed by a project-wide search
    // before removing it, not assumed safe to drop).
    suspend fun fetchUpcomingEvents(): List<MapEvent> {
        val now = Instant.now()
        val dayOut = now.plus(24, ChronoUnit.HOURS)

        val response = client.get("https://www.googleapis.com/calendar/v3/calendars/primary/events") {
            header("Authorization", "Bearer $accessToken")
            url {
                parameters.append("timeMin", now.toString())
                parameters.append("timeMax", dayOut.toString())
                parameters.append("singleEvents", "true")
                parameters.append("orderBy", "startTime")
                parameters.append("maxResults", "50")
            }
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw MapFetchException(extractErrorMessage(bodyText) ?: "Calendar request failed (${response.status.value})")
        }

        val parsed = json.decodeFromString<CalendarEventsResponse>(bodyText)
        return parsed.items.mapNotNull { item ->
            val startIso = item.start.dateTime ?: item.start.date ?: return@mapNotNull null
            MapEvent(
                id = item.id,
                title = item.summary ?: "",
                startIso = startIso,
                endIso = item.end.dateTime ?: item.end.date,
                description = item.description ?: "",
                location = item.location?.takeIf { it.isNotBlank() },
                colorId = item.colorId,
                gpxLink = parseGpxLink(item.description),
            )
        }
    }

    // The GPX file lives in the user's own Drive, not publicly shared --
    // reading it needs the same drive.readonly-scoped access token as above.
    suspend fun fetchGpxPoints(gpxLink: String): List<GpxPoint> {
        val fileId = extractDriveFileId(gpxLink)
            ?: throw MapFetchException("Couldn't find a Drive file ID in this link.")

        val response = client.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header("Authorization", "Bearer $accessToken")
            url { parameters.append("alt", "media") }
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw MapFetchException(extractErrorMessage(bodyText) ?: "Drive request failed (${response.status.value})")
        }

        return parseGpxPoints(bodyText)
    }

    // Handles both share-link shapes Drive actually produces, same as the
    // web dashboard's extractDriveFileId().
    private fun extractDriveFileId(url: String): String? {
        Regex("""/file/d/([a-zA-Z0-9_-]+)""").find(url)?.let { return it.groupValues[1] }
        Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)?.let { return it.groupValues[1] }
        return null
    }

    private fun extractErrorMessage(body: String): String? =
        try {
            json.decodeFromString<GoogleErrorResponse>(body).error?.message
        } catch (e: Exception) {
            null
        }
}

@Serializable
private data class CalendarEventsResponse(val items: List<CalendarEvent> = emptyList())

@Serializable
private data class CalendarEvent(
    val id: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val colorId: String? = null,
    val start: CalendarEventDateTime = CalendarEventDateTime(),
    val end: CalendarEventDateTime = CalendarEventDateTime(),
)

@Serializable
private data class CalendarEventDateTime(val dateTime: String? = null, val date: String? = null)

@Serializable
private data class GoogleErrorResponse(val error: GoogleErrorDetail? = null)

@Serializable
private data class GoogleErrorDetail(val message: String? = null)
