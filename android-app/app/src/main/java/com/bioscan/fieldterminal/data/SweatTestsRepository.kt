package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.SweatProfile
import com.bioscan.fieldterminal.domain.fuel.SweatTest
import com.bioscan.fieldterminal.domain.fuel.sweatProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Sweat tests (supabase/migrations/20261025_sweat_tests.sql; domain/fuel/SweatProfile.kt).

@Serializable
private data class SweatTestRow(
    val id: Long? = null,
    @SerialName("tested_on") val testedOn: String,
    @SerialName("exercise_session_id") val exerciseSessionId: Long? = null,
    @SerialName("session_type") val sessionType: String,
    val intensity: String,
    @SerialName("duration_min") val durationMin: Double,
    @SerialName("weight_before_kg") val weightBeforeKg: Double,
    @SerialName("weight_after_kg") val weightAfterKg: Double,
    @SerialName("fluid_in_ml") val fluidInMl: Double = 0.0,
    @SerialName("urine_ml") val urineMl: Double = 0.0,
    @SerialName("temp_c") val tempC: Double? = null,
    @SerialName("humidity_pct") val humidityPct: Double? = null,
    val notes: String? = null,
)

private fun SweatTestRow.toDomain() = SweatTest(
    date = LocalDate.parse(testedOn),
    sessionType = sessionType,
    intensity = Intensity.entries.firstOrNull { it.name.lowercase() == intensity } ?: Intensity.MODERATE,
    durationMin = durationMin,
    weightBeforeKg = weightBeforeKg,
    weightAfterKg = weightAfterKg,
    fluidInMl = fluidInMl,
    urineMl = urineMl,
    tempC = tempC,
    humidityPct = humidityPct,
)

class SweatTestsRepository(private val supabase: SupabaseClient) {

    suspend fun loadAll(): List<SweatTest> = ReadCache.get("fuel.sweat_tests", tables = setOf("sweat_tests")) {
        supabase.postgrest.from("sweat_tests")
            .select { order("tested_on", Order.DESCENDING); limit(200) }
            .decodeList<SweatTestRow>().map { it.toDomain() }
    }

    suspend fun profile(weightKg: Double): SweatProfile = sweatProfile(loadAll(), weightKg)

    suspend fun add(test: SweatTest, sessionId: Long? = null, notes: String? = null) {
        supabase.postgrest.from("sweat_tests").insert(
            SweatTestRow(
                testedOn = test.date.toString(),
                exerciseSessionId = sessionId,
                sessionType = test.sessionType,
                intensity = test.intensity.name.lowercase(),
                durationMin = test.durationMin,
                weightBeforeKg = test.weightBeforeKg,
                weightAfterKg = test.weightAfterKg,
                fluidInMl = test.fluidInMl,
                urineMl = test.urineMl,
                tempC = test.tempC,
                humidityPct = test.humidityPct,
                notes = notes?.trim()?.ifBlank { null },
            ),
        )
    }
}
