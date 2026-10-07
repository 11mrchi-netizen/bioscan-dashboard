package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.stress.PhysiologicalStressReading
import com.bioscan.fieldterminal.domain.stress.StressDailySummary
import com.bioscan.fieldterminal.domain.stress.StressDay
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

// 05.1 Stress Rhythm (DAV-246). Decodes zepp-extract's already-stored raw
// stress payload (real, confirmed 2026-09-30: extractor v8, real 200
// responses) client-side rather than adding a new typed table -- there's no
// per-sample SQL query need yet that would justify a migration, same
// "generous margin, decode in Kotlin" precedent AnalysisRepository's other
// reads already follow. avgStress/max/min/proportions arrive as STRINGS in
// the real payload (confirmed against a live sync), not numbers -- kept
// nullable here, parsed to Int in the mapper.
@Serializable
private data class ZeppRawExtractRow(
    @SerialName("query_date") val queryDate: String,
    @SerialName("raw_body") val rawBody: ZeppStressRawBody? = null,
)

@Serializable
private data class ZeppStressRawBody(val items: List<ZeppStressItem> = emptyList())

@Serializable
private data class ZeppStressItem(
    val data: String? = null,
    val avgStress: String? = null,
    val maxStress: String? = null,
    val minStress: String? = null,
    val relaxProportion: String? = null,
    val normalProportion: String? = null,
    val mediumProportion: String? = null,
    val highProportion: String? = null,
)

@Serializable
private data class ZeppStressSamplePoint(val time: Long, val value: Int)

class ZeppStressRepository(private val supabase: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadStressDays(limit: Int = 60): List<StressDay> {
        val rows = supabase.postgrest.from("zepp_raw_extracts")
            .select(columns = Columns.list("query_date,raw_body")) {
                filter {
                    eq("metric", "stress")
                    eq("status_code", 200)
                }
                order("query_date", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<ZeppRawExtractRow>()

        return rows.mapNotNull { it.toStressDay() }
    }

    private fun ZeppRawExtractRow.toStressDay(): StressDay? {
        val item = rawBody?.items?.firstOrNull() ?: return null
        val date = runCatching { LocalDate.parse(queryDate) }.getOrNull() ?: return null
        val samples = item.data
            ?.let { raw -> runCatching { json.decodeFromString<List<ZeppStressSamplePoint>>(raw) }.getOrNull() }
            .orEmpty()
            .map { PhysiologicalStressReading(Instant.ofEpochMilli(it.time), it.value) }

        val summary = StressDailySummary(
            avg = item.avgStress?.toIntOrNull(),
            max = item.maxStress?.toIntOrNull(),
            min = item.minStress?.toIntOrNull(),
            relaxProportion = item.relaxProportion?.toIntOrNull(),
            normalProportion = item.normalProportion?.toIntOrNull(),
            mediumProportion = item.mediumProportion?.toIntOrNull(),
            highProportion = item.highProportion?.toIntOrNull(),
        )
        return StressDay(date, samples, summary)
    }
}
