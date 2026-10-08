package com.bioscan.fieldterminal.domain.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MovementKeyTest {
    @Test
    fun spellingsOfOneMovementShareAKey() {
        val weighted = listOf("Pull Up (Weighted)", "Weighted Pull-up", "Weighted Pull Ups", "weighted pullups").map(::movementKey).toSet()
        assertEquals(1, weighted.size)
        assertEquals(movementKey("Overhead Press"), movementKey("Standing Military Press"))
        assertEquals(movementKey("Front Squat"), movementKey("Front Barbell Squat"))
        assertEquals(movementKey("Squat"), movementKey("Barbell Squat"))
        assertEquals(movementKey("Bench Press"), movementKey("Chest Press"))
        assertEquals(movementKey("Incline Bench Press"), movementKey("Inclined Chest Press (ICP)"))
    }

    // Names the Zepp watch writes (its own exercise catalog) against the spellings used elsewhere.
    @Test
    fun zeppCatalogNamesFoldOntoLibraryAndPlanNames() {
        assertEquals(movementKey("Standing Military Press"), movementKey("Standing Barbell Press"))
        assertEquals(movementKey("Overhead Press"), movementKey("Standing Barbell Press"))
        assertEquals(movementKey("Front Barbell Squat"), movementKey("Barbell Front Squat"))
        assertEquals(movementKey("Pullups"), movementKey("Pull Up"))
        // The watch logs the weighted pull-up as "Pull Up" with a load; the plan names it weighted.
        assertEquals(looseMovementKey("Weighted Pull-up"), looseMovementKey("Pull Up"))
        assertNotEquals(movementKey("Weighted Pull-up"), movementKey("Pull Up"))
    }

    @Test
    fun differentMovementsKeepSeparateKeys() {
        assertNotEquals(movementKey("Squat"), movementKey("Front Squat"))
        assertNotEquals(movementKey("Pull-up"), movementKey("Weighted Pull-up"))
        assertNotEquals(movementKey("Clean and Press"), movementKey("Overhead Press"))
        assertNotEquals(movementKey("Deadlift"), movementKey("Rack Pull"))
    }
}
