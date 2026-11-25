package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.notifications.ReportBrief
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Weekly and monthly reports (docs/reports): only what the phone shows of them. The report itself is read on the web.
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

class ReportsRepository(private val supabase: SupabaseClient) {
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
