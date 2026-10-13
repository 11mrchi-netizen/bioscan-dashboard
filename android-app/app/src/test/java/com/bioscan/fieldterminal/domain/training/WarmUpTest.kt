package com.bioscan.fieldterminal.domain.training

import org.junit.Assert.assertEquals
import org.junit.Test

class WarmUpTest {
    @Test
    fun barbellRampFollowsTheLiftersRule() {
        assertEquals(emptyList<Pair<Int, Double>>(), warmupSets(20.0))
        assertEquals(listOf(10 to 20.0), warmupSets(30.0))                     // 150% of the bar: bar only
        assertEquals(listOf(10 to 20.0, 3 to 70.0), warmupSets(100.0))         // over 150%: plus 70% x 3
        assertEquals(listOf(10 to 20.0, 3 to 42.5), warmupSets(60.0))          // 42 rounds to the 2.5 kg step
    }

    @Test
    fun weightedPullUpStartsFromBodyweight() {
        assertEquals(emptyList<Pair<Int, Double>>(), warmupSets(0.0, bodyweight = true))
        assertEquals(listOf(10 to 0.0), warmupSets(15.0, bodyweight = true))
        assertEquals(listOf(10 to 0.0, 3 to 25.0), warmupSets(35.0, bodyweight = true))
    }

    // The zepp-training function maps these keys to Zepp exercises (template.ts BY_MOVEMENT).
    @Test
    fun movementKeysMatchTheFunctionsExerciseMap() {
        assertEquals("press", movementKey("Standing Barbell Press"))
        assertEquals("overhead press", movementKey("Overhead Press"))
        assertEquals("front squat", movementKey("Barbell Front Squat"))
        assertEquals("pull up weighted", movementKey("Weighted Pull-up"))
        assertEquals("deadlift romanian", movementKey("Romanian Deadlift"))
    }
}
