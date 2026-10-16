package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.ZoneAnchors
import com.bioscan.fieldterminal.domain.zoneAnchorsAsOf
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

// The inputs heart-rate zones rest on (domain/HeartRateZones.kt), as three small date series so the anchors can be
// taken as they were on any session's date: each session's maximum heart rate, the daily resting heart rate, and Zepp's
// lactate-threshold heart rate per run. Three years back covers the 24-month maximum window of any session the app
// shows. The series are a few thousand numbers, cached like every other read.
data class ZoneAnchorSeries(
    val sessionMaxima: List<Pair<LocalDate, Double>>,
    val restingDaily: List<Pair<LocalDate, Double>>,
    val lthr: List<Pair<LocalDate, Double>>,
) {
    fun anchorsFor(date: LocalDate): ZoneAnchors = zoneAnchorsAsOf(date, sessionMaxima, restingDaily, lthr)
}

@Serializable
private data class SessionMaxRow(@SerialName("start_time") val startTime: String, @SerialName("max_hr") val maxHr: Double? = null)

@Serializable
private data class RestingRow(val date: String, val rhr: Double? = null)

@Serializable
private data class LthrRow(@SerialName("start_time") val startTime: String, val lthr: Double? = null)

private const val YEARS_BACK = 3L
private const val PAGE = 1000L

class ZoneAnchorsRepository(private val supabase: SupabaseClient) {

    suspend fun load(): ZoneAnchorSeries = ReadCache.get("zones.anchors") {
        val from = LocalDate.now().minusYears(YEARS_BACK).toString()
        val maxima = paged<SessionMaxRow>("exercise_sessions", "start_time,max_hr", "start_time") {
            filter { gte("start_time", from); gt("max_hr", 0) }
        }.mapNotNull { r -> r.maxHr?.let { OffsetDateTime.parse(r.startTime).toLocalDate() to it } }
        val resting = paged<RestingRow>("wearable_daily", "date,rhr", "date") {
            filter { gte("date", from); gt("rhr", 0) }
        }.mapNotNull { r -> r.rhr?.let { LocalDate.parse(r.date) to it } }
        ZoneAnchorSeries(maxima, resting, loadLthrSeries())
    }

    /** Zepp's lactate-threshold heart rate per qualifying run, oldest first: only that one number of each summary. */
    suspend fun loadLthrSeries(): List<Pair<LocalDate, Double>> = ReadCache.get("zones.lthr") {
        paged<LthrRow>("zepp_workout_detail", "start_time,lthr:decoded->summary->lactateThresholdHrBpm", "start_time") {
            filter { gte("start_time", LocalDate.now().minusYears(YEARS_BACK).toString()) }
        }.mapNotNull { r -> r.lthr?.let { OffsetDateTime.parse(r.startTime).toLocalDate() to it } }
    }

    // A project's API returns at most 1,000 rows per request; three years of sessions are more than that.
    private suspend inline fun <reified T : Any> paged(
        table: String,
        columns: String,
        orderBy: String,
        crossinline where: PostgrestRequestBuilder.() -> Unit,
    ): List<T> {
        val out = mutableListOf<T>()
        var start = 0L
        while (true) {
            val page = supabase.postgrest.from(table)
                .select(columns = Columns.raw(columns)) {
                    where()
                    order(orderBy, Order.ASCENDING)
                    range(start, start + PAGE - 1)
                }
                .decodeList<T>()
            out += page
            if (page.size < PAGE) return out
            start += PAGE
        }
    }
}
