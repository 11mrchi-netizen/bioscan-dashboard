package com.bioscan.fieldterminal.domain.levels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StrengthStandardsTest {

    @Test
    fun spellingVariantsReachTheSameStandard() {
        assertEquals("Overhead Press", liftStandardFor("Standing Military Press")?.name)
        assertEquals("Bench Press", liftStandardFor("Chest Press")?.name)
        assertEquals("Front Squat", liftStandardFor("Front Barbell Squat")?.name)
        assertEquals("Weighted Pull-up", liftStandardFor("Pull Up (Weighted)")?.name)
        assertNull(liftStandardFor("Bicep Curl"))
    }

    @Test
    fun tiersFollowBodyweightMultiples() {
        // 80 kg lifter, 120 kg squat e1RM = 1.5x -> between Intermediate (1.25) and Advanced (1.75).
        val r = liftTier("Barbell Squat", 120.0, 80.0)!!
        assertEquals(StrengthTier.INTERMEDIATE, r.tier)
        assertEquals(StrengthTier.ADVANCED, r.nextTier)
        assertEquals(1.75, r.nextThreshold!!, 1e-9)
        assertEquals(0.5 + 0.25 * (1.5 - 1.25) / (1.75 - 1.25), r.ladderFraction, 1e-9)
    }

    @Test
    fun belowNoviceIsUntrainedAndEliteHasNoNext() {
        assertEquals(StrengthTier.UNTRAINED, liftTier("Deadlift", 40.0, 80.0)!!.tier)
        val elite = liftTier("Deadlift", 240.0, 80.0)!!
        assertEquals(StrengthTier.ELITE, elite.tier)
        assertNull(elite.nextTier)
        assertEquals(1.0, elite.ladderFraction, 1e-9)
    }

    @Test
    fun weightedPullUpUsesAddedLoadNotBodyweight() {
        val r = liftTier("Weighted Pull Ups", 30.0, null)
        assertNotNull(r)
        assertEquals(StrengthTier.INTERMEDIATE, r!!.tier)
    }

    @Test
    fun bodyweightLiftsNeedABodyWeightRatherThanAnAssumedOne() {
        assertNull(liftTier("Deadlift", 150.0, null))
        assertNull(liftTier("Deadlift", 150.0, 0.0))
    }

    @Test
    fun ladderIsMonotonicAndHitsTheTierPoints() {
        val s = liftStandardFor("Squat")!!
        assertEquals(0.0, ladderFraction(s, 0.0), 1e-9)
        assertEquals(0.25, ladderFraction(s, s.novice), 1e-9)
        assertEquals(0.5, ladderFraction(s, s.intermediate), 1e-9)
        assertEquals(0.75, ladderFraction(s, s.advanced), 1e-9)
        assertEquals(1.0, ladderFraction(s, s.elite + 5), 1e-9)
    }
}
