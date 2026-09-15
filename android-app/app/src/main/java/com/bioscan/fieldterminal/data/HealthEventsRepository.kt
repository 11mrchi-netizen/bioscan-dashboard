package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.IllnessRow
import com.bioscan.fieldterminal.data.model.InjuryRow
import com.bioscan.fieldterminal.domain.HealthEvent
import com.bioscan.fieldterminal.domain.mergeHealthEvents
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns

data class HealthEventsOverview(
    val open: List<HealthEvent>,
    val resolved: List<HealthEvent>, // unfiltered -- every resolved event ever, not just recent ones
)

class HealthEventsRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): HealthEventsOverview {
        val injuries = supabase.postgrest.from("injuries")
            .select(columns = Columns.list("part,type,status,start_date,end_date,notes"))
            .decodeList<InjuryRow>()

        val illnesses = supabase.postgrest.from("illnesses")
            .select(columns = Columns.list("name,symptoms,status,start_date,end_date"))
            .decodeList<IllnessRow>()

        val all = mergeHealthEvents(injuries, illnesses)
        val (open, resolved) = all.partition { it.status == "active" || it.status == "monitoring" }

        return HealthEventsOverview(open = open, resolved = resolved)
    }
}
