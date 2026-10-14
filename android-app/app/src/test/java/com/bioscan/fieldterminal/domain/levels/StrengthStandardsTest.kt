package com.bioscan.fieldterminal.domain.levels

import com.bioscan.fieldterminal.domain.StrengthLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The standards tables themselves are tested in domain/StrengthStandardsTest; this covers the ladder position built on top.
class StrengthStandardsTest {

    @Test
    fun spellingVariantsReachTheSameStandard() {
        val a = liftTier("Standing Military Press", 60.0, 80.0)!!
        val b = liftTier("Overhead Press", 60.0, 80.0)!!
        assertEquals(a.thresholdsKg, b.thresholdsKg)
        assertNull(liftTier("Bicep Curl", 30.0, 80.0))
    }

    @Test
    fun tiersFollowBodyweightMultiples() {
        // 80 kg lifter, 120 kg squat e1RM = 1.5x -> between Intermediate (1.25) and Advanced (1.75).
        val r = liftTier("Barbell Squat", 120.0, 80.0)!!
        assertEquals(StrengthLevel.INTERMEDIATE, r.tier)
        assertEquals(StrengthLevel.ADVANCED, r.nextTier)
        assertEquals(1.75 * 80.0, r.nextKg!!, 1e-9)
        assertEquals(StandardKind.BODYWEIGHT_MULTIPLE, r.kind)
        assertEquals(0.5 + 0.25 * (1.5 - 1.25) / (1.75 - 1.25), r.ladderFraction, 1e-9)
    }

    @Test
    fun belowNoviceIsUntrainedAndEliteHasNoNext() {
        val low = liftTier("Barbell Deadlift", 40.0, 80.0)!!
        assertEquals(StrengthLevel.UNTRAINED, low.tier)
        assertEquals(StrengthLevel.NOVICE, low.nextTier)
        val elite = liftTier("Barbell Deadlift", 240.0, 80.0)!!
        assertEquals(StrengthLevel.ELITE, elite.tier)
        assertNull(elite.nextTier)
        assertNull(elite.nextKg)
        assertEquals(1.0, elite.ladderFraction, 1e-9)
    }

    @Test
    fun weightedPullUpUsesAddedLoadNotBodyweight() {
        val r = liftTier("Weighted Pull Ups", 30.0, null)!!
        assertEquals(StandardKind.ADDED_LOAD_KG, r.kind)
        assertEquals(StrengthLevel.INTERMEDIATE, r.tier)
    }

    @Test
    fun bodyweightLiftsNeedABodyWeightRatherThanAnAssumedOne() {
        assertNull(liftTier("Barbell Deadlift", 150.0, null))
    }

    @Test
    fun ladderIsMonotonicAndHitsTheTierPoints() {
        val t = listOf(80.0, 100.0, 140.0, 200.0)
        assertEquals(0.0, ladderFraction(t, 0.0), 1e-9)
        assertEquals(0.25, ladderFraction(t, 80.0), 1e-9)
        assertEquals(0.5, ladderFraction(t, 100.0), 1e-9)
        assertEquals(0.75, ladderFraction(t, 140.0), 1e-9)
        assertEquals(1.0, ladderFraction(t, 250.0), 1e-9)
        assertEquals(0.125, ladderFraction(t, 40.0), 1e-9)
    }
}
