package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HeartRateZonesTest {

    private val day = LocalDate.of(2026, 10, 1)

    // Synthetic anchors shaped like a typical recreational runner: max 196, resting 53, threshold 169.
    private val anchors = ZoneAnchors(maxHr = 196.0, restingHr = 53.0, lthr = 169.0, lthrDate = day.minusDays(10), asOf = day)

    private fun bounds(method: ZoneMethod, a: ZoneAnchors = anchors, sport: String? = "run") = zoneBounds(method, a, sport)!!

    @Test
    fun percentOfMaxEdges() {
        val b = bounds(ZoneMethod.MAX, ZoneAnchors(maxHr = 200.0))
        assertEquals(HeartRateZone.BelowZ1, heartRateZoneFor(98.0, b)) // 49%
        assertEquals(HeartRateZone.Z1, heartRateZoneFor(100.0, b)) // 50%
        assertEquals(HeartRateZone.Z1, heartRateZoneFor(118.0, b)) // 59%
        assertEquals(HeartRateZone.Z2, heartRateZoneFor(120.0, b)) // 60%
        assertEquals(HeartRateZone.Z3, heartRateZoneFor(140.0, b)) // 70%
        assertEquals(HeartRateZone.Z4, heartRateZoneFor(160.0, b)) // 80%
        assertEquals(HeartRateZone.Z5, heartRateZoneFor(180.0, b)) // 90%
        assertEquals(HeartRateZone.Z5, heartRateZoneFor(200.0, b))
    }

    @Test
    fun heartRateReserveEdges() {
        // Reserve 143: zones start at 53 + 50/60/70/80/90% of it.
        val b = bounds(ZoneMethod.HRR)
        listOf(124.5, 138.8, 153.1, 167.4, 181.7).zip(b.lowerBpm).forEach { (want, got) -> assertEquals(want, got, 1e-9) }
    }

    @Test
    fun thresholdEdges() {
        val b = bounds(ZoneMethod.LTHR)
        assertEquals(124.5, b.lowerBpm[0], 1e-9) // where HRR's Z1 starts
        assertEquals(0.85 * 169, b.lowerBpm[1], 1e-9)
        assertEquals(HeartRateZone.Z4, heartRateZoneFor(168.0, b))
        assertEquals(HeartRateZone.Z5, heartRateZoneFor(169.0, b))
        assertEquals("144–151", b.rangeText(HeartRateZone.Z2))
        assertEquals("169+", b.rangeText(HeartRateZone.Z5))
        assertEquals("under 125", b.rangeText(HeartRateZone.BelowZ1))
    }

    @Test
    fun anEasyRunReadsModerateByMaxButLightByReserveAndThreshold() {
        assertEquals(HeartRateZone.Z3, heartRateZoneFor(148.0, bounds(ZoneMethod.MAX)))
        assertEquals(HeartRateZone.Z2, heartRateZoneFor(148.0, bounds(ZoneMethod.HRR)))
        assertEquals(HeartRateZone.Z2, heartRateZoneFor(148.0, bounds(ZoneMethod.LTHR)))
    }

    @Test
    fun thresholdWithoutRestingHeartRateStartsZ1AtHalfIt() {
        val b = bounds(ZoneMethod.LTHR, anchors.copy(restingHr = null))
        assertEquals(84.5, b.lowerBpm[0], 1e-9)
    }

    @Test
    fun autoPicksThresholdForARunAndReserveOtherwise() {
        assertEquals(ZoneMethod.LTHR, resolveAuto(anchors, "run"))
        assertEquals(ZoneMethod.HRR, resolveAuto(anchors, "ride"))
        assertEquals(ZoneMethod.HRR, resolveAuto(anchors, null))
        assertEquals(ZoneMethod.LTHR, bounds(ZoneMethod.AUTO).method)
        assertEquals(ZoneMethod.HRR, bounds(ZoneMethod.AUTO, sport = "ride").method)
    }

    @Test
    fun autoSkipsAStaleThreshold() {
        val stale = anchors.copy(lthrDate = day.minusDays(LTHR_MAX_AGE_DAYS + 1))
        assertEquals(ZoneMethod.HRR, resolveAuto(stale, "run"))
        // Still selectable by hand.
        assertTrue(ZoneMethod.LTHR in availableMethods(stale, "run"))
    }

    @Test
    fun missingInputsFallBackOrGiveNothing() {
        val maxOnly = ZoneAnchors(maxHr = 190.0)
        assertEquals(ZoneMethod.MAX, resolveAuto(maxOnly, "run"))
        assertNull(zoneBounds(ZoneMethod.HRR, maxOnly, "run"))
        assertNull(zoneBounds(ZoneMethod.LTHR, maxOnly, "run"))
        assertEquals(listOf(ZoneMethod.MAX), availableMethods(maxOnly, "run"))
        assertNull(resolveAuto(ZoneAnchors(), "run"))
        assertNull(zoneBounds(ZoneMethod.AUTO, ZoneAnchors(), "run"))
        // A threshold is never applied outside running, and a resting rate at or above the max is no reserve.
        assertNull(zoneBounds(ZoneMethod.LTHR, anchors, "ride"))
        assertNull(zoneBounds(ZoneMethod.HRR, ZoneAnchors(maxHr = 60.0, restingHr = 60.0), "run"))
        assertEquals(ZoneMethod.of("hrr"), ZoneMethod.HRR)
        assertEquals(ZoneMethod.of("bogus"), ZoneMethod.AUTO)
        assertEquals(ZoneMethod.of(null), ZoneMethod.AUTO)
    }

    @Test
    fun breakdownSumsDurationsPerZoneFromStartingSample() {
        val b = bounds(ZoneMethod.MAX, ZoneAnchors(maxHr = 200.0))
        // 0-60s at 100bpm (Z1), 60-180s at 140bpm (Z3), 180-240s at 100bpm (Z1) again.
        val points = listOf(TimePoint(0, 100.0), TimePoint(60, 140.0), TimePoint(180, 100.0), TimePoint(240, 100.0))
        val breakdown = heartRateZoneBreakdown(points, b)
        assertEquals(240L, breakdown.sumOf { it.seconds })
        assertEquals(120L, breakdown.first { it.zone == HeartRateZone.Z1 }.seconds)
        assertEquals(120L, breakdown.first { it.zone == HeartRateZone.Z3 }.seconds)
    }

    @Test
    fun fewerThanTwoPointsReturnsEmpty() {
        val b = bounds(ZoneMethod.MAX)
        assertTrue(heartRateZoneBreakdown(listOf(TimePoint(0, 100.0)), b).isEmpty())
        assertTrue(heartRateZoneBreakdown(emptyList(), b).isEmpty())
    }

    // ---- anchors as of a date ----

    @Test
    fun robustMaxIgnoresOneSpikeAndImplausibleValues() {
        val maxima = listOf(
            day.minusDays(5) to 241.0, // implausible, dropped
            day.minusDays(30) to 205.0, // one optical spike
            day.minusDays(60) to 194.0,
            day.minusDays(90) to 190.0,
            day.plusDays(3) to 199.0, // after the date: not yet known
            day.minusMonths(30) to 201.0, // outside 24 months
        )
        assertEquals(194.0, robustMaxHr(maxima, day)!!, 0.0)
        assertEquals(190.0, robustMaxHr(listOf(day to 190.0), day)!!, 0.0)
        assertNull(robustMaxHr(listOf(day to 100.0), day))
    }

    @Test
    fun restingHeartRateIsTheMedianOfTheThirtyDaysToTheDate() {
        val daily = (0 until 60).map { i -> day.minusDays(i.toLong()) to (if (i < 30) 50.0 else 60.0) } +
            listOf(day.plusDays(1) to 40.0)
        assertEquals(50.0, restingHrAsOf(daily, day)!!, 0.0)
        // Thirty days earlier the window holds the older readings only.
        assertEquals(60.0, restingHrAsOf(daily, day.minusDays(30))!!, 0.0)
        // A gap: the window ends at the last day with a value.
        val gappy = listOf(day.minusDays(100) to 55.0, day.minusDays(101) to 57.0)
        assertEquals(56.0, restingHrAsOf(gappy, day)!!, 0.0)
        assertNull(restingHrAsOf(listOf(day.plusDays(1) to 50.0), day))
    }

    @Test
    fun anchorsAsOfAPastDateUseOnlyWhatWasKnownThen() {
        val maxima = listOf(LocalDate.of(2026, 6, 20) to 200.0, LocalDate.of(2026, 3, 1) to 192.0, LocalDate.of(2025, 9, 1) to 188.0)
        val resting = listOf(LocalDate.of(2025, 12, 31) to 56.0, LocalDate.of(2026, 9, 30) to 52.0)
        val lthr = listOf(LocalDate.of(2026, 1, 10) to 165.0, LocalDate.of(2026, 9, 20) to 169.0)
        val then = zoneAnchorsAsOf(LocalDate.of(2026, 2, 1), maxima, resting, lthr)
        assertEquals(188.0, then.maxHr!!, 0.0)
        assertEquals(56.0, then.restingHr!!, 0.0)
        assertEquals(165.0, then.lthr!!, 0.0)
        assertEquals(LocalDate.of(2026, 1, 10), then.lthrDate)
        val now = zoneAnchorsAsOf(day, maxima, resting, lthr)
        assertEquals(192.0, now.maxHr!!, 0.0)
        assertEquals(169.0, now.lthr!!, 0.0)
    }

    @Test
    fun captionNamesTheInputs() {
        val ltDay = day.minusDays(10).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        assertEquals("% of threshold · LTHR 169 (Zepp, $ltDay)", anchorsCaption(ZoneMethod.LTHR, anchors))
        assertTrue(anchorsCaption(ZoneMethod.HRR, anchors).contains("resting 53"))
        assertTrue(anchorsCaption(ZoneMethod.MAX, anchors).contains("max 196"))
    }

    @Test
    fun theChartOpensOnTheDefaultWhenItApplies() {
        assertEquals(ZoneMethod.MAX, initialZoneMethod(ZoneMethod.MAX, anchors, "run"))
        assertEquals(ZoneMethod.LTHR, initialZoneMethod(ZoneMethod.AUTO, anchors, "run"))
        // A threshold default on a ride falls back as AUTO would.
        assertEquals(ZoneMethod.HRR, initialZoneMethod(ZoneMethod.LTHR, anchors, "ride"))
        assertNull(initialZoneMethod(ZoneMethod.HRR, ZoneAnchors(), "run"))
    }
}
