package com.bioscan.hometerminal.env

import android.content.Context
import com.bioscan.hometerminal.data.Supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import java.io.File
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
private data class SensorRow(
    val id: String,
    val name: String,
    val room: String?,
    @SerialName("device_types") val deviceTypes: List<String>,
    val vendor: String?,
    val product: String?,
    @SerialName("last_seen_at") val lastSeenAt: String,
)

@Serializable
private data class ReadingRow(
    @SerialName("sensor_id") val sensorId: String,
    val metric: String,
    @SerialName("measured_at") val measuredAt: String,
    val value: Double,
    val raw: Double?,
)

@Serializable
private data class HeartbeatRow(val stream: String, val ts: String)

@Serializable
private data class StoredSensor(
    val id: String,
    val name: String,
    val room: String? = null,
    val deviceTypes: List<String> = emptyList(),
    val vendor: String? = null,
    val product: String? = null,
    val lastSeenMs: Long,
)

/**
 * Writes what the collector queued to Supabase as the signed-in user (RLS fills user_id). Sensors go first, since every
 * reading references its sensor; the sensors ever seen are kept in a file, so readings queued before a restart still
 * find theirs. Upserts ignore duplicates, so a batch sent twice (an upload cut off before [EnvOutbox.drop]) is harmless.
 */
class EnvUploader(context: Context) {
    private val dir = context.filesDir
    val readings = EnvOutbox(File(dir, "env-readings.jsonl"))
    val heartbeats = EnvOutbox(File(dir, "env-heartbeats.jsonl"))
    private val sensorsFile = File(dir, "env-sensors.json")
    private val sensorsSerializer = ListSerializer(StoredSensor.serializer())

    @Synchronized
    fun rememberSensors(sensors: List<SensorInfo>, now: Instant = Instant.now()) {
        val known = loadSensors().associateBy { it.id }.toMutableMap()
        sensors.forEach { known[it.id] = StoredSensor(it.id, it.name, it.room, it.deviceTypes, it.vendor, it.product, now.toEpochMilli()) }
        sensorsFile.writeText(EnvOutbox.json.encodeToString(sensorsSerializer, known.values.toList()))
    }

    @Synchronized
    private fun loadSensors(): List<StoredSensor> =
        if (!sensorsFile.exists()) emptyList()
        else runCatching { EnvOutbox.json.decodeFromString(sensorsSerializer, sensorsFile.readText()) }.getOrDefault(emptyList())

    /** Uploads everything queued. Returns false without trying when no one is signed in. Throws on a failed request. */
    suspend fun flush(): Boolean {
        val auth = Supabase.client.auth
        auth.awaitInitialization()
        if (auth.currentSessionOrNull() == null) return false
        val db = Supabase.client.postgrest

        val sensors = loadSensors()
        if (sensors.isNotEmpty()) {
            db.from("home_sensors").upsert(
                sensors.map { SensorRow(it.id, it.name, it.room, it.deviceTypes, it.vendor, it.product, Instant.ofEpochMilli(it.lastSeenMs).toString()) },
            ) { onConflict = "user_id,id" }
        }
        val knownIds = sensors.mapTo(HashSet()) { it.id }

        while (true) {
            val batch = readings.peek(CHUNK)
            if (batch.isEmpty()) break
            // A line that does not decode, or whose sensor was never stored, can never be written: it is dropped.
            val rows = batch.mapNotNull(EnvOutbox::decode).filter { it.sensorId in knownIds }.map {
                ReadingRow(it.sensorId, it.metric, Instant.ofEpochMilli(it.atMs).toString(), it.value, it.raw)
            }
            if (rows.isNotEmpty()) {
                db.from("home_env_readings").upsert(rows) {
                    onConflict = "user_id,sensor_id,metric,measured_at"
                    ignoreDuplicates = true
                }
            }
            readings.drop(batch.size)
        }

        while (true) {
            val batch = heartbeats.peek(CHUNK)
            if (batch.isEmpty()) break
            val rows = batch.mapNotNull { it.toLongOrNull() }.map { HeartbeatRow(STREAM, Instant.ofEpochMilli(it).toString()) }
            if (rows.isNotEmpty()) {
                db.from("collector_heartbeats").upsert(rows) {
                    onConflict = "user_id,stream,ts"
                    ignoreDuplicates = true
                }
            }
            heartbeats.drop(batch.size)
        }
        return true
    }

    companion object {
        const val STREAM = "home_env"
        private const val CHUNK = 500
    }
}
