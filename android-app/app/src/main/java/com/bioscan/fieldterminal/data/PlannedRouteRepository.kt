package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.CalendarEventRow
import com.bioscan.fieldterminal.data.model.NewPlannedRouteRow
import com.bioscan.fieldterminal.data.model.PlannedRouteFailureUpdate
import com.bioscan.fieldterminal.data.model.PlannedRouteFreshUpdate
import com.bioscan.fieldterminal.data.model.PlannedRouteRow
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

// DAV-151's cache-freshness cutoff: a preview for an event more than this
// far in the past is no longer useful to anyone (the run either happened or
// was long since skipped) -- matches HealthConnectWriteBackRepository's own
// 7-day lookback convention rather than inventing a new number.
private const val STALE_PREVIEW_MAX_AGE_DAYS = 7L
private val json = Json { ignoreUnknownKeys = true }

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
    companion object { private val client = HttpClient(Android) }

    // A failed download/parse never blocks the caller from moving on to the
    // next event (DAV-148's own acceptance criteria) -- every real failure
    // resolves to a Failure result, matching CalendarSyncRepository's same
    // never-throw-past-this-boundary convention.
    //
    // DAV-151's skip-recompute check happens first: a metadata-only Drive
    // call (no `alt=media`, so no download) gets the file's real md5Checksum;
    // if that matches what's already cached *and* both algorithm versions
    // still match the running app's own constants, nothing is re-downloaded
    // or recomputed at all -- only a fresh computed_at is written, clearing
    // any prior stale/error flag. A parser or segmentation version bump
    // invalidates the cache even when the GPX file itself hasn't changed,
    // since the *algorithm* is what's different now.
    suspend fun generatePreview(event: CalendarEventRow, forceRecompute: Boolean = false): PlannedRoutePreviewResult {
        val resolved = resolveGpxDriveFileId(event.attachments, event.description)
            ?: return PlannedRoutePreviewResult.NoGpxLinked

        return try {
            val existing = if (forceRecompute) null else loadPreview(event.eventId, event.calendarId)
            val remoteChecksum = fetchDriveChecksum(resolved.driveFileId)
            val upToDate = existing != null &&
                remoteChecksum != null &&
                remoteChecksum == existing.gpxChecksum &&
                existing.parserVersion == TRACKPOINT_MODEL_VERSION &&
                existing.terrainAnalysisVersion == SEGMENTATION_MODEL_VERSION

            if (upToDate) {
                markFresh(event.eventId, existing)
                return PlannedRoutePreviewResult.Success(existing.distanceM)
            }

            val gpxText = downloadGpx(resolved.driveFileId)
            val preview = computePlannedRoutePreview(gpxText)

            supabase.postgrest.from("planned_routes").upsert(
                NewPlannedRouteRow(
                    calendarId = event.calendarId,
                    calendarEventId = event.eventId,
                    eventTitle = event.title,
                    eventStartTime = event.startTime ?: Instant.now().toString(),
                    driveFileId = resolved.driveFileId,
                    // Drive's own md5Checksum when available -- the value
                    // this function's own skip-check compares against next
                    // time, cheaper to fetch than our sha256 over the full
                    // content. Falls back to the domain-computed sha256 only
                    // if Drive's metadata call itself failed.
                    gpxChecksum = remoteChecksum ?: preview.gpxChecksum,
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
            // clobbering last-good numbers with nulls -- this is exactly
            // DAV-151's "network/offline failures leave the last valid
            // preview available with a stale indicator" requirement. A
            // brand-new event that's never once succeeded has nothing to
            // mark, which is correct: there's no "last valid preview" yet.
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

    // DAV-151. A preview for an event this far in the past can't help
    // anyone anymore (the run happened or was long since skipped) -- the
    // real cleanup rule this ticket asks for, since no raw GPX bytes are
    // ever cached in Storage (the metadata-checksum check above makes that
    // unnecessary for the skip-recompute case), so there's no file-storage
    // growth to separately clean up, only rows.
    suspend fun cleanupPastPreviews(maxAgeDays: Long = STALE_PREVIEW_MAX_AGE_DAYS) {
        val cutoff = Instant.now().minusSeconds(maxAgeDays * 86_400).toString()
        supabase.postgrest.from("planned_routes").delete { filter { lt("event_start_time", cutoff) } }
    }

    private suspend fun markFresh(calendarEventId: String, existing: PlannedRouteRow) {
        if (!existing.isStale && existing.lastError == null) return // already clean, nothing to write
        supabase.postgrest.from("planned_routes")
            .update(PlannedRouteFreshUpdate(computedAt = Instant.now().toString())) {
                filter { eq("calendar_event_id", calendarEventId) }
            }
    }

    // Metadata only (no `alt=media`) -- never downloads the file's content,
    // the whole point of this being cheaper than a full re-fetch+reparse.
    private suspend fun fetchDriveChecksum(fileId: String): String? {
        val response = client.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header("Authorization", "Bearer $accessToken")
            url { parameters.append("fields", "md5Checksum") }
        }
        if (!response.status.isSuccess()) return null
        return runCatching { json.decodeFromString<DriveFileMetadata>(response.bodyAsText()).md5Checksum }.getOrNull()
    }

    // DAV-149. Read-only, no Drive/Calendar call needed -- lets the Map tab's
    // existing per-event sheet (MapScreen.kt) show a cached preview the
    // pipeline already computed, without ever recomputing from the UI.
    suspend fun loadPreview(calendarEventId: String, calendarId: String = "primary"): PlannedRouteRow? =
        supabase.postgrest.from("planned_routes")
            .select {
                filter {
                    eq("calendar_id", calendarId)
                    eq("calendar_event_id", calendarEventId)
                }
            }
            .decodeList<PlannedRouteRow>()
            .firstOrNull()

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

@Serializable
private data class DriveFileMetadata(val md5Checksum: String? = null)
