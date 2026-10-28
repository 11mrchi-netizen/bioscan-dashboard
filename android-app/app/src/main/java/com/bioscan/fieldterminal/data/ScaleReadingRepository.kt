package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.WeightUpsertRow
import com.bioscan.fieldterminal.domain.ScaleReading
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.ZoneOffset

// Scale weigh-ins (supabase/migrations/20261106_scale_readings.sql): the raw reading goes to scale_readings, the weight also
// to today's body_metrics row like every other weight source. Local wall-clock time labelled UTC, like body_checkins.

@Serializable
private data class NewScaleReadingRow(
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("impedance_ohm") val impedanceOhm: Double,
    @SerialName("raw_hex") val rawHex: String,
    @SerialName("device_mac") val deviceMac: String,
)

class ScaleReadingRepository(private val supabase: SupabaseClient) {
    suspend fun save(reading: ScaleReading, at: LocalDateTime = LocalDateTime.now()) {
        supabase.postgrest.from("scale_readings")
            .insert(NewScaleReadingRow(at.atOffset(ZoneOffset.UTC).toString(), reading.weightKg, reading.impedanceOhm, reading.rawHex, reading.mac))
        supabase.postgrest.from("body_metrics")
            .upsert(WeightUpsertRow(at.toLocalDate().toString(), reading.weightKg)) { onConflict = "user_id,date" }
    }
}
