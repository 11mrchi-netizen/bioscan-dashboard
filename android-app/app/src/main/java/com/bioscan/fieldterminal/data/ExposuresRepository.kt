package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

// Risk profile R1 (docs/risk-profile/01-plan.md): what the nightly air run wrote (analysis-core/runAir.ts):
// air_exposure_daily, one row per local date, and the `air_exposure` summary in analysis_results with the ways to improve.
// The phone computes nothing here; it reads, draws and publishes the Me tab for the web.

@Serializable
data class AirDayRow(
    val date: String,
    @SerialName("coverage_h") val coverageH: Int,
    @SerialName("pm25_ugm3") val pm25: Double? = null,
    @SerialName("pm25_indoor_ugm3") val pm25Indoor: Double? = null,
    @SerialName("pm25_outdoor_home_ugm3") val pm25OutdoorHome: Double? = null,
    @SerialName("no2_ugm3") val no2: Double? = null,
    @SerialName("o3_mda8_ugm3") val o3: Double? = null,
    @SerialName("uv_max") val uvMax: Double? = null,
    @SerialName("co2_night_ppm") val co2Night: Double? = null,
    @SerialName("dose_home_share") val doseHomeShare: Double? = null,
    val sessions: List<AirSessionRow> = emptyList(),
)

@Serializable
data class AirSessionRow(
    val start: String,
    val type: String,
    val minutes: Int,
    val pm25: Double? = null,
    val o3: Double? = null,
    val uv: Double? = null,
)

@Serializable
data class AirImprovement(
    val id: String,
    val title: String,
    val why: String,
    val evidence: String,
    val effort: String,
    val lowers: List<String> = emptyList(),
)

@Serializable
data class AirSummary(
    @SerialName("window_days") val windowDays: Int = 30,
    @SerialName("days_with_data") val daysWithData: Int = 0,
    @SerialName("full_days") val fullDays: Int = 0,
    @SerialName("pm25_mean") val pm25Mean: Double? = null,
    @SerialName("pm25_days_over_24h") val pm25DaysOver: Int = 0,
    @SerialName("no2_mean") val no2Mean: Double? = null,
    @SerialName("no2_days_over_24h") val no2DaysOver: Int = 0,
    @SerialName("o3_days_over_8h") val o3DaysOver: Int = 0,
    @SerialName("dose_home_share") val doseHomeShare: Double? = null,
    @SerialName("indoor_sensor") val indoorSensor: Boolean = false,
    @SerialName("indoor_pm25_mean") val indoorPm25Mean: Double? = null,
    @SerialName("outdoor_home_pm25_mean") val outdoorHomePm25Mean: Double? = null,
    @SerialName("co2_night_mean") val co2NightMean: Double? = null,
    @SerialName("co2_poor_nights") val co2PoorNights: Int = 0,
    @SerialName("sessions_outdoor") val sessionsOutdoor: Int = 0,
    @SerialName("sessions_poor_air") val sessionsPoorAir: Int = 0,
    @SerialName("sessions_high_uv") val sessionsHighUv: Int = 0,
    val states: Map<String, String?> = emptyMap(),
    val improvements: List<AirImprovement> = emptyList(),
    val missing: List<String> = emptyList(),
)

@Serializable
private data class SummaryRow(@SerialName("as_of") val asOf: String, val state: String? = null, val payload: JsonObject)

@Serializable
data class ExposuresData(val days: List<AirDayRow>, val summary: AirSummary?, val summaryAsOf: String?, val state: String?) {
    /** A metric's daily values, oldest first, for its history. */
    fun series(pick: (AirDayRow) -> Double?): Map<LocalDate, Double> =
        days.mapNotNull { d -> pick(d)?.let { LocalDate.parse(d.date) to it } }.toMap().toSortedMap()
}

private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Days kept on the page: 3 months of history for the charts. */
const val EXPOSURE_HISTORY_DAYS = 90L

class ExposuresRepository(private val supabase: SupabaseClient) {
    suspend fun load(today: LocalDate = LocalDate.now()): ExposuresData = ReadCache.kept("exposures.air", tables = setOf("air_exposure_daily", "analysis_results")) {
        val days = supabase.postgrest.from("air_exposure_daily")
            .select(Columns.list("date,coverage_h,pm25_ugm3,pm25_indoor_ugm3,pm25_outdoor_home_ugm3,no2_ugm3,o3_mda8_ugm3,uv_max,co2_night_ppm,dose_home_share,sessions")) {
                filter { gte("date", today.minusDays(EXPOSURE_HISTORY_DAYS).toString()) }
                order("date", Order.ASCENDING)
            }
            .decodeList<AirDayRow>()
        val summary = supabase.postgrest.from("analysis_results")
            .select(Columns.list("as_of,state,payload")) {
                filter { eq("kind", "air_exposure"); eq("key", "summary") }
                order("as_of", Order.DESCENDING)
                limit(1)
            }
            .decodeList<SummaryRow>()
            .firstOrNull()
        ExposuresData(
            days = days,
            summary = summary?.let { runCatching { lenient.decodeFromJsonElement(AirSummary.serializer(), it.payload) }.getOrNull() },
            summaryAsOf = summary?.asOf,
            state = summary?.state,
        )
    }
}
