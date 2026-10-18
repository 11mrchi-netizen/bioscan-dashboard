package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.events.AidStation
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// A fuel and hydration plan for a race or a long session (docs/fuel-planning/README.md, phase 5): what to take and when,
// by time and by aid station, what to carry between stations, what to do before and after. Built from the planned
// duration spread along the course (CourseTimeEstimate.kt), the weather at each hour and height (HeatStress.kt), the
// person's sweat rate (SweatProfile.kt), their tolerated carbohydrate rate and products, and the days before
// (IntakeHistory.kt).
//
//   carbohydrate  g/h by duration (Jeukendrup 2014; races at the top of the range), capped at the tolerated rate, the
//                 first hour at 75 %, from the 20th minute (the 15th when loading fell short); above 60 g/h products
//                 need glucose and fructose together;
//   fluid         per step from the sweat rate at that hour's temperature and altitude: enough to keep the loss within
//                 2 % of body weight, never more than the sweat (Sawka 2007; over-drinking causes hyponatraemia);
//   sodium        mg per litre drunk, 500, 700 in heat, 1,000 for a salty sweater (ISSN ultra-marathon stand 2019);
//   caffeine      opt-in, 3 mg/kg (400 mg at most): before the start for races up to 90 minutes, otherwise a third at
//                 40 % of the way and the rest at 70 %, when it helps most (ISSN 2019);
//   before        loading days, the evening meal, breakfast 1-4 g/kg 3 hours out, 5-7 ml/kg of fluid 4 hours out
//                 (more after a low morning weight or in heat), pre-cooling in heat (McCubbin 2020);
//   after         1-1.2 g/kg/h of carbohydrate for 4 hours with 0.3 g/kg protein, fluid 1.25-1.5 times what was lost,
//                 and a weigh-in, which makes the race a sweat test.
//
// Every number carries the assumption it rests on; a TYPICAL rather than FORECAST climate and an estimated rather than
// tested sweat rate are said so.

data class FuelProductOption(
    val id: Long?,
    val name: String,
    val kind: String,
    val carbsG: Double,
    val fluidMl: Double? = null,
    val sodiumMg: Double? = null,
    val caffeineMg: Double? = null,
    val fructoseG: Double? = null,
)

/** Used when the person has no products in their catalog: a typical 25 g gel, labelled generic. */
val GENERIC_GEL = FuelProductOption(null, "gel (25 g, generic)", "gel", 25.0, sodiumMg = 50.0)

data class RaceFuelSettings(
    val toleratedGPerHour: Double = DEFAULT_TOLERATED_G_PER_H,
    val caffeineOk: Boolean = false,
    val caffeineMgPerKg: Double = 3.0,
    val saltySweater: Boolean = false,
)

data class RaceFuelInput(
    val title: String,
    val type: String,
    val intensity: Intensity,
    val start: ZonedDateTime,
    val durationMin: Double,
    /** How sure the duration is: the target time ±5 %, an expected time ±10 %, or a history estimate's range. */
    val durationLowMin: Double,
    val durationHighMin: Double,
    val durationBasis: String,
    val course: List<CoursePoint> = emptyList(),
    val distanceKm: Double? = null,
    val aidStations: List<AidStation> = emptyList(),
    val weather: WeatherSeries = WeatherSeries.NONE,
    val sweat: SweatProfile,
    val weightKg: Double,
    val settings: RaceFuelSettings = RaceFuelSettings(),
    val products: List<FuelProductOption> = emptyList(),
    val history: IntakeHistoryResult? = null,
    /** True when the two days before are carbohydrate loading days in the day plan. */
    val loadingDays: Boolean = false,
)

data class FuelStep(
    val elapsedMin: Double,
    val km: Double?,
    val tempC: Double?,
    val carbsG: Double,
    val fluidMl: Double,
    val sodiumMg: Double,
    val caffeineMg: Double,
    val items: List<String>,
    val aidStation: AidStation? = null,
)

data class CarryLeg(
    val from: String,
    val to: String,
    val minutes: Double,
    val carbsG: Double,
    val fluidMl: Double,
    val items: Map<String, Int>,
    val bottlesMl: Int,
    val note: String? = null,
)

data class PrepItem(val minutesFromStart: Double, val whenLabel: String, val text: String)

data class RaceFuelPlan(
    val title: String,
    val durationMin: Double,
    val durationLowMin: Double,
    val durationHighMin: Double,
    val carbsPerHourLow: Double,
    val carbsPerHourHigh: Double,
    val fluidMlPerHourLow: Double,
    val fluidMlPerHourHigh: Double,
    val sodiumMgPerL: Double,
    val sweat: SweatEstimate,
    val weatherSource: WeatherSource,
    val heat: HeatSummary?,
    val steps: List<FuelStep>,
    val legs: List<CarryLeg>,
    val before: List<PrepItem>,
    val after: List<PrepItem>,
    val totalCarbsG: Double,
    val totalFluidMl: Double,
    val totalSodiumMg: Double,
    val totalCaffeineMg: Double,
    /** Expected body-mass loss at the end, % (sweat minus what the plan drinks). */
    val expectedLossPct: Double,
    val assumptions: List<String>,
    val warnings: List<String>,
)

const val STEP_MIN = 20.0
const val CAFFEINE_CAP_MG = 400.0

private fun fmtMin(m: Double): String {
    val t = m.roundToInt()
    return if (t >= 60) "%d:%02d".format(t / 60, t % 60) else "$t min"
}

fun planRaceFuel(input: RaceFuelInput): RaceFuelPlan {
    val kg = input.weightKg
    val duration = input.durationMin
    val times = timeAlongCourse(input.course, duration)
    val totalKm = input.distanceKm ?: input.course.lastOrNull()?.distanceM?.div(1000)
    val startElevation = input.course.firstOrNull()?.elevationM
    val assumptions = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    // Carbohydrate rate.
    val sf = sessionFuel(duration, input.intensity, input.settings.toleratedGPerHour)
    val raceLike = input.intensity == Intensity.RACE || input.intensity == Intensity.HARD
    val gph = if (raceLike) sf.gPerHourHigh else (sf.gPerHourLow + sf.gPerHourHigh) / 2
    val early = input.history?.earlyFuelling == true
    val firstIntake = if (early) 15.0 else 20.0

    // Weather along the way.
    fun weatherAt(elapsed: Double): Pair<Double?, Double?> {
        val h = input.weather.at(input.start.plusSeconds((elapsed * 60).toLong())) ?: return null to null
        val km = kmAtElapsed(input.course, times, elapsed, duration, totalKm)
        val climb = if (km != null && startElevation != null) (elevationAtKm(input.course, km) ?: startElevation) - startElevation else 0.0
        return tempAtElevation(h.tempC, climb) to h.humidityPct
    }
    val raceHours = input.weather.hours.filter { !it.time.isBefore(input.start.minusMinutes(30)) && !it.time.isAfter(input.start.plusMinutes(duration.toLong() + 30)) }
    val heat = summariseHeat(raceHours, input.weather.source)
    val band = heat?.band ?: HeatBand.MILD
    val meanTemp = raceHours.map { it.tempC }.takeIf { it.isNotEmpty() }?.average() ?: DEFAULT_TEST_TEMP_C
    val sweatMean = predictSweatRate(input.sweat, input.intensity, input.type, meanTemp, kg)
    val fluidMean = fluidPlan(sweatMean, duration, kg, band, input.settings.saltySweater)

    // Caffeine doses (elapsed minutes), opt-in.
    val caffeineTotal = if (input.settings.caffeineOk && duration >= 60) min(CAFFEINE_CAP_MG, input.settings.caffeineMgPerKg * kg) else 0.0
    val inRaceCaffeine = if (caffeineTotal > 0 && duration > 90) listOf(0.4 * duration to caffeineTotal / 3, 0.7 * duration to caffeineTotal * 2 / 3) else emptyList()

    // Steps: every 20 minutes from the first intake, plus each aid station.
    val stationTimes = input.aidStations.sortedBy { it.km }.map { it to elapsedAtKm(input.course, times, it.km, duration, totalKm) }
        .filter { it.second < duration }
    val stepTimes = generateSequence(firstIntake) { it + STEP_MIN }.takeWhile { it <= duration - 10 }.toMutableList()
    val products = input.products.filter { it.carbsG > 0 }.ifEmpty { listOf(GENERIC_GEL) }
    if (input.products.none { it.carbsG > 0 }) assumptions += "No products in your fuel catalog: shown as generic 25 g gels. Add yours under + › RUN FUEL."
    val solid = products.filter { it.kind != "drink_mix" }.maxByOrNull { it.carbsG } ?: products.first()
    val drink = products.firstOrNull { it.kind == "drink_mix" && (it.fluidMl ?: 0.0) > 0 }

    var carbDebt = 0.0
    val steps = stepTimes.mapIndexed { i, t ->
        val span = if (i == 0) t - 0.0 else t - stepTimes[i - 1]
        val ramp = if (t <= 60) 0.75 else 1.0
        val need = gph * ramp * span / 60 + carbDebt
        val (temp, humidity) = weatherAt(t)
        val stepBand = temp?.let { heatBand(it, humidity) } ?: band
        val stepSweat = predictSweatRate(input.sweat, input.intensity, input.type, temp ?: meanTemp, kg)
        val stepFluid = fluidPlan(stepSweat, duration, kg, stepBand, input.settings.saltySweater)
        val fluid = (stepFluid.mlPerHourLow + stepFluid.mlPerHourHigh) / 2 * span / 60
        val items = mutableListOf<String>()
        var carbs = 0.0
        var sodium = fluid / 1000 * stepFluid.sodiumMgPerL
        // A drink mix covers the fluid first (and brings its carbohydrate), solid products the rest.
        if (drink != null && fluid >= drink.fluidMl!! * 0.6) {
            val servings = max(1, (fluid / drink.fluidMl).roundToInt())
            carbs += servings * drink.carbsG
            items += "$servings × ${drink.name}"
        }
        val remaining = need - carbs
        val n = floor(remaining / solid.carbsG + 0.4).toInt().coerceAtLeast(0)
        if (n > 0) {
            carbs += n * solid.carbsG
            items += "$n × ${solid.name}"
            sodium += n * (solid.sodiumMg ?: 0.0)
        }
        carbDebt = need - carbs
        if (fluid > 0) items += "${(fluid / 10).roundToInt() * 10} ml to drink"
        val caffeine = inRaceCaffeine.filter { (at, _) -> at > t - span && at <= t }.sumOf { it.second }
        if (caffeine > 0) items += "${caffeine.roundToInt()} mg caffeine"
        val station = stationTimes.firstOrNull { kotlin.math.abs(it.second - t) <= STEP_MIN / 2 }?.first
        FuelStep(t, kmAtElapsed(input.course, times, t, duration, totalKm), temp, carbs, fluid, sodium, caffeine, items, station)
    }

    // What to carry between the places you can refill (aid stations with water or food), start to finish.
    val refills = stationTimes.filter { it.first.water || it.first.food }
    val boundaries = listOf("Start" to 0.0) + refills.map { it.first.name to it.second } + listOf("Finish" to duration)
    val legs = boundaries.zipWithNext { (fromName, fromT), (toName, toT) ->
        val inLeg = steps.filter { it.elapsedMin > fromT && it.elapsedMin <= toT }
        val counts = mutableMapOf<String, Int>()
        inLeg.flatMap { it.items }.forEach { s ->
            Regex("""^(\d+) × (.+)$""").matchEntire(s)?.let { m -> counts[m.groupValues[2]] = (counts[m.groupValues[2]] ?: 0) + m.groupValues[1].toInt() }
        }
        val fluid = inLeg.sumOf { it.fluidMl }
        CarryLeg(
            from = fromName, to = toName, minutes = toT - fromT, carbsG = inLeg.sumOf { it.carbsG }, fluidMl = fluid, items = counts,
            bottlesMl = (ceil(fluid / 250) * 250).toInt(),
            note = refills.firstOrNull { it.first.name == toName }?.first?.let { s ->
                listOfNotNull("water".takeIf { s.water }, "food".takeIf { s.food }, "drop bag".takeIf { s.dropBag }).joinToString(", ").let { "Refill at $toName: $it." }
            },
        )
    }
    if (input.aidStations.isEmpty() && duration > 90) assumptions += "No aid stations entered: the plan assumes you carry everything. Add them on the event to split it."

    // Cutoffs at the planned pace and at the slow end of the range.
    stationTimes.forEach { (s, t) ->
        val cutoff = s.cutoffMin ?: return@forEach
        val slow = t * input.durationHighMin / duration
        when {
            t > cutoff -> warnings += "At the planned pace you reach ${s.name} at ${fmtMin(t)}, after its ${fmtMin(cutoff)} cutoff."
            slow > cutoff -> warnings += "At the slow end of your range you reach ${s.name} at ${fmtMin(slow)}, after its ${fmtMin(cutoff)} cutoff."
        }
    }

    // The whole session's sweat, each step at its own conditions, the tail after the last step at the mean.
    val totalSweat = stepTimes.indices.sumOf { i ->
        val t = stepTimes[i]
        val span = if (i == 0) t else t - stepTimes[i - 1]
        val (temp, _) = weatherAt(t)
        predictSweatRate(input.sweat, input.intensity, input.type, temp ?: meanTemp, kg).mlPerHour * span / 60
    } + sweatMean.mlPerHour * max(0.0, duration - (stepTimes.lastOrNull() ?: 0.0)) / 60
    val totalFluid = steps.sumOf { it.fluidMl }
    val lossPct = max(0.0, totalSweat - totalFluid) / (kg * 1000) * 100
    if (lossPct > 3) {
        warnings += when {
            // Without a test the sweat estimate is wide and drinking is capped at its low end, so the loss looks large.
            input.sweat.testCount == 0 -> ("With no sweat test yet the plan keeps drinking cautious (never more than you might sweat), so the " +
                "estimated loss reaches about %.1f %% of body weight. Drink to thirst on top of the plan, up to about %d ml an hour, and " +
                "do a sweat test in a long training session.").format(lossPct, (sweatMean.mlPerHour * MAX_REPLACEMENT_SHARE / 50).roundToInt() * 50)
            band == HeatBand.HOT || band == HeatBand.VERY_HOT ->
                "Even drinking to plan you may lose about %.1f %% of body weight: in this heat start slower and pre-cool.".format(lossPct)
            else -> "Even drinking to plan you may lose about %.1f %% of body weight: drink to thirst on top of the plan, never more than you sweat.".format(lossPct)
        }
    }
    if (gph > 60 && products.none { (it.fructoseG ?: 0.0) > 0 }) {
        warnings += "Above 60 g an hour, use products that mix glucose (or maltodextrin) with fructose; check yours."
    }
    if (sf.kind == SessionFuelKind.HIGH && input.settings.toleratedGPerHour < MAX_G_PER_H) {
        assumptions += "Capped at your tolerated ${input.settings.toleratedGPerHour.roundToInt()} g an hour; train the gut in long sessions to raise it."
    }

    // Before and after.
    val before = mutableListOf<PrepItem>()
    if (input.loadingDays) before += PrepItem(-48 * 60.0, "2 DAYS BEFORE", "Carbohydrate loading: 10-12 g/kg a day (${(10 * kg).roundToInt()}-${(12 * kg).roundToInt()} g), as Today's fuel plans it.")
    before += PrepItem(-15 * 60.0, "EVENING BEFORE", "A familiar carbohydrate-rich meal, low in fibre and fat. Nothing new.")
    val breakfastGPerKg = if ((input.history?.catchUpG ?: 0.0) > 0) 3.0 else 2.0
    before += PrepItem(
        -180.0, "3 H BEFORE",
        "Breakfast: about ${(breakfastGPerKg * kg).roundToInt()} g of carbohydrate (${breakfastGPerKg.roundToInt()} g/kg; 1-4 g/kg works), little fat and fibre.",
    )
    val preMlPerKg = 5.0 + (if (input.history?.preHydrationBoost == true || band == HeatBand.HOT || band == HeatBand.VERY_HOT) 2.0 else 0.0)
    before += PrepItem(-240.0, "4 H BEFORE", "Drink ${(preMlPerKg * kg / 50).roundToInt() * 50} ml (${preMlPerKg.roundToInt()} ml/kg) with something salty, then to thirst.")
    if (band == HeatBand.HOT || band == HeatBand.VERY_HOT) before += PrepItem(-30.0, "30 MIN BEFORE", "Pre-cool: an ice slurry or a cold drink (about 7 g/kg), and stay in the shade.")
    before += PrepItem(-15.0, "15 MIN BEFORE", if (early) "150-250 ml of fluid and a first gel: loading fell short, so start topping up early." else "150-250 ml of fluid.")
    if (caffeineTotal > 0 && duration <= 90) before += PrepItem(-50.0, "45-60 MIN BEFORE", "Caffeine: ${caffeineTotal.roundToInt()} mg (${input.settings.caffeineMgPerKg} mg/kg), only if you have used it in training.")
    before.sortBy { it.minutesFromStart }

    val lossMl = max(0.0, totalSweat - totalFluid)
    val after = listOf(
        PrepItem(duration + 15, "FIRST 30 MIN", "Carbohydrate ${(1.0 * kg).roundToInt()}-${(1.2 * kg).roundToInt()} g an hour for 4 hours, with ${(0.3 * kg).roundToInt()} g of protein soon."),
        PrepItem(duration + 30, "NEXT HOURS", "Drink ${(1.25 * lossMl / 50).roundToInt() * 50}-${(1.5 * lossMl / 50).roundToInt() * 50} ml over the next hours (1.25-1.5 × the ~${(lossMl / 50).roundToInt() * 50} ml you are likely to lose), with salty food."),
        PrepItem(duration + 5, "AT THE FINISH", "Weigh yourself before drinking: with your start weight and what you drank, the race becomes a sweat test."),
    ).sortedBy { it.minutesFromStart }

    assumptions += "Duration: ${fmtMin(duration)} (${input.durationBasis}), likely ${fmtMin(input.durationLowMin)}-${fmtMin(input.durationHighMin)}."
    assumptions += when (input.weather.source) {
        WeatherSource.FORECAST -> "Weather: the forecast at the start."
        WeatherSource.TYPICAL -> "Weather: ${input.weather.note ?: "typical for the date"} Ranges are wider until the forecast reaches the race (16 days out)."
        WeatherSource.NONE -> "Weather: unknown (no place or no forecast); a mild 18 °C is assumed."
    }
    assumptions += "Sweat: about ${(sweatMean.mlPerHour / 10).roundToInt() * 10} ml an hour (${(sweatMean.lowMlPerHour / 10).roundToInt() * 10}-${(sweatMean.highMlPerHour / 10).roundToInt() * 10}), from ${sweatMean.basis}."
    input.history?.notes?.let { assumptions += it }

    return RaceFuelPlan(
        title = input.title,
        durationMin = duration,
        durationLowMin = input.durationLowMin,
        durationHighMin = input.durationHighMin,
        carbsPerHourLow = sf.gPerHourLow,
        carbsPerHourHigh = sf.gPerHourHigh,
        fluidMlPerHourLow = fluidMean.mlPerHourLow,
        fluidMlPerHourHigh = fluidMean.mlPerHourHigh,
        sodiumMgPerL = fluidMean.sodiumMgPerL,
        sweat = sweatMean,
        weatherSource = input.weather.source,
        heat = heat,
        steps = steps,
        legs = legs,
        before = before,
        after = after,
        totalCarbsG = steps.sumOf { it.carbsG },
        totalFluidMl = totalFluid,
        totalSodiumMg = steps.sumOf { it.sodiumMg },
        totalCaffeineMg = caffeineTotal,
        expectedLossPct = lossPct,
        assumptions = assumptions,
        warnings = warnings,
    )
}

/** The duration and its range for a plan: a target time ±5 %, an expected time ±10 %, widened by a history estimate. */
fun durationRange(targetMin: Double?, expectedMin: Double?, estimate: TimeEstimate?): Triple<Double, Pair<Double, Double>, String>? {
    val base = targetMin ?: expectedMin ?: estimate?.minutes ?: return null
    val (lo, hi, basis) = when {
        targetMin != null -> Triple(base * 0.95, base * 1.05, "your target time")
        expectedMin != null -> Triple(base * 0.9, base * 1.1, "your expected time")
        else -> Triple(estimate!!.lowMinutes, estimate.highMinutes, estimate.basis)
    }
    return Triple(base, min(lo, base) to max(hi, base), basis)
}
