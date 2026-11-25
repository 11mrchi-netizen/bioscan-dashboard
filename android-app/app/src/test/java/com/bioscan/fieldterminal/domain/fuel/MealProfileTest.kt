package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealProfileTest {
    private fun profile(
        calories: Double? = 450.0, protein: Double? = 45.0, grams: Double? = 320.0, ed: Double? = 1.22, pd: Double? = 10.0,
        cd: Double? = 10.0, fd: Double? = 6.7, gl: Double? = 29.0, ff: Double? = 2.6, upf: Double? = 0.0,
        groups: List<String> = listOf("poultry", "refined_grains", "vegetables"), tags: List<String> = emptyList(),
    ) = MealProfile(1, calories, protein, grams, ed, pd, cd, fd, gl, ff, upf, groups, tags, false)

    private fun List<MealIndexLine>.of(i: MealIndex) = first { it.index == i }

    @Test fun everyIndexIsItsOwnLineWithNoCombinedScore() {
        val lines = mealIndexLines(profile(upf = 40.0), weightKg = 75.0)
        assertEquals(MealIndex.entries.toList(), lines.map { it.index })
    }

    @Test fun bandsReadInWords() {
        val lines = mealIndexLines(profile(), weightKg = 75.0)
        assertEquals("light", lines.of(MealIndex.ENERGY_DENSITY).band)
        assertEquals("1.2 kcal/g", lines.of(MealIndex.ENERGY_DENSITY).value)
        assertEquals("full dose", lines.of(MealIndex.PROTEIN).band)
        assertEquals("moderate carb", lines.of(MealIndex.CARBS).band) // 10 g per 100 kcal = 40% of the energy
        assertEquals("low fibre", lines.of(MealIndex.FIBER).band)
        assertEquals("high", lines.of(MealIndex.GLYCEMIC_LOAD).band)
        assertEquals("average", lines.of(MealIndex.FULLNESS).band)
        assertTrue(lines.of(MealIndex.GLYCEMIC_LOAD).value.endsWith("est."))
    }

    @Test fun proteinDoseFollowsBodyWeight() {
        assertEquals(22.5, proteinDoseG(75.0), 1e-9)
        assertEquals(25.0, proteinDoseG(null), 1e-9)
        assertEquals("partial dose", mealIndexLines(profile(protein = 15.0), 75.0).of(MealIndex.PROTEIN).band)
        assertEquals("little", mealIndexLines(profile(protein = 5.0), 75.0).of(MealIndex.PROTEIN).band)
    }

    @Test fun missingInputsLeaveTheLineOutInsteadOfGuessing() {
        val lines = mealIndexLines(profile(grams = null, ed = null, ff = null, fd = null, upf = null), null)
        assertFalse(lines.any { it.index == MealIndex.ENERGY_DENSITY || it.index == MealIndex.FULLNESS || it.index == MealIndex.FIBER })
        assertFalse(lines.any { it.index == MealIndex.PROCESSED })
        // A drink of a few kcal has no protein line.
        assertFalse(mealIndexLines(profile(calories = 3.0, protein = 0.0, pd = null, cd = null), null).any { it.index == MealIndex.PROTEIN })
    }

    @Test fun groupsAndTagsInWords() {
        val p = profile(groups = listOf("other", "sweets", "dairy"), tags = listOf("dairy", "lactose", "gluten", "ultra_processed"))
        assertEquals(listOf("Dairy", "Sweets", "Other"), mealGroupLabels(p))
        assertEquals(listOf("Gluten", "Lactose"), mealTagLabels(p))
    }
}
