package com.bioscan.hometerminal.env

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A reading waiting to be uploaded. [atMs] is epoch milliseconds (a real instant); [room] the sensor's room then. */
@Serializable
data class QueuedReading(val sensorId: String, val metric: String, val atMs: Long, val value: Double, val raw: Double?, val room: String? = null)

/**
 * Readings and heartbeats wait here, one JSON line each, until an upload succeeds: the TV can lose the network or the
 * Supabase session for hours and nothing measured meanwhile is lost. Past [maxBytes] the oldest half is dropped (24 MB is
 * weeks of readings at the usual rate).
 */
class EnvOutbox(private val file: File, private val maxBytes: Long = 24L * 1024 * 1024) {
    private val mutex = Mutex()

    suspend fun add(lines: List<String>) = mutex.withLock {
        if (lines.isEmpty()) return@withLock
        file.parentFile?.mkdirs()
        file.appendText(lines.joinToString("\n", postfix = "\n"))
        if (file.length() > maxBytes) readLines().let { all -> write(all.takeLast(all.size / 2)) }
    }

    suspend fun size(): Int = mutex.withLock { readLines().size }

    /** The oldest [limit] lines, still queued. */
    suspend fun peek(limit: Int): List<String> = mutex.withLock {
        if (!file.exists()) emptyList() else file.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.take(limit).toList() }
    }

    /** Removes the oldest [count] lines, the ones a [peek] returned and that were uploaded. New lines only ever go last. */
    suspend fun drop(count: Int) = mutex.withLock {
        if (count > 0) write(readLines().drop(count))
    }

    private fun readLines(): List<String> = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun write(lines: List<String>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
        tmp.renameTo(file)
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun encode(m: Measurement, room: String? = null): String =
            json.encodeToString(QueuedReading.serializer(), QueuedReading(m.sensorId, m.metric.column, m.at.toEpochMilli(), m.value, m.raw, room))

        fun decode(line: String): QueuedReading? = runCatching { json.decodeFromString(QueuedReading.serializer(), line) }.getOrNull()
    }
}
