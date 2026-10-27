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
    companion object { private val client = HttpClient(Android) }
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

    // Routine intelligence R3: what OpenStreetMap has at a place, as a naming suggestion. The point is rounded to three
    // decimals (about 100 m) before it leaves the phone (plan section 8.3, rule 3), so the answer describes the area, not
    // the doorway. Null on no match or failure; cancellation propagates.
    suspend fun reverse(lat: Double, lon: Double): ReversePlace? = try {
        val response = client.get("https://nominatim.openstreetmap.org/reverse") {
            header("User-Agent", "FieldTerminal/1.0 (personal health app; single user)")
            url {
                parameters.append("lat", "%.3f".format(java.util.Locale.ROOT, lat))
                parameters.append("lon", "%.3f".format(java.util.Locale.ROOT, lon))
                parameters.append("format", "jsonv2")
                parameters.append("zoom", "18")
            }
        }
        if (!response.status.isSuccess()) null else {
            val r = json.decodeFromString<NominatimReverse>(response.bodyAsText())
            ReversePlace(r.name?.ifBlank { null } ?: r.displayName?.substringBefore(','), r.category, r.type)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

data class ReversePlace(val name: String?, val osmClass: String?, val osmType: String?)

@Serializable
private data class NominatimResult(val lat: String, val lon: String)

@Serializable
private data class NominatimReverse(
    val name: String? = null,
    @kotlinx.serialization.SerialName("display_name") val displayName: String? = null,
    val category: String? = null,
    val type: String? = null,
)
