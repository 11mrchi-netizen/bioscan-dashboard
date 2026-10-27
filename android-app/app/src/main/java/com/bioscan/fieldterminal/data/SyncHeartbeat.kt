package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import java.time.Instant

// Analysis hub phase C (docs/analysis-console/PLAN.md): one collector_heartbeats row per successful Health Connect or
// Zepp sync, so the console's Integrity workspace can tell "the sync ran and found nothing new" from "the sync has not
// run" (stale ingestion) instead of reading every quiet day as unknown. Location and phone use already write their own.
object SyncHeartbeat {
    const val HEALTH_CONNECT = "health_connect_sync"
    const val ZEPP = "zepp_sync"

    @Serializable
    private data class Row(val stream: String, val ts: String)

    // Best effort: a heartbeat must never fail the sync it reports on.
    suspend fun record(client: SupabaseClient, stream: String, now: Instant = Instant.now()) {
        val done = orNullUnlessCancelled {
            client.postgrest.from("collector_heartbeats").upsert(listOf(Row(stream, now.toString()))) {
                onConflict = "user_id,stream,ts"
                ignoreDuplicates = true
            }
        }
        if (done == null) android.util.Log.w("SyncHeartbeat", "heartbeat for $stream not recorded")
    }
}
