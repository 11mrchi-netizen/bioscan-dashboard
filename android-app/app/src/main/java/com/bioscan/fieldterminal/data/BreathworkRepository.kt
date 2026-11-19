package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.LogBreathworkRow
import com.bioscan.fieldterminal.data.model.NewBreathworkRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

// Breathwork sessions (breathwork_sessions): saved by the session screen, read back for the page's RECENT list and by
// LogRepository for the Log feed. Deleting goes through the Log's generic delete (LogSource.Breathwork).
class BreathworkRepository(private val supabase: SupabaseClient) {
    suspend fun add(row: NewBreathworkRow): LogBreathworkRow =
        supabase.postgrest.from(TABLE).insert(row) { select(Columns.list(COLUMNS)) }.decodeSingle()

    suspend fun recent(limit: Long = 5): List<LogBreathworkRow> =
        supabase.postgrest.from(TABLE).select(Columns.list(COLUMNS)) {
            order("started_at", Order.DESCENDING)
            limit(limit)
        }.decodeList()

    suspend fun delete(id: Long) {
        supabase.postgrest.from(TABLE).delete { filter { eq("id", id) } }
    }

    companion object {
        const val TABLE = "breathwork_sessions"
        const val COLUMNS = "id,started_at,pattern_name,steps,rounds_planned,rounds_done,duration_s,completed"
    }
}
