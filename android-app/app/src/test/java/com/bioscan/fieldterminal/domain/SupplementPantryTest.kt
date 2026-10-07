package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SupplementPantryTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 6)
    private fun at(d: LocalDate): Instant = d.atTime(9, 0).toInstant(ZoneOffset.UTC)
    private fun purchase(daysAgo: Long, servings: Double, expires: LocalDate? = null) =
        StockEvent(StockEventKind.Purchase, servings, at(today.minusDays(daysAgo)), expires)
    private fun takenDaily(fromDaysAgo: Long, perDay: Double = 1.0) =
        (fromDaysAgo downTo 0).map { TakenServings(at(today.minusDays(it)), perDay) }

    private fun assess(
        events: List<StockEvent>, taken: List<TakenServings> = emptyList(), scheduled: Double? = 1.0,
        lead: Int? = null, flags: List<String> = emptyList(),
    ) = assessPantry(events, taken, scheduled, lead, flags, today, zone)

    @Test
    fun noPurchaseMeansNoStockNotANegativeNumber() {
        val a = assess(emptyList(), takenDaily(10))
        assertNull(a.remainingServings)
        assertNull(a.daysLeft)
        assertEquals(listOf(PantryFindingKind.NoStock), a.findings.map { it.kind })
    }

    @Test
    fun logsBeforeTheFirstPurchaseDoNotConsumeStock() {
        // 240 bought 10 days ago; 30 days of logs but only the last 11 count.
        val a = assess(listOf(purchase(10, 240.0)), takenDaily(30))
        assertEquals(240.0 - 11.0, a.remainingServings!!, 1e-9)
        assertEquals(229, a.daysLeft)
        assertEquals(today.plusDays(229), a.runoutDate)
        assertEquals(today.plusDays(229 - 14), a.reorderBy)
        assertTrue(a.findings.isEmpty())
    }

    @Test
    fun runningOutSoonIsLowWithAReason() {
        val a = assess(listOf(purchase(20, 25.0)), takenDaily(20))
        assertEquals(4.0, a.remainingServings!!, 1e-9)
        assertEquals(PantryFindingKind.Low, a.findings.single().kind)
    }

    @Test
    fun reorderSoonOnceInsideTheLeadTime() {
        val a = assess(listOf(purchase(10, 40.0)), takenDaily(10), lead = 30)
        // 29 left at 1/day, lead 30 -> reorder date already passed, not yet "low".
        assertEquals(PantryFindingKind.ReorderSoon, a.findings.single().kind)
    }

    @Test
    fun usedUpStockClampsToZero() {
        val a = assess(listOf(purchase(5, 3.0)), takenDaily(5))
        assertEquals(0.0, a.remainingServings!!, 1e-9)
        assertEquals(PantryFindingKind.Low, a.findings.single().kind)
    }

    @Test
    fun discardAndAdjustmentChangeStock() {
        val events = listOf(
            purchase(0, 100.0),
            StockEvent(StockEventKind.Discard, -10.0, at(today)),
            StockEvent(StockEventKind.Adjustment, -5.0, at(today)),
        )
        assertEquals(85.0, assess(events).remainingServings!!, 1e-9)
    }

    @Test
    fun asNeededUsesTheObservedRateOnceThereIsEnoughHistory() {
        val taken = (0..13 step 2).map { TakenServings(at(today.minusDays(it.toLong())), 1.0) } // 7 doses in 14 days
        val a = assess(listOf(purchase(13, 60.0)), taken, scheduled = null)
        assertEquals(RateBasis.Observed, a.rateBasis)
        assertEquals(7.0 / 14, a.dailyRate!!, 1e-9)
        assertEquals(53.0, a.remainingServings!!, 1e-9)
    }

    @Test
    fun asNeededWithTooLittleHistoryHasNoRate() {
        val a = assess(listOf(purchase(2, 60.0)), takenDaily(2), scheduled = null)
        assertNull(a.dailyRate)
        assertNull(a.daysLeft)
        assertNull(a.runoutDate)
    }

    @Test
    fun observedRateIsFlaggedWhenItDiffersFromScheduledByMoreThanAQuarter() {
        val a = assess(listOf(purchase(13, 100.0)), takenDaily(13, perDay = 2.0), scheduled = 1.0)
        assertEquals(RateBasis.Scheduled, a.rateBasis)
        assertTrue(a.rateDiffers)
        val same = assess(listOf(purchase(13, 100.0)), takenDaily(13), scheduled = 1.0)
        assertFalse(same.rateDiffers)
    }

    @Test
    fun expiryBeforeRunoutIsFlagged() {
        val a = assess(listOf(purchase(0, 100.0, expires = today.plusDays(30))))
        assertEquals(PantryFindingKind.ExpiresFirst, a.findings.single().kind)
    }

    @Test
    fun providerFlagsAreCarriedAsFindings() {
        val a = assess(listOf(purchase(0, 100.0)), flags = listOf("Active FDA recall"))
        assertEquals(PantryFindingKind.ProviderFlag, a.findings.single().kind)
        // Provider flags must not hide the NoStock state either.
        assertEquals(
            setOf(PantryFindingKind.ProviderFlag, PantryFindingKind.NoStock),
            assess(emptyList(), flags = listOf("Off market")).findings.map { it.kind }.toSet(),
        )
    }
}
