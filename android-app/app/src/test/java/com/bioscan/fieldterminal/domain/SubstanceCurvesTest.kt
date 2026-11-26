package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.SubstanceCurves.Dose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// The same cases as console/src/lib/substanceCurves.test.ts: the web and the phone must draw the same curves.
class SubstanceCurvesTest {
    private val sc = SubstanceCurves

    @Test
    fun caffeinePeaksAboutFiftyMinutesAfterTheDoseAndHalvesEveryFiveHours() {
        val d = listOf(Dose(600.0, 100.0))
        assertEquals(0.0, sc.caffeineAt(d, 600.0), 1e-12)
        val peak = sc.caffeinePeakMin()
        assertTrue("peak $peak", peak in 40.0..65.0)
        assertTrue(sc.caffeineAt(d, 600 + peak) in 85.0..100.0)
        val a = sc.caffeineAt(d, 600.0 + 6 * 60)
        val b = sc.caffeineAt(d, 600.0 + 11 * 60)
        assertEquals(0.5, b / a, 0.01)
    }

    @Test
    fun dosesAddUpAndTheEveningBeforeCarriesOver() {
        val carried = sc.caffeineAt(listOf(Dose(-120.0, 200.0)), 0.0)
        assertTrue("carried $carried", carried in 100.0..160.0)
        val two = sc.caffeineAt(listOf(Dose(0.0, 100.0), Dose(0.0, 100.0)), 120.0)
        assertEquals(2 * sc.caffeineAt(listOf(Dose(0.0, 100.0)), 120.0), two, 1e-9)
    }

    @Test
    fun whenCaffeineFallsBelowTheBedtimeLine() {
        val t = sc.caffeineBelowAt(listOf(Dose(11 * 60.0, 100.0)), 50.0, 0.0)
        assertNotNull(t)
        assertTrue("t $t", t!! in (11 * 60.0 + 5 * 60)..(11 * 60.0 + 7 * 60))
        assertEquals(0.0, sc.caffeineBelowAt(emptyList(), 50.0, 0.0)!!, 1e-9)
    }

    @Test
    fun alcoholRisesThenFallsAtSevenGramsAnHourAndIsGone() {
        val s = sc.alcoholSeries(listOf(Dose(20 * 60.0, 28.0)), 19 * 60.0, 30 * 60.0)
        val peak = s.maxOf { it.v }
        assertTrue("peak $peak", peak in 15.0..28.0)
        fun at(t: Double) = s.first { it.t == t }.v
        assertEquals(7.0, at(22 * 60.0 + 30) - at(23 * 60.0 + 30), 0.3)
        assertEquals(0.0, at(30 * 60.0), 1e-9)
        assertEquals(0.0, at(19 * 60.0), 1e-9)
    }

    @Test
    fun aDrinkTheEveningBeforeIsClearedByMorning() {
        assertEquals(0.0, sc.alcoholSeries(listOf(Dose(-180.0, 14.0)), 0.0, 360.0).first().v, 1e-9)
    }

    @Test
    fun usualBedtimeIsTheMedianInEachNightsOwnZone() {
        // 23:57, 00:33 and 23:00 local in UTC+8 -> 1437, 1473, 1380 -> median 1437
        val nights = listOf(
            "2026-11-25T15:57:00+00:00" to 480, "2026-11-24T16:33:00+00:00" to 480, "2026-11-23T15:00:00+00:00" to 480,
        )
        assertEquals(1437.0, sc.usualBedtimeMin(nights)!!, 1e-9)
        assertNull(sc.usualBedtimeMin(nights.take(1)))
    }

    @Test
    fun supplementsByKeyAndUnit() {
        assertEquals("l-theanine", sc.compoundFor("Theanine")?.key)
        assertNull(sc.compoundFor("magnesium"))
        val gly = sc.compoundFor("glycine")!!
        assertEquals(3.0, sc.amountIn(gly, 3.0, "Gram(s)")!!, 1e-9)
        assertEquals(3.0, sc.amountIn(gly, 3000.0, "mg")!!, 1e-9)
        val mel = sc.compoundFor("melatonin")!!
        assertEquals(0.5, sc.amountIn(mel, 500.0, "mcg")!!, 1e-9)
        assertNull(sc.amountIn(mel, 1.0, "IU"))
        val s = sc.compoundSeries(mel, listOf(Dose(0.0, 1.0)), 0.0, 480.0)
        assertTrue(s.first { it.t == 60.0 }.v in 0.4..0.8)
        assertTrue(s.first { it.t == 420.0 }.v < 0.01)
    }

    @Test
    fun loggedWallClockMinutesFromTheDayMidnight() {
        val day = LocalDate.parse("2026-11-24")
        assertEquals(636.0, sc.minutesFromDay("2026-11-24T10:36:22.508766+00:00", day)!!, 1e-9)
        assertEquals(-31.0, sc.minutesFromDay("2026-11-23T23:29:00+00:00", day)!!, 1e-9)
        assertEquals("23:57", sc.clockLabel(1437.0))
    }
}
