package com.bioscan.fieldterminal.data.routine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// Routine intelligence R2: what the collector recorded and has not synced yet, as JSON lines in the app's own storage.
// The receivers and the trip service append (cheap, works offline, survives process death); RoutineCaptureWorker drains.
// Draining renames the file first, so lines appended while an upload is in flight land in a fresh file and are never lost;
// a failed upload leaves the renamed file to be retried on the next run.
@Serializable
data class OutboxFix(val ts: Long, val lat: Double, val lon: Double, val acc: Float? = null, val kind: String, val activity: String? = null)

@Serializable
data class OutboxTransition(val ts: Long, val activity: String, val transition: String)

@Serializable
data class OutboxHeartbeat(val stream: String, val ts: Long)

object RoutineOutbox {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun dir(context: Context) = File(context.filesDir, "routine").apply { mkdirs() }
    private fun live(context: Context, name: String) = File(dir(context), "$name.jsonl")
    private fun sending(context: Context, name: String) = File(dir(context), "$name.sending.jsonl")

    private fun append(context: Context, name: String, line: String) = synchronized(lock) {
        live(context, name).appendText(line + "\n")
    }

    fun addFix(context: Context, fix: OutboxFix) = append(context, "fixes", json.encodeToString(fix))
    fun addTransition(context: Context, t: OutboxTransition) = append(context, "transitions", json.encodeToString(t))
    fun addHeartbeat(context: Context, h: OutboxHeartbeat) = append(context, "heartbeats", json.encodeToString(h))

    // Lines waiting, for the Setup card.
    fun pendingCount(context: Context): Int = synchronized(lock) {
        listOf("fixes", "transitions", "heartbeats").sumOf { name ->
            listOf(live(context, name), sending(context, name)).filter { it.exists() }.sumOf { f -> f.useLines { it.count() } }
        }
    }

    // Hands the waiting lines of one kind to `upload`; deletes them only if it returns without throwing.
    suspend fun <T> drain(context: Context, name: String, decode: (String) -> T, upload: suspend (List<T>) -> Unit) {
        val batch = synchronized(lock) {
            val s = sending(context, name)
            val l = live(context, name)
            if (!s.exists() && l.exists()) l.renameTo(s)
            s
        }
        if (!batch.exists()) return
        val items = batch.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { decode(it) }.getOrNull() }
        if (items.isNotEmpty()) upload(items)
        synchronized(lock) { batch.delete() }
    }

    fun decodeFix(line: String): OutboxFix = json.decodeFromString(line)
    fun decodeTransition(line: String): OutboxTransition = json.decodeFromString(line)
    fun decodeHeartbeat(line: String): OutboxHeartbeat = json.decodeFromString(line)

    fun clear(context: Context) = synchronized(lock) { dir(context).listFiles()?.forEach { it.delete() } }
}
