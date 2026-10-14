package com.bioscan.fieldterminal.domain.levels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SkillLevelTest {

    private val asOf = LocalDate.of(2026, 10, 14)

    @Test
    fun runningUsesTheAgeSexTablePercentileWhenOneFits() {
        val level = runningSkillLevel(vo2max = 52.0, vo2Percentile = 75.0, recentPaceMinPerKm = null, paceHistoryMinPerKm = emptyList())
        assertEquals(75.0, level.position!!, 1e-9)
        assertEquals("Strong", level.label)
        assertEquals(LevelBasis.AGE_NORM, level.basis)
        assertFalse(level.provisional)
    }

    @Test
    fun runningFallsBackToOwnHistoryAndIsProvisional() {
        val history = listOf(6.0, 5.8, 5.6, 5.4)
        val level = runningSkillLevel(vo2max = null, vo2Percentile = null, recentPaceMinPerKm = 5.5, paceHistoryMinPerKm = history)
        // 3 of 4 historical runs were slower than 5.5 min/km.
        assertEquals(75.0, level.position!!, 1e-9)
        assertEquals(LevelBasis.PERSONAL, level.basis)
        assertTrue(level.provisional)
        assertEquals("Strong", level.label)
    }

    @Test
    fun runningWithVo2maxButNoSexExplainsWhatIsMissing() {
        val level = runningSkillLevel(52.0, null, null, emptyList())
        assertNull(level.position)
        assertTrue(level.reason!!.contains("sex"))
        assertEquals("Not enough data yet", level.label)
    }

    @Test
    fun strengthShowsFromOneLiftAndIsProvisionalUnderThree() {
        val one = listOfNotNull(liftTier("Barbell Squat", 120.0, 80.0))
        val level = strengthSkillLevel(one, bodyWeightMissing = false)
        assertNotNull(level.position)
        assertTrue(level.provisional)
        assertEquals(LevelBasis.STANDARD, level.basis)

        val three = listOfNotNull(
            liftTier("Barbell Squat", 120.0, 80.0), liftTier("Barbell Deadlift", 160.0, 80.0), liftTier("Chest Press", 80.0, 80.0),
        )
        assertFalse(strengthSkillLevel(three, false).provisional)
    }

    @Test
    fun strengthLevelIsTheMedianLiftAndTheWeakerMiddleDecidesWhenEven() {
        // ladder fractions: squat 1.5x = 0.55, deadlift 1.0x = 0.25, chest press 1.0x = 0.5+ ... pick two lifts for the even case.
        val squat = liftTier("Barbell Squat", 120.0, 80.0)!!        // 1.5x  -> between intermediate and advanced
        val deadlift = liftTier("Barbell Deadlift", 80.0, 80.0)!!   // 1.0x  -> novice
        val level = strengthSkillLevel(listOf(squat, deadlift), bodyWeightMissing = false)
        assertEquals(deadlift.ladderFraction * 100.0, level.position!!, 1e-9)
    }

    @Test
    fun strengthWithoutRatedLiftsSaysWhy() {
        assertTrue(strengthSkillLevel(emptyList(), bodyWeightMissing = true).reason!!.contains("body weight"))
        assertTrue(strengthSkillLevel(emptyList(), bodyWeightMissing = false).reason!!.contains("Log sets"))
    }

    @Test
    fun conditioningComparesTheLastFourWeeksWithYourOwnBest() {
        // Best rolling 28 days: 8 sessions in the older block; the latest 28 days hold 2 -> 25%.
        val older = (0 until 8).map { asOf.minusDays(60L + it * 2) }
        val recent = listOf(asOf.minusDays(3), asOf.minusDays(10))
        val level = conditioningSkillLevel(older + recent, asOf)
        assertEquals(25.0, level.position!!, 1e-9)
        assertEquals("Below usual", level.label)
        assertEquals(LevelBasis.PERSONAL, level.basis)
        assertFalse(level.provisional) // 10 sessions in the 90-day window
    }

    @Test
    fun conditioningAtYourBestReadsAtYourBestAndFewSessionsAreProvisional() {
        val level = conditioningSkillLevel(listOf(asOf.minusDays(2), asOf.minusDays(9), asOf.minusDays(16)), asOf)
        assertEquals(100.0, level.position!!, 1e-9)
        assertEquals("At your best", level.label)
        assertTrue(level.provisional)
    }

    @Test
    fun conditioningWithNothingInNinetyDaysIsUnavailable() {
        val level = conditioningSkillLevel(listOf(asOf.minusDays(200)), asOf)
        assertNull(level.position)
        assertNotNull(level.reason)
    }

    @Test
    fun labelsUseTheSameCutoffsAsPercentileBands() {
        assertEquals("Elite", levelLabel(90.0, LevelBasis.AGE_NORM))
        assertEquals("Strong", levelLabel(60.0, LevelBasis.STANDARD))
        assertEquals("Solid", levelLabel(40.0, LevelBasis.STANDARD))
        assertEquals("Developing", levelLabel(10.0, LevelBasis.STANDARD))
        assertEquals("Building", levelLabel(9.9, LevelBasis.STANDARD))
        assertEquals("Typical", levelLabel(50.0, LevelBasis.PERSONAL))
    }
}
