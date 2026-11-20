package com.bioscan.fieldterminal.domain.bp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BloodPressureTest {
    private val d0 = LocalDate.of(2026, 11, 10)
    private fun r(day: Int, h: Int, m: Int, s: Int, d: Int) = BpReading(d0.plusDays(day.toLong()).atTime(h, m), s, d)

    /** A full AHA week: 2 readings morning and evening for [days] days, every reading [s]/[d] (day 1 at [s1]/[d1]). */
    private fun week(days: Int, s: Int, d: Int, s1: Int = s, d1: Int = d) = (0 until days).flatMap { day ->
        val (ss, dd) = if (day == 0) s1 to d1 else s to d
        listOf(r(day, 7, 0, ss, dd), r(day, 7, 1, ss, dd), r(day, 19, 0, ss, dd), r(day, 19, 1, ss, dd))
    }

    @Test
    fun categoriesFollowAccAha2017AndTheHigherOneWins() {
        assertEquals(BpCategory.NORMAL, categorize(119.0, 79.0))
        assertEquals(BpCategory.ELEVATED, categorize(125.0, 79.0))
        assertEquals(BpCategory.STAGE_1, categorize(118.0, 82.0))
        assertEquals(BpCategory.STAGE_1, categorize(130.0, 70.0))
        assertEquals(BpCategory.STAGE_2, categorize(128.0, 90.0))
        assertEquals(BpCategory.STAGE_2, categorize(140.0, 60.0))
    }

    @Test
    fun readingsMinutesApartAreOneSittingAndMorningEveningAreSlots() {
        val s = sittings(listOf(r(0, 7, 0, 120, 80), r(0, 7, 2, 118, 78), r(0, 19, 0, 124, 82)))
        assertEquals(2, s.size)
        assertEquals(119.0, s[0].systolic, 1e-9)
        assertEquals(BpSlot.MORNING, s[0].slot)
        assertEquals(BpSlot.EVENING, s[1].slot)
    }

    @Test
    fun theFirstDayIsDroppedFromTheHomeMean() {
        val all = series(sittings(week(7, 120, 76, s1 = 150, d1 = 95)))
        assertEquals(1, all.size)
        val w = all.single()
        assertTrue(w.usable)
        assertEquals(120.0, w.systolic!!, 1e-9)
        assertEquals(24, w.readingCount)
        assertEquals(BpCategory.ELEVATED, w.category)
    }

    @Test
    fun threeDaysAfterTheFirstAreNeededForAUsableMean() {
        assertFalse(series(sittings(week(3, 120, 76))).single().usable)
        assertTrue(series(sittings(week(4, 120, 76))).single().usable)
    }

    @Test
    fun aLongGapStartsANewSeries() {
        val early = week(7, 135, 85)
        val later = week(7, 118, 74).map { it.copy(at = it.at.plusDays(40)) }
        val all = series(sittings(early + later))
        assertEquals(2, all.size)
        assertEquals(BpCategory.STAGE_1, all[0].category)
        assertEquals(BpCategory.NORMAL, all[1].category)
    }

    @Test
    fun protocolTracksTodaysMorningAndEvening() {
        val readings = week(2, 120, 76) + r(2, 7, 0, 120, 76)
        val p = protocolToday(series(sittings(readings)), d0.plusDays(2))
        assertEquals(3, p.day)
        assertEquals(1, p.morningReadings)
        assertFalse(p.morningDone)
        assertFalse(p.eveningDone)
        assertFalse(p.complete)
        val none = protocolToday(series(sittings(readings)), d0.plusDays(30))
        assertNull(none.day)
    }

    @Test
    fun clinicVersusHomeNamesWhiteCoatAndMaskedPatterns() {
        val home = series(sittings(week(7, 122, 76))).single()
        assertEquals(ClinicHomePattern.WHITE_COAT, clinicHomePattern(ClinicBp(d0, 138.0, 84.0), home))
        val highHome = series(sittings(week(7, 134, 82))).single()
        assertEquals(ClinicHomePattern.MASKED, clinicHomePattern(ClinicBp(d0, 124.0, 76.0), highHome))
        assertEquals(ClinicHomePattern.CONSISTENT, clinicHomePattern(ClinicBp(d0, 120.0, 70.0), home))
        assertNull(clinicHomePattern(ClinicBp(d0, 120.0, 70.0), series(sittings(week(2, 120, 76))).single()))
    }

    @Test
    fun screensUseTheHomeMeanAndFallBackToTheClinic() {
        val clinic = ClinicBp(d0.minusDays(100), 131.0, 79.0)
        val withHome = bpForScreens(series(sittings(week(7, 121, 77))), clinic)!!
        assertTrue(withHome.fromHome)
        assertEquals(121.0, withHome.systolic, 1e-9)
        val noHome = bpForScreens(emptyList(), clinic)!!
        assertFalse(noHome.fromHome)
        assertNull(bpForScreens(emptyList(), null))
    }

    @Test
    fun overviewFlagsARecentCrisisReadingAndTheNextSeries() {
        val o = bpOverview(week(7, 121, 77) + r(6, 20, 0, 185, 100), null, d0.plusDays(6))
        assertEquals(185, o.latestCrisis!!.systolic)
        assertEquals(d0.plusDays(6).plusMonths(RECHECK_MONTHS), o.nextSeriesDue)
        assertTrue(o.protocol.complete)
        assertEquals(LocalDateTime.of(2026, 11, 10, 7, 0), o.sittings.first().start)
    }

    @Test
    fun clinicIsTheLatestDrawWithBothMarkers() {
        val c = com.bioscan.fieldterminal.data.latestClinic(
            listOf(
                Triple(1L, "SBP, Sitting", 128.0), Triple(1L, "DBP, Sitting", 82.0),
                Triple(2L, "SBP, Sitting", 121.0), // the newer draw has no diastolic: not used
            ),
            mapOf(1L to "2026-01-14", 2L to "2026-04-25"),
        )!!
        assertEquals(LocalDate.of(2026, 1, 14), c.date)
        assertEquals(82.0, c.diastolic, 1e-9)
    }
}
