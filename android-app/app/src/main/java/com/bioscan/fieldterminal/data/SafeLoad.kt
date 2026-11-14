package com.bioscan.fieldterminal.data

import android.util.Log
import kotlinx.coroutines.CancellationException

// A load that may fail without failing its caller. Kotlin's runCatching is wrong around suspend work: it also catches
// CancellationException, so a screen closed mid-read saw "no data" and its coroutine kept going. These two are the only
// ways a failure is swallowed in suspend code; both let cancellation through and log what they swallowed. A page's main
// load uses neither: it runs in a LoadGuard (ui/components/LoadGuard.kt), so its failure is shown with RETRY.

/** runCatching for suspend work: the failure is kept as a [Result] (and logged), cancellation propagates. */
suspend inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logSwallowed(e)
    Result.failure(e)
}

/** An optional read (an enrichment, one input among many): a failure is null, never a crash or a stuck screen. */
suspend inline fun <T> softLoad(block: () -> T): T? = catching(block).getOrNull()

@PublishedApi
internal fun logSwallowed(e: Exception) {
    // android.util.Log is absent in plain JVM unit tests.
    runCatching { Log.w("softLoad", "a load failed and was skipped: ${e.message}", e) }
}

// Keyset pagination over a key that is unique and ordered descending (a daily table's date): each page asks for rows
// strictly before the last key seen. Unlike offset ranges, a row written between two page reads cannot shift the window
// and duplicate or skip a row. fetch(null) is the first page.
suspend fun <T> keysetPagesDescending(
    pageSize: Long,
    maxRows: Long,
    keyOf: (T) -> String,
    fetch: suspend (before: String?) -> List<T>,
): List<T> {
    val out = mutableListOf<T>()
    var before: String? = null
    while (out.size < maxRows) {
        val page = fetch(before)
        out += page
        if (page.size < pageSize) break
        before = keyOf(page.last())
    }
    return out
}
