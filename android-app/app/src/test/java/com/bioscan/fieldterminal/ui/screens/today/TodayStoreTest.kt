package com.bioscan.fieldterminal.ui.screens.today

import com.bioscan.fieldterminal.data.ReadCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.bioscan.fieldterminal.domain.score.scoreInputsKey
import com.bioscan.fieldterminal.tour.tourPerson
import java.time.LocalTime
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

    // The page kept on disk comes back exactly as it was written: every score, strip, scorecard and series.
    @Test
    fun theKeptPageSurvivesTheRoundTripToDisk() {
        val view = tourPerson()
        val rows = listOf(
            PlanRow(LocalTime.of(8, 0), "Morning supplements", "3 due", false, PlanAction.Supplements("morning"), PlanKind.SUPPLEMENTS),
            PlanRow(null, "Close Monday", "1 min", false, PlanAction.CloseDay(view.day.minusDays(1)), PlanKind.CLOSE),
            PlanRow(LocalTime.of(13, 0), "Lunch", "800 kcal", true, PlanAction.SoFar, PlanKind.MEAL, kcal = 800.0),
        )
        val kept = KeptToday(TodayStore.FORMAT, view.day, 123L, "k", view, rows, SoFar(1200.0, 2600.0, 150.0, 80.0, 40.0), setOf(view.day.minusDays(1)))
        val back = TodayStore.decode(TodayStore.encode(kept), view.day)!!
        assertEquals(view.scores, back.view.scores)
        assertEquals(view.chargeStrip, back.view.chargeStrip)
        assertEquals(view.domainStrips, back.view.domainStrips)
        assertEquals(view.series, back.view.series)
        assertEquals(view.quality, back.view.quality)
        assertEquals(view.weights, back.view.weights)
        assertEquals(view.stressDays, back.view.stressDays)
        assertEquals(kept, back)
    }

    @Test
    fun anotherDaysOrAnOlderFormatsPageIsIgnored() {
        val view = tourPerson()
        val kept = KeptToday(TodayStore.FORMAT, view.day, 1L, "k", view, emptyList(), null, emptySet())
        assertNull(TodayStore.decode(TodayStore.encode(kept), view.day.plusDays(1)))
        assertNull(TodayStore.decode(TodayStore.encode(kept.copy(format = TodayStore.FORMAT - 1)), view.day))
        assertNull(TodayStore.decode("not json", view.day))
    }

    // The scores are recomputed only when what they read changed; a sync that rewrites the same rows keeps them.
    @Test
    fun theScoreKeyChangesOnlyWithTheInputs() {
        val view = tourPerson()
        val base = scoreInputsKey(view.day, view.series, emptyMap(), emptyList(), closedYesterday = true, afterNoon = false)
        val reordered = view.series.entries.reversed().associate { (k, v) -> k to v.entries.reversed().associate { it.key to it.value } }
        assertEquals(base, scoreInputsKey(view.day, reordered, emptyMap(), emptyList(), true, false))
        val hrv = view.series.getValue(com.bioscan.fieldterminal.domain.score.ScoreKeys.HRV).toMutableMap().apply { put(view.day, getValue(view.day) + 1) }
        assertNotEquals(base, scoreInputsKey(view.day, view.series + (com.bioscan.fieldterminal.domain.score.ScoreKeys.HRV to hrv), emptyMap(), emptyList(), true, false))
        assertNotEquals(base, scoreInputsKey(view.day, view.series, emptyMap(), emptyList(), true, true))
        assertNotEquals(base, scoreInputsKey(view.day, view.series, emptyMap(), listOf("gut 2026-11-02"), true, false))
        assertNotEquals(base, scoreInputsKey(view.day.plusDays(1), view.series, emptyMap(), emptyList(), true, false))
    }

    // Today's meals, water and stress land on tomorrow morning; planned sessions lie ahead. Neither moves this morning's
    // scores, so neither changes the key (they used to, and the scores were recomputed on every open).
    @Test
    fun whatIsDatedAfterTheMorningDoesNotChangeTheKey() {
        val view = tourPerson()
        val base = scoreInputsKey(view.day, view.series, emptyMap(), emptyList(), closedYesterday = true, afterNoon = false)
        val tomorrow = view.day.plusDays(1)
        val withToday = view.series + (com.bioscan.fieldterminal.domain.score.ScoreKeys.STRESSED_SHARE to
            (view.series[com.bioscan.fieldterminal.domain.score.ScoreKeys.STRESSED_SHARE].orEmpty() + (tomorrow to 37.0)))
        assertEquals(base, scoreInputsKey(view.day, withToday, emptyMap(), emptyList(), true, false))
        assertEquals(base, scoreInputsKey(view.day, view.series, mapOf(tomorrow to 0.0), emptyList(), true, false))
    }
}
