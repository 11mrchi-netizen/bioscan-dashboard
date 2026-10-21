package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.SessionWeather
import com.bioscan.fieldterminal.domain.fuel.FORECAST_DAYS
import com.bioscan.fieldterminal.domain.fuel.HourWeather
import com.bioscan.fieldterminal.domain.fuel.WeatherSeries
import com.bioscan.fieldterminal.domain.fuel.WeatherSource
import com.bioscan.fieldterminal.domain.fuel.climatology
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class WeatherFetchException(message: String) : Exception(message)

// Same keyless Open-Meteo provider the web dashboard already uses for live
// weather/AQI/UV (no API key, no billing) -- first use of it on Android.
// Pulls the full hourly array (Open-Meteo's free tier covers 16 days, well
// past the 7-day window fetchNextSession() already searches) and picks the
// entry closest to the session's own start time, rather than only "now",
// since this is a forecast for a session that may be up to a week out.
class WeatherRepository {
    companion object { private val client = HttpClient(Android) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAt(lat: Double, lon: Double, at: ZonedDateTime): SessionWeather {
        val response = client.get("https://api.open-meteo.com/v1/forecast") {
            url {
                parameters.append("latitude", lat.toString())
                parameters.append("longitude", lon.toString())
                parameters.append("hourly", "temperature_2m,weathercode,windspeed_10m,precipitation")
                parameters.append("forecast_days", "16")
                parameters.append("timezone", "auto")
            }
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw WeatherFetchException("Weather request failed (${response.status.value})")
        }

        val hourly = json.decodeFromString<OpenMeteoResponse>(bodyText).hourly
            ?: throw WeatherFetchException("No hourly forecast returned.")
        val index = closestHourIndex(hourly.time, at)
            ?: throw WeatherFetchException("Session time is outside the forecast window.")

        return SessionWeather(
            temperatureC = hourly.temperature_2m.getOrNull(index)
                ?: throw WeatherFetchException("Missing temperature data."),
            code = hourly.weathercode.getOrNull(index) ?: throw WeatherFetchException("Missing weather code."),
            windSpeedKmh = hourly.windspeed_10m.getOrNull(index) ?: 0.0,
            precipitationMm = hourly.precipitation.getOrNull(index) ?: 0.0,
        )
    }

    // DAV-173. Historical weather for a past session. Same SessionWeather
    // shape as fetchAt(); callers treat them identically -- the distinction
    // (forecast vs. archive endpoint) lives here, not in the caller.
    // archive-api.open-meteo.com is Open-Meteo's historical endpoint --
    // same no-key, no-billing policy as the forecast endpoint.
    suspend fun fetchHistoricalAt(lat: Double, lon: Double, at: ZonedDateTime): SessionWeather {
        val date = at.toLocalDate()
        val response = client.get("https://archive-api.open-meteo.com/v1/archive") {
            url {
                parameters.append("latitude", lat.toString())
                parameters.append("longitude", lon.toString())
                parameters.append("start_date", date.toString())
                parameters.append("end_date", date.toString())
                parameters.append("hourly", "temperature_2m,weathercode,windspeed_10m,precipitation")
                parameters.append("timezone", "auto")
            }
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw WeatherFetchException("Historical weather request failed (${response.status.value})")
        }

        val hourly = json.decodeFromString<OpenMeteoResponse>(bodyText).hourly
            ?: throw WeatherFetchException("No hourly data returned from archive endpoint.")
        val index = closestHourIndex(hourly.time, at)
            ?: throw WeatherFetchException("Session time $at is outside the archive window for $date.")

        return SessionWeather(
            temperatureC = hourly.temperature_2m.getOrNull(index)
                ?: throw WeatherFetchException("Missing temperature data for $date."),
            code = hourly.weathercode.getOrNull(index) ?: throw WeatherFetchException("Missing weather code for $date."),
            windSpeedKmh = hourly.windspeed_10m.getOrNull(index) ?: 0.0,
            precipitationMm = hourly.precipitation.getOrNull(index) ?: 0.0,
        )
    }

    // Fuel planning (domain/fuel/HeatStress.kt): every hour from [from] to [to] at a place. Within the 16-day forecast,
    // the forecast; further out, the TYPICAL weather of those dates (the same hours within 3 days either side over the
    // last [years] years, from the archive), labelled as such. Cached for 30 minutes per place and window.
    suspend fun fetchHourly(lat: Double, lon: Double, from: ZonedDateTime, to: ZonedDateTime, years: Int = 10): WeatherSeries {
        val key = "weather.hourly.%.3f.%.3f.%s.%s".format(lat, lon, from.toLocalDate(), to.toLocalDate())
        return ReadCache.get(key, ttlMs = 30 * 60_000L, tables = emptySet()) {
            val now = ZonedDateTime.now(from.zone)
            val targetHours = generateSequence(from.truncatedTo(ChronoUnit.HOURS)) { it.plusHours(1) }.takeWhile { !it.isAfter(to) }.toList()
            if (!to.toLocalDate().isAfter(now.toLocalDate().plusDays(FORECAST_DAYS - 1))) {
                val hours = hourly("https://api.open-meteo.com/v1/forecast", lat, lon, from.toLocalDate(), to.toLocalDate(), from.zone)
                WeatherSeries(WeatherSource.FORECAST, targetHours.mapNotNull { t -> hours.firstOrNull { it.time == t } })
            } else {
                val samples = mutableListOf<HourWeather>()
                for (y in 1..years) {
                    val start = from.toLocalDate().minusYears(y.toLong()).minusDays(3)
                    val end = to.toLocalDate().minusYears(y.toLong()).plusDays(3)
                    samples += runCatching { hourly("https://archive-api.open-meteo.com/v1/archive", lat, lon, start, end, from.zone) }.getOrDefault(emptyList())
                }
                if (samples.isEmpty()) WeatherSeries.NONE else climatology(samples, targetHours, years)
            }
        }
    }

    private suspend fun hourly(url: String, lat: Double, lon: Double, start: java.time.LocalDate, end: java.time.LocalDate, zone: java.time.ZoneId): List<HourWeather> {
        val response = client.get(url) {
            url {
                parameters.append("latitude", lat.toString())
                parameters.append("longitude", lon.toString())
                parameters.append("hourly", "temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature,shortwave_radiation,windspeed_10m,precipitation")
                parameters.append("start_date", start.toString())
                parameters.append("end_date", end.toString())
                parameters.append("timezone", zone.id)
            }
        }
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) throw WeatherFetchException("Weather request failed (${response.status.value})")
        val h = json.decodeFromString<OpenMeteoFullResponse>(bodyText).hourly ?: return emptyList()
        return h.time.indices.mapNotNull { i ->
            val t = runCatching { LocalDateTime.parse(h.time[i]).atZone(zone) }.getOrNull() ?: return@mapNotNull null
            val temp = h.temperature_2m.getOrNull(i) ?: return@mapNotNull null
            HourWeather(
                time = t, tempC = temp, humidityPct = h.relative_humidity_2m.getOrNull(i), dewPointC = h.dew_point_2m.getOrNull(i),
                apparentC = h.apparent_temperature.getOrNull(i), solarWm2 = h.shortwave_radiation.getOrNull(i),
                windKmh = h.windspeed_10m.getOrNull(i), precipMm = h.precipitation.getOrNull(i),
            )
        }
    }

    // Open-Meteo returns naive local-time strings (already resolved to the
    // location's own zone via timezone=auto) -- compared here against the
    // session's own zoned start time by instant, same principle as the web
    // dashboard's sunrise/sunset offset handling.
    private fun closestHourIndex(times: List<String>, at: ZonedDateTime): Int? {
        var bestIndex: Int? = null
        var bestDiffMinutes = Long.MAX_VALUE
        times.forEachIndexed { i, t ->
            val parsed = try {
                LocalDateTime.parse(t).atZone(at.zone)
            } catch (e: Exception) {
                return@forEachIndexed
            }
            val diff = abs(ChronoUnit.MINUTES.between(parsed, at))
            if (diff < bestDiffMinutes) {
                bestDiffMinutes = diff
                bestIndex = i
            }
        }
        // More than 6h off means the session is genuinely outside the
        // forecast window, not just an hour-boundary rounding difference.
        return if (bestDiffMinutes <= 360) bestIndex else null
    }
}

// Nullable entries: the archive has gaps, and a missing hour must not shift the others.
@Serializable
private data class OpenMeteoFullHourly(
    val time: List<String> = emptyList(),
    val temperature_2m: List<Double?> = emptyList(),
    val relative_humidity_2m: List<Double?> = emptyList(),
    val dew_point_2m: List<Double?> = emptyList(),
    val apparent_temperature: List<Double?> = emptyList(),
    val shortwave_radiation: List<Double?> = emptyList(),
    val windspeed_10m: List<Double?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
)

@Serializable
private data class OpenMeteoFullResponse(val hourly: OpenMeteoFullHourly? = null)

@Serializable
private data class OpenMeteoResponse(val hourly: OpenMeteoHourly? = null)

@Serializable
private data class OpenMeteoHourly(
    val time: List<String> = emptyList(),
    val temperature_2m: List<Double> = emptyList(),
    val weathercode: List<Int> = emptyList(),
    val windspeed_10m: List<Double> = emptyList(),
    val precipitation: List<Double> = emptyList(),
)
