package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarWorkoutTest {
    // Twelve runs at 6.0 min per km-effort, 8-14 km on the flat.
    private val runs = (0 until 12).map { RecentRun(8.0 + it % 7, 0.0, (8.0 + it % 7) * 6.0) }

    @Test
    fun aLongRunIsTimedFromItsDistanceNotItsPlaceholder() {
        val p = parseCalendarWorkout("🏃 Long Run — 24.0km (Day 1 of 2)", "Back-to-back long run — Day 1.", "run")
        assertEquals(24.0, p.distanceKm!!, 1e-9)
        val e = estimateCalendarWorkout(p, "run", runs)!!
        assertEquals(Intensity.LONG, e.classified.intensity)
        assertTrue(e.durationMin in 140.0..170.0) // 24 km at 6 min/km, slowed a little for length
        assertTrue(e.classified.reason, e.classified.reason.startsWith("24.0 km from the calendar"))
    }

    @Test
    fun theTitleDistanceWinsAndTheRouteGivesTheClimb() {
        val p = parseCalendarWorkout("🏃 Long Run — 16.0km (Day 2 of 2)", "Day 2, easy pace\nRoute: Hill loop (15.5km, +800m)\nGPX: https://drive.google.com/file/d/abc/view", "run")
        assertEquals(16.0, p.distanceKm!!, 1e-9)
        assertEquals(800.0, p.ascentM!!, 1e-9)
        assertTrue(p.easy && p.long)
        assertNotNull(p.gpxUrl)
        val e = estimateCalendarWorkout(p, "run", runs)!!
        // km-effort 24: well over the flat 16 km time.
        assertTrue(e.durationMin > 16 * 6.0 * 1.3)
        assertEquals(Intensity.LONG, e.classified.intensity)
    }

    @Test
    fun anEasyRunOfAnHourStaysEasy() {
        val p = parseCalendarWorkout("🏃 LSS — 9.0km (Riverside)", "Easy run, flat", "run")
        val e = estimateCalendarWorkout(p, "run", runs)!!
        assertEquals(Intensity.EASY, e.classified.intensity)
        assertTrue(e.durationMin in 50.0..65.0)
    }

    @Test
    fun repeatsAreHardAndTimedFromRounds() {
        val p = parseCalendarWorkout("🔁 800m Repeats — 5-8 rounds", "800m fast + 3-5min recovery, repeat", "run")
        assertEquals(6.5, p.rounds!!, 1e-9)
        assertEquals(800.0, p.repDistanceM!!, 1e-9)
        val e = estimateCalendarWorkout(p, "run", runs)!!
        assertEquals(Intensity.HARD, e.classified.intensity)
        // 15 min warm-up + 6.5 × (800 m at about 5.1 min/km + 4 min) + 10 min cool-down.
        assertTrue(e.durationMin.toString(), e.durationMin in 70.0..85.0)
        val q = parseCalendarWorkout("Track 6x400", null, "run")
        assertEquals(6.0, q.rounds!!, 1e-9)
        assertEquals(400.0, q.repDistanceM!!, 1e-9)
    }

    @Test
    fun strengthSetsAndLoadSetDurationAndClass() {
        val heavy = parseCalendarWorkout("🏋️ Strength — Wave 3 (85%)", "Overhead Press 4x4 @40kg\nFront Squat 4x3 @60kg\nWeighted Pull-Up 4x3 @20", "strength")
        assertEquals(12.0, heavy.sets!!, 1e-9)
        assertEquals(3, heavy.minReps)
        val e = estimateCalendarWorkout(heavy, "strength", runs)!!
        assertEquals(46.0, e.durationMin, 1e-9)
        assertEquals(Intensity.HARD, e.classified.intensity)
        val ranged = parseCalendarWorkout("🏋️ Strength", "Press 3-5x3 @85%\nSquat 3-5x3 @85%", "strength")
        assertEquals(8.0, ranged.sets!!, 1e-9)
        val light = parseCalendarWorkout("🏋️ Accessory", "Rows 3x12\nCurls 3x12", "strength")
        assertEquals(Intensity.MODERATE, estimateCalendarWorkout(light, "strength", runs)!!.classified.intensity)
    }

    @Test
    fun aDeloadIsShortAndEasyAndPlainTextFallsBack() {
        val d = estimateCalendarWorkout(parseCalendarWorkout("🔻 Deload — light activity", "walk/swim/cycle OK", "run"), "run", runs)!!
        assertEquals(Intensity.EASY, d.classified.intensity)
        assertEquals(30.0, d.durationMin, 1e-9)
        assertNull(estimateCalendarWorkout(parseCalendarWorkout("Gym", null, "run"), "run", runs))
    }

    @Test
    fun theCalendarEntryInThePlanCarriesTheParsedSession() {
        val zone = ZoneId.of("Asia/Taipei")
        val day = LocalDate.of(2026, 11, 7)
        val start = ZonedDateTime.of(day.atTime(7, 0), zone)
        val entry = CalendarSessionInput("e1", "🏃 Long Run — 24.0km", start, start.plusHours(1), "8", "Back-to-back long run")
        val history = DemandHistory(recentRuns = runs, weightKg = 70.0)
        val plannedDay = resolvePlannedDays(day, 1, emptyList(), emptyList(), listOf(entry), emptyList(), history, start.minusDays(2)).single()
        val item = plannedDay.items.single()
        assertEquals(Intensity.LONG, item.intensity)
        assertTrue(item.durationMin > 120)
        assertTrue(item.intensityReason.contains("from the calendar"))
    }
}
