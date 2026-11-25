package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.meview.MeViewDoc
import com.bioscan.fieldterminal.domain.meview.toJson
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Saves a Me tab for the web Dashboard (domain/meview/MeView.kt; supabase/migrations/20261118121249_me_views.sql): once
// per change, from the tab on screen or from the background refresh (MeViewPublishWorker).
object MeViewPublisher {
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun publish(supabase: SupabaseClient, doc: MeViewDoc) {
        val view = doc.toJson()
        if (lastSent[doc.tab] == view.hashCode()) return
        supabase.postgrest.from("me_views").upsert(
            buildJsonObject { put("tab", doc.tab); put("view", view); put("built_at", java.time.Instant.now().toString()) },
        ) { onConflict = "user_id,tab" }
        lastSent[doc.tab] = view.hashCode()
    }
}
