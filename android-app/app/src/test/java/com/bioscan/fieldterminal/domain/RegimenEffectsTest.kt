package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.score.ScoreKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RegimenEffectsTest {
    private val change = LocalDate.of(2026, 10, 1)
    private val today = LocalDate.of(2026, 11, 3)

    // A day-to-day wobble of ±2 around [level], so the robust spread is about 3.
    private fun series(beforeLevel: Double, afterLevel: Double) =
        (-40..33).associate { d ->
            val day = change.plusDays(d.toLong())
            day to (if (d < 0) beforeLevel else afterLevel) + listOf(-2.0, 0.0, 2.0)[Math.floorMod(d, 3)]
        }

    @Test
    fun aClearMoveIsReportedAndNoiseIsNot() {
        val clear = changeReadings(change, mapOf(ScoreKeys.HRV to series(46.0, 52.0)), today)
        assertEquals(1, clear.size)
        assertEquals(6.0, clear.single().difference, 0.5)
        assertEquals(true, clear.single().better)
        assertTrue(changeReadingWords(clear.single()).startsWith("Overnight HRV averaged 52 ms in the 21 days with data after, 46 ms in the 21 before (+6 ms)"))
        // A one-unit drift inside a ±2 wobble is noise.
        assertTrue(changeReadings(change, mapOf(ScoreKeys.HRV to series(46.0, 47.0)), today).isEmpty())
        // Resting heart rate going up is the worse direction.
        assertEquals(false, changeReadings(change, mapOf(ScoreKeys.RHR to series(50.0, 56.0)), today).single().better)
    }

    @Test
    fun tooRecentOrTooSparseSaysNothing() {
        assertTrue(changeReadings(today.minusDays(5), mapOf(ScoreKeys.HRV to series(46.0, 60.0)), today).isEmpty())
        val sparse = series(46.0, 60.0).filterKeys { it.dayOfMonth % 4 == 0 }
        assertTrue(changeReadings(change, mapOf(ScoreKeys.HRV to sparse), today).isEmpty())
    }

    @Test
    fun otherChangesAroundTheSameTimeAreListed() {
        val changes = listOf(
            RegimenChange(change, "Creatine", listOf("started"), null, "5 g"),
            RegimenChange(change.plusDays(5), "Zinc", listOf("dose"), "50 mg", "25 mg"),
            RegimenChange(change.plusDays(40), "Iron", listOf("started"), null, "36 mg"),
        )
        assertEquals(listOf("Zinc"), nearbyChanges(changes, change, "Creatine").map { it.itemName })
    }
}
