package com.bioscan.fieldterminal.data

import kotlinx.coroutines.CancellationException

// An optional read in a repository: a failure becomes null, cancellation propagates. runCatching catches
// CancellationException too, so a screen closed mid-read used to see "no data" and the coroutine kept going; this is the
// data-layer twin of ui/components/LoadGuard.kt's softLoad.
suspend fun <T> orNullUnlessCancelled(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
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
