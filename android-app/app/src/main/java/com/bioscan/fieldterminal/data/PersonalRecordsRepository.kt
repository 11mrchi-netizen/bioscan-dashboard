package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.RecordSet
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate

// Personal records (domain/PersonalRecords.kt): every time a record was set, from the database's v_personal_records
// (worked out there from each run's stored best efforts).

@Serializable
private data class RecordSetRow(
    @SerialName("record_key") val recordKey: String,
    val value: Double,
    @SerialName("achieved_on") val achievedOn: String,
    @SerialName("exercise_session_id") val sessionId: Long? = null,
    @SerialName("previous_value") val previousValue: Double? = null,
    @SerialName("previous_on") val previousOn: String? = null,
)

class PersonalRecordsRepository(private val supabase: SupabaseClient) {
    suspend fun loadSets(): List<RecordSet> = ReadCache.get(
        "records.sets", tables = setOf("exercise_sessions", "zepp_workout_detail", "run_best_efforts"), serializer = ListSerializer(RecordSet.serializer()),
    ) {
        supabase.postgrest.from("v_personal_records")
            .select(columns = Columns.list("record_key,value,achieved_on,exercise_session_id,previous_value,previous_on")) {
                order("achieved_on", Order.ASCENDING)
            }
            .decodeList<RecordSetRow>()
            .mapNotNull { r ->
                val d = runCatching { LocalDate.parse(r.achievedOn) }.getOrNull() ?: return@mapNotNull null
                RecordSet(r.recordKey, r.value, d, r.sessionId, r.previousValue, r.previousOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
            }
    }
}
