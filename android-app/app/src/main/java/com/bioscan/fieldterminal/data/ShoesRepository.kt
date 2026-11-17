package com.bioscan.fieldterminal.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.bioscan.fieldterminal.domain.Shoe
import com.bioscan.fieldterminal.domain.ShoeRun
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.isTrailRun
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

// Shoes (domain/Shoes.kt): the shoe list, the runs with their Zepp shoe code (zepp_workout_detail.gear_id) and the
// person's own choice (exercise_sessions.shoe_id). Writes leave out nothing: a cleared field is sent as null.

@Serializable
private data class ShoeRow(
    val id: Long,
    val name: String,
    val use: String = "road",
    @SerialName("zepp_gear_id") val zeppGearId: String? = null,
    @SerialName("start_km") val startKm: Double = 0.0,
    @SerialName("retire_km") val retireKm: Double = 700.0,
    @SerialName("started_on") val startedOn: String? = null,
    @SerialName("retired_on") val retiredOn: String? = null,
    val notes: String? = null,
    @SerialName("photo_jpeg") val photoJpeg: String? = null,
) {
    fun toShoe() = Shoe(
        id, name, use, zeppGearId, startKm, retireKm,
        startedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        retiredOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        notes, photoJpeg,
    )
}

@Serializable
private data class ShoeSessionRow(
    val id: Long,
    @SerialName("start_time") val startTime: String,
    @SerialName("distance_km") val distanceKm: Double? = null,
    val type: String? = null,
    @SerialName("shoe_id") val shoeId: Long? = null,
    val details: JsonObject? = null,
)

@Serializable
private data class ShoeGearRow(
    @SerialName("exercise_session_id") val sessionId: Long? = null,
    @SerialName("gear_id") val gearId: String? = null,
    @SerialName("sport_type") val sportType: String? = null,
)

/** What the shoe form saves. */
data class ShoeDraft(
    val name: String,
    val use: String,
    val zeppGearId: String?,
    val startKm: Double,
    val retireKm: Double,
    val startedOn: LocalDate?,
    val retiredOn: LocalDate?,
    val notes: String?,
    val photoJpeg: String?,
)

class ShoesRepository(private val supabase: SupabaseClient) {

    suspend fun loadShoes(): List<Shoe> = ReadCache.get("shoes.list", tables = setOf("shoes"), serializer = ListSerializer(Shoe.serializer())) {
        supabase.postgrest.from("shoes").select { order("created_at", Order.ASCENDING) }
            .decodeList<ShoeRow>().map { it.toShoe() }
    }

    /** Every run, and every session given shoes, with its Zepp shoe code. */
    suspend fun loadRuns(): List<ShoeRun> = ReadCache.get(
        "shoes.runs", tables = setOf("exercise_sessions", "zepp_workout_detail"), serializer = ListSerializer(ShoeRun.serializer()),
    ) {
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,start_time,distance_km,type,shoe_id,details")) {
                filter { or { eq("type", "run"); filterNot("shoe_id", FilterOperator.IS, "null") } }
            }.decodeList<ShoeSessionRow>()
        val gear = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.list("exercise_session_id,gear_id,sport_type")) { filter { filterNot("exercise_session_id", FilterOperator.IS, "null") } }
            .decodeList<ShoeGearRow>().filter { it.sessionId != null }.associateBy { it.sessionId!! }
        sessions.mapNotNull { s ->
            val date = WallClock.date(s.startTime) ?: return@mapNotNull null
            val g = gear[s.id]
            val routeType = runCatching { s.details?.get("routeType")?.jsonPrimitive?.content }.getOrNull()
            ShoeRun(
                sessionId = s.id, date = date, distanceKm = s.distanceKm ?: 0.0,
                trail = isTrailRun(routeType, g?.sportType),
                gearId = g?.gearId?.takeIf { it.isNotBlank() }, chosenShoeId = s.shoeId, zeppSportType = g?.sportType,
            )
        }
    }

    suspend fun save(id: Long?, d: ShoeDraft) {
        val body = buildJsonObject {
            put("name", d.name.trim())
            put("use", d.use)
            if (d.zeppGearId.isNullOrBlank()) put("zepp_gear_id", JsonNull) else put("zepp_gear_id", d.zeppGearId.trim())
            put("start_km", d.startKm)
            put("retire_km", d.retireKm)
            if (d.startedOn == null) put("started_on", JsonNull) else put("started_on", d.startedOn.toString())
            if (d.retiredOn == null) put("retired_on", JsonNull) else put("retired_on", d.retiredOn.toString())
            if (d.notes.isNullOrBlank()) put("notes", JsonNull) else put("notes", d.notes.trim())
            if (d.photoJpeg == null) put("photo_jpeg", JsonNull) else put("photo_jpeg", d.photoJpeg)
            put("updated_at", Instant.now().toString())
        }
        d.zeppGearId?.takeIf { it.isNotBlank() }?.let { releaseCode(it.trim(), keep = id) }
        if (id == null) supabase.postgrest.from("shoes").insert(body)
        else supabase.postgrest.from("shoes").update(body) { filter { eq("id", id) } }
    }

    suspend fun delete(id: Long) {
        supabase.postgrest.from("shoes").delete { filter { eq("id", id) } }
    }

    /** Gives [code] to the shoe [shoeId] (a code belongs to one shoe: any other shoe holding it lets it go). */
    suspend fun matchCode(shoeId: Long, code: String) {
        releaseCode(code, keep = shoeId)
        supabase.postgrest.from("shoes").update(buildJsonObject { put("zepp_gear_id", code) }) { filter { eq("id", shoeId) } }
    }

    /** The person's own choice of shoes for one session; null goes back to the Zepp code. */
    suspend fun choose(sessionId: Long, shoeId: Long?) {
        supabase.postgrest.from("exercise_sessions")
            .update(buildJsonObject { if (shoeId == null) put("shoe_id", JsonNull) else put("shoe_id", shoeId) }) { filter { eq("id", sessionId) } }
    }

    private suspend fun releaseCode(code: String, keep: Long?) {
        supabase.postgrest.from("shoes").update(buildJsonObject { put("zepp_gear_id", JsonNull) }) {
            filter { eq("zepp_gear_id", code); if (keep != null) neq("id", keep) }
        }
    }
}

/** A photo as the shoe's icon: centre square, 320 px, JPEG, base64 (about 20-40 KB). Null when it can't be read. */
fun shoePhotoFrom(context: Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return null
    val side = minOf(bmp.width, bmp.height)
    val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, 320, 320, true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()

/** The icon back as a bitmap. */
fun shoePhotoBitmap(base64: String?): Bitmap? = base64?.let {
    runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }.getOrNull()
}
