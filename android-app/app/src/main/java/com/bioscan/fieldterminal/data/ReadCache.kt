package com.bioscan.fieldterminal.data

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

// Performance pass, Stage 2. One person, a few thousand rows: the same wearable_daily / sleep_daily / session lists were
// fetched again by every screen (wearable_daily by six of them). This is a small process-wide read cache with two rules
// that keep it honest:
//
//  1. A cached list is at most TTL old, and a write to Supabase (a POST/PATCH/PUT/DELETE through the client, including the
//     Health Connect sync, a log entry, an edge-function call) drops it (see CacheInvalidation below), so a screen never
//     shows data older than the last thing this app wrote. Pull-to-refresh and sign-out clear everything too.
//     An entry that names the tables it reads ([get]'s `tables`) is dropped only by writes to one of those tables (or by a
//     write the plugin cannot attribute to a table: an rpc or an edge function). Without the names it is dropped by any
//     write: on app open the Health Connect sync posts dozens of wearable_daily / provenance rows, and the session lists
//     used to be refetched after every one of them.
//  2. Concurrent callers of the same key share one request: the first loads, the rest wait on the same lock and then read
//     the stored value. A failed load stores nothing, so an error is never cached.
//
// Kept on disk (pages open at once): a read that passes its `serializer` is also written to disk, and when its memory copy
// is missing or stale it is answered at once with the last value it had (in memory, or on disk from an earlier run of the
// app) while the fresh one loads in the background. When the fresh one differs, [updates] names the key and the pages on
// screen load again (rememberLoadGuard, rememberCacheVersion), now from memory. A read that must not see an old value
// (TODAY's own rebuild after a write) runs inside [RequireFresh] and waits for the network as before.
object ReadCache {
    const val DEFAULT_TTL_MS = 90_000L
    const val LONG_TTL_MS = 30 * 60_000L // reference data that only changes with a deploy (benchmarks, exercise library)

    private class Entry(val at: Long, val value: Any?, val tables: Set<String>?)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() } // swappable in tests

    /** A read made inside this element never gets an old value: it waits for the network (TODAY's rebuild after a write). */
    object RequireFresh : AbstractCoroutineContextElement(Key) {
        object Key : CoroutineContext.Key<RequireFresh>
    }

    // The last value of each key, kept after it went stale or a write dropped it, to answer with while the fresh one loads.
    private val lastKnown = ConcurrentHashMap<String, Hit<Any?>>()
    private val lastHash = ConcurrentHashMap<String, Int>()
    private val refreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true; encodeDefaults = true }
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Keys whose background refresh brought a different value. */
    val updates: SharedFlow<String> = _updates

    @Volatile
    private var dir: File? = null

    /** Where the disk copies live (the app's files dir); without it reads are kept in memory only. */
    fun init(filesDir: File) {
        dir = File(filesDir, "read_cache").apply { mkdirs() }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(
        key: String,
        ttlMs: Long = DEFAULT_TTL_MS,
        tables: Set<String>? = null,
        serializer: KSerializer<T>? = null,
        load: suspend () -> T,
    ): T {
        fresh<T>(key, ttlMs)?.let { return it.value }
        if (serializer != null && coroutineContext[RequireFresh.Key] == null) {
            val stale = lastKnown[key] ?: readDisk(key, serializer)
            if (stale != null) {
                refreshInBackground(key, tables, serializer, load)
                return stale.value as T
            }
        }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            fresh<T>(key, ttlMs)?.let { return@withLock it.value }
            val value = load()
            store(key, value, tables, serializer)
            value
        }
    }

    /** Stores a loaded value; true when it differs from the last one kept for [key]. */
    private fun <T> store(key: String, value: T, tables: Set<String>?, serializer: KSerializer<T>?): Boolean {
        entries[key] = Entry(clock(), value, tables)
        lastKnown[key] = Hit(value)
        if (serializer == null) return false
        val text = runCatching { json.encodeToString(serializer, value) }.getOrNull() ?: return false
        val hash = text.hashCode()
        if (lastHash.put(key, hash) == hash) return false
        writeDisk(key, text)
        return true
    }

    private fun <T> refreshInBackground(key: String, tables: Set<String>?, serializer: KSerializer<T>, load: suspend () -> T) {
        if (!refreshing.add(key)) return
        scope.launch {
            try {
                val changed = locks.getOrPut(key) { Mutex() }.withLock { store(key, load(), tables, serializer) }
                if (changed) _updates.tryEmit(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or a failed request: the last value stays on screen, and the next read tries again.
            } finally {
                refreshing.remove(key)
            }
        }
    }

    private fun fileFor(key: String): File? = dir?.let { d ->
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        File(d, "$name.json")
    }

    private fun <T> readDisk(key: String, serializer: KSerializer<T>): Hit<Any?>? {
        val text = runCatching { fileFor(key)?.takeIf { it.exists() }?.readText() }.getOrNull() ?: return null
        val value = runCatching { json.decodeFromString(serializer, text) }.getOrNull() ?: return null
        lastHash[key] = text.hashCode()
        return Hit<Any?>(value).also { lastKnown[key] = it }
    }

    private fun writeDisk(key: String, text: String) {
        val file = fileFor(key) ?: return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    /** Tests: forget everything in memory, as a new run of the app would, keeping the disk copies. */
    internal fun forgetMemoryForTest() {
        entries.clear()
        lastKnown.clear()
        lastHash.clear()
    }

    /** Sign-out: nothing of the last user survives, in memory or on disk. */
    fun clearEverything() {
        invalidateAll()
        lastKnown.clear()
        lastHash.clear()
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
    }

    // Boxed so a cached null (e.g. "no active cycle") is distinguishable from a miss.
    private class Hit<T>(val value: T)

    @Suppress("UNCHECKED_CAST")
    private fun <T> fresh(key: String, ttlMs: Long): Hit<T>? {
        val e = entries[key] ?: return null
        return if (clock() - e.at <= ttlMs) Hit(e.value as T) else null
    }

    fun invalidateAll() {
        entries.clear()
        unattributedWrites.incrementAndGet()
    }

    /** A write to [table] (null: a write that names no table) drops every entry that may depend on it. */
    fun invalidateWrite(table: String?) {
        if (table == null) return invalidateAll()
        writeCounts.computeIfAbsent(table) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        entries.entries.removeIf { (_, e) -> e.tables == null || table in e.tables }
    }

    // Scoring system rebuild: a screen that keeps its own finished result (TODAY) asks whether anything it reads was
    // written since. The stamp grows with every write to one of [tables] and with every write that names no table.
    private val writeCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val unattributedWrites = java.util.concurrent.atomic.AtomicLong()

    fun writeStamp(tables: Set<String>): Long = unattributedWrites.get() + tables.sumOf { writeCounts[it]?.get() ?: 0L }

    fun size(): Int = entries.size
}

/** The table a PostgREST write goes to (`/rest/v1/<table>`), or null for an rpc, an edge function or anything else. */
fun writtenTable(path: String): String? {
    val rest = path.substringAfter("/rest/v1/", missingDelimiterValue = "").substringBefore('/').substringBefore('?')
    return rest.takeIf { it.isNotEmpty() && it != "rpc" }
}

// Ktor client plugin: after any request that is not a read, drop the entries it may have made stale. Runs for every
// repository because it is installed on the one shared Supabase client.
val CacheInvalidation = createClientPlugin("CacheInvalidation") {
    onResponse { response ->
        val request = response.call.request
        val method = request.method
        if (method != HttpMethod.Get && method != HttpMethod.Head && method != HttpMethod.Options) {
            ReadCache.invalidateWrite(writtenTable(request.url.encodedPath))
        }
    }
}
