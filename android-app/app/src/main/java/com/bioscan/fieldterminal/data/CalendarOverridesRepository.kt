package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.CalendarOverride
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.OverrideKind
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The person's answers for calendar entries the plan could not read (fuel planning phase 11, WHAT IS THIS?):
// calendar_event_overrides (supabase/migrations/20261029_meal_prep.sql), one row per entry.

@Serializable
private data class OverrideRow(
    @SerialName("event_id") val eventId: String,
    @SerialName("treat_as") val treatAs: String,
    @SerialName("duration_min") val durationMin: Double? = null,
    val intensity: String? = null,
)

class CalendarOverridesRepository(private val supabase: SupabaseClient) {

    suspend fun load(): Map<String, CalendarOverride> = ReadCache.get("fuel.calendar_overrides", tables = setOf(TABLE)) {
        supabase.postgrest.from(TABLE).select(Columns.list("event_id,treat_as,duration_min,intensity")).decodeList<OverrideRow>()
            .mapNotNull { r ->
                val kind = OverrideKind.of(r.treatAs) ?: return@mapNotNull null
                r.eventId to CalendarOverride(r.eventId, kind, r.durationMin, r.intensity?.let { i -> Intensity.entries.firstOrNull { it.name.equals(i, true) } })
            }.toMap()
    }

    suspend fun save(o: CalendarOverride) {
        supabase.postgrest.from(TABLE).upsert(OverrideRow(o.eventId, o.kind.key, o.durationMin, o.intensity?.name?.lowercase())) {
            onConflict = "user_id,event_id"
        }
    }

    private companion object {
        const val TABLE = "calendar_event_overrides"
    }
}
