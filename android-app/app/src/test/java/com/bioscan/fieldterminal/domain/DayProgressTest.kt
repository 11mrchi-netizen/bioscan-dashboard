package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class DayProgressTest {
    @Test
    fun theExpectedShareFollowsTheEatingWindow() {
        assertEquals(0.0, expectedShareAt(LocalTime.of(6, 30)), 1e-9)
        assertEquals(6.0 / 14.0, expectedShareAt(LocalTime.of(13, 0)), 1e-9)
        assertEquals(1.0, expectedShareAt(LocalTime.of(22, 0)), 1e-9)
    }

    @Test
    fun aMorningIsNotShortForEatingAMorningsWorth() {
        // 10:00 expects 3/14 of 2,800 kcal = 600; 520 is above 80 % of that.
        val kcal = progressItem("Calories", "kcal", 520.0, 2800.0, LocalTime.of(10, 0))
        assertEquals(ProgressMark.ON_TRACK, kcal.mark)
        assertEquals(2280.0, kcal.toGo!!, 1e-9)
    }

    @Test
    fun behindThePaceIsShortAndListed() {
        val protein = progressItem("Protein", "g", 40.0, 160.0, LocalTime.of(18, 0))
        val carbs = progressItem("Carbs", "g", 300.0, 380.0, LocalTime.of(18, 0))
        assertEquals(ProgressMark.SHORT, protein.mark)
        assertEquals(ProgressMark.ON_TRACK, carbs.mark)
        assertEquals(listOf("Protein"), shortfalls(listOf(protein, carbs)).map { it.label })
    }

    @Test
    fun overIsPastTheDayOrItsRange() {
        assertEquals(ProgressMark.OVER, progressItem("Fat", "g", 100.0, 85.0, LocalTime.of(12, 0)).mark)
        assertEquals(ProgressMark.ON_TRACK, progressItem("Fat", "g", 90.0, 85.0, LocalTime.of(12, 0)).mark)
        // Water reads against its own range: over only past the top of it.
        assertEquals(ProgressMark.ON_TRACK, progressItem("Water", "ml", 3200.0, 2500.0, LocalTime.of(20, 0), upper = 3500.0).mark)
        assertEquals(ProgressMark.OVER, progressItem("Water", "ml", 3600.0, 2500.0, LocalTime.of(20, 0), upper = 3500.0).mark)
    }

    @Test
    fun noTargetIsSaidSo() {
        val item = progressItem("Fat", "g", 30.0, null, LocalTime.NOON)
        assertEquals(ProgressMark.NO_TARGET, item.mark)
        assertNull(item.toGo)
        assertEquals(emptyList<DayProgressItem>(), shortfalls(listOf(item)))
    }
}
