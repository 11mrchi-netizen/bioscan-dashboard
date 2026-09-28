package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.SessionWeather
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
