package com.bioscan.fieldterminal.util

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.contentLength
import io.ktor.util.AttributeKey

// Performance pass: the cheapest possible measurement. Everything logs to logcat under the tag "perf" and only in debug
// builds (MainActivity switches it on from BuildConfig.DEBUG), so release builds pay nothing.
//
//   adb logcat -s perf
//
// reads as: "nav <route>" markers (a screen was opened), then one "http" line per Supabase request (method, path, ms,
// bytes), and "timed" lines for wrapped computations. Counting http lines between two nav markers gives the requests a
// screen made; the ms on each gives where the time went.
object Perf {
    @Volatile
    var enabled: Boolean = false

    // Swappable so JVM tests can capture lines without android.util.Log (which is not available off-device).
    @Volatile
    var sink: (String) -> Unit = { line -> runCatching { android.util.Log.d("perf", line) } }

    fun mark(label: String) {
        if (enabled) sink("nav $label")
    }

    @PublishedApi
    internal fun emit(line: String) {
        if (enabled) sink(line)
    }

    // Times a block and logs "timed <tag> ms=<n>". Also opens a System Trace section so it shows in Perfetto.
    inline fun <T> timed(tag: String, block: () -> T): T {
        if (!enabled) return block()
        runCatching { android.os.Trace.beginSection(tag.take(120)) }
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            runCatching { android.os.Trace.endSection() }
            emit("timed $tag ms=${"%.1f".format(ms)}")
        }
    }
}

private val startedAt = AttributeKey<Long>("perf.startedAt")

// Ktor client plugin: one log line per request. Never logs headers (the JWT lives there) and cuts the query string.
val PerfHttpLog = createClientPlugin("PerfHttpLog") {
    onRequest { request, _ -> request.attributes.put(startedAt, System.nanoTime()) }
    onResponse { response ->
        if (Perf.enabled) {
            val t0 = response.call.request.attributes.getOrNull(startedAt)
            val ms = if (t0 == null) -1.0 else (System.nanoTime() - t0) / 1_000_000.0
            val url = response.call.request.url
            val query = url.encodedQuery.take(110)
            Perf.emit(
                "http ${response.call.request.method.value} ${url.encodedPath}${if (query.isEmpty()) "" else "?$query"} " +
                    "status=${response.status.value} ms=${"%.0f".format(ms)} bytes=${response.contentLength() ?: -1}",
            )
        }
    }
}
