package com.bioscan.hometerminal.env

import java.time.Instant
import kotlin.math.abs
import kotlin.math.pow

/** What the collector measures. [column] is home_env_readings.metric; [unit] is shown on the TV. */
enum class EnvMetric(val column: String, val unit: String) {
    TEMPERATURE_C("temperature_c", "°C"),
    HUMIDITY_PCT("humidity_pct", "%"),
    ILLUMINANCE_LUX("illuminance_lux", "lx"),
    CO2_PPM("co2_ppm", "ppm"),
    PM25_UGM3("pm25_ugm3", "µg/m³"),
    // Matter's AirQuality enum as a number: 1 good, 2 fair, 3 moderate, 4 poor, 5 very poor, 6 extremely poor.
    AIR_QUALITY("air_quality", ""),
}

/** One Google Home device that measures something. [id] is the Home APIs device id. */
data class SensorInfo(
    val id: String,
    val name: String,
    val room: String?,
    val deviceTypes: List<String>,
    val vendor: String?,
    val product: String?,
)

/**
 * One value as received. [at] is when the collector received it, a real instant (stored as UTC; see CLAUDE.md, "Dates
 * and times"). [raw] is the value exactly as Matter reported it.
 */
data class Measurement(val sensorId: String, val metric: EnvMetric, val value: Double, val raw: Double?, val at: Instant)

/** Matter's cluster encodings, turned into plain units. */
object MatterUnits {
    /** TemperatureMeasurement.measuredValue: hundredths of a degree Celsius. */
    fun temperatureC(raw: Double): Double = raw / 100.0

    /** RelativeHumidityMeasurement.measuredValue: hundredths of a percent. Out-of-range values are dropped. */
    fun humidityPct(raw: Double): Double? = (raw / 100.0).takeIf { it in 0.0..100.0 }

    /**
     * IlluminanceMeasurement.measuredValue is log-scaled in the Matter spec: 10,000 × log10(lux) + 1, so 1 is 1 lx and
     * 0 means too dark to measure; 0xFFFF is invalid. The Home APIs reference calls the value "lux", so the raw value is
     * kept with every reading: if the spike shows Google already converts it, the readings can be redone from raw.
     */
    fun illuminanceLux(raw: Double): Double? = when {
        raw <= 0.0 -> 0.0
        raw >= 65535.0 -> null
        else -> 10.0.pow((raw - 1.0) / 10_000.0)
    }
}

/**
 * Decides which received values are written. A value is written when it is the first for its sensor and metric, when it
 * moved by at least the metric's step from the last one written, or when [repeatAfterSeconds] passed since then (the
 * same value again, when the sensor reports it, so a flat line still shows the sensor alive). Not thread-safe: the
 * collector calls it from one coroutine.
 */
class SampleGate(private val repeatAfterSeconds: Long = 15 * 60) {
    private data class Last(val value: Double, val at: Instant)

    private val last = HashMap<Pair<String, EnvMetric>, Last>()

    fun admit(m: Measurement): Boolean {
        val key = m.sensorId to m.metric
        val prev = last[key]
        val write = prev == null ||
            moved(m.metric, prev.value, m.value) ||
            m.at.epochSecond - prev.at.epochSecond >= repeatAfterSeconds
        if (write) last[key] = Last(m.value, m.at)
        return write
    }

    private fun moved(metric: EnvMetric, from: Double, to: Double): Boolean {
        val delta = abs(to - from)
        return when (metric) {
            EnvMetric.TEMPERATURE_C -> delta >= 0.1
            EnvMetric.HUMIDITY_PCT -> delta >= 0.5
            // Light swings over orders of magnitude: 10 % of the last value, at least 1 lx.
            EnvMetric.ILLUMINANCE_LUX -> delta >= maxOf(1.0, abs(from) * 0.1)
            EnvMetric.CO2_PPM -> delta >= 10.0
            EnvMetric.PM25_UGM3 -> delta >= 1.0
            EnvMetric.AIR_QUALITY -> delta >= 1.0
        }
    }
}
