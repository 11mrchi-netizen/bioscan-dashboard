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
//  1. A cached list is at most TTL old, and ANY write to Supabase (a POST/PATCH/PUT/DELETE through the client, including the
//     Health Connect sync, a log entry, an edge-function call) clears the whole cache (see CacheInvalidation below), so a
//     screen never shows data older than the last thing this app wrote. Pull-to-refresh and sign-out clear it too.
//  2. Concurrent callers of the same key share one request: the first loads, the rest wait on the same lock and then read
//     the stored value. A failed load stores nothing, so an error is never cached.
object ReadCache {
    const val DEFAULT_TTL_MS = 90_000L
    const val LONG_TTL_MS = 30 * 60_000L // reference data that only changes with a deploy (benchmarks, exercise library)

    private class Entry(val at: Long, val value: Any?)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() } // swappable in tests

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String, ttlMs: Long = DEFAULT_TTL_MS, load: suspend () -> T): T {
        fresh<T>(key, ttlMs)?.let { return it.value }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            fresh<T>(key, ttlMs)?.let { return@withLock it.value }
            val value = load()
            entries[key] = Entry(clock(), value)
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

    fun size(): Int = entries.size
}

// Ktor client plugin: after any request that is not a read, drop the cache. Runs for every repository because it is
// installed on the one shared Supabase client.
val CacheInvalidation = createClientPlugin("CacheInvalidation") {
    onResponse { response ->
        val method = response.call.request.method
        if (method != HttpMethod.Get && method != HttpMethod.Head && method != HttpMethod.Options) ReadCache.invalidateAll()
    }
}
