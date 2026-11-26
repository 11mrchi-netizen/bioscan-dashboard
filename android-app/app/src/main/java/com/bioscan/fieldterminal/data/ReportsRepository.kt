package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.notifications.ReportBrief
import com.bioscan.fieldterminal.domain.notifications.ReportDetail
import com.bioscan.fieldterminal.domain.notifications.ReportLab
import com.bioscan.fieldterminal.domain.notifications.ReportMetric
import com.bioscan.fieldterminal.domain.notifications.ReportMicro
import com.bioscan.fieldterminal.domain.notifications.ReportRegimen
import com.bioscan.fieldterminal.domain.notifications.ReportSuggestion
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// Weekly and monthly reports (docs/reports): the briefs for the notification and the REPORTS card, and one whole report for
// its page (ui/screens/status/ReportOverlay.kt).
@Serializable
private data class ReportSummaryJson(
    val better: List<String> = emptyList(),
    val worse: List<String> = emptyList(),
    @SerialName("notFollowed") val notFollowed: List<String> = emptyList(),
)

@Serializable
private data class ReportSuggestionJson(val text: String = "")

@Serializable
private data class ReportPayloadJson(
    val key: String = "",
    val grain: String = "week",
    val start: String = "",
    val end: String = "",
    val summary: ReportSummaryJson = ReportSummaryJson(),
    val suggestions: List<ReportSuggestionJson> = emptyList(),
)

@Serializable
private data class ReportRow(val key: String, @SerialName("as_of") val asOf: String, val payload: ReportPayloadJson = ReportPayloadJson())

// The whole payload, read leniently: reports written by older engine versions miss fields, and any number may be null.
@Serializable
private data class FullSummaryJson(
    val better: List<String> = emptyList(), val worse: List<String> = emptyList(), val working: List<String> = emptyList(),
    val wasted: List<String> = emptyList(), val notFollowed: List<String> = emptyList(), val excess: List<String> = emptyList(),
    val short: List<String> = emptyList(),
)

@Serializable
private data class FullMetricJson(
    val id: String = "", val label: String = "", val unit: String = "", val section: String = "", val value: Double? = null,
    val prev: Double? = null, val delta: Double? = null, val days: Int = 0, val status: String = "no_data", val perWeek: Boolean = false,
)

@Serializable
private data class FullRegimenJson(val id: Long = 0, val name: String = "", val planned: Int = 0, val taken: Int = 0, val rate: Double? = null, val status: String = "")

@Serializable
private data class FullLabJson(val name: String = "", val verdict: String = "", val detail: String = "")

@Serializable
private data class FullMicroJson(
    val key: String = "", val label: String = "", val unit: String = "", val value: Double? = null, val food: Double? = null,
    val supplements: Double? = null, val reference: Double? = null, val upper: Double? = null, val status: String = "no_data",
    val fromSupplements: List<String> = emptyList(),
)

@Serializable
private data class FullMicroSectionJson(val lines: List<FullMicroJson> = emptyList())

@Serializable
private data class FullSuggestionJson(val text: String = "", val because: String = "")

@Serializable
private data class FullPayloadJson(
    val key: String = "", val grain: String = "week", val label: String = "", val start: String = "", val end: String = "",
    val summary: FullSummaryJson = FullSummaryJson(), val suggestions: List<FullSuggestionJson> = emptyList(),
    val metrics: List<FullMetricJson> = emptyList(), val regimen: List<FullRegimenJson> = emptyList(),
    val lab: List<FullLabJson> = emptyList(), val micro: FullMicroSectionJson = FullMicroSectionJson(),
)

@Serializable
private data class RawReportRow(val key: String, val payload: JsonObject)

private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

class ReportsRepository(private val supabase: SupabaseClient) {
    /** One whole report by its key ("week:2026-11-16"), or null when none is stored. */
    suspend fun report(key: String): ReportDetail? {
        val row = supabase.postgrest.from("analysis_results")
            .select(columns = Columns.list("key,payload")) {
                filter { eq("kind", "period_report"); eq("key", key) }
                limit(1)
            }
            .decodeList<RawReportRow>().firstOrNull() ?: return null
        val p = lenient.decodeFromJsonElement(FullPayloadJson.serializer(), row.payload)
        return ReportDetail(
            key = row.key, grain = p.grain, label = p.label, start = p.start, end = p.end,
            better = p.summary.better, worse = p.summary.worse, working = p.summary.working, wasted = p.summary.wasted,
            notFollowed = p.summary.notFollowed, excess = p.summary.excess, short = p.summary.short,
            suggestions = p.suggestions.filter { it.text.isNotBlank() }.map { ReportSuggestion(it.text, it.because) },
            metrics = p.metrics.map { ReportMetric(it.id, it.label, it.unit, it.section, it.value, it.prev, it.delta, it.days, it.status, it.perWeek) },
            regimen = p.regimen.map { ReportRegimen(it.id, it.name, it.planned, it.taken, it.rate, it.status) },
            lab = p.lab.map { ReportLab(it.name, it.verdict, it.detail) },
            micro = p.micro.lines.map { ReportMicro(it.key, it.label, it.unit, it.value, it.food, it.supplements, it.reference, it.upper, it.status, it.fromSupplements) },
        )
    }

    /** The newest few reports, newest period first. */
    suspend fun latest(limit: Long = 6): List<ReportBrief> =
        supabase.postgrest.from("analysis_results")
            .select(columns = Columns.list("key,as_of,payload")) {
                filter { eq("kind", "period_report") }
                order("as_of", Order.DESCENDING)
                limit(limit)
            }
            .decodeList<ReportRow>()
            .filter { it.payload.end.isNotEmpty() }
            .map { r ->
                val p = r.payload
                ReportBrief(
                    key = r.key, grain = p.grain, start = p.start, end = p.end,
                    better = p.summary.better.size, worse = p.summary.worse.size, notFollowed = p.summary.notFollowed.size,
                    firstSuggestion = p.suggestions.firstOrNull()?.text?.ifBlank { null },
                )
            }
}
