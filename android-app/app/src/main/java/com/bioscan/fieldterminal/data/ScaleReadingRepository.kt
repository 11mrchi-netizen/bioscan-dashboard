package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.WeightUpsertRow
import com.bioscan.fieldterminal.domain.BodyComposition
import com.bioscan.fieldterminal.domain.BodyCompositionInput
import com.bioscan.fieldterminal.domain.ScaleReading
import com.bioscan.fieldterminal.domain.aging.chronologicalAgeYears
import com.bioscan.fieldterminal.domain.bodyComposition
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Scale weigh-ins (supabase/migrations/20261106_scale_readings.sql): the raw reading goes to scale_readings, the weight also
// to today's body_metrics row like every other weight source. Local wall-clock time labelled UTC, like body_checkins.
//
// Body composition (domain/BodyComposition.kt) is computed from the raw rows, never stored as the source of truth, so a
// better formula can be applied to history. The estimate that passes the quality gate is also written to the day's
// body_metrics row (body_fat_pct, lean_mass_kg = fat-free mass) with source = 'scale_bia', which is what the body-fat blend
// and the fuel planner read. The Health Connect body-fat sync skips such days (HealthConnectDailySyncRepository).

const val SCALE_BIA_SOURCE = "scale_bia"

@Serializable
private data class NewScaleReadingRow(
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("impedance_ohm") val impedanceOhm: Double,
    @SerialName("raw_hex") val rawHex: String,
    @SerialName("device_mac") val deviceMac: String,
)

// Weight plus the derived columns in one upsert; only used when the composition passed the quality gate, so no nulls are sent.
@Serializable
private data class ScaleBodyMetricsUpsertRow(
    val date: String,
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("body_fat_pct") val bodyFatPct: Double,
    @SerialName("lean_mass_kg") val leanMassKg: Double,
    val source: String, // no default: defaults are not encoded unless the client's Json says so
)

@Serializable
data class ScaleReadingRow(
    val id: Long,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("impedance_ohm") val impedanceOhm: Double? = null,
)

/** One weigh-in with the composition derived from today's profile. [composition] is null when profile data or impedance is missing. */
data class ScaleCompositionPoint(
    val at: LocalDateTime,
    val weightKg: Double,
    val impedanceOhm: Double?,
    val composition: BodyComposition?,
)

class ScaleReadingRepository(private val supabase: SupabaseClient) {
    private val profiles = UserProfileRepository(supabase)
    private val checkins = BodyCheckinRepository(supabase)

    suspend fun save(reading: ScaleReading, at: LocalDateTime = LocalDateTime.now()) {
        supabase.postgrest.from("scale_readings")
            .insert(NewScaleReadingRow(at.atOffset(ZoneOffset.UTC).toString(), reading.weightKg, reading.impedanceOhm, reading.rawHex, reading.mac))
        val date = at.toLocalDate().toString()
        val composition = profileInput(reading.weightKg, reading.impedanceOhm, at.toLocalDate())?.let { bodyComposition(it) }
        val split = composition?.split?.takeIf { composition.qualityOk }
        if (split != null) {
            supabase.postgrest.from("body_metrics")
                .upsert(ScaleBodyMetricsUpsertRow(date, reading.weightKg, split.bodyFatPct, split.fatFreeKg, SCALE_BIA_SOURCE)) { onConflict = "user_id,date" }
        } else {
            supabase.postgrest.from("body_metrics")
                .upsert(WeightUpsertRow(date, reading.weightKg)) { onConflict = "user_id,date" }
        }
    }

    /** Composition for a reading not yet saved; null when height, date of birth or sex is missing. */
    suspend fun preview(reading: ScaleReading, on: LocalDate = LocalDate.now()): BodyComposition? =
        profileInput(reading.weightKg, reading.impedanceOhm, on)?.let { bodyComposition(it) }

    /** Every saved weigh-in, oldest first. */
    suspend fun loadReadings(): List<ScaleReadingRow> = ReadCache.get(CACHE_KEY, tables = setOf("scale_readings")) {
        supabase.postgrest.from("scale_readings")
            .select(Columns.list("id,occurred_at,weight_kg,impedance_ohm")) { order("occurred_at", Order.ASCENDING) }
            .decodeList<ScaleReadingRow>()
    }

    /** Weigh-ins with their composition, derived from the current profile (raw rows are the source of truth). */
    suspend fun loadCompositionSeries(): List<ScaleCompositionPoint> {
        val readings = loadReadings()
        val profile = profiles.loadProfile()
        val heightCm = profile?.heightCm ?: runCatching { checkins.latestHeightCm() }.getOrNull()
        return readings.map { r ->
            val at = OffsetDateTime.parse(r.occurredAt).toLocalDateTime()
            val input = r.impedanceOhm?.let { z -> inputFrom(r.weightKg, z, at.toLocalDate(), heightCm, profile?.dateOfBirth, profile?.sex) }
            ScaleCompositionPoint(at, r.weightKg, r.impedanceOhm, input?.let { bodyComposition(it) })
        }
    }

    /**
     * Recomputes body_fat_pct / lean_mass_kg for every day that has a weigh-in (the last one of the day wins), e.g. after height
     * is first entered. Returns the number of days written. Days whose composition fails the quality gate are left alone.
     */
    suspend fun backfillBodyMetrics(): Int {
        val lastPerDay = loadCompositionSeries().groupBy { it.at.toLocalDate() }.mapValues { (_, v) -> v.maxBy { it.at } }
        val rows = lastPerDay.mapNotNull { (day, p) ->
            val c = p.composition
            val split = c?.split?.takeIf { c.qualityOk } ?: return@mapNotNull null
            ScaleBodyMetricsUpsertRow(day.toString(), p.weightKg, split.bodyFatPct, split.fatFreeKg, SCALE_BIA_SOURCE)
        }
        if (rows.isNotEmpty()) supabase.postgrest.from("body_metrics").upsert(rows) { onConflict = "user_id,date" }
        return rows.size
    }

    private suspend fun profileInput(weightKg: Double, impedanceOhm: Double, on: LocalDate): BodyCompositionInput? {
        val profile = runCatching { profiles.loadProfile() }.getOrNull()
        val heightCm = profile?.heightCm ?: runCatching { checkins.latestHeightCm() }.getOrNull()
        return inputFrom(weightKg, impedanceOhm, on, heightCm, profile?.dateOfBirth, profile?.sex)
    }

    private fun inputFrom(weightKg: Double, impedanceOhm: Double, on: LocalDate, heightCm: Double?, dateOfBirth: String?, sex: String?): BodyCompositionInput? {
        val dob = dateOfBirth?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        if (heightCm == null || (sex != "male" && sex != "female")) return null
        return BodyCompositionInput(weightKg, impedanceOhm, heightCm, chronologicalAgeYears(dob, on), isMale = sex == "male")
    }

    private companion object {
        const val CACHE_KEY = "scale_readings"
    }
}
