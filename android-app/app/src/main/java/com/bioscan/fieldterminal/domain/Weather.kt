package com.bioscan.fieldterminal.domain

// WMO weather codes -- ported 1:1 from index.html's weatherCodeLabel() (same
// table Open-Meteo's own docs define). weatherCodeSymbol() is new here: the
// web dashboard uses a set of illustrated SVG weather variants instead, which
// don't exist on Android -- a plain emoji symbol serves the same "icon for
// the forecast" role the user asked for without needing an icon asset set.
data class SessionWeather(
    val temperatureC: Double,
    val code: Int,
    val windSpeedKmh: Double,
    val precipitationMm: Double,
)

fun weatherCodeLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45 -> "Fog"
    48 -> "Depositing fog"
    51 -> "Light drizzle"
    53 -> "Drizzle"
    55 -> "Dense drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Slight rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71 -> "Slight snow"
    73 -> "Snow"
    75 -> "Heavy snow"
    77 -> "Snow grains"
    80 -> "Slight showers"
    81 -> "Showers"
    82 -> "Violent showers"
    85 -> "Slight snow showers"
    86 -> "Snow showers"
    95 -> "Thunderstorm"
    96 -> "Thunderstorm w/ hail"
    99 -> "Thunderstorm w/ heavy hail"
    else -> "Unknown"
}

fun weatherCodeSymbol(code: Int): String = when (code) {
    0, 1 -> "☀️" // clear
    2 -> "⛅" // partly cloudy
    3 -> "☁️" // overcast
    45, 48 -> "🌫️" // fog
    51, 53, 55, 56, 57, 80, 81, 82 -> "🌦️" // drizzle/showers
    61, 63, 65, 66, 67 -> "🌧️" // rain
    71, 73, 75, 77, 85, 86 -> "❄️" // snow
    95, 96, 99 -> "⛈️" // thunderstorm
    else -> "❓"
}
