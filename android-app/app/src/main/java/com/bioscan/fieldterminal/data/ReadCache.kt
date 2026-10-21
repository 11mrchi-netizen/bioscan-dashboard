package com.bioscan.fieldterminal.data

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
object ReadCache {
    const val DEFAULT_TTL_MS = 90_000L
    const val LONG_TTL_MS = 30 * 60_000L // reference data that only changes with a deploy (benchmarks, exercise library)

    private class Entry(val at: Long, val value: Any?, val tables: Set<String>?)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() } // swappable in tests

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String, ttlMs: Long = DEFAULT_TTL_MS, tables: Set<String>? = null, load: suspend () -> T): T {
        fresh<T>(key, ttlMs)?.let { return it.value }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            fresh<T>(key, ttlMs)?.let { return@withLock it.value }
            val value = load()
            entries[key] = Entry(clock(), value, tables)
            value
        }
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
    }

    /** A write to [table] (null: a write that names no table) drops every entry that may depend on it. */
    fun invalidateWrite(table: String?) {
        if (table == null) return invalidateAll()
        entries.entries.removeIf { (_, e) -> e.tables == null || table in e.tables }
    }

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
