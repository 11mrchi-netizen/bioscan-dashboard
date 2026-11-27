package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.NewWellnessRow
import com.bioscan.fieldterminal.data.model.WellnessRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

// Wellness sessions (wellness_sessions): saved by the session screen, read back to plan the next session and for RECENT.
class WellnessRepository(private val supabase: SupabaseClient) {
    suspend fun add(row: NewWellnessRow): WellnessRow =
        supabase.postgrest.from(TABLE).insert(row) { select(Columns.list(COLUMNS)) }.decodeSingle()

    suspend fun recent(programmeKey: String, limit: Long = 12): List<WellnessRow> =
        supabase.postgrest.from(TABLE).select(Columns.list(COLUMNS)) {
            filter { eq("programme_key", programmeKey) }
            order("started_at", Order.DESCENDING)
            limit(limit)
        }.decodeList()

    suspend fun delete(id: Long) {
        supabase.postgrest.from(TABLE).delete { filter { eq("id", id) } }
    }

    companion object {
        const val TABLE = "wellness_sessions"
        const val COLUMNS = "id,started_at,programme_key,stage_key,duration_s,completed,cycles_planned,cycles_done,peak_arousal,late_stops,felt"
    }
}
