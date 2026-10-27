package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.localDateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// PR #24 review: optional reads keep cancellation, daily histories page by key, and timestamps land on their local day.
class SafeLoadTest {

    @Test
    fun aFailureIsNullButCancellationPropagates() = runBlocking {
        assertNull(orNullUnlessCancelled<Int> { throw IllegalStateException("offline") })
        assertEquals(3, orNullUnlessCancelled { 3 })
        try {
            orNullUnlessCancelled<Int> { throw CancellationException("screen closed") }
            fail("cancellation was swallowed")
        } catch (_: CancellationException) {
        }
    }

    // A table of dates, newest first, served the way PostgREST would: rows strictly before the cursor, a page at a time.
    private fun server(rows: MutableList<String>, pageSize: Int): suspend (String?) -> List<String> = { before ->
        rows.sortedDescending().filter { before == null || it < before }.take(pageSize)
    }

    @Test
    fun keysetPagingReadsEveryRowOnceEvenWhenARowArrivesBetweenPages() = runBlocking {
        val rows = (1..25).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()).toString() }.toMutableList()
        val fetch = server(rows, 10)
        var calls = 0
        val read = keysetPagesDescending(10, 1_000, { it }) { before ->
            // After the first page a new day syncs at the top: with offsets this would push one old row into page two again.
            if (calls++ == 1) rows += "2026-03-01"
            fetch(before)
        }
        assertEquals(25, read.size)
        assertEquals(read.distinct(), read)
        assertEquals(rows.filter { it != "2026-03-01" }.sortedDescending(), read)
    }

    @Test
    fun keysetPagingStopsAtTheCapAndOnAShortPage() = runBlocking {
        val rows = (1..30).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()).toString() }.toMutableList()
        assertEquals(20, keysetPagesDescending(10, 20, { it }, server(rows, 10)).size)
        assertEquals(30, keysetPagesDescending(10, 1_000, { it }, server(rows, 10)).size)
    }

    @Test
    fun utcTimestampsLandOnTheirLocalDay() {
        val taipei = ZoneId.of("Asia/Taipei")
        // 23:30 UTC on the 19th is 07:30 on the 20th in Taipei: the session belongs to the 20th.
        assertEquals(LocalDate.of(2026, 10, 20), localDateIn("2026-10-19T23:30:00+00:00", taipei))
        assertEquals(LocalDate.of(2026, 10, 20), localDateIn("2026-10-19 23:30:00+00:00", taipei))
        assertEquals(LocalDate.of(2026, 10, 19), localDateIn("2026-10-19T15:59:59Z", taipei))
        assertEquals(LocalDate.of(2026, 10, 19), localDateIn("2026-10-19", taipei))
        assertNull(localDateIn("yesterday", taipei))
    }
}
