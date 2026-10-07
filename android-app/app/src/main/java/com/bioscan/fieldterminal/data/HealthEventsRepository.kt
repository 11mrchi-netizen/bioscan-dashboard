package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.IllnessRow
import com.bioscan.fieldterminal.data.model.InjuryRow
import com.bioscan.fieldterminal.data.model.MedicationEntry
import com.bioscan.fieldterminal.data.model.NewIllnessRow
import com.bioscan.fieldterminal.data.model.NewInjuryRow
import com.bioscan.fieldterminal.domain.HealthEvent
import com.bioscan.fieldterminal.domain.mergeHealthEvents
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

data class HealthEventsOverview(
    val open: List<HealthEvent>,
    val resolved: List<HealthEvent>, // unfiltered -- every resolved event ever, not just recent ones
)

class HealthEventsRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): HealthEventsOverview {
        val injuries = supabase.postgrest.from("injuries")
            .select(columns = Columns.list("id,part,type,status,start_date,end_date,notes"))
            .decodeList<InjuryRow>()

        val illnesses = supabase.postgrest.from("illnesses")
            .select(columns = Columns.list("id,name,symptoms,status,start_date,end_date,doctor_seen,medications"))
            .decodeList<IllnessRow>()

        val all = mergeHealthEvents(injuries, illnesses)
        val (open, resolved) = all.partition { it.status == "active" || it.status == "monitoring" }

        return HealthEventsOverview(open = open, resolved = resolved)
    }

    suspend fun addIllness(name: String, symptoms: String?, startDate: LocalDate, doctorSeen: Boolean, medications: List<MedicationEntry>, notes: String?) {
        supabase.postgrest.from("illnesses").insert(
            NewIllnessRow(name = name, symptoms = symptoms, status = "active", startDate = startDate.toString(), doctorSeen = doctorSeen, medications = medications, notes = notes)
        )
    }

    suspend fun resolveIllness(id: Long, endDate: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("illnesses").update(
            buildJsonObject {
                put("status", "resolved")
                put("end_date", endDate.toString())
            }
        ) { filter { eq("id", id) } }
    }

    suspend fun addInjury(part: String, type: String, severity: Int, startDate: LocalDate, notes: String?) {
        supabase.postgrest.from("injuries").insert(
            NewInjuryRow(part = part, type = type, severity = severity, status = "active", startDate = startDate.toString(), notes = notes)
        )
    }

    suspend fun resolveInjury(id: Long, endDate: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("injuries").update(
            buildJsonObject {
                put("status", "resolved")
                put("end_date", endDate.toString())
            }
        ) { filter { eq("id", id) } }
    }
}
