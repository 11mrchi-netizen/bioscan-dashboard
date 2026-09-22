package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.CalendarEventRow
import com.bioscan.fieldterminal.data.model.NewPlannedRouteRow
import com.bioscan.fieldterminal.data.model.PlannedRouteFailureUpdate
import com.bioscan.fieldterminal.domain.trail.SEGMENTATION_MODEL_VERSION
import com.bioscan.fieldterminal.domain.trail.TRACKPOINT_MODEL_VERSION
import com.bioscan.fieldterminal.domain.trail.computePlannedRoutePreview
import com.bioscan.fieldterminal.domain.trail.resolveGpxDriveFileId
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.time.Instant

// DAV-148. The end-to-end pipeline: a synced calendar_events row (DAV-145)
// -> resolved Drive file (DAV-146) -> downloaded GPX -> domain/trail's pure
// computePlannedRoutePreview() -> a cached planned_routes row. I/O only --
// every real calculation lives in domain/trail/PlannedRoutePreview.kt.
sealed interface PlannedRoutePreviewResult {
    data class Success(val distanceM: Double?) : PlannedRoutePreviewResult
    data object NoGpxLinked : PlannedRoutePreviewResult
    data class Failure(val message: String) : PlannedRoutePreviewResult
}

class PlannedRouteRepository(
    private val accessToken: String,
    private val supabase: SupabaseClient,
) {
    private val client = HttpClient(Android)

    // A failed download/parse never blocks the caller from moving on to the
    // next event (DAV-148's own acceptance criteria) -- every real failure
    // resolves to a Failure result, matching CalendarSyncRepository's same
    // never-throw-past-this-boundary convention.
    suspend fun generatePreview(event: CalendarEventRow): PlannedRoutePreviewResult {
        val resolved = resolveGpxDriveFileId(event.attachments, event.description)
            ?: return PlannedRoutePreviewResult.NoGpxLinked

        return try {
            val gpxText = downloadGpx(resolved.driveFileId)
            val preview = computePlannedRoutePreview(gpxText)

            supabase.postgrest.from("planned_routes").upsert(
                NewPlannedRouteRow(
                    calendarId = event.calendarId,
                    calendarEventId = event.eventId,
                    eventTitle = event.title,
                    eventStartTime = event.startTime ?: Instant.now().toString(),
                    driveFileId = resolved.driveFileId,
                    gpxChecksum = preview.gpxChecksum,
                    parserVersion = TRACKPOINT_MODEL_VERSION,
                    terrainAnalysisVersion = SEGMENTATION_MODEL_VERSION,
                    distanceM = preview.courseDemand.totalDistanceM,
                    elevationGainM = preview.courseDemand.elevationGainM,
                    elevationLossM = preview.courseDemand.elevationLossM,
                    mountainIndex = preview.courseDemand.mountainIndex,
                    kmEffort = preview.courseDemand.kmEffort,
                    gradeDistribution = preview.gradeDistributionByBand,
                    confidenceTier = preview.confidenceTier,
                    computedAt = Instant.now().toString(),
                    isStale = false,
                    lastError = null,
                ),
            ) { onConflict = "user_id,calendar_event_id" }

            PlannedRoutePreviewResult.Success(preview.courseDemand.totalDistanceM)
        } catch (e: Exception) {
            // Best-effort: marks an *existing* preview stale rather than
            // clobbering last-good numbers with nulls. A brand-new event
            // that's never once succeeded has nothing to mark -- DAV-151
            // owns the fuller freshness policy (retry cadence, first-failure
            // visibility); this is deliberately the minimum DAV-148 itself
            // needs, not that ticket's whole job pulled forward.
            try {
                supabase.postgrest.from("planned_routes")
                    .update(PlannedRouteFailureUpdate(lastError = e.message ?: "Preview generation failed")) {
                        filter { eq("calendar_event_id", event.eventId) }
                    }
            } catch (updateFailure: Exception) {
                // Swallow -- the original failure is what matters to the caller.
            }
            PlannedRoutePreviewResult.Failure(e.message ?: "Preview generation failed")
        }
    }

    // The pipeline's other half: which real, upcoming, non-cancelled events
    // (DAV-145's sync already keeps this current) are candidates for a
    // preview at all. generatePreview() itself decides per-event whether a
    // GPX is actually linked.
    suspend fun loadUpcomingEvents(calendarId: String = "primary"): List<CalendarEventRow> =
        supabase.postgrest.from("calendar_events")
            .select {
                filter {
                    eq("calendar_id", calendarId)
                    eq("is_cancelled", false)
                    gte("start_time", Instant.now().toString())
                }
                order("start_time", Order.ASCENDING)
            }
            .decodeList<CalendarEventRow>()

    // One call, per the ticket's own "end-to-end pipeline" framing --
    // upcoming event -> resolved Drive file -> download -> parse -> segment
    // -> course-demand metrics -> cached preview, for every real candidate
    // at once. A failure on one event doesn't stop the rest.
    suspend fun runPipeline(calendarId: String = "primary"): List<PlannedRoutePreviewResult> =
        loadUpcomingEvents(calendarId).map { generatePreview(it) }

    private suspend fun downloadGpx(fileId: String): String {
        val response = client.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header("Authorization", "Bearer $accessToken")
            url { parameters.append("alt", "media") }
        }
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw MapFetchException("Drive request failed (${response.status.value})")
        }
        return bodyText
    }
}
