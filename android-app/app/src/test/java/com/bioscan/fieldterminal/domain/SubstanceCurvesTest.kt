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

    @Test
    fun regimenAmountsInABaseUnitAndDosesFromTheLogOrFromServings() {
        assertEquals(2.5, sc.massMg(2.5, "mg")!!, 1e-9)
        assertEquals(0.5, sc.massMg(500.0, "mcg")!!, 1e-9)
        assertNull(sc.massMg(1.0, "tablet"))
        assertEquals(SubstanceCurves.Qty(250.0, SubstanceCurves.QtyBase.IU), sc.quantity(250.0, "IU"))
        assertEquals(SubstanceCurves.Qty(10.0, SubstanceCurves.QtyBase.UNITS), sc.quantity(10.0, "units"))
        val mg5 = SubstanceCurves.Qty(5.0, SubstanceCurves.QtyBase.MG)
        assertEquals(2.5, sc.doseQty(0.5, "tablet", 0.5, mg5)!!.v, 1e-9)
        assertEquals(2.5, sc.doseQty(2.5, "mg", 1.0, null)!!.v, 1e-9)
        assertEquals(0.25, sc.doseQty(250.0, "mcg", 1.0, null)!!.v, 1e-9)
        assertNull(sc.doseQty(0.5, "tablet", 0.5, null))
        assertEquals("250 mcg", sc.qtyText(0.25, SubstanceCurves.QtyBase.MG))
        assertEquals("2.5 mg", sc.qtyText(2.5, SubstanceCurves.QtyBase.MG))
        assertEquals("40 IU", sc.qtyText(40.0, SubstanceCurves.QtyBase.IU))
        assertEquals(4L, sc.lookbackDays(17.5))
        assertEquals(1L, sc.lookbackDays(0.75))
        assertEquals(40L, sc.lookbackDays(8 * 24.0))
    }

    private fun peakOf(halfLifeH: Double, tmaxH: Double): Double {
        val ka = sc.kaFromTmax(halfLifeH, tmaxH)
        val d = listOf(Dose(0.0, 1.0))
        return (1..(60 * 24 * 20)).maxByOrNull { sc.oneCompartmentAt(d, it.toDouble(), halfLifeH, ka) }!! / 60.0
    }

    @Test
    fun theAbsorptionRateIsSolvedSoTheCurvePeaksWhereEntered() {
        assertEquals(2.0, peakOf(17.5, 2.0), 0.05)
        assertEquals(1.0, peakOf(4.5, 1.0), 0.05)
        assertEquals(0.5, peakOf(0.75, 0.5), 0.05)
        assertEquals(60.0, peakOf(8 * 24.0, 2.5 * 24), 1.0)
        // a peak later than 1/ke (a slow-release product with a short half-life) still peaks where entered
        assertEquals(8.0, peakOf(4.0, 8.0), 0.1)
        // the 17.5 h tablet carries into the next day: about 40 % of the dose is left 24 h later
        assertTrue(sc.oneCompartmentAt(listOf(Dose(0.0, 1.0)), 26 * 60.0, 17.5, sc.kaFromTmax(17.5, 2.0)) > 0.35)
    }
}
