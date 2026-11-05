package com.bioscan.fieldterminal.data.routine

import android.content.Context
import com.bioscan.fieldterminal.data.GeocodingRepository
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.WeatherRepository
import com.bioscan.fieldterminal.data.keysetPagesDescending
import com.bioscan.fieldterminal.data.orNullUnlessCancelled
import com.bioscan.fieldterminal.domain.routine.DayVisit
import com.bioscan.fieldterminal.domain.routine.Fix
import com.bioscan.fieldterminal.domain.routine.Place
import com.bioscan.fieldterminal.domain.routine.PlaceCategory
import com.bioscan.fieldterminal.domain.routine.PlaceVisitTime
import com.bioscan.fieldterminal.domain.routine.PlacedPoint
import com.bioscan.fieldterminal.domain.routine.Stay
import com.bioscan.fieldterminal.domain.routine.TransitionEvent
import com.bioscan.fieldterminal.domain.routine.Trip
import com.bioscan.fieldterminal.domain.routine.TripLeg
import com.bioscan.fieldterminal.domain.routine.TripVisit
import com.bioscan.fieldterminal.domain.routine.assignStaysToPlaces
import com.bioscan.fieldterminal.domain.routine.categoryFromOsm
import com.bioscan.fieldterminal.domain.routine.commuteFlagChanges
import com.bioscan.fieldterminal.domain.routine.hourlyPlaceVector
import com.bioscan.fieldterminal.domain.routine.isCommute
import com.bioscan.fieldterminal.domain.routine.mobilityDay
import com.bioscan.fieldterminal.domain.routine.suggestWorkPlace
import com.bioscan.fieldterminal.domain.routine.tripsBetween
import com.bioscan.fieldterminal.domain.routine.visitsForNewPlace
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// Routine intelligence R3 (docs/routine-intelligence/01-research-and-plan.md, section 10): places, trips and mobility_daily
// (supabase/migrations/20261107_routine_places.sql), built on the phone from what R2 stored.
@Serializable
data class PlaceRow(
    val id: String,
    val lat: Double,
    val lon: Double,
    @SerialName("radius_m") val radiusM: Double = 150.0,
    val label: String? = null,
    val category: String? = null,
    val source: String = "learned",
    @SerialName("osm_name") val osmName: String? = null,
    @SerialName("osm_category") val osmCategory: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toPlace() = Place(id, lat, lon, radiusM, label, PlaceCategory.of(category))
}

@Serializable
private data class NewPlaceRow(
    val id: String,
    val lat: Double,
    val lon: Double,
    val label: String? = null,
    val category: String? = null,
    val source: String,
    @SerialName("radius_m") val radiusM: Double = 150.0,
)

// What a trip insert sends. No property has a default on purpose: the Supabase client's JSON leaves out a property that
// equals its default, and in a multi-row insert PostgREST turns a key one row lacks into null, not the column default.
// TripRow's active_min = 0.0 default made every batch mixing a still trip with an active one fail on the not-null
// constraint (400), so no trip was stored from 1 Nov 2026 and mobility_daily stopped behind it.
@Serializable
internal data class TripInsertRow(
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("from_place_id") val fromPlaceId: String?,
    @SerialName("to_place_id") val toPlaceId: String?,
    @SerialName("distance_km") val distanceKm: Double,
    val legs: List<TripLegRow>,
    @SerialName("active_min") val activeMin: Double,
    @SerialName("is_commute") val isCommute: Boolean,
)

@Serializable
private data class CommuteFlagUpdate(@SerialName("is_commute") val isCommute: Boolean)

@Serializable
private data class VisitPlaceUpdate(@SerialName("place_id") val placeId: String?)

@Serializable
private data class TripFromUpdate(@SerialName("from_place_id") val fromPlaceId: String)

@Serializable
private data class TripToUpdate(@SerialName("to_place_id") val toPlaceId: String)

@Serializable
private data class DayFixRow(
    val ts: String,
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    val kind: String,
    val activity: String? = null,
)

// One day of what was recorded, for the day map.
data class DayRecord(
    val visits: List<com.bioscan.fieldterminal.domain.routine.TimelineVisit>,
    val trips: List<Trip>,
    val storedCommute: Set<Long>,
    val fixes: List<DayFix>,
)

data class DayFix(val ts: Long, val lat: Double, val lon: Double, val accuracyM: Double?, val activity: String?)

@Serializable
data class TripRow(
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("from_place_id") val fromPlaceId: String? = null,
    @SerialName("to_place_id") val toPlaceId: String? = null,
    @SerialName("distance_km") val distanceKm: Double,
    val legs: List<TripLegRow> = emptyList(),
    @SerialName("active_min") val activeMin: Double = 0.0,
    @SerialName("is_commute") val isCommute: Boolean = false,
)

@Serializable
data class TripLegRow(val mode: String, val minutes: Double)

@Serializable
data class MobilityDailyRow(
    val date: String,
    @SerialName("coverage_pct") val coveragePct: Int,
    @SerialName("minutes_by_category") val minutesByCategory: Map<String, Double> = emptyMap(),
    @SerialName("n_places") val nPlaces: Int,
    @SerialName("new_places") val newPlaces: Int,
    @SerialName("entropy_norm") val entropyNorm: Double? = null,
    @SerialName("radius_gyration_km") val radiusGyrationKm: Double? = null,
    @SerialName("time_from_home_min") val timeFromHomeMin: Double,
    @SerialName("outdoor_daylight_min") val outdoorDaylightMin: Double,
    @SerialName("routine_similarity") val routineSimilarity: Double? = null,
    @SerialName("day_type") val dayType: String,
)

@Serializable
private data class VisitReadRow(
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    val lat: Double,
    val lon: Double,
    @SerialName("place_id") val placeId: String? = null,
)

@Serializable
private data class TransitionReadRow(val ts: String, val activity: String, val transition: String)

@Serializable
private data class TripFixRow(val ts: String, val lat: Double, val lon: Double, @SerialName("accuracy_m") val accuracyM: Double? = null)

@Serializable
private data class DailyContextWeatherRow(
    val date: String,
    @SerialName("utc_offset_min") val utcOffsetMin: Int,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("temp_max_c") val tempMaxC: Double? = null,
    @SerialName("humidity_mean_pct") val humidityMeanPct: Double? = null,
    @SerialName("daylight_hours") val daylightHours: Double? = null,
)

@Serializable
private data class PlaceLabelUpdate(val label: String?, val category: String?, @SerialName("updated_at") val updatedAt: String)

@Serializable
private data class PlaceOsmUpdate(
    @SerialName("osm_name") val osmName: String?,
    @SerialName("osm_category") val osmCategory: String?,
    @SerialName("osm_checked_at") val osmCheckedAt: String,
)

private fun ms(ts: String): Long? = parseTimestamp(ts)?.toEpochMilli()

private const val PAGE = 1000L

class PlacesRepository(private val supabase: SupabaseClient) {

    suspend fun loadPlaces(): List<PlaceRow> =
        supabase.postgrest.from("places")
            .select(columns = Columns.list("id,lat,lon,radius_m,label,category,source,osm_name,osm_category,created_at"))
            .decodeList<PlaceRow>()

    // Each stay's place, creating places for stays no known place holds. Setup's home location becomes the Home place the
    // first time, so home never has to be learned or named.
    suspend fun assign(context: Context, stays: List<Stay>): List<String> {
        val known = loadPlaces().toMutableList()
        val home = MapSettingsStore.getHome(context)
        if (home != null && known.none { it.source == "home_setting" }) {
            val row = NewPlaceRow(UUID.randomUUID().toString(), home.first, home.second, "Home", PlaceCategory.HOME.key, "home_setting")
            supabase.postgrest.from("places").insert(row)
            known += PlaceRow(row.id, row.lat, row.lon, label = row.label, category = row.category, source = row.source)
        }
        val result = assignStaysToPlaces(stays, known.map { it.toPlace() }) { UUID.randomUUID().toString() }
        if (result.created.isNotEmpty()) {
            supabase.postgrest.from("places").insert(result.created.map { NewPlaceRow(it.id, it.lat, it.lon, source = "learned") })
        }
        return result.placeIds
    }

    // Trips between the visits of the last 72 hours; those starting in the last 48 are replaced, as visits are. A larger
    // [writeFrom] rebuilds further back (the one-off repair after the insert bug above).
    suspend fun refreshTrips(now: Instant = Instant.now(), writeFrom: Instant = now.minusSeconds(48 * 3600)) {
        val windowStart = writeFrom.minusSeconds(24 * 3600)
        val visits = loadVisits(windowStart).map { TripVisit(it.first, it.second, it.third.lat, it.third.lon, it.third.placeId) }
        // Paged by offset, not by ts: an exit and an enter often share a timestamp, and a keyset page boundary between them
        // would drop one. One page of 1,000 used to cover only the oldest ~36 hours of a 72-hour window, so the newest
        // trips had no transitions and read "unknown".
        val transitions = mutableListOf<TransitionReadRow>()
        var offset = 0L
        while (true) {
            val page = supabase.postgrest.from("activity_transitions")
                .select(columns = Columns.list("ts,activity,transition")) {
                    filter { gte("ts", windowStart.toString()) }
                    order("ts", Order.ASCENDING)
                    order("activity", Order.ASCENDING)
                    order("transition", Order.ASCENDING)
                    range(offset, offset + PAGE - 1)
                }
                .decodeList<TransitionReadRow>()
            transitions += page
            if (page.size < PAGE) break
            offset += PAGE
        }
        val events = transitions.mapNotNull { r -> ms(r.ts)?.let { TransitionEvent(it, r.activity, r.transition == "enter") } }
        val fixes = keysetPagesDescending<TripFixRow>(1000, 100_000, { it.ts }) { before ->
            supabase.postgrest.from("location_fixes")
                .select(columns = Columns.list("ts,lat,lon,accuracy_m")) {
                    filter {
                        gte("ts", windowStart.toString())
                        eq("kind", "trip")
                        if (before != null) lt("ts", before)
                    }
                    order("ts", Order.DESCENDING)
                    limit(1000)
                }
                .decodeList<TripFixRow>()
        }.mapNotNull { r -> ms(r.ts)?.let { Fix(it, r.lat, r.lon, r.accuracyM) } }
            .filter { (it.accuracyM ?: 0.0) <= 200.0 }
        val categories = loadPlaces().associate { it.id to PlaceCategory.of(it.category) }
        val trips = tripsBetween(visits, events, fixes).filter { it.startMs >= writeFrom.toEpochMilli() }
        supabase.postgrest.from("trips").delete { filter { gte("start_at", writeFrom.toString()) } }
        trips.chunked(500).forEach { chunk ->
            supabase.postgrest.from("trips").insert(chunk.map { it.toRow(isCommute(it) { id -> categories[id] }) })
        }
    }

    // mobility_daily for the last [days] completed local days, and those days' weather and daylight in daily_context.
    suspend fun refreshMobility(context: Context, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now(), days: Long = 2) {
        val today = now.atZone(zone).toLocalDate()
        val dates = (days downTo 1).map { today.minusDays(it) }
        val since = dates.first().minusDays(35).atStartOfDay(zone).toInstant()
        val places = loadPlaces()
        val byId = places.associateBy { it.id }
        val visits = loadVisits(since).map { (s, e, v) -> DayVisit(s, e, v.lat, v.lon, v.placeId, PlaceCategory.of(v.placeId?.let { byId[it]?.category })) }
        val trips = loadTrips(dates.first().minusDays(1).atStartOfDay(zone).toInstant())
        val homePlace = places.firstOrNull { it.category == PlaceCategory.HOME.key }
        val home = homePlace?.let { it.lat to it.lon } ?: MapSettingsStore.getHome(context)
        val firstSeen = places.mapNotNull { p -> p.createdAt?.let(::parseTimestamp)?.let { p.id to it } }.toMap()
        for (date in dates) {
            val earlier = (1..4L).map { w -> hourlyPlaceVector(date.minusWeeks(w), zone, visits) }
            val day = mobilityDay(date, zone, visits, trips, home, firstSeen, earlier)
            supabase.postgrest.from("mobility_daily").upsert(
                MobilityDailyRow(
                    date = date.toString(),
                    coveragePct = day.coveragePct,
                    minutesByCategory = day.minutesByCategory,
                    nPlaces = day.nPlaces,
                    newPlaces = day.newPlaces,
                    entropyNorm = day.entropyNorm,
                    radiusGyrationKm = day.radiusGyrationKm,
                    timeFromHomeMin = day.timeFromHomeMin,
                    outdoorDaylightMin = day.outdoorDaylightMin,
                    routineSimilarity = day.routineSimilarity,
                    dayType = day.dayType.key,
                ),
            ) { onConflict = "user_id,date" }
            recordDayContext(date, zone, mainPlace(date, zone, visits) ?: home, day.daylightHours)
        }
    }

    // The analysis review's passive weather and daylight (docs/analysis-review/README.md, phase B): the day's temperature
    // peak and mean humidity where most of it was spent, and its day length. Weather is optional; a failed fetch leaves
    // those two columns as they were.
    private suspend fun recordDayContext(date: LocalDate, zone: ZoneId, at: Pair<Double, Double>?, daylightHours: Double?) {
        val hours = at?.let { (lat, lon) ->
            orNullUnlessCancelled {
                WeatherRepository().fetchHourly(lat, lon, date.atStartOfDay(zone), date.atTime(23, 0).atZone(zone)).hours
            }
        }.orEmpty()
        val offset = date.atStartOfDay(zone).offset.totalSeconds / 60
        supabase.postgrest.from("daily_context").upsert(
            DailyContextWeatherRow(
                date = date.toString(),
                utcOffsetMin = offset,
                timeZone = zone.id,
                tempMaxC = hours.maxOfOrNull { it.tempC },
                humidityMeanPct = hours.mapNotNull { it.humidityPct }.takeIf { it.isNotEmpty() }?.average(),
                daylightHours = daylightHours,
            ),
        ) { onConflict = "user_id,date" }
    }

    private fun mainPlace(date: LocalDate, zone: ZoneId, visits: List<DayVisit>): Pair<Double, Double>? {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return visits.maxByOrNull { (minOf(it.endMs, to) - maxOf(it.startMs, from)) }
            ?.takeIf { minOf(it.endMs, to) > maxOf(it.startMs, from) }
            ?.let { it.lat to it.lon }
    }

    // One OpenStreetMap suggestion for up to [max] places not looked up yet (Nominatim asks for light use).
    suspend fun lookUpSuggestions(max: Int = 3) {
        val pending = supabase.postgrest.from("places")
            .select(columns = Columns.list("id,lat,lon")) {
                filter { filter("osm_checked_at", io.github.jan.supabase.postgrest.query.filter.FilterOperator.IS, "null") }
                limit(max.toLong())
            }
            .decodeList<PlaceRow>()
        val geocoder = GeocodingRepository()
        for (p in pending) {
            val found = geocoder.reverse(p.lat, p.lon)
            supabase.postgrest.from("places").update(
                PlaceOsmUpdate(found?.name, categoryFromOsm(found?.osmClass, found?.osmType)?.key, Instant.now().toString()),
            ) { filter { eq("id", p.id) } }
        }
    }

    // A new label or category can make trips commutes or stop them being ones: the stored flags follow.
    suspend fun saveLabel(id: String, label: String?, category: PlaceCategory?) {
        supabase.postgrest.from("places").update(
            PlaceLabelUpdate(label?.trim()?.ifBlank { null }, category?.key, Instant.now().toString()),
        ) { filter { eq("id", id) } }
        recomputeCommuteFlags()
    }

    // A place marked by hand on the day map. It takes the visits of the last [days] days whose centre is inside its circle
    // and closer to it than to their current place, so it counts from the first day recorded, not only from now on.
    suspend fun addManualPlace(lat: Double, lon: Double, radiusM: Double, label: String?, category: PlaceCategory?, days: Long = 60): String {
        val id = UUID.randomUUID().toString()
        supabase.postgrest.from("places").insert(
            NewPlaceRow(id, lat, lon, label?.trim()?.ifBlank { null }, category?.key, "manual", radiusM),
        )
        val places = loadPlaces().filter { it.id != id }.map { it.toPlace() }
        val visits = loadVisits(Instant.now().minusSeconds(days * 86_400))
            .map { (s, _, v) -> PlacedPoint(s, v.lat, v.lon, v.placeId) to v.startAt }
        val take = visitsForNewPlace(visits.map { it.first }, Place(id, lat, lon, radiusM, label, category), places).map { it.startMs }.toSet()
        visits.filter { it.first.startMs in take }.forEach { (_, startAt) ->
            supabase.postgrest.from("place_visits").update(VisitPlaceUpdate(id)) { filter { eq("start_at", startAt) } }
        }
        if (take.isNotEmpty()) relinkTrips()
        recomputeCommuteFlags()
        return id
    }

    // Merging [fromId] into [intoId]: its visits and trips move over, then it is deleted. For two places that are one
    // (both ends of a building), or a place recorded by mistake next to a real one.
    suspend fun mergePlace(fromId: String, intoId: String) {
        supabase.postgrest.from("place_visits").update(VisitPlaceUpdate(intoId)) { filter { eq("place_id", fromId) } }
        supabase.postgrest.from("trips").update(TripFromUpdate(intoId)) { filter { eq("from_place_id", fromId) } }
        supabase.postgrest.from("trips").update(TripToUpdate(intoId)) { filter { eq("to_place_id", fromId) } }
        supabase.postgrest.from("places").delete { filter { eq("id", fromId) } }
        recomputeCommuteFlags()
    }

    // Deleting a place keeps its visits (place_id becomes null, the time counts as unlabelled). A later stay there starts
    // a new place, so this is for a place that should not exist at all; merge is usually what is wanted.
    suspend fun deletePlace(id: String) {
        supabase.postgrest.from("places").delete { filter { eq("id", id) } }
        recomputeCommuteFlags()
    }

    // The trips' ends follow their visits' places after visits were reassigned.
    private suspend fun relinkTrips(days: Long = 60) {
        val since = Instant.now().minusSeconds(days * 86_400)
        val visits = loadVisits(since)
        val endsAt = visits.associate { (_, e, v) -> e to v.placeId }
        val startsAt = visits.associate { (s, _, v) -> s to v.placeId }
        for (row in loadTripRows(since)) {
            val s = ms(row.startAt) ?: continue
            val e = ms(row.endAt) ?: continue
            val from = endsAt[s]
            val to = startsAt[e]
            if (from != null && from != row.fromPlaceId) {
                supabase.postgrest.from("trips").update(TripFromUpdate(from)) { filter { eq("start_at", row.startAt) } }
            }
            if (to != null && to != row.toPlaceId) {
                supabase.postgrest.from("trips").update(TripToUpdate(to)) { filter { eq("start_at", row.startAt) } }
            }
        }
    }

    // Each stored trip's is_commute made to agree with the places' current categories.
    suspend fun recomputeCommuteFlags() {
        val categories = loadPlaces().associate { it.id to PlaceCategory.of(it.category) }
        val rows = loadTripRows(Instant.EPOCH)
        val trips = rows.mapNotNull { r ->
            val s = ms(r.startAt) ?: return@mapNotNull null
            val e = ms(r.endAt) ?: return@mapNotNull null
            Trip(s, e, r.fromPlaceId, r.toPlaceId, r.distanceKm, emptyList()) to r.isCommute
        }
        val startAtOf = rows.associateBy { ms(it.startAt) }
        commuteFlagChanges(trips) { categories[it] }.forEach { (trip, flag) ->
            val startAt = startAtOf[trip.startMs]?.startAt ?: return@forEach
            supabase.postgrest.from("trips").update(CommuteFlagUpdate(flag)) { filter { eq("start_at", startAt) } }
        }
    }

    // The visits, trips and fixes of one local day (and the visits and trips that cross its midnights), for the day map.
    suspend fun loadDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayRecord {
        val from = date.atStartOfDay(zone).toInstant()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant()
        val visits = supabase.postgrest.from("place_visits")
            .select(columns = Columns.list("start_at,end_at,lat,lon,place_id")) {
                filter {
                    lt("start_at", to.toString())
                    gt("end_at", from.toString())
                }
                order("start_at", Order.ASCENDING)
                limit(1000)
            }
            .decodeList<VisitReadRow>()
            .mapNotNull { v ->
                val s = ms(v.startAt) ?: return@mapNotNull null
                val e = ms(v.endAt) ?: return@mapNotNull null
                com.bioscan.fieldterminal.domain.routine.TimelineVisit(s, e, v.lat, v.lon, v.placeId)
            }
        val tripRows = supabase.postgrest.from("trips")
            .select(columns = Columns.list("start_at,end_at,from_place_id,to_place_id,distance_km,legs,active_min,is_commute")) {
                filter {
                    lt("start_at", to.toString())
                    gt("end_at", from.toString())
                }
                order("start_at", Order.ASCENDING)
                limit(1000)
            }
            .decodeList<TripRow>()
        val trips = tripRows.mapNotNull { it.toTrip() }
        val commute = tripRows.filter { it.isCommute }.mapNotNull { ms(it.startAt) }.toSet()
        val fixes = keysetPagesDescending<DayFixRow>(1000, 20_000, { it.ts }) { before ->
            supabase.postgrest.from("location_fixes")
                .select(columns = Columns.list("ts,lat,lon,accuracy_m,kind,activity")) {
                    filter {
                        gte("ts", from.toString())
                        lt("ts", (before ?: to.toString()))
                    }
                    order("ts", Order.DESCENDING)
                    limit(1000)
                }
                .decodeList<DayFixRow>()
        }.mapNotNull { r -> ms(r.ts)?.let { DayFix(it, r.lat, r.lon, r.accuracyM, r.activity) } }.sortedBy { it.ts }
        return DayRecord(visits, trips, commute, fixes)
    }

    // Every trip since [since], oldest first, for the commute analysis (door-to-door chains need the non-commute legs too).
    suspend fun tripsSince(since: Instant): List<Trip> = loadTripRows(since).mapNotNull { it.toTrip() }

    private suspend fun loadTripRows(since: Instant): List<TripRow> =
        keysetPagesDescending<TripRow>(1000, 50_000, { it.startAt }) { before ->
            supabase.postgrest.from("trips")
                .select(columns = Columns.list("start_at,end_at,from_place_id,to_place_id,distance_km,legs,active_min,is_commute")) {
                    filter {
                        gte("start_at", since.toString())
                        if (before != null) lt("start_at", before)
                    }
                    order("start_at", Order.DESCENDING)
                    limit(1000)
                }
                .decodeList<TripRow>()
        }.sortedBy { ms(it.startAt) ?: 0L }

    // Hours and visits per place over the last [days] days, for the PLACES card.
    suspend fun placeTotals(days: Long = 14, now: Instant = Instant.now()): Map<String, Pair<Double, Int>> =
        loadVisits(now.minusSeconds(days * 86_400))
            .filter { it.third.placeId != null }
            .groupBy { it.third.placeId!! }
            .mapValues { (_, vs) -> vs.sumOf { (s, e, _) -> (e - s) / 3_600_000.0 } to vs.size }

    // The place weekday office hours were spent at over the last two weeks, if it is not labelled yet (a suggestion only).
    suspend fun workSuggestion(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String? {
        val places = loadPlaces()
        if (places.any { it.category == PlaceCategory.WORK.key }) return null
        val homeIds = places.filter { it.category == PlaceCategory.HOME.key }.map { it.id }.toSet()
        val visits = loadVisits(now.minusSeconds(14 * 86_400)).mapNotNull { (s, e, v) -> v.placeId?.let { PlaceVisitTime(it, s, e) } }
        return suggestWorkPlace(visits, homeIds, zone)
    }

    suspend fun recentCommutes(days: Long = 14, now: Instant = Instant.now()): List<TripRow> =
        supabase.postgrest.from("trips")
            .select(columns = Columns.list("start_at,end_at,from_place_id,to_place_id,distance_km,legs,active_min,is_commute")) {
                filter {
                    gte("start_at", now.minusSeconds(days * 86_400).toString())
                    eq("is_commute", true)
                }
                order("start_at", Order.DESCENDING)
            }
            .decodeList<TripRow>()

    suspend fun latestMobility(): MobilityDailyRow? =
        supabase.postgrest.from("mobility_daily")
            .select(columns = Columns.list("date,coverage_pct,minutes_by_category,n_places,new_places,entropy_norm,radius_gyration_km,time_from_home_min,outdoor_daylight_min,routine_similarity,day_type")) {
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<MobilityDailyRow>()
            .firstOrNull()

    private suspend fun loadVisits(since: Instant): List<Triple<Long, Long, VisitReadRow>> =
        keysetPagesDescending<VisitReadRow>(1000, 10_000, { it.startAt }) { before ->
            supabase.postgrest.from("place_visits")
                .select(columns = Columns.list("start_at,end_at,lat,lon,place_id")) {
                    filter {
                        gte("start_at", since.toString())
                        if (before != null) lt("start_at", before)
                    }
                    order("start_at", Order.DESCENDING)
                    limit(1000)
                }
                .decodeList<VisitReadRow>()
        }.mapNotNull { v -> val s = ms(v.startAt); val e = ms(v.endAt); if (s == null || e == null) null else Triple(s, e, v) }
            .sortedBy { it.first }

    // Trips since [since], oldest first (Training › Load's ACTIVE TRAVEL card, and mobility_daily).
    suspend fun loadTrips(since: Instant): List<Trip> =
        supabase.postgrest.from("trips")
            .select(columns = Columns.list("start_at,end_at,from_place_id,to_place_id,distance_km,legs,active_min,is_commute")) {
                filter { gte("start_at", since.toString()) }
                order("start_at", Order.ASCENDING)
                limit(1000)
            }
            .decodeList<TripRow>()
            .mapNotNull { it.toTrip() }

    // DELETE ALL in Setup's LOCATIONS card also removes what R3 derived.
    suspend fun deleteAll() {
        val epoch = "1970-01-01T00:00:00Z"
        supabase.postgrest.from("trips").delete { filter { gte("start_at", epoch) } }
        supabase.postgrest.from("mobility_daily").delete { filter { gte("date", "1900-01-01") } }
        supabase.postgrest.from("places").delete { filter { gte("created_at", epoch) } }
    }
}

private fun TripRow.toTrip(): Trip? {
    val s = ms(startAt) ?: return null
    val e = ms(endAt) ?: return null
    return Trip(s, e, fromPlaceId, toPlaceId, distanceKm, legs.map { TripLeg(it.mode, it.minutes) })
}

internal fun Trip.toRow(isCommute: Boolean) = TripInsertRow(
    startAt = Instant.ofEpochMilli(startMs).toString(),
    endAt = Instant.ofEpochMilli(endMs).toString(),
    fromPlaceId = fromPlaceId,
    toPlaceId = toPlaceId,
    distanceKm = distanceKm,
    legs = legs.map { TripLegRow(it.mode, it.minutes) },
    activeMin = activeMinutes,
    isCommute = isCommute,
)
