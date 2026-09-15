package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.GpxPoint
import com.bioscan.fieldterminal.domain.NextSession
import com.bioscan.fieldterminal.domain.classifySessionKind
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

// Step 14. Same colorId '8' == training-session convention index.html's
// fetchNextSession() already established (confirmed there, not guessed) --
// more reliable than matching emoji/title text, which can vary. Meal-prep
// and other non-training events use different colorIds and are correctly
// excluded.
private const val TRAINING_COLOR_ID = "8"

private val json = Json { ignoreUnknownKeys = true }

// Takes a live Google access token (from GoogleAuthorizationManager, minted
// fresh per screen load -- see that file for why this app needs no stored
// refresh token the way the web dashboard does).
class MapRepository(private val accessToken: String) {
    private val client = HttpClient(Android)

    suspend fun fetchNextSession(): NextSession? {
        val now = Instant.now()
        val weekOut = now.plus(7, ChronoUnit.DAYS)

        val response = client.get("https://www.googleapis.com/calendar/v3/calendars/primary/events") {
            header("Authorization", "Bearer $accessToken")
            url {
                parameters.append("timeMin", now.toString())
                parameters.append("timeMax", weekOut.toString())
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
        val next = parsed.items.firstOrNull { it.colorId == TRAINING_COLOR_ID } ?: return null
        val summary = next.summary ?: ""
        val startIso = next.start.dateTime ?: next.start.date ?: return null

        return NextSession(
            title = summary,
            startIso = startIso,
            description = next.description ?: "",
            kind = classifySessionKind(summary),
            gpxLink = parseGpxLink(next.description),
        )
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
    val summary: String? = null,
    val description: String? = null,
    val colorId: String? = null,
    val start: CalendarEventDateTime = CalendarEventDateTime(),
)

@Serializable
private data class CalendarEventDateTime(val dateTime: String? = null, val date: String? = null)

@Serializable
private data class GoogleErrorResponse(val error: GoogleErrorDetail? = null)

@Serializable
private data class GoogleErrorDetail(val message: String? = null)
