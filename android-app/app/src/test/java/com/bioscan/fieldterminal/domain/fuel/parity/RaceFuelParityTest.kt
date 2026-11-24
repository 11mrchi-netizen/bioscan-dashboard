package com.bioscan.fieldterminal.domain.fuel.parity

import com.bioscan.fieldterminal.domain.events.AidStation
import com.bioscan.fieldterminal.domain.fuel.DayFuelPlan
import com.bioscan.fieldterminal.domain.fuel.FuelProductOption
import com.bioscan.fieldterminal.domain.fuel.HourWeather
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.IntakeHistoryResult
import com.bioscan.fieldterminal.domain.fuel.LoggedDay
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.RaceFuelInput
import com.bioscan.fieldterminal.domain.fuel.RaceFuelPlan
import com.bioscan.fieldterminal.domain.fuel.RaceFuelSettings
import com.bioscan.fieldterminal.domain.fuel.SweatTest
import com.bioscan.fieldterminal.domain.fuel.WeatherSeries
import com.bioscan.fieldterminal.domain.fuel.WeatherSource
import com.bioscan.fieldterminal.domain.fuel.WeekEnergy
import com.bioscan.fieldterminal.domain.fuel.checkIntakeHistory
import com.bioscan.fieldterminal.domain.fuel.climatology
import com.bioscan.fieldterminal.domain.fuel.planFuelDays
import com.bioscan.fieldterminal.domain.fuel.planRaceFuel
import com.bioscan.fieldterminal.domain.fuel.sweatProfile
import com.bioscan.fieldterminal.domain.fuel.withCatchUp
import com.bioscan.fieldterminal.domain.fuel.FuelBlock
import com.bioscan.fieldterminal.domain.fuel.expandSchedule
import com.bioscan.fieldterminal.domain.fuel.scheduleTotals
import com.bioscan.fieldterminal.domain.fuel.scheduleWarnings
import com.bioscan.fieldterminal.domain.fuel.seedBlocks
import com.bioscan.fieldterminal.domain.fuel.EventSnapshot
import com.bioscan.fieldterminal.domain.fuel.describeChanges
import com.bioscan.fieldterminal.domain.fuel.snapshotOf
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import java.time.ZoneId
import java.time.ZonedDateTime

// Parity with the web's race and long-session fuel plan (supabase/functions/_shared/fuel-core/raceFuel.ts, heat.ts, sweat.ts, course.ts): scenarios
// (fixtures/race-input.json) with courses, aid stations, weather of every band, sweat tests, products, settings and the days before are planned by
// planRaceFuel; the plans, every sentence included, are recorded (fixtures/race-expected.json). Also the TYPICAL weather (climatology) and the days
// before (checkIntakeHistory). After a deliberate change: FT_PARITY_WRITE=1 ./gradlew :app:testDebugUnitTest --tests '*RaceFuelParityTest*'
class RaceFuelParityTest {
    private val dir = File("../../supabase/functions/_shared/fuel-core/fixtures")
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val zone = ZoneId.of("Asia/Taipei")
    private var seed = 20261124L
    private fun next(m: Int): Int { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return (seed % m).toInt() }
    private fun pick(vararg v: Double) = v[next(v.size)]

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

    private val start = ZonedDateTime.of(2026, 12, 5, 6, 30, 0, 0, zone)

    private fun hours(startAt: ZonedDateTime, n: Int, temp: (Int) -> Double, humidity: Double?, solar: Double?, typical: Boolean = false) = (0 until n).map { i ->
        val t = startAt.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(i.toLong())
        mapOf("time" to t.toString(), "tempC" to temp(i), "humidityPct" to humidity?.let { it - i }, "dewPointC" to humidity?.let { temp(i) - 5 + 0.5 * i }, "apparentC" to null,
            "solarWm2" to solar?.let { it + 40 * i }, "windKmh" to 8.0, "precipMm" to 0.0, "tempP90C" to if (typical) temp(i) + 3.5 else null)
    }

    private fun course(km: Int, climb: Boolean, gaps: Boolean): List<Map<String, Any?>> = (0..km).map { k ->
        val el = if (!climb) 100.0 else 100 + 900 * Math.sin(k * Math.PI / km * 2.0).coerceAtLeast(-0.3) + (k % 7) * 15
        mapOf("distanceM" to k * 1000.0, "elevationM" to if (gaps && k % 9 == 4) null else el)
    }

    private fun scenarios(): List<JsonObject> {
        val gel = mapOf("id" to 1L, "name" to "Gel A", "kind" to "gel", "carbsG" to 22.0, "fluidMl" to null, "sodiumMg" to 60.0, "caffeineMg" to null, "fructoseG" to 8.0)
        val chew = mapOf("id" to 2L, "name" to "Chews", "kind" to "chew", "carbsG" to 36.0, "fluidMl" to null, "sodiumMg" to 90.0, "caffeineMg" to null, "fructoseG" to null)
        val mix = mapOf("id" to 3L, "name" to "Mix 500", "kind" to "drink_mix", "carbsG" to 40.0, "fluidMl" to 500.0, "sodiumMg" to 300.0, "caffeineMg" to null, "fructoseG" to 15.0)
        val tests = listOf(
            mapOf("date" to "2026-10-04", "sessionType" to "run", "intensity" to "HARD", "durationMin" to 75.0, "weightBeforeKg" to 73.4, "weightAfterKg" to 72.6, "fluidInMl" to 500.0, "urineMl" to 0.0, "tempC" to 24.0, "humidityPct" to 70.0),
            mapOf("date" to "2026-10-18", "sessionType" to "run", "intensity" to "MODERATE", "durationMin" to 110.0, "weightBeforeKg" to 73.0, "weightAfterKg" to 71.9, "fluidInMl" to 800.0, "urineMl" to 100.0, "tempC" to null, "humidityPct" to null),
            mapOf("date" to "2026-11-01", "sessionType" to "ride", "intensity" to "EASY", "durationMin" to 90.0, "weightBeforeKg" to 73.2, "weightAfterKg" to 72.9, "fluidInMl" to 600.0, "urineMl" to 0.0, "tempC" to 20.0, "humidityPct" to null),
            mapOf("date" to "2026-11-09", "sessionType" to "run", "intensity" to "RACE", "durationMin" to 20.0, "weightBeforeKg" to 73.0, "weightAfterKg" to 72.0, "fluidInMl" to 0.0, "urineMl" to 0.0, "tempC" to 30.0, "humidityPct" to 80.0),
        )
        val stations = listOf(
            mapOf("name" to "CP1", "km" to 9.0, "water" to true, "food" to false, "dropBag" to false, "cutoffMin" to 120.0),
            mapOf("name" to "CP2", "km" to 21.0, "water" to true, "food" to true, "dropBag" to true, "cutoffMin" to 260.0),
            mapOf("name" to "CP3", "km" to 33.0, "water" to false, "food" to false, "dropBag" to false, "cutoffMin" to null),
            mapOf("name" to "CP4", "km" to 41.0, "water" to true, "food" to true, "dropBag" to false, "cutoffMin" to 330.0),
            mapOf("name" to "Beyond", "km" to 90.0, "water" to true, "food" to false, "dropBag" to false, "cutoffMin" to null),
        )
        val out = mutableListOf<JsonObject>()
        fun add(name: String, intensity: String, type: String, duration: Double, weather: Map<String, Any?>, course: List<Map<String, Any?>>, km: Double?, aid: List<Map<String, Any?>>,
                products: List<Map<String, Any?>>, testCount: Int, settings: Map<String, Any?>, history: Map<String, Any?>?, loading: Boolean, spread: Double = 0.05, weight: Double = 73.5, testList: List<Map<String, Any?>>? = null) {
            out += buildJsonObject {
                put("name", name); put("intensity", intensity); put("type", type); put("durationMin", duration)
                put("durationLowMin", duration * (1 - spread)); put("durationHighMin", duration * (1 + spread)); put("durationBasis", "your target time")
                put("weather", prim(weather)); put("course", prim(course)); put("distanceKm", km?.let { JsonPrimitive(it) } ?: JsonNull); put("aidStations", prim(aid))
                put("products", prim(products)); put("sweatTests", prim(testList ?: tests.take(testCount))); put("settings", prim(settings)); put("history", prim(history)); put("loadingDays", loading)
                put("weightKg", weight); put("start", start.toString()); put("title", name)
            }
        }
        val defaults = mapOf("toleratedGPerHour" to 60.0, "caffeineOk" to false, "caffeineMgPerKg" to 3.0, "saltySweater" to false)
        fun wx(source: String, n: Int, temp: (Int) -> Double, h: Double?, solar: Double?, note: String? = null) = mapOf("source" to source, "hours" to hours(start, n, temp, h, solar, source == "TYPICAL"), "note" to note)
        val mild = wx("FORECAST", 14, { 15.0 + it * 0.4 }, 60.0, 200.0)
        val hot = wx("FORECAST", 14, { 28.0 + it * 0.6 }, 75.0, 700.0)
        val cold = wx("FORECAST", 14, { 2.0 + it * 0.3 }, 50.0, null)
        val typical = wx("TYPICAL", 14, { 22.0 + it * 0.5 }, 70.0, 450.0, "Typical weather for these dates over the last 10 years, not a forecast.")
        val none = mapOf("source" to "NONE", "hours" to emptyList<Any>(), "note" to null)
        val flatCourse = course(25, false, false)
        val mountain = course(50, true, true)
        val ultra = course(45, true, false)

        add("short_hard_mild", "HARD", "run", 50.0, mild, emptyList(), 10.0, emptyList(), emptyList(), 0, defaults, null, false)
        add("hour_race_gel", "RACE", "run", 70.0, hot, flatCourse.take(13), 12.0, emptyList(), listOf(gel), 1, defaults, null, false)
        add("half_race_mix", "RACE", "run", 105.0, mild, flatCourse, 21.1, stations.take(2), listOf(gel, mix), 3, defaults + mapOf("caffeineOk" to true), null, true)
        add("long_run_nothing", "LONG", "run", 150.0, none, emptyList(), null, emptyList(), emptyList(), 0, defaults, null, false, 0.1)
        add("long_run_typical", "LONG", "run", 200.0, typical, flatCourse, 25.0, stations.take(1), listOf(gel, chew), 2, defaults, null, false, 0.1)
        add("trail_50_hot", "RACE", "run", 420.0, hot, mountain, 50.0, stations, listOf(gel, chew, mix), 4, defaults + mapOf("toleratedGPerHour" to 90.0, "caffeineOk" to true, "saltySweater" to true), null, true, 0.1)
        add("trail_50_tolerance", "RACE", "run", 420.0, cold, mountain, 50.0, stations, listOf(chew), 0, defaults + mapOf("toleratedGPerHour" to 45.0), null, false, 0.12)
        add("ultra_cutoffs_tight", "RACE", "run", 330.0, mild, ultra, 45.0, stations.map { s -> if (s["cutoffMin"] != null) s + mapOf("cutoffMin" to (s["cutoffMin"] as Double) * 0.7) else s }, listOf(gel, mix), 3, defaults, null, false, 0.2)
        add("ride_long_easy", "EASY", "ride", 240.0, typical, emptyList(), 90.0, emptyList(), listOf(mix, chew), 1, defaults, null, false)
        add("hike_moderate", "MODERATE", "hike", 300.0, mild, ultra, 20.0, stations.take(3), listOf(gel), 0, defaults + mapOf("caffeineOk" to true, "caffeineMgPerKg" to 2.5), null, false)
        add("caffeine_short_race", "RACE", "run", 80.0, hot, emptyList(), 15.0, emptyList(), listOf(gel), 2, defaults + mapOf("caffeineOk" to true, "caffeineMgPerKg" to 6.0), null, false, 0.05, 90.0)
        val early = mapOf("days" to emptyList<Any>(), "loadingShortfallG" to 120.0, "catchUpG" to 120.0, "earlyFuelling" to true, "preHydrationBoost" to true, "notes" to listOf("Loading so far is 120 g short of plan."))
        add("history_short_loading", "RACE", "run", 150.0, mild, flatCourse, 21.0, stations.take(1), listOf(gel), 1, defaults, early, true)
        add("history_boost_only", "HARD", "run", 120.0, hot, emptyList(), 18.0, emptyList(), listOf(gel, mix), 0, defaults, early + mapOf("earlyFuelling" to false, "catchUpG" to 0.0), false)
        // The edges: durations of exactly 60 and 90 minutes (caffeine, fluid, aid stations), weather at the band edges, a sun of exactly 600, impossible and edge sweat tests, a course steeper than the model allows.
        for (d in listOf(60.0, 90.0)) add("edge_${d.toInt()}_min", "RACE", "run", d, mild, emptyList(), null, emptyList(), listOf(gel), 1, defaults + mapOf("caffeineOk" to true), null, false)
        for (temp in listOf(4.9, 5.0, 11.9, 12.0, 21.9, 22.0, 26.9, 27.0, 31.9, 32.0)) {
            add("band_${temp}", "HARD", "run", 120.0, mapOf("source" to "FORECAST", "hours" to hours(start, 6, { temp }, null, null), "note" to null), emptyList(), null, emptyList(), listOf(gel), 0, defaults, null, false)
        }
        add("sun_exactly_600", "HARD", "run", 120.0, mapOf("source" to "FORECAST", "hours" to hours(start, 6, { 20.0 }, 50.0, 560.0).map { it + mapOf("solarWm2" to 600.0) }, "note" to null), emptyList(), null, emptyList(), listOf(gel), 0, defaults, null, false)
        val edgeTests = listOf(100.0, 4000.0, 99.0, 4001.0).mapIndexed { i, rate ->
            mapOf("date" to "2026-11-0${i + 1}", "sessionType" to "run", "intensity" to "MODERATE", "durationMin" to 60.0, "weightBeforeKg" to 70.0, "weightAfterKg" to 70.0 - rate / 1000.0, "fluidInMl" to 0.0, "urineMl" to 0.0, "tempC" to 20.0, "humidityPct" to null)
        }
        for (n in 1..4) add("sweat_edge_$n", "MODERATE", "run", 100.0, mild, emptyList(), null, emptyList(), listOf(gel), 0, defaults, null, false, testList = edgeTests.take(n))
        // 1 km at a 60 % grade up, 3 km flat, 1 km at 60 % down: steeper than the model allows either way.
        val steep = (0..50).map { k -> mapOf("distanceM" to k * 100.0, "elevationM" to if (k <= 10) 100.0 + 60 * k else if (k <= 40) 700.0 else 700.0 - 60 * (k - 40)) }
        add("steep_course", "RACE", "run", 200.0, mild, steep, 5.0, stations.take(1).map { it + mapOf("km" to 2.0) }, listOf(gel, mix), 0, defaults, null, false)
        add("hot_humid_mix_drink", "HARD", "run", 150.0, hot, emptyList(), 20.0, emptyList(), listOf(mix), 2, defaults, null, false)
        for (ml in listOf(380.0, 420.0, 450.0, 480.0)) add("drink_$ml", "HARD", "run", 150.0, hot, emptyList(), 20.0, emptyList(), listOf(mapOf("id" to 5L, "name" to "Mix $ml", "kind" to "drink_mix", "carbsG" to 50.0, "fluidMl" to ml, "sodiumMg" to 300.0, "caffeineMg" to null, "fructoseG" to 20.0)), 2, defaults, null, false)
        add("hot_small_drink", "HARD", "run", 150.0, hot, emptyList(), 20.0, emptyList(), listOf(mapOf("id" to 4L, "name" to "Mix 1000", "kind" to "drink_mix", "carbsG" to 80.0, "fluidMl" to 1000.0, "sodiumMg" to 600.0, "caffeineMg" to null, "fructoseG" to 30.0)), 2, defaults, null, false)
        // A spread of the rest, repeatable.
        for (i in 0 until 14) {
            val d = pick(45.0, 80.0, 95.0, 130.0, 180.0, 260.0, 380.0)
            val w = listOf(mild, hot, cold, typical, none)[next(5)]
            val c = listOf(emptyList(), flatCourse, mountain, ultra)[next(4)]
            add("mix_$i", listOf("EASY", "MODERATE", "HARD", "LONG", "RACE")[next(5)], listOf("run", "ride", "hike", "swim")[next(4)], d, w, c, if (c.isEmpty()) null else pick(10.0, 25.0, 50.0),
                listOf(emptyList(), stations.take(2), stations)[next(3)], listOf(emptyList(), listOf(gel), listOf(chew, mix), listOf(gel, chew, mix))[next(4)], next(5).coerceAtMost(4),
                defaults + mapOf("toleratedGPerHour" to pick(30.0, 45.0, 60.0, 75.0, 90.0, 120.0), "caffeineOk" to (next(2) == 0), "saltySweater" to (next(3) == 0)), if (next(4) == 0) early else null, next(2) == 0, 0.05 + next(4) * 0.05, pick(58.0, 66.5, 73.5, 82.0))
        }
        return out
    }

    private fun JsonObject.s(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.d(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.l(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
    private fun JsonObject.b(k: String) = this[k]!!.jsonPrimitive.booleanOrNull!!
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray.map { it.jsonObject }
    private fun z(s: String) = ZonedDateTime.parse(s)

    private fun hour(o: JsonObject) = HourWeather(z(o.s("time")!!), o.d("tempC")!!, o.d("humidityPct"), o.d("dewPointC"), o.d("apparentC"), o.d("solarWm2"), o.d("windKmh"), o.d("precipMm"), o.d("tempP90C"))
    private fun weather(o: JsonObject) = WeatherSeries(WeatherSource.valueOf(o.s("source")!!), o.arr("hours").map { hour(it) }, o.s("note"))
    private fun product(o: JsonObject) = FuelProductOption(o.l("id"), o.s("name")!!, o.s("kind")!!, o.d("carbsG")!!, o.d("fluidMl"), o.d("sodiumMg"), o.d("caffeineMg"), o.d("fructoseG"))
    private fun history(o: JsonObject?) = o?.let { IntakeHistoryResult(emptyList(), it.d("loadingShortfallG")!!, it.d("catchUpG")!!, it.b("earlyFuelling"), it.b("preHydrationBoost"), it["notes"]!!.jsonArray.map { n -> n.jsonPrimitive.content }) }

    private fun run(s: JsonObject): RaceFuelPlan {
        val kg = s.d("weightKg")!!
        val tests = s.arr("sweatTests").map { t ->
            SweatTest(LocalDate.parse(t.s("date")!!), t.s("sessionType")!!, Intensity.valueOf(t.s("intensity")!!), t.d("durationMin")!!, t.d("weightBeforeKg")!!, t.d("weightAfterKg")!!, t.d("fluidInMl")!!, t.d("urineMl") ?: 0.0, t.d("tempC"), t.d("humidityPct"))
        }
        val st = s["settings"]!!.jsonObject
        val input = RaceFuelInput(
            title = s.s("title")!!, type = s.s("type")!!, intensity = Intensity.valueOf(s.s("intensity")!!), start = z(s.s("start")!!), durationMin = s.d("durationMin")!!,
            durationLowMin = s.d("durationLowMin")!!, durationHighMin = s.d("durationHighMin")!!, durationBasis = s.s("durationBasis")!!,
            course = s.arr("course").map { CoursePoint(it.d("distanceM")!!, it.d("elevationM")) }, distanceKm = s.d("distanceKm"),
            aidStations = s.arr("aidStations").map { AidStation(it.s("name")!!, it.d("km")!!, it.b("water"), it.b("food"), it.b("dropBag"), it.d("cutoffMin")) },
            weather = weather(s["weather"]!!.jsonObject), sweat = sweatProfile(tests, kg), weightKg = kg,
            settings = RaceFuelSettings(st.d("toleratedGPerHour")!!, st.b("caffeineOk"), st.d("caffeineMgPerKg")!!, st.b("saltySweater")),
            products = s.arr("products").map { product(it) }, history = history(s["history"]?.takeIf { it !is JsonNull }?.jsonObject), loadingDays = s.b("loadingDays"),
        )
        return planRaceFuel(input)
    }

    private fun intakeCases(): Pair<JsonObject, JsonElement> {
        val energy = WeekEnergy(2600.0, 150.0, true)
        val monday = LocalDate.of(2026, 12, 1)
        // A week with a B race on the 5th: loading on the 3rd and 4th.
        fun item(d: LocalDate, minutes: Double, intensity: Intensity, title: String, priority: com.bioscan.fieldterminal.domain.events.EventPriority?) = com.bioscan.fieldterminal.domain.fuel.PlannedItem(
            source = if (priority != null) com.bioscan.fieldterminal.domain.fuel.PlanSource.EVENT else com.bioscan.fieldterminal.domain.fuel.PlanSource.PLANNED_SESSION, sourceId = "t:$title", title = title, type = "run",
            start = d.atTime(8, 0).atZone(zone), durationMin = minutes, intensity = intensity, intensityReason = "test", kcal = minutes * 9, eventPriority = priority)
        val days = (0 until 7).map { i ->
            val d = monday.plusDays(i.toLong())
            PlannedDay(d, when (i) {
                1 -> listOf(item(d, 60.0, Intensity.MODERATE, "Steady", null))
                4 -> listOf(item(d, 150.0, Intensity.RACE, "B race", com.bioscan.fieldterminal.domain.events.EventPriority.B))
                else -> emptyList()
            })
        }
        val plans = planFuelDays(days, energy, 73.5)
        val cases = listOf(
            Triple("short_and_soon", listOf(LoggedDay(monday.plusDays(2), 4, 380.0, 2500.0), LoggedDay(monday.plusDays(3), 3, 410.0, 2700.0), LoggedDay(monday, 3, 200.0, 2000.0)), Triple(true, 72.0 as Double?, 73.5 as Double?)),
            Triple("on_plan_not_soon", listOf(LoggedDay(monday.plusDays(2), 4, plans[2].carbG, null), LoggedDay(monday.plusDays(3), 4, plans[3].carbG * 1.05, null)), Triple(false, null, null)),
            Triple("not_logged", listOf(LoggedDay(monday.plusDays(2), 2, 100.0, null)), Triple(true, 73.5 as Double?, 73.5 as Double?)),
            Triple("over", listOf(LoggedDay(monday.plusDays(1), 5, plans[1].carbG * 1.5, 3500.0), LoggedDay(monday.plusDays(2), 3, plans[2].carbG * 1.2, null)), Triple(true, 74.0 as Double?, 73.0 as Double?)),
        )
        val out = buildJsonObject {
            for ((name, logged, extra) in cases) {
                val (soon, morning, usual) = extra
                val r = checkIntakeHistory(plans.take(5), logged, 73.5, soon, morning, usual)
                put(name, buildJsonObject {
                    put("result", json.encodeToJsonElement(IntakeHistoryResult.serializer(), r))
                    put("withCatchUp", json.encodeToJsonElement(DayFuelPlan.serializer(), withCatchUp(plans[4], r.catchUpG)))
                })
            }
        }
        val input = buildJsonObject {
            put("energy", prim(mapOf("dailyKcal" to 2600.0, "proteinG" to 150.0, "fromGoal" to true, "deficit" to false, "observedMaintenance" to false)))
            put("weightKg", 73.5)
            put("days", json.encodeToJsonElement(ListSerializer(PlannedDay.serializer()), days))
            put("cases", JsonArray(cases.map { (name, logged, extra) ->
                prim(mapOf("name" to name, "logged" to logged.map { mapOf("date" to it.date.toString(), "mealsLogged" to it.mealsLogged, "carbG" to it.carbG, "kcal" to it.kcal) },
                    "raceSoon" to extra.first, "morningWeightKg" to extra.second, "usualWeightKg" to extra.third))
            }))
        }
        return input to out
    }

    private fun climatologyCase(): Pair<JsonElement, JsonElement> {
        val samples = (1..10).flatMap { y -> (0 until 3 * 24).map { h ->
            val t = ZonedDateTime.of(2026 - y, 12, 2, 0, 0, 0, 0, zone).plusHours(h.toLong())
            mapOf("time" to t.toString(), "tempC" to 14.0 + 8 * Math.sin((h % 24 - 6) * Math.PI / 12) + (y % 4) * 1.3 + next(30) / 10.0, "humidityPct" to if (next(9) == 0) null else 55.0 + next(40),
                "dewPointC" to 10.0 + next(60) / 10.0, "apparentC" to null, "solarWm2" to if (next(5) == 0) null else next(800).toDouble(), "windKmh" to next(30).toDouble(), "precipMm" to next(4).toDouble(), "tempP90C" to null)
        } }
        val targets = (0 until 14).map { start.plusHours(it.toLong()).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString() } + listOf(start.plusHours(20).toString())
        val series = climatology(samples.map { hour(prim(it).jsonObject) }, targets.map { z(it) }, 10)
        val input = prim(mapOf("samples" to samples, "targets" to targets, "years" to 10))
        val expected = buildJsonObject {
            put("source", series.source.name); put("note", series.note?.let { JsonPrimitive(it) } ?: JsonNull)
            put("hours", prim(series.hours.map { h -> mapOf("time" to h.time.toString(), "tempC" to h.tempC, "humidityPct" to h.humidityPct, "dewPointC" to h.dewPointC, "apparentC" to h.apparentC, "solarWm2" to h.solarWm2, "windKmh" to h.windKmh, "precipMm" to h.precipMm, "tempP90C" to h.tempP90C) }))
        }
        return input to expected
    }

    private fun heatGrid(): Pair<JsonElement, JsonElement> {
        val temps = (0..24).map { 4.0 + it * 1.6 } + listOf(80.0, 87.0, 112.0, 95.0).map { (it - 32) * 5 / 9 }
        val hums = listOf(0.0, 5.0, 12.0, 13.0, 20.0, 35.0, 50.0, 65.0, 80.0, 85.0, 86.0, 90.0, 100.0)
        val cells = temps.flatMap { t -> hums.map { h -> t to h } } + temps.map { it to null }
        val input = prim(cells.map { (t, h) -> mapOf("tempC" to t, "humidityPct" to h) })
        val expected = prim(cells.map { (t, h) -> mapOf("index" to (h?.let { com.bioscan.fieldterminal.domain.fuel.heatIndexC(t, it) } ?: t), "band" to com.bioscan.fieldterminal.domain.fuel.heatBand(t, h).name) })
        return input to expected
    }

    private fun estimateCases(): Pair<JsonElement, JsonElement> {
        val runs = listOf(Triple(10.0, 150.0, 62.0), Triple(12.5, 300.0, 85.0), Triple(8.0, 40.0, 46.0), Triple(21.0, 520.0, 158.0), Triple(15.0, 200.0, 96.0), Triple(5.0, 20.0, 28.0), Triple(2.0, 0.0, 12.0), Triple(9.0, 10.0, 25.0))
        val recent = runs.map { com.bioscan.fieldterminal.domain.fuel.RecentRun(it.first, it.second, it.third) }
        val asks = listOf(5.0 to 0.0, 10.0 to 100.0, 21.1 to 600.0, 42.0 to 1500.0, 50.6 to 1400.0, 3.0 to 0.0)
        val cases = asks.flatMap { (km, up) -> listOf(recent, recent.take(4), recent.drop(2)).map { rs -> Triple(km, up, rs) } }
        val input = prim(cases.map { (km, up, rs) -> mapOf("km" to km, "ascentM" to up, "runs" to rs.map { mapOf("distanceKm" to it.distanceKm, "ascentM" to it.ascentM, "durationMin" to it.durationMin) }) })
        val expected = prim(cases.map { (km, up, rs) ->
            val e = com.bioscan.fieldterminal.domain.fuel.estimateRunTime(km, up, rs)
            val r1 = com.bioscan.fieldterminal.domain.fuel.durationRange(null, null, e)
            val r2 = com.bioscan.fieldterminal.domain.fuel.durationRange(210.0, 180.0, e)
            val r3 = com.bioscan.fieldterminal.domain.fuel.durationRange(null, 540.0, e)
            fun r(t: Triple<Double, Pair<Double, Double>, String>?) = t?.let { mapOf("base" to it.first, "low" to it.second.first, "high" to it.second.second, "basis" to it.third) }
            mapOf("estimate" to e?.let { mapOf("minutes" to it.minutes, "lowMinutes" to it.lowMinutes, "highMinutes" to it.highMinutes, "basis" to it.basis) }, "history" to r(r1), "target" to r(r2), "expected" to r(r3))
        })
        return input to expected
    }

    // The schedule: the planner's plan as blocks, what they expand into, add up to and warn about (several tolerances, and a few hand-made blocks at the edges).
    private fun scheduleJson(blocks: List<FuelBlock>, duration: Int): JsonElement {
        fun totals(durationMin: Int) = scheduleTotals(blocks, durationMin).let { t ->
            mapOf("carbsG" to t.carbsG, "fluidMl" to t.fluidMl, "sodiumMg" to t.sodiumMg, "caffeineMg" to t.caffeineMg, "intakes" to t.intakes, "preStartCarbsG" to t.preStartCarbsG,
                "carbsPerHour" to t.carbsPerHour, "fluidPerHour" to t.fluidPerHour, "sodiumPerHour" to t.sodiumPerHour)
        }
        return buildJsonObject {
            put("blocks", json.encodeToJsonElement(ListSerializer(FuelBlock.serializer()), blocks))
            put("expanded", prim(expandSchedule(blocks).map { mapOf("blockId" to it.blockId, "index" to it.index, "elapsedMin" to it.elapsedMin) }))
            put("totals", prim(totals(duration)))
            put("totalsShort", prim(totals(1)))
            put("totalsZero", prim(totals(0)))
            put("warnings", prim(listOf(60.0, 45.0, 30.0, 90.0).map { tol -> scheduleWarnings(blocks, duration, tol) }))
            put("warningsShort", prim(scheduleWarnings(blocks, 20, 60.0)))
        }
    }

    private fun schedules(plans: List<Pair<String, RaceFuelPlan>>): JsonObject {
        val hand = listOf(
            FuelBlock("a", "Gel", "gel", 30, 30, 6, 25.0, 0.0, 50.0, 0.0, 1L, null), FuelBlock("b", "Pre-workout", "preworkout", -30, 0, 1, 0.0, 0.0, 0.0, 200.0, null, 7L),
            FuelBlock("c", "Mix", "drink_mix", 0, 20, 12, 40.0, 500.0, 300.0, 0.0, 3L, null), FuelBlock("d", "Tab", "tablet", 60, 0, 3, 0.0, 0.0, 0.0, 150.0, null, null),
            FuelBlock("e", "Bar", "bar", 45, 45, 4, 30.0, 0.0, 0.0, 0.0, null, null), FuelBlock("f", "Same time as a", "gel", 30, 60, 2, 10.0, 0.0, 0.0, 0.0, null, null),
        )
        return buildJsonObject {
            put("_hand", scheduleJson(hand, 240))
            put("_hand_short", scheduleJson(hand.take(2), 90))
            plans.forEach { (name, plan) -> put(name, scheduleJson(seedBlocks(plan), plan.durationMin.toInt())) }
        }
    }

    @Test
    fun theWebPlansTheSameRace() {
        val inputFile = File(dir, "race-input.json")
        val expectedFile = File(dir, "race-expected.json")
        val scenarios = scenarios()
        val (intakeIn, intakeOut) = intakeCases()
        val (climIn, climOut) = climatologyCase()
        val (heatIn, heatOut) = heatGrid()
        val (estIn, estOut) = estimateCases()
        val input = buildJsonObject { put("scenarios", JsonArray(scenarios)); put("intake", intakeIn); put("climatology", climIn); put("heat", heatIn); put("estimates", estIn) }
        val plans = scenarios.map { run(it) }
        val snaps = plans.map { snapshotOf(it) }
        val snapshots = buildJsonObject {
            scenarios.forEachIndexed { i, sc ->
                val obj = json.encodeToJsonElement(EventSnapshot.serializer(), snaps[i]).jsonObject
                put(sc.s("name")!!, buildJsonObject { put("snapshot", obj); put("text", obj.toString()); put("hash", obj.toString().hashCode().toString(16)) })
            }
            put("_changes", JsonArray(snaps.zipWithNext { a, b -> JsonArray(describeChanges(a, b).map { JsonPrimitive(it) }) }))
        }
        val expected = buildJsonObject {
            put("plans", buildJsonObject { scenarios.forEachIndexed { i, sc -> put(sc.s("name")!!, json.encodeToJsonElement(RaceFuelPlan.serializer(), plans[i])) } })
            put("intake", intakeOut); put("climatology", climOut); put("heat", heatOut); put("estimates", estOut)
            put("schedules", schedules(scenarios.indices.map { scenarios[it].s("name")!! to plans[it] }))
        }
        if (System.getenv("FT_PARITY_WRITE") != null || !expectedFile.exists()) {
            dir.mkdirs()
            inputFile.writeText(json.encodeToString(JsonElement.serializer(), input) + "\n")
            expectedFile.writeText(json.encodeToString(JsonElement.serializer(), expected) + "\n")
            File(dir, "snapshots-event.json").writeText(json.encodeToString(JsonElement.serializer(), snapshots) + "\n")
            return
        }
        assertEquals(Json.parseToJsonElement(File(dir, "snapshots-event.json").readText()), snapshots)
        assertEquals(Json.parseToJsonElement(expectedFile.readText()), expected)
        assertEquals(Json.parseToJsonElement(inputFile.readText()), input)
    }
}
