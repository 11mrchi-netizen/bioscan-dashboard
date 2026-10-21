package com.bioscan.fieldterminal.domain.fuel

import java.time.ZonedDateTime
import kotlin.math.roundToInt

// The weather a fuel and hydration plan is built for (docs/fuel-planning/README.md, phase 4), and how hot it is.
//
// A forecast covers 16 days (Open-Meteo). Further out, the plan uses the TYPICAL weather of those dates: the same hours,
// within 3 days either side, over the last 10 years (Open-Meteo's archive), as the median and the 90th percentile of the
// temperature. It is always labelled TYPICAL, never FORECAST, and it widens the plan's ranges.
//
// Heat is shown as temperature, humidity and dew point plus a heat-index band (the US National Weather Service's
// Rothfusz regression). The simplified WBGT is not used: it greatly overstates heat stress in humid air (Kong & Huber
// 2022). Strong sun is named separately. Along a course the temperature falls 6.5 °C per 1,000 m of climb (the
// standard lapse rate).

enum class WeatherSource(val label: String) { FORECAST("FORECAST"), TYPICAL("TYPICAL"), NONE("NO WEATHER") }

data class HourWeather(
    val time: ZonedDateTime,
    val tempC: Double,
    val humidityPct: Double? = null,
    val dewPointC: Double? = null,
    val apparentC: Double? = null,
    val solarWm2: Double? = null,
    val windKmh: Double? = null,
    val precipMm: Double? = null,
    /** TYPICAL only: the 90th percentile temperature of that hour across the years read. */
    val tempP90C: Double? = null,
)

data class WeatherSeries(val source: WeatherSource, val hours: List<HourWeather>, val note: String? = null) {
    fun at(t: ZonedDateTime): HourWeather? = hours.minByOrNull { kotlin.math.abs(java.time.Duration.between(it.time, t).toMinutes()) }
        ?.takeIf { kotlin.math.abs(java.time.Duration.between(it.time, t).toMinutes()) <= 90 }

    companion object {
        val NONE = WeatherSeries(WeatherSource.NONE, emptyList())
    }
}

const val FORECAST_DAYS = 16L
const val LAPSE_RATE_C_PER_KM = 6.5

/** The air temperature [climbM] metres above (negative: below) where it was measured. */
fun tempAtElevation(tempC: Double, climbM: Double): Double = tempC - LAPSE_RATE_C_PER_KM * climbM / 1000.0

/**
 * The TYPICAL weather for [targetHours], from archive samples of the same dates in past years: per hour of day, the
 * median of each quantity and the 90th percentile of temperature. Hours with no sample are left out.
 */
fun climatology(samples: List<HourWeather>, targetHours: List<ZonedDateTime>, years: Int): WeatherSeries {
    val byHour = samples.groupBy { it.time.hour }
    val hours = targetHours.mapNotNull { t ->
        val s = byHour[t.hour]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val temps = s.map { it.tempC }.sorted()
        HourWeather(
            time = t,
            tempC = median(temps),
            humidityPct = s.mapNotNull { it.humidityPct }.takeIf { it.isNotEmpty() }?.let { median(it.sorted()) },
            dewPointC = s.mapNotNull { it.dewPointC }.takeIf { it.isNotEmpty() }?.let { median(it.sorted()) },
            apparentC = s.mapNotNull { it.apparentC }.takeIf { it.isNotEmpty() }?.let { median(it.sorted()) },
            solarWm2 = s.mapNotNull { it.solarWm2 }.takeIf { it.isNotEmpty() }?.let { median(it.sorted()) },
            windKmh = s.mapNotNull { it.windKmh }.takeIf { it.isNotEmpty() }?.let { median(it.sorted()) },
            precipMm = s.mapNotNull { it.precipMm }.takeIf { it.isNotEmpty() }?.let { median(it.sorted()) },
            tempP90C = percentile(temps, 0.9),
        )
    }
    return WeatherSeries(WeatherSource.TYPICAL, hours, "Typical weather for these dates over the last $years years, not a forecast.")
}

private fun median(sorted: List<Double>): Double =
    if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

private fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.size == 1) return sorted[0]
    val pos = p * (sorted.size - 1)
    val lo = pos.toInt()
    val hi = minOf(lo + 1, sorted.lastIndex)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
}

/** NWS heat index, °C, from air temperature (°C) and relative humidity (%). Equal to the temperature below about 27 °C. */
fun heatIndexC(tempC: Double, humidityPct: Double): Double {
    val t = tempC * 9 / 5 + 32
    val rh = humidityPct.coerceIn(0.0, 100.0)
    val simple = 0.5 * (t + 61.0 + (t - 68.0) * 1.2 + rh * 0.094)
    val hiF = if ((simple + t) / 2 < 80) {
        simple
    } else {
        var hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh - 0.00683783 * t * t -
            0.05481717 * rh * rh + 0.00122874 * t * t * rh + 0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh
        if (rh < 13 && t in 80.0..112.0) hi -= ((13 - rh) / 4) * kotlin.math.sqrt((17 - kotlin.math.abs(t - 95)) / 17)
        if (rh > 85 && t in 80.0..87.0) hi += ((rh - 85) / 10) * ((87 - t) / 5)
        hi
    }
    return (hiF - 32) * 5 / 9
}

enum class HeatBand(val label: String) {
    COLD("COLD"), COOL("COOL"), MILD("MILD"), WARM("WARM"), HOT("HOT"), VERY_HOT("VERY HOT");

    val raisesFluid: Boolean get() = this == WARM || this == HOT || this == VERY_HOT
}

/** The band for exercise: by heat index (NWS: caution from 27 °C, extreme caution from 32, danger from 41). */
fun heatBand(tempC: Double, humidityPct: Double?): HeatBand {
    val hi = humidityPct?.let { heatIndexC(tempC, it) } ?: tempC
    return when {
        tempC < 5 -> HeatBand.COLD
        tempC < 12 -> HeatBand.COOL
        hi < 22 -> HeatBand.MILD
        hi < 27 -> HeatBand.WARM
        hi < 32 -> HeatBand.HOT
        else -> HeatBand.VERY_HOT
    }
}

data class HeatSummary(val band: HeatBand, val maxTempC: Double, val maxHeatIndexC: Double, val strongSun: Boolean, val lines: List<String>)

/** The heat over a stretch of hours (a race, a long run): its hottest hour, and what that means for the plan. */
fun summariseHeat(hours: List<HourWeather>, source: WeatherSource): HeatSummary? {
    if (hours.isEmpty()) return null
    val hottest = hours.maxBy { it.humidityPct?.let { h -> heatIndexC(it.tempC, h) } ?: it.tempC }
    val hi = hottest.humidityPct?.let { heatIndexC(hottest.tempC, it) } ?: hottest.tempC
    val band = heatBand(hottest.tempC, hottest.humidityPct)
    val sun = hours.any { (it.solarWm2 ?: 0.0) >= 600 }
    val lines = mutableListOf<String>()
    lines += buildString {
        append("Up to ${hottest.tempC.roundToInt()} °C")
        hottest.humidityPct?.let { append(", ${it.roundToInt()} % humidity") }
        hottest.dewPointC?.let { append(", dew point ${it.roundToInt()} °C") }
        if (hi - hottest.tempC >= 1) append(" (feels like ${hi.roundToInt()} °C)")
        if (source == WeatherSource.TYPICAL) hottest.tempP90C?.let { append("; 1 year in 10 above ${it.roundToInt()} °C") }
        append(".")
    }
    when (band) {
        HeatBand.HOT, HeatBand.VERY_HOT -> {
            lines += "Hot: sweat losses rise and the gut tolerates less. Drink to the plan from the start, add sodium, and pre-cool with a cold drink or ice slurry."
            lines += "Heat acclimatisation (10-14 days of training in the heat) is the biggest single help."
        }
        HeatBand.WARM -> lines += "Warm: sweat losses rise; fluid and sodium are raised."
        HeatBand.COLD -> lines += "Cold: you sweat less and feel less thirsty; still drink on a schedule in long sessions."
        else -> Unit
    }
    if (sun) lines += "Strong sun: shade and a cap help; it adds to heat strain beyond the air temperature."
    return HeatSummary(band, hottest.tempC, hi, sun, lines)
}
