package com.bioscan.fieldterminal.data.routine

import com.bioscan.fieldterminal.domain.routine.BikeDock
import com.bioscan.fieldterminal.domain.routine.MetroHop
import com.bioscan.fieldterminal.domain.routine.MetroStation
import com.bioscan.fieldterminal.domain.routine.PhoneDaySlice
import com.bioscan.fieldterminal.domain.routine.PlaceCategory
import com.bioscan.fieldterminal.domain.routine.PlacedVisit
import com.bioscan.fieldterminal.domain.routine.Trip
import com.bioscan.fieldterminal.domain.routine.TripLeg
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

// Routine intelligence R5: the reads behind Lab › ROUTINE, Today's routine line and the Log's unlogged visits, and the
// transit-alternatives function (supabase/functions/transit-alternatives).
@Serializable
private data class VisitWithPlaceRow(
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("place_id") val placeId: String? = null,
)

@Serializable
private data class SessionStartRow(@SerialName("start_time") val startTime: String)

@Serializable
data class AppWeekTotal(val pkg: String, val label: String?, val category: String, val minutes: Double, val launches: Int)

@Serializable
private data class TransitResponse(
    val stations: List<TransitStation> = emptyList(),
    val hops: List<TransitHop> = emptyList(),
    val docks: List<TransitDock> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class TransitStation(val id: String, val name: String, val lat: Double, val lon: Double)

@Serializable
private data class TransitHop(val fromId: String, val toId: String, val runSec: Int, val stopSec: Int = 0)

@Serializable
private data class TransitDock(val id: String, val lat: Double, val lon: Double, val rentable: Int? = null, val returnable: Int? = null)

data class TransitData(val stations: List<MetroStation>, val hops: List<MetroHop>, val docks: List<BikeDock>)

sealed interface TransitResult {
    data class Ready(val data: TransitData) : TransitResult
    data object NotConfigured : TransitResult
    data class Failed(val message: String) : TransitResult
}

class RoutineRepository(private val supabase: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun mobilitySince(from: LocalDate): List<MobilityDailyRow> =
        supabase.postgrest.from("mobility_daily")
            .select(columns = Columns.list("date,coverage_pct,minutes_by_category,n_places,new_places,entropy_norm,radius_gyration_km,time_from_home_min,outdoor_daylight_min,routine_similarity,day_type")) {
                filter { gte("date", from.toString()) }
                order("date", Order.ASCENDING)
            }
            .decodeList<MobilityDailyRow>()

    suspend fun phoneSince(from: LocalDate): List<PhoneDaySlice> =
        supabase.postgrest.from("phone_usage_daily")
            .select(columns = Columns.list("date,coverage_pct,screen_min,unlocks,bed_window_min,night_unlocks")) {
                filter { gte("date", from.toString()) }
                order("date", Order.ASCENDING)
            }
            .decodeList<PhoneUsageDailyRow>()
            .map { PhoneDaySlice(LocalDate.parse(it.date), it.screenMin, it.bedWindowMin, it.nightUnlocks) }

    // Minutes and launches per app over the days since [from], most used first.
    suspend fun appsSince(from: LocalDate, top: Int = 5): List<AppWeekTotal> =
        supabase.postgrest.from("phone_app_usage_daily")
            .select(columns = Columns.list("date,package,app_label,category,foreground_min,launches,bed_window_min,after_21_min")) {
                filter { gte("date", from.toString()) }
                limit(1000)
            }
            .decodeList<PhoneAppUsageRow>()
            .groupBy { it.pkg }
            .map { (pkg, rows) -> AppWeekTotal(pkg, rows.firstNotNullOfOrNull { it.appLabel }, rows.last().category, rows.sumOf { it.foregroundMin }, rows.sumOf { it.launches }) }
            .sortedByDescending { it.minutes }
            .take(top)

    suspend fun visitsSince(since: Instant, places: List<PlaceRow>): List<PlacedVisit> {
        val byId = places.associateBy { it.id }
        return supabase.postgrest.from("place_visits")
            .select(columns = Columns.list("start_at,end_at,place_id")) {
                filter { gte("start_at", since.toString()) }
                order("start_at", Order.ASCENDING)
                limit(1000)
            }
            .decodeList<VisitWithPlaceRow>()
            .mapNotNull { v ->
                val s = parseTimestamp(v.startAt) ?: return@mapNotNull null
                val e = parseTimestamp(v.endAt) ?: return@mapNotNull null
                val p = v.placeId?.let { byId[it] }
                PlacedVisit(s.toEpochMilli(), e.toEpochMilli(), v.placeId, p?.label, PlaceCategory.of(p?.category))
            }
    }

    suspend fun sessionStartsSince(since: Instant): List<Long> =
        supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("start_time")) {
                filter { gte("start_time", since.toString()) }
                limit(1000)
            }
            .decodeList<SessionStartRow>()
            .mapNotNull { parseTimestamp(it.startTime)?.toEpochMilli() }

    suspend fun commutesSince(since: Instant): List<Trip> =
        supabase.postgrest.from("trips")
            .select(columns = Columns.list("start_at,end_at,from_place_id,to_place_id,distance_km,legs,active_min,is_commute")) {
                filter {
                    gte("start_at", since.toString())
                    eq("is_commute", true)
                }
                order("start_at", Order.ASCENDING)
            }
            .decodeList<TripRow>()
            .mapNotNull { r ->
                val s = parseTimestamp(r.startAt) ?: return@mapNotNull null
                val e = parseTimestamp(r.endAt) ?: return@mapNotNull null
                Trip(s.toEpochMilli(), e.toEpochMilli(), r.fromPlaceId, r.toPlaceId, r.distanceKm, r.legs.map { TripLeg(it.mode, it.minutes) })
            }

    // Station lists and live docks for the commute alternatives. The request carries no location.
    suspend fun transit(): TransitResult = try {
        val response = supabase.functions.invoke(function = "transit-alternatives")
        val body = json.decodeFromString<TransitResponse>(response.bodyAsText())
        when {
            body.error == "tdx_not_configured" -> TransitResult.NotConfigured
            body.error != null -> TransitResult.Failed(body.error)
            else -> TransitResult.Ready(
                TransitData(
                    body.stations.map { MetroStation(it.id, it.name, it.lat, it.lon) },
                    body.hops.map { MetroHop(it.fromId, it.toId, it.runSec, it.stopSec) },
                    body.docks.map { BikeDock(it.id, it.lat, it.lon, it.rentable, it.returnable) },
                ),
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // supabase-kt throws on a non-2xx answer; the function's 503 means TDX is not set up yet.
        if (e.message?.contains("tdx_not_configured") == true) TransitResult.NotConfigured
        else TransitResult.Failed(e.message ?: e.javaClass.simpleName)
    }
}
