package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.domain.BodyMeasurement
import com.bioscan.fieldterminal.domain.BodySide
import com.bioscan.fieldterminal.domain.BodySite
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

// The monthly body check-in (supabase/migrations/20261019_body_checkins.sql): one body_checkins row per sitting, one
// body_measurements row per site and side (every repeat kept, the median as `value`), and the sitting's grip readings in
// grip_strength_log pointing at it. Times are local wall-clock labelled with an offset, like grip_strength_log.

@Serializable
private data class NewBodyCheckinRow(
    @SerialName("occurred_at") val occurredAt: String,
    val fasted: Boolean? = null,
    @SerialName("before_training") val beforeTraining: Boolean? = null,
    val caliper: String? = null,
    val notes: String? = null,
)

@Serializable
private data class NewCheckinIdRow(val id: Long)

@Serializable
private data class NewBodyMeasurementRow(
    @SerialName("checkin_id") val checkinId: Long,
    val site: String,
    val side: String,
    val method: String,
    val readings: List<Double>,
    val value: Double,
)

@Serializable
private data class NewCheckinGripRow(
    @SerialName("occurred_at") val occurredAt: String,
    val hand: String,
    val kg: Double,
    @SerialName("checkin_id") val checkinId: Long,
)

@Serializable
private data class BodyCheckinRow(
    val id: Long,
    @SerialName("occurred_at") val occurredAt: String,
    val fasted: Boolean? = null,
    @SerialName("before_training") val beforeTraining: Boolean? = null,
    val caliper: String? = null,
    val notes: String? = null,
)

@Serializable
private data class BodyMeasurementRow(
    @SerialName("checkin_id") val checkinId: Long,
    val site: String,
    val side: String,
    val readings: List<Double>,
    val value: Double,
)

@Serializable
private data class CheckinGripRow(@SerialName("checkin_id") val checkinId: Long, val hand: String, val kg: Double)

@Serializable
private data class HeightRow(val date: String, @SerialName("height_cm") val heightCm: Double? = null)

@Serializable
private data class HeightUpsert(val date: String, @SerialName("height_cm") val heightCm: Double)

/** What the check-in sheet hands over to save. */
data class BodyCheckinDraft(
    val occurredAt: LocalDateTime,
    val fasted: Boolean?,
    val beforeTraining: Boolean?,
    val caliper: String?,
    val notes: String?,
    val measurements: List<BodyMeasurement>,
    val grip: Map<String, Double>,
)

class BodyCheckinRepository(private val supabase: SupabaseClient) {

    /** Saves the sitting, then its measurements and grip readings; returns the new check-in's id. */
    suspend fun save(draft: BodyCheckinDraft): Long {
        val at = draft.occurredAt.atOffset(ZoneOffset.UTC).toString()
        val id = supabase.postgrest.from("body_checkins")
            .insert(NewBodyCheckinRow(at, draft.fasted, draft.beforeTraining, draft.caliper?.trim()?.takeIf { it.isNotEmpty() }, draft.notes?.trim()?.takeIf { it.isNotEmpty() })) {
                select(Columns.list("id"))
            }
            .decodeSingle<NewCheckinIdRow>().id
        try {
            if (draft.measurements.isNotEmpty()) {
                supabase.postgrest.from("body_measurements").insert(
                    draft.measurements.map { m ->
                        NewBodyMeasurementRow(id, m.site.key, m.side.key, m.site.method.key, m.readings, m.value)
                    },
                )
            }
            if (draft.grip.isNotEmpty()) {
                supabase.postgrest.from("grip_strength_log").insert(draft.grip.map { (hand, kg) -> NewCheckinGripRow(at, hand, kg, id) })
            }
        } catch (e: Exception) {
            // No half-saved sitting: the check-in goes too (its measurements cascade), so SAVE can simply be pressed again.
            runCatching { supabase.postgrest.from("body_checkins").delete { filter { eq("id", id) } } }
            throw e
        }
        return id
    }

    /** Every check-in with its measurements and grip, oldest first. */
    suspend fun loadHistory(): List<BodyCheckinRecord> = ReadCache.get(CACHE_KEY) {
        val checkins = supabase.postgrest.from("body_checkins")
            .select(Columns.list("id,occurred_at,fasted,before_training,caliper,notes")) {
                order("occurred_at", Order.DESCENDING)
                limit(60)
            }
            .decodeList<BodyCheckinRow>()
        if (checkins.isEmpty()) return@get emptyList()
        val ids = checkins.map { it.id }
        val measurements = supabase.postgrest.from("body_measurements")
            .select(Columns.list("checkin_id,site,side,readings,value")) { filter { isIn("checkin_id", ids) } }
            .decodeList<BodyMeasurementRow>()
            .groupBy { it.checkinId }
        val grip = supabase.postgrest.from("grip_strength_log")
            .select(Columns.list("checkin_id,hand,kg")) { filter { isIn("checkin_id", ids) } }
            .decodeList<CheckinGripRow>()
            .groupBy { it.checkinId }
        checkins.mapNotNull { c ->
            val at = runCatching { OffsetDateTime.parse(c.occurredAt).toLocalDateTime() }.getOrNull() ?: return@mapNotNull null
            BodyCheckinRecord(
                id = c.id,
                occurredAt = at,
                fasted = c.fasted,
                beforeTraining = c.beforeTraining,
                caliper = c.caliper,
                notes = c.notes,
                measurements = measurements[c.id].orEmpty().mapNotNull { m ->
                    val site = BodySite.of(m.site) ?: return@mapNotNull null
                    val side = BodySide.entries.firstOrNull { it.key == m.side } ?: return@mapNotNull null
                    if (m.readings.isEmpty()) null else BodyMeasurement(site, side, m.readings, m.value)
                },
                grip = grip[c.id].orEmpty().associate { it.hand to it.kg },
            )
        }.sortedBy { it.occurredAt }
    }

    /** The date of the newest check-in, for the monthly reminder (null before the first). */
    suspend fun lastCheckinDate(): LocalDate? =
        supabase.postgrest.from("body_checkins")
            .select(Columns.list("id,occurred_at")) {
                order("occurred_at", Order.DESCENDING)
                limit(1)
            }
            .decodeList<BodyCheckinRow>()
            .firstOrNull()
            ?.let { runCatching { OffsetDateTime.parse(it.occurredAt).toLocalDate() }.getOrNull() }

    /** The newest height on record (Health Connect or typed at a check-in), in cm. */
    suspend fun latestHeightCm(): Double? =
        supabase.postgrest.from("body_metrics")
            .select(Columns.list("date,height_cm")) {
                filter { filterNot("height_cm", FilterOperator.IS, "null") }
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<HeightRow>()
            .firstOrNull()?.heightCm

    /** Stores a height typed during the check-in on today's body_metrics row (only that column is written). */
    suspend fun saveHeight(date: LocalDate, heightCm: Double) {
        supabase.postgrest.from("body_metrics").upsert(HeightUpsert(date.toString(), heightCm)) { onConflict = "user_id,date" }
    }

    companion object {
        const val CACHE_KEY = "body.checkins"
    }
}
