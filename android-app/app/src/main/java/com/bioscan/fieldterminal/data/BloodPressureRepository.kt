package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.health.connect.client.records.BloodPressureRecord
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.bp.BpOverview
import com.bioscan.fieldterminal.domain.bp.BpReading
import com.bioscan.fieldterminal.domain.bp.ClinicBp
import com.bioscan.fieldterminal.domain.bp.bpOverview
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import com.bioscan.fieldterminal.healthconnect.readAllRecords
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

// Risk profile R0 (docs/risk-profile/01-plan.md; supabase/migrations/20261119090000_bp_readings.sql): home readings in
// bp_readings, typed here or imported from Health Connect, and the clinic readings that already come with lab draws
// (lab_results 'SBP, Sitting' / 'DBP, Sitting'). occurred_at is a logged time: the local digits labelled +00 (WallClock).

@Serializable
data class BpReadingRow(
    val id: Long,
    @SerialName("occurred_at") val occurredAt: String,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int? = null,
    val arm: String? = null,
    val position: String? = null,
    @SerialName("on_bp_meds") val onBpMeds: Boolean? = null,
    val source: String = "manual",
    val note: String? = null,
)

@Serializable
data class NewBpReadingRow(
    @SerialName("occurred_at") val occurredAt: String,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int? = null,
    val arm: String? = null,
    val position: String? = null,
    @SerialName("on_bp_meds") val onBpMeds: Boolean? = null,
    val note: String? = null,
)

@Serializable
private data class HcBpRow(
    @SerialName("occurred_at") val occurredAt: String,
    val systolic: Int,
    val diastolic: Int,
    val arm: String?,
    val position: String?,
    val source: String,
    @SerialName("hc_record_id") val hcRecordId: String,
    @SerialName("hc_origin") val hcOrigin: String,
)

@Serializable
private data class ClinicDrawRow(val id: Long, @SerialName("draw_date") val drawDate: String)

@Serializable
private data class ClinicResultRow(@SerialName("draw_id") val drawId: Long, @SerialName("marker_name") val markerName: String, val value: Double? = null)

const val CLINIC_SYSTOLIC_MARKER = "SBP, Sitting"
const val CLINIC_DIASTOLIC_MARKER = "DBP, Sitting"

/** How far back each Health Connect import reads: readings are few, and the import is idempotent on the record id. */
const val BP_IMPORT_DAYS = 400L

fun BpReadingRow.toReading(): BpReading? =
    WallClock.read(occurredAt)?.let { BpReading(it, systolic, diastolic, pulse, manual = source == "manual") }

class BloodPressureRepository(private val supabase: SupabaseClient) {

    suspend fun loadReadings(): List<BpReadingRow> = ReadCache.kept("bp.readings", tables = setOf("bp_readings")) {
        supabase.postgrest.from("bp_readings")
            .select(Columns.list("id,occurred_at,systolic,diastolic,pulse,arm,position,on_bp_meds,source,note")) {
                order("occurred_at", Order.ASCENDING)
            }
            .decodeList<BpReadingRow>()
    }

    /** The latest lab draw with both clinic markers, or null. */
    suspend fun loadClinic(): ClinicBp? = ReadCache.kept("bp.clinic", tables = setOf("lab_draws", "lab_results")) {
        val results = supabase.postgrest.from("lab_results")
            .select(Columns.list("draw_id,marker_name,value")) {
                filter { isIn("marker_name", listOf(CLINIC_SYSTOLIC_MARKER, CLINIC_DIASTOLIC_MARKER)) }
            }
            .decodeList<ClinicResultRow>()
        val ids = results.map { it.drawId }.distinct()
        val dates = if (ids.isEmpty()) emptyMap() else supabase.postgrest.from("lab_draws")
            .select(Columns.list("id,draw_date")) { filter { isIn("id", ids) } }
            .decodeList<ClinicDrawRow>()
            .associate { it.id to it.drawDate }
        latestClinic(results.map { Triple(it.drawId, it.markerName, it.value) }, dates)
    }

    suspend fun loadOverview(today: LocalDate = LocalDate.now()): BpOverview =
        bpOverview(loadReadings().mapNotNull { it.toReading() }, loadClinic(), today)

    suspend fun add(row: NewBpReadingRow) {
        supabase.postgrest.from("bp_readings").insert(row)
    }

    suspend fun update(id: Long, row: NewBpReadingRow) {
        supabase.postgrest.from("bp_readings").update(row) { filter { eq("id", id) } }
    }

    suspend fun delete(id: Long) {
        supabase.postgrest.from("bp_readings").delete { filter { eq("id", id) } }
    }

    suspend fun fetch(id: Long): BpReadingRow =
        supabase.postgrest.from("bp_readings")
            .select(Columns.list("id,occurred_at,systolic,diastolic,pulse,arm,position,on_bp_meds,source,note")) { filter { eq("id", id) } }
            .decodeSingle<BpReadingRow>()

    /**
     * Imports the cuff readings Health Connect holds (Omron Connect and most cuff apps write there). Runs only when the
     * person granted the blood pressure permission, which is optional (HealthConnectManager.OPTIONAL_PERMISSIONS).
     * Returns the number of readings read; a reading already imported is overwritten with the same values.
     */
    suspend fun importFromHealthConnect(context: Context, until: Instant = Instant.now()): Int {
        if (!HealthConnectManager.hasBloodPressurePermission(context)) return 0
        val since = until.minusSeconds(BP_IMPORT_DAYS * 86_400)
        val records = HealthConnectManager.client(context).readAllRecords(BloodPressureRecord::class, since, until)
        val zone = ZoneId.systemDefault()
        val rows = records.mapNotNull { r ->
            val sys = r.systolic.inMillimetersOfMercury.roundToInt()
            val dia = r.diastolic.inMillimetersOfMercury.roundToInt()
            if (sys !in 60..260 || dia !in 30..160 || sys <= dia) return@mapNotNull null
            // The reading's own offset when the cuff app gave one, else the phone's zone: a logged time is its local digits.
            val local = r.zoneOffset?.let { LocalDateTime.ofInstant(r.time, it) } ?: LocalDateTime.ofInstant(r.time, zone)
            HcBpRow(
                occurredAt = WallClock.write(local),
                systolic = sys,
                diastolic = dia,
                arm = hcArm(r.measurementLocation),
                position = hcPosition(r.bodyPosition),
                source = "health_connect",
                hcRecordId = r.metadata.id,
                hcOrigin = r.metadata.dataOrigin.packageName,
            )
        }
        rows.chunked(200).forEach { chunk ->
            supabase.postgrest.from("bp_readings").upsert(chunk) { onConflict = "user_id,hc_record_id" }
        }
        return rows.size
    }
}

/** The latest draw carrying both clinic markers. [results] are (draw id, marker, value); [dates] draw id to its date. */
internal fun latestClinic(results: List<Triple<Long, String, Double?>>, dates: Map<Long, String>): ClinicBp? =
    results.groupBy { it.first }
        .mapNotNull { (drawId, rows) ->
            val s = rows.firstOrNull { it.second == CLINIC_SYSTOLIC_MARKER }?.third
            val d = rows.firstOrNull { it.second == CLINIC_DIASTOLIC_MARKER }?.third
            val date = dates[drawId]?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            if (s != null && d != null && date != null) ClinicBp(date, s, d) else null
        }
        .maxByOrNull { it.date }

private fun hcArm(location: Int): String? = when (location) {
    BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM, BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST -> "left"
    BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM, BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST -> "right"
    else -> null
}

private fun hcPosition(position: Int): String? = when (position) {
    BloodPressureRecord.BODY_POSITION_SITTING_DOWN -> "seated"
    BloodPressureRecord.BODY_POSITION_STANDING_UP -> "standing"
    BloodPressureRecord.BODY_POSITION_LYING_DOWN, BloodPressureRecord.BODY_POSITION_RECLINING -> "lying"
    else -> null
}
