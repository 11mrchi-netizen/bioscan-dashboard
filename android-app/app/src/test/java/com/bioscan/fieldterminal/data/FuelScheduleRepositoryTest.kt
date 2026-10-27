package com.bioscan.fieldterminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelScheduleRepositoryTest {
    @Test
    fun amountsAreReadInMilligrams() {
        assertEquals(200.0, toMilligrams(200.0, "mg")!!, 0.0)
        assertEquals(1500.0, toMilligrams(1.5, "g")!!, 0.0)
        assertEquals(0.5, toMilligrams(500.0, "mcg")!!, 1e-9)
        assertNull(toMilligrams(5.0, "IU"))
        assertNull(toMilligrams(5.0, null))
    }

    @Test
    fun ingredientsAreRecognisedByTheirWords() {
        assertEquals("caffeine", fuelNutrientOf("Caffeine Anhydrous"))
        assertEquals("sodium", fuelNutrientOf("Sodium (as sodium chloride)"))
        assertEquals("carbs", fuelNutrientOf("Total Carbohydrate"))
        assertNull(fuelNutrientOf("Citrulline Malate"))
    }
}
