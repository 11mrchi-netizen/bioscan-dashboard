package com.bioscan.fieldterminal.data.routine

import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.ReadCache
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.routine.CommuteWay
import com.bioscan.fieldterminal.domain.routine.DailyLine
import com.bioscan.fieldterminal.domain.routine.PlaceCategory
import com.bioscan.fieldterminal.domain.routine.RibbonDay
import com.bioscan.fieldterminal.domain.routine.RibbonInterval
import com.bioscan.fieldterminal.domain.routine.RibbonKind
import com.bioscan.fieldterminal.domain.routine.RoutineActivity
import com.bioscan.fieldterminal.domain.routine.RoutineLog
import com.bioscan.fieldterminal.domain.routine.TimeSource
import com.bioscan.fieldterminal.domain.routine.clockSeries
import com.bioscan.fieldterminal.domain.routine.commuteJourneys
import com.bioscan.fieldterminal.domain.routine.everyDaySince
import com.bioscan.fieldterminal.domain.routine.inferredTime
import com.bioscan.fieldterminal.domain.routine.loggedTime
import com.bioscan.fieldterminal.domain.routine.placeLines
import com.bioscan.fieldterminal.domain.routine.ribbonDay
import com.bioscan.fieldterminal.domain.routine.ribbonKindOf
import com.bioscan.fieldterminal.domain.routine.rollingSpread
import com.bioscan.fieldterminal.domain.routine.timeLines
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// What ME › ROUTINE shows (domain/routine/RoutinePage.kt): a year of daily lines, 28 days of ribbons, the practices and
// their logs. Every read is soft: a stream that is off, or a table that fails, gives an empty section, not an error.

data class RoutinePageData(
    val today: LocalDate,
    /** The last 28 days, today first. */
    val ribbons: List<RibbonDay>,
    val time: List<DailyLine>,
    val places: List<DailyLine>,
    val phone: List<DailyLine>,
    val regularity: List<DailyLine>,
    val activities: List<RoutineActivity>,
    val logs: List<RoutineLog>,
    val apps: List<AppWeekTotal>,
)

@Serializable
private data class PageSleepRow(
    val date: String,
    val bedtime: String? = null,
    @SerialName("wake_time") val wakeTime: String? = null,
    val hours: Double? = null,
)

@Serializable
private data class PageSessionRow(val type: String, @SerialName("start_time") val startTime: String, @SerialName("duration_min") val durationMin: Double? = null)

private const val YEAR = 365L
private const val RIBBON_DAYS = 28

class RoutinePageRepository(private val supabase: SupabaseClient) {
    private val practices = PracticesRepository(supabase)
    private val routine = RoutineRepository(supabase)
    private val placesRepo = PlacesRepository(supabase)

    suspend fun load(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): RoutinePageData =
        ReadCache.kept("routine.page.${LocalDate.now(zone)}", tables = TABLES) { compute(zone, now) }

    private suspend fun compute(zone: ZoneId, now: Instant): RoutinePageData = coroutineScope {
        val today = now.atZone(zone).toLocalDate()
        val yesterday = today.minusDays(1)
        val from = today.minusDays(YEAR)
        val ribbonFrom = today.minusDays(RIBBON_DAYS.toLong() - 1)

        val activitiesD = async { catching { practices.activities(includeArchived = true) }.getOrDefault(emptyList()) }
        val logsD = async { catching { practices.logsSince(from) }.getOrDefault(emptyList()) }
        val inferredD = async { practices.inferred(from, zone) }
        val mobilityD = async { catching { routine.mobilitySince(from) }.getOrDefault(emptyList()) }
        val phoneD = async { catching { routine.phoneSince(from) }.getOrDefault(emptyList()) }
        val appsD = async { catching { routine.appsSince(today.minusDays(28), top = 8) }.getOrDefault(emptyList()) }
        val placesD = async { catching { placesRepo.loadPlaces() }.getOrDefault(emptyList()) }
        val tripsD = async { catching { placesRepo.tripsSince(from.atStartOfDay(zone).toInstant()) }.getOrDefault(emptyList()) }
        val sleepD = async { catching { sleepTimes(from) }.getOrDefault(emptyList()) }
        val sessionsD = async { catching { sessionSpans(ribbonFrom.minusDays(1)) }.getOrDefault(emptyList()) }

        val places = placesD.await()
        val categoryOf = places.associate { it.id to PlaceCategory.of(it.category) }
        val trips = tripsD.await()
        val visitsD = async { catching { routine.visitsSince(ribbonFrom.minusDays(1).atStartOfDay(zone).toInstant(), places) }.getOrDefault(emptyList()) }

        // Commuting, door to door, by the day it left.
        val commute = commuteJourneys(trips, categoryOf = { categoryOf[it] })
            .groupBy { Instant.ofEpochMilli(it.departMs).atZone(zone).toLocalDate() }
            .mapValues { (_, j) -> j.filter { it.way == CommuteWay.TO_WORK || it.way == CommuteWay.TO_HOME }.sumOf { it.minutes } }

        val activities = activitiesD.await()
        val logs = logsD.await()
        val mobility = mobilityD.await()
        val phone = phoneD.await()
        val sleep = sleepD.await()
        val inferred = inferredD.await().copy(commuteMin = commute)

        // The days each stream recorded.
        val locationDays = mobility.filter { it.coveragePct >= 50 }.map { LocalDate.parse(it.date) }.toSet()
        val phoneDays = phone.map { it.date }.toSet()
        val recorded = mapOf(
            TimeSource.LOGGED to everyDaySince(logs.minOfOrNull { it.date }, yesterday),
            TimeSource.WATCH to everyDaySince(inferred.sessions.minOfOrNull { it.second }, yesterday),
            TimeSource.LOCATION to locationDays,
            TimeSource.PHONE to phoneDays,
            TimeSource.SLEEP to sleep.mapNotNull { r -> runCatching { LocalDate.parse(r.date) }.getOrNull() }.toSet(),
        )
        // Complete days only: today is still going.
        val time = timeLines(loggedTime(activities, logs) + inferredTime(inferred), recorded, from, yesterday)
        val placeLines = placeLines(mobility.filter { !LocalDate.parse(it.date).isAfter(yesterday) }
            .associate { LocalDate.parse(it.date) to (it.minutesByCategory to it.coveragePct) })

        val phoneDone = phone.filter { !it.date.isAfter(yesterday) }
        val byCategory = inferred.phoneByCategory.filterKeys { !it.isAfter(yesterday) }
        val phoneLines = listOfNotNull(
            DailyLine("phone:screen", "Screen time", TimeSource.PHONE, phoneDone.associate { it.date to it.screenMin }),
            DailyLine("phone:bed", "In the hour before sleep", TimeSource.PHONE, phoneDone.mapNotNull { p -> p.bedWindowMin?.let { p.date to it } }.toMap()),
            DailyLine("phone:night", "Unlocks at night", TimeSource.PHONE, phoneDone.mapNotNull { p -> p.nightUnlocks?.let { p.date to it.toDouble() } }.toMap()),
            DailyLine("phone:video", "Video apps", TimeSource.PHONE, byCategory.mapValues { it.value["video"] ?: 0.0 }),
            DailyLine("phone:social", "Social apps", TimeSource.PHONE, byCategory.mapValues { it.value["social"] ?: 0.0 }),
            DailyLine("phone:game", "Games", TimeSource.PHONE, byCategory.mapValues { it.value["game"] ?: 0.0 }),
        ).filter { l -> l.series.values.any { it > 0 } }

        val bedtimes = sleep.mapNotNull { r -> r.bedtime?.let { b -> instant(b)?.let { LocalDate.parse(r.date) to it } } }.toMap()
        val wakes = sleep.mapNotNull { r -> r.wakeTime?.let { w -> instant(w)?.let { LocalDate.parse(r.date) to it } } }.toMap()
        val regularity = listOfNotNull(
            DailyLine("reg:bedtime", "Bedtime spread", TimeSource.SLEEP, rollingSpread(clockSeries(bedtimes, zone))),
            DailyLine("reg:wake", "Wake time spread", TimeSource.SLEEP, rollingSpread(clockSeries(wakes, zone))),
            DailyLine("reg:similarity", "Like the same weekday", TimeSource.LOCATION,
                mobility.mapNotNull { m -> m.routineSimilarity?.let { LocalDate.parse(m.date) to it * 100 } }.toMap()),
            DailyLine("reg:places", "Places in a day", TimeSource.LOCATION,
                mobility.filter { it.coveragePct >= 50 }.associate { LocalDate.parse(it.date) to it.nPlaces.toDouble() }),
        ).filter { it.series.isNotEmpty() }

        // The ribbons: what took each 15 minutes of the last 28 days.
        val intervals = mutableListOf<RibbonInterval>()
        visitsD.await().forEach { v -> intervals += RibbonInterval(v.startMs, v.endMs, ribbonKindOf(v.category)) }
        trips.filter { it.endMs >= ribbonFrom.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
            .forEach { t -> intervals += RibbonInterval(t.startMs, t.endMs, RibbonKind.MOVE) }
        sleep.forEach { r ->
            val b = r.bedtime?.let(::instant) ?: return@forEach
            val w = r.wakeTime?.let(::instant) ?: return@forEach
            if (w.isAfter(b)) intervals += RibbonInterval(b.toEpochMilli(), w.toEpochMilli(), RibbonKind.ASLEEP)
        }
        sessionsD.await().forEach { s ->
            val start = WallClock.instant(s.startTime, zone) ?: return@forEach
            val min = s.durationMin?.takeIf { it >= 10 } ?: return@forEach
            if (s.type != "walk") intervals += RibbonInterval(start.toEpochMilli(), start.toEpochMilli() + (min * 60_000).toLong(), RibbonKind.TRAINING)
        }
        logs.filter { !it.date.isBefore(ribbonFrom.minusDays(1)) && it.start != null && it.end != null }.forEach { l ->
            intervals += RibbonInterval(l.start!!.atZone(zone).toInstant().toEpochMilli(), l.end!!.atZone(zone).toInstant().toEpochMilli(), RibbonKind.LOGGED)
        }
        val ribbons = (0 until RIBBON_DAYS).map { ribbonDay(today.minusDays(it.toLong()), zone, intervals, now.toEpochMilli()) }

        RoutinePageData(today, ribbons, time, placeLines, phoneLines, regularity, activities, logs, appsD.await())
    }

    private suspend fun sleepTimes(from: LocalDate): List<PageSleepRow> =
        supabase.postgrest.from("sleep_daily").select(Columns.list("date,bedtime,wake_time,hours")) { filter { gte("date", from.toString()) } }
            .decodeList<PageSleepRow>()

    private suspend fun sessionSpans(from: LocalDate): List<PageSessionRow> =
        supabase.postgrest.from("exercise_sessions").select(Columns.list("type,start_time,duration_min")) { filter { gte("start_time", from.toString()) } }
            .decodeList<PageSessionRow>()

    private fun instant(text: String): Instant? = runCatching { OffsetDateTime.parse(text.replace(' ', 'T')).toInstant() }.getOrNull()

    private companion object {
        val TABLES = setOf(
            "routine_activities", "routine_logs", "mobility_daily", "phone_usage_daily", "phone_app_usage_daily", "places",
            "place_visits", "trips", "sleep_daily", "exercise_sessions",
        )
    }
}
