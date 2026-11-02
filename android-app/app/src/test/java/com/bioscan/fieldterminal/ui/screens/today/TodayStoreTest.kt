package com.bioscan.fieldterminal.ui.screens.today

import com.bioscan.fieldterminal.data.ReadCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TodayStoreTest {
    private val day = LocalDate.of(2026, 11, 3)

    @Test
    fun freshUntilAWriteANewDayOrFifteenMinutes() {
        val built = 1_000_000L
        assertTrue(TodayStore.isFresh(day, built, 7, day, built + 60_000, 7))
        assertFalse("a write to a table it reads", TodayStore.isFresh(day, built, 7, day, built + 60_000, 8))
        assertFalse("a new day", TodayStore.isFresh(day, built, 7, day.plusDays(1), built + 60_000, 7))
        assertFalse("too old", TodayStore.isFresh(day, built, 7, day, built + TodayStore.STALE_MS, 7))
        assertFalse("nothing kept", TodayStore.isFresh(null, null, null, day, built, 7))
    }

    @Test
    fun onlyWritesToItsTablesMoveTheStamp() {
        val before = ReadCache.writeStamp(TodayStore.TABLES)
        ReadCache.invalidateWrite("daily_context") // the load's own bookkeeping: not a table TODAY reads
        assertEquals(before, ReadCache.writeStamp(TodayStore.TABLES))
        ReadCache.invalidateWrite("meals")
        assertEquals(before + 1, ReadCache.writeStamp(TodayStore.TABLES))
        ReadCache.invalidateWrite(null) // an rpc or edge function: could have written anything
        assertEquals(before + 2, ReadCache.writeStamp(TodayStore.TABLES))
    }
}
