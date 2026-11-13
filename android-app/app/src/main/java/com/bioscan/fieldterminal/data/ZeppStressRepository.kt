package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.LenientJson
import com.bioscan.fieldterminal.domain.stress.PhysiologicalStressReading
import com.bioscan.fieldterminal.domain.stress.stressDaysByLocalDate
import com.bioscan.fieldterminal.domain.stress.StressDay
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

// 05.1 Stress Rhythm (DAV-246). Decodes zepp-extract's already-stored raw
// stress payload (real, confirmed 2026-09-30: extractor v8, real 200
// responses) client-side rather than adding a new typed table -- there's no
// per-sample SQL query need yet that would justify a migration, same
// "generous margin, decode in Kotlin" precedent AnalysisRepository's other
// reads already follow. Each row is a UTC day; the samples are regrouped by
// the local day and the daily summary recomputed from them (StressLocalDays.kt).
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
)

@Serializable
private data class ZeppStressSamplePoint(val time: Long, val value: Int)

class ZeppStressRepository(private val supabase: SupabaseClient) {
    private val json = LenientJson

    suspend fun loadStressDays(limit: Int = 60): List<StressDay> = ReadCache.get("zepp.stress.v2.$limit", tables = setOf("zepp_raw_extracts"), serializer = kotlinx.serialization.builtins.ListSerializer(com.bioscan.fieldterminal.domain.stress.StressDay.serializer())) { fetchStressDays(limit) }

    private suspend fun fetchStressDays(limit: Int): List<StressDay> {
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

        // Every item of every row, regrouped by the local day (domain/stress/StressLocalDays.kt).
        val samples = rows.flatMap { row ->
            row.rawBody?.items.orEmpty().flatMap { item ->
                item.data
                    ?.let { raw -> runCatching { json.decodeFromString<List<ZeppStressSamplePoint>>(raw) }.getOrNull() }
                    .orEmpty()
                    .map { PhysiologicalStressReading(Instant.ofEpochMilli(it.time), it.value) }
            }
        }
        return stressDaysByLocalDate(samples, ZoneId.systemDefault())
    }
}
