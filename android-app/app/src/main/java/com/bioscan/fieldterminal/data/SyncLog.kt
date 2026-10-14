package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

// The last Health Connect sync the app recorded (sync_log, sync_source = 'health_connect'): the sync coordinator's resume
// watermark, and the "SYNCED 07:42" part of the Status hero's freshness line (contract A5). OffsetDateTime because
// PostgREST returns timestamptz with a "+00:00" offset, which Instant.parse only accepts on newer runtimes.
suspend fun loadLastHealthConnectSync(supabase: SupabaseClient): Instant? =
    supabase.postgrest.from("sync_log")
        .select(columns = Columns.list("synced_at")) {
            filter { eq("sync_source", "health_connect") }
            order("synced_at", Order.DESCENDING)
            limit(1)
        }
        .decodeList<SyncLogTimeRow>()
        .firstOrNull()
        ?.let { OffsetDateTime.parse(it.syncedAt).toInstant() }

@Serializable
private data class SyncLogTimeRow(@SerialName("synced_at") val syncedAt: String)
