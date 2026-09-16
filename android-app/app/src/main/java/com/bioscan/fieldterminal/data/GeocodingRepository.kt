package com.bioscan.fieldterminal.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Phase M1 (Map tab rework). Nominatim (OpenStreetMap's free public
// geocoder) -- same "free tier, no Google billing" choice this app already
// made for CARTO tiles and Open-Meteo weather, and needs no signup/API key
// at all. Its usage policy asks for a real identifying User-Agent and caps
// to light, non-bulk use -- both true here (a handful of calendar events'
// addresses per screen load, on demand, nothing cached/scheduled).
class GeocodingRepository {
    private val client = HttpClient(Android)
    private val json = Json { ignoreUnknownKeys = true }

    // Null on no match or any failure, deliberately not a thrown exception --
    // callers fall back to the user's home coordinates rather than dropping
    // the event off the map entirely.
    suspend fun geocode(address: String): Pair<Double, Double>? = try {
        val response = client.get("https://nominatim.openstreetmap.org/search") {
            header("User-Agent", "FieldTerminal/1.0 (personal health app; single user)")
            url {
                parameters.append("q", address)
                parameters.append("format", "jsonv2")
                parameters.append("limit", "1")
            }
        }
        if (!response.status.isSuccess()) {
            null
        } else {
            val results = json.decodeFromString<List<NominatimResult>>(response.bodyAsText())
            results.firstOrNull()?.let { r -> r.lat.toDoubleOrNull()?.let { lat -> r.lon.toDoubleOrNull()?.let { lon -> lat to lon } } }
        }
    } catch (e: Exception) {
        null
    }
}

@Serializable
private data class NominatimResult(val lat: String, val lon: String)
