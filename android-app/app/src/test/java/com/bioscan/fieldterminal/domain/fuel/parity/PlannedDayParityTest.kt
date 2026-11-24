package com.bioscan.fieldterminal.domain.fuel.parity

import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.events.EventSport
import com.bioscan.fieldterminal.domain.events.EventStatus
import com.bioscan.fieldterminal.domain.fuel.CalendarOverride
import com.bioscan.fieldterminal.domain.fuel.CalendarSessionInput
import com.bioscan.fieldterminal.domain.fuel.DemandHistory
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.OverrideKind
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.PlannedSessionInput
import com.bioscan.fieldterminal.domain.fuel.RecentRun
import com.bioscan.fieldterminal.domain.fuel.RecordedSessionInput
import com.bioscan.fieldterminal.domain.fuel.StoredLink
import com.bioscan.fieldterminal.domain.fuel.resolvePlannedDays
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Parity with the web's training-day builder (supabase/functions/_shared/fuel-core/plannedDay.ts). Scenarios are written as plain JSON
// (fixtures/planned-day-input.json: events, planned sessions, calendar entries, recordings, history), read here into the phone's input
// types and resolved by resolvePlannedDays; the days it gives (fixtures/planned-day-expected.json) must be what the TypeScript gives.
// After a deliberate change: FT_PARITY_WRITE=1 ./gradlew :app:testDebugUnitTest --tests '*PlannedDayParityTest*'
class PlannedDayParityTest {
    private val dir = File("../../supabase/functions/_shared/fuel-core/fixtures")
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    // ---- scenario writers ----
    private fun obj(vararg p: Pair<String, Any?>) = buildJsonObject { p.forEach { (k, v) -> put(k, prim(v)) } }
    private fun prim(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { prim(it) })
        is Map<*, *> -> buildJsonObject { v.forEach { (k, x) -> put(k as String, prim(x)) } }
        else -> error("unsupported $v")
    }
    private fun ev(id: Long, name: String, kind: String, sport: String, priority: String, date: String, start: String?, status: String = "PLANNED",
                   dur: Double? = null, km: Double? = null, courseEndM: Double? = null, cal: String? = null) =
        obj("id" to id, "name" to name, "kind" to kind, "sport" to sport, "priority" to priority, "date" to date, "start" to start, "status" to status,
            "plannedDurationMin" to dur, "distanceKm" to km, "courseEndM" to courseEndM, "calendarEventId" to cal)
    private fun pl(id: Long, date: String, time: String?, dur: Double?, domain: String?, title: String, gcal: String? = null, linked: Long? = null) =
        obj("id" to id, "date" to date, "startTime" to time, "durationMin" to dur, "domain" to domain, "title" to title, "gcalEventId" to gcal, "exerciseSessionId" to linked)
    private fun cal(id: String, title: String, start: String, end: String?, color: String? = "8", desc: String? = null) =
        obj("eventId" to id, "title" to title, "start" to start, "end" to end, "colorId" to color, "description" to desc)
    private fun rec(id: Long, type: String, start: String, dur: Double, kcal: Double? = null, hr: Double? = null, rpe: Int? = null, km: Double? = null, up: Double? = null) =
        obj("id" to id, "type" to type, "start" to start, "durationMin" to dur, "caloriesActive" to kcal, "avgHr" to hr, "rpe" to rpe, "distanceKm" to km, "ascentM" to up)
    private fun history(w: Double? = 70.0, rest: Double? = 50.0, max: Double? = 190.0, withRuns: Boolean = true, own: Boolean = true) = obj(
        "weightKg" to w, "restingHr" to rest, "maxHr" to max,
        "kcalPerMinByType" to if (own) mapOf("run" to listOf(8.5, 9.0, 11.0), "ride" to listOf(7.0, 8.0)) else emptyMap<String, Any>(),
        "trimpPerMinByType" to if (own) mapOf("run" to listOf(1.0, 1.3, 1.9, 2.1, 0.8), "strength" to listOf(0.5, 0.6, 0.7)) else emptyMap<String, Any>(),
        "recentRuns" to if (withRuns) listOf(
            obj("distanceKm" to 10.0, "ascentM" to 150.0, "durationMin" to 62.0), obj("distanceKm" to 12.5, "ascentM" to 300.0, "durationMin" to 85.0),
            obj("distanceKm" to 8.0, "ascentM" to 40.0, "durationMin" to 46.0), obj("distanceKm" to 21.0, "ascentM" to 520.0, "durationMin" to 158.0),
            obj("distanceKm" to 15.0, "ascentM" to 200.0, "durationMin" to 96.0), obj("distanceKm" to 5.0, "ascentM" to 20.0, "durationMin" to 28.0),
        ) else emptyList<Any>(),
    )
    private fun scenario(name: String, zone: String, from: String, days: Int, now: String, history: JsonObject, events: List<JsonObject> = emptyList(),
                         planned: List<JsonObject> = emptyList(), calendar: List<JsonObject> = emptyList(), recorded: List<JsonObject> = emptyList(),
                         overrides: Map<String, JsonObject> = emptyMap(), links: Map<String, JsonObject> = emptyMap()) =
        obj("name" to name, "zone" to zone, "from" to from, "days" to days, "now" to now, "history" to history, "events" to events, "planned" to planned,
            "calendar" to calendar, "recorded" to recorded, "overrides" to overrides, "links" to links)

    private fun scenarios(): List<JsonObject> {
        val t = "+08:00"
        val day = "2026-11-24"
        val out = mutableListOf<JsonObject>()
        // A Taipei week, Monday 23 to Sunday 29 November, read on Wednesday morning: a past day with its recordings, today, and the future.
        out += scenario(
            "taipei_week", "Asia/Taipei", "2026-11-23", 7, "2026-11-25T06:00:00$t", history(),
            events = listOf(
                ev(1, "Alpine 25", "RACE", "TRAIL_RUN", "B", "2026-11-28", "2026-11-28T07:00:00$t", dur = 150.0, km = 25.0),
                ev(2, "Big mountain day", "LONG_SESSION", "TRAIL_RUN", "C", "2026-11-29", null, dur = 200.0, cal = null),
                ev(3, "Cancelled race", "RACE", "RUN", "A", "2026-11-27", "2026-11-27T08:00:00$t", status = "CANCELLED", dur = 60.0),
                ev(4, "Did not start", "RACE", "RUN", "A", "2026-11-26", "2026-11-26T08:00:00$t", status = "DNS", dur = 60.0),
                ev(5, "Five-a-side", "MATCH", "TEAM_SPORT", "C", "2026-11-26", "2026-11-26T20:00:00$t", dur = 70.0),
            ),
            planned = listOf(
                pl(10, "2026-11-23", "07:00", 60.0, "aerobic_base", "Base run"),
                pl(11, "2026-11-24", "18:00", 55.0, "max_strength", "Squat day"),
                pl(12, "2026-11-25", "17:00", 70.0, "speed", "Track 800m repeats"),
                pl(13, "2026-11-26", "07:00", 45.0, "recovery", "Shakeout"),
                pl(14, "2026-11-27", "06:30", 100.0, "specific_endurance", "Tempo long", gcal = "gc-tempo"),
                pl(15, "2026-11-27", null, null, null, "No time, no domain"),
                pl(16, "2026-11-29", "08:30", 120.0, "aerobic_base", "Long Sunday"),
                pl(17, "2026-11-26", "14:00", 50.0, null, "Fartlek hills"),
                pl(18, "2026-11-26", "15:00", 40.0, null, "Lento  "),
                pl(19, "2026-11-28", "14:00", 60.0, null, "Lunghissimo"),
            ),
            calendar = listOf(
                cal("gc-1", "🏋 Overhead Press 4x4 @47.5kg", "2026-11-24T12:00:00$t", "2026-11-24T13:00:00$t", desc = "Overhead Press 4x4 @47.5kg\nBack Squat 3-5x3 (85%)\nRow 3x8"),
                cal("gc-2", "Easy run 12km", "2026-11-26T12:30:00$t", "2026-11-26T13:30:00$t", desc = "Route: Jade Ridge (12.1km, +200m)"),
                cal("gc-3", "Resurrected", "2026-11-25T13:00:00$t", "2026-11-26T06:00:00$t"),
                cal("gc-4", "Deload — light activity", "2026-11-27T12:00:00$t", "2026-11-27T13:00:00$t"),
                cal("gc-5", "Long Run — 28km", "2026-11-29T06:00:00$t", "2026-11-29T10:00:00$t"),
                cal("gc-6", "Dentist", "2026-11-26T15:00:00$t", "2026-11-26T16:00:00$t", color = "5"),
                cal("gc-tempo", "Tempo long", "2026-11-27T06:30:00$t", "2026-11-27T08:00:00$t"),
                cal("gc-7", "800m Repeats — 5-8 rounds", "2026-11-28T17:00:00$t", "2026-11-28T18:00:00$t"),
                cal("gc-8", "Ignored thing", "2026-11-24T19:00:00$t", "2026-11-24T20:00:00$t"),
                cal("gc-9", "Mystery block", "2026-11-26T09:00:00$t", "2026-11-26T11:30:00$t"),
                cal("gc-10", "Ride 🚴", "2026-11-26T17:00:00$t", null),
                cal("gc-11", "Swim 2000m", "2026-11-27T17:00:00$t", "2026-11-27T18:00:00$t"),
                cal("gc-12", "Hike Yangmingshan 14km +900m", "2026-11-28T09:00:00$t", "2026-11-28T14:00:00$t"),
            ),
            recorded = listOf(
                rec(9001, "run", "2026-11-23T07:20:00$t", 55.0, kcal = 520.0, hr = 138.0, km = 9.5),
                rec(9002, "strength", "2026-11-24T18:10:00$t", 50.0, rpe = 8),
                rec(9003, "other", "2026-11-24T12:00:00$t", 12.0),
                rec(9004, "walk", "2026-11-24T08:00:00$t", 30.0),
                rec(9005, "run", "2026-11-25T05:30:00$t", 40.0, hr = 160.0, km = 7.0),
                rec(9006, "run", "2026-11-27T06:00:00$t", 30.0),
            ),
            overrides = mapOf("gc-8" to obj("eventId" to "gc-8", "kind" to "ignore"), "gc-9" to obj("eventId" to "gc-9", "kind" to "long_session", "durationMin" to 150.0, "intensity" to "HARD")),
            links = mapOf("9005" to obj("sessionId" to 9005, "planRef" to "planned:12", "manual" to true, "confidence" to 1.0, "planTitle" to "Track 800m repeats")),
        )
        out += scenario("taipei_week_no_history", "Asia/Taipei", "2026-11-23", 7, "2026-11-25T06:00:00$t", history(null, null, null, withRuns = false, own = false),
            events = listOf(ev(1, "Alpine 25", "RACE", "TRAIL_RUN", "A", "2026-11-28", "2026-11-28T07:00:00$t", dur = 150.0, courseEndM = 25400.0)),
            planned = listOf(pl(11, "2026-11-24", "18:00", 55.0, "max_strength", "Squat day"), pl(12, "2026-11-25", "17:00", 70.0, "hypertrophy", "Upper")),
            calendar = listOf(cal("gc-2", "Easy run 12km +200m", "2026-11-26T12:30:00$t", "2026-11-26T13:30:00$t"), cal("gc-3", "Track 6x800m", "2026-11-27T17:00:00$t", "2026-11-27T18:00:00$t")),
            recorded = listOf(rec(9001, "run", "2026-11-23T07:20:00$t", 55.0, hr = 100.0)))
        // Rome around the change to summer time (28 March 2027): the offsets differ either side of it.
        out += scenario("rome_dst", "Europe/Rome", "2027-03-26", 5, "2027-03-27T07:00:00+01:00", history(),
            planned = listOf(pl(21, "2027-03-26", "18:00", 60.0, "aerobic_base", "Fri"), pl(22, "2027-03-27", "10:00", 80.0, "specific_endurance", "Sat"),
                pl(23, "2027-03-28", "09:00", 120.0, "aerobic_base", "Sun long"), pl(24, "2027-03-29", "07:00", 45.0, "speed", "Mon")),
            calendar = listOf(cal("rc-1", "Lungo 22km", "2027-03-28T07:30:00+02:00", "2027-03-28T11:30:00+02:00"), cal("rc-2", "Ripetute 8 giri 400m", "2027-03-30T18:00:00+02:00", "2027-03-30T19:00:00+02:00")),
            recorded = listOf(rec(9101, "run", "2027-03-26T18:10:00+01:00", 58.0, km = 10.0), rec(9102, "run", "2027-03-27T10:30:00+01:00", 75.0, km = 13.0)))
        // Matching: several recordings against several plans of a day, a crowded day (greedy), a hike for a run, an impossible swim.
        out += scenario("matching", "Asia/Taipei", "2026-11-23", 3, "2026-11-26T06:00:00$t", history(),
            planned = listOf(pl(31, day, "07:00", 60.0, "aerobic_base", "Morning run"), pl(32, day, "17:30", 100.0, "specific_endurance", "Long afternoon"),
                pl(33, day, "19:00", 45.0, "speed", "Intervals"), pl(34, "2026-11-25", "08:00", 90.0, "aerobic_base", "Hike plan"),
                pl(35, "2026-11-23", "18:00", 40.0, "max_strength", "Lifts")),
            events = listOf(ev(36, "Trail 19", "RACE", "TRAIL_RUN", "C", "2026-11-25", "2026-11-25T09:00:00$t", dur = 140.0, km = 19.3)),
            recorded = listOf(
                rec(9201, "run", "2026-11-24T08:10:00$t", 58.0, km = 10.0), rec(9202, "run", "2026-11-24T18:00:00$t", 95.0, km = 15.0),
                rec(9203, "swim", "2026-11-24T12:00:00$t", 40.0), rec(9204, "run", "2026-11-24T19:20:00$t", 35.0, hr = 172.0),
                rec(9205, "hike", "2026-11-25T10:00:00$t", 150.0, km = 13.0, up = 700.0), rec(9206, "strength", "2026-11-23T18:30:00$t", 45.0, rpe = 6),
            ))
        val crowdedPlans = (0 until 8).map { i -> pl(40L + i, day, "%02d:00".format(6 + i * 2), 30.0 + 10 * i, if (i % 2 == 0) "aerobic_base" else "speed", "Plan $i") }
        val crowdedRecs = (0 until 8).map { i -> rec(9300L + i, "run", "2026-11-24T%02d:%02d:00$t".format(6 + i * 2, 5 * i), 32.0 + 9 * i) }
        out += scenario("crowded_day", "Asia/Taipei", "2026-11-24", 1, "2026-11-25T06:00:00$t", history(), planned = crowdedPlans, recorded = crowdedRecs)
        // The edges of the rules: 120 and 60 minutes apart, a 5-minute clock, text that reads hard or long, an entry sent in UTC, equal costs.
        out += scenario("boundaries", "Asia/Taipei", "2026-11-23", 5, "2026-11-24T06:00:00$t", history(),
            events = listOf(ev(51, "Edge race", "RACE", "RUN", "A", "2026-11-24", "2026-11-24T07:00:00$t", dur = 100.0)),
            planned = listOf(pl(52, "2026-11-24", "09:00", 60.0, "aerobic_base", "Exactly two hours later"), pl(53, "2026-11-24", "09:01", 60.0, "aerobic_base", "A minute past two hours"),
                pl(54, "2026-11-25", "10:00", 60.0, "aerobic_base", "Calendar neighbour")),
            calendar = listOf(
                cal("b-1", "Exactly an hour later", "2026-11-25T11:00:00$t", "2026-11-25T12:00:00$t"), cal("b-2", "A minute past the hour", "2026-11-25T11:01:00$t", "2026-11-25T12:00:00$t"),
                cal("b-3", "Quick thing", "2026-11-26T12:00:00$t", "2026-11-26T12:05:00$t"), cal("b-4", "Tempo 10km", "2026-11-26T15:00:00$t", "2026-11-26T16:00:00$t"),
                cal("b-5", "Long 20km", "2026-11-26T18:00:00$t", "2026-11-26T19:00:00$t"), cal("b-6", "Night run 8km", "2026-11-26T17:30:00Z", "2026-11-26T18:30:00Z"),
                cal("b-7", "Interval 6x400m", "2026-11-23T05:00:00$t", "2026-11-23T06:00:00$t"), cal("b-8", "Brick 🏋", "2026-11-27T05:00:00$t", "2026-11-27T05:04:00$t"),
            ))
        val twin = { id: Long, h: Int -> pl(id, "2026-11-24", "%02d:00".format(h), 60.0, "aerobic_base", "Twin $id") }
        out += scenario("equal_costs", "Asia/Taipei", "2026-11-24", 1, "2026-11-25T06:00:00$t", history(),
            planned = listOf(twin(61, 8), twin(62, 8), twin(63, 8)),
            recorded = listOf(rec(9401, "run", "2026-11-24T08:00:00$t", 60.0), rec(9402, "run", "2026-11-24T08:00:00$t", 60.0), rec(9403, "run", "2026-11-24T08:00:00$t", 60.0)))
        // Seven plans and seven recordings: still searched in full (8 are paired closest first). Pseudo-random, repeatable.
        var seed = 12345L
        val next = { m: Int -> seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; (seed % m).toInt() }
        for (n in 0 until 6) {
            val plans = (0 until 7).map { i -> pl(70L + i, day, "%02d:%02d".format(5 + next(14), next(60)), 25.0 + next(120), if (next(2) == 0) "aerobic_base" else "speed", "P$i") }
            val recs = (0 until 7).map { i -> rec(9500L + i, if (next(5) == 0) "hike" else "run", "2026-11-24T%02d:%02d:00$t".format(5 + next(14), next(60)), 20.0 + next(130), km = if (next(2) == 0) 4.0 + next(15) else null) }
            out += scenario("seven_$n", "Asia/Taipei", "2026-11-24", 1, "2026-11-25T06:00:00$t", history(), planned = plans, recorded = recs)
        }
        return out
    }

    // ---- reading the JSON into the phone's types ----
    private fun JsonObject.s(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.d(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.l(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.doubles(k: String): Map<String, List<Double>> = this[k]!!.jsonObject.mapValues { e -> e.value.jsonArray.map { it.jsonPrimitive.content.toDouble() } }

    private fun run(s: JsonObject): List<PlannedDay> {
        val zone = ZoneId.of(s.s("zone")!!)
        fun z(iso: String): ZonedDateTime = OffsetDateTime.parse(iso).atZoneSameInstant(zone)
        val h = s["history"]!!.jsonObject
        val history = DemandHistory(
            kcalPerMinByType = h.doubles("kcalPerMinByType"), trimpPerMinByType = h.doubles("trimpPerMinByType"), restingHr = h.d("restingHr"), maxHr = h.d("maxHr"),
            weightKg = h.d("weightKg"), recentRuns = h.arr("recentRuns").map { RecentRun(it.d("distanceKm")!!, it.d("ascentM")!!, it.d("durationMin")!!) },
        )
        val events = s.arr("events").map { e ->
            AthleteEvent(
                id = e.l("id"), name = e.s("name")!!, kind = EventKind.valueOf(e.s("kind")!!), sport = EventSport.valueOf(e.s("sport")!!),
                priority = EventPriority.valueOf(e.s("priority")!!), date = LocalDate.parse(e.s("date")!!), start = e.s("start")?.let { z(it) },
                status = EventStatus.valueOf(e.s("status")!!), targetTimeMin = e.d("plannedDurationMin"), distanceKm = e.d("distanceKm"),
                course = e.d("courseEndM")?.let { listOf(CoursePoint(0.0, null), CoursePoint(it, null)) } ?: emptyList(), calendarEventId = e.s("calendarEventId"),
            )
        }
        val planned = s.arr("planned").map { p ->
            PlannedSessionInput(p.l("id")!!, LocalDate.parse(p.s("date")!!), p.s("startTime")?.let { LocalTime.parse(it) }, p.d("durationMin"), p.s("domain"), p.s("title")!!, p.s("gcalEventId"), p.l("exerciseSessionId"))
        }
        val calendar = s.arr("calendar").map { c -> CalendarSessionInput(c.s("eventId")!!, c.s("title")!!, z(c.s("start")!!), c.s("end")?.let { z(it) }, c.s("colorId"), c.s("description")) }
        val recorded = s.arr("recorded").map { r ->
            RecordedSessionInput(r.l("id")!!, r.s("type")!!, z(r.s("start")!!), r.d("durationMin")!!, r.d("caloriesActive"), r.d("avgHr"), r.s("rpe")?.toInt(), r.d("distanceKm"), r.d("ascentM"))
        }
        val overrides = s["overrides"]!!.jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            CalendarOverride(o.s("eventId")!!, OverrideKind.of(o.s("kind"))!!, o.d("durationMin"), o.s("intensity")?.let { Intensity.valueOf(it) })
        }
        val links = s["links"]!!.jsonObject.entries.associate { (k, v) ->
            val o = v.jsonObject
            k.toLong() to StoredLink(o.l("sessionId")!!, o.s("planRef"), o["manual"]!!.jsonPrimitive.booleanOrNull == true, o.d("confidence"), o.s("planTitle"))
        }
        return resolvePlannedDays(LocalDate.parse(s.s("from")!!), s["days"]!!.jsonPrimitive.content.toInt(), events, planned, calendar, recorded, history, z(s.s("now")!!), overrides, links)
    }

    @Test
    fun theWebBuildsTheSameDays() {
        val inputFile = File(dir, "planned-day-input.json")
        val expectedFile = File(dir, "planned-day-expected.json")
        val scenarios = scenarios()
        val input = JsonArray(scenarios)
        val expected = buildJsonObject {
            scenarios.forEach { s -> put(s.s("name")!!, json.encodeToJsonElement(ListSerializer(PlannedDay.serializer()), run(s))) }
        }
        if (System.getenv("FT_PARITY_WRITE") != null || !expectedFile.exists()) {
            dir.mkdirs()
            inputFile.writeText(json.encodeToString(JsonElement.serializer(), input) + "\n")
            expectedFile.writeText(json.encodeToString(JsonElement.serializer(), expected) + "\n")
            return
        }
        assertEquals(Json.parseToJsonElement(expectedFile.readText()), expected)
        assertEquals(Json.parseToJsonElement(inputFile.readText()), input)
    }
}
