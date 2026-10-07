package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementStackTest {

    private fun ing(
        canonical: String?,
        nutrient: String? = canonical,
        amount: Double,
        unit: String = "mg",
        elemental: Boolean = true,
    ) = StackIngredient(canonical, nutrient, amount, unit, elemental)

    private fun entry(
        id: Long,
        name: String,
        vararg ingredients: StackIngredient,
        everyNDays: Int? = null,
        asNeeded: Boolean = false,
        confidence: String? = "manual",
    ) = StackEntry(id, name, everyNDays, asNeeded, confidence, ingredients.toList())

    private fun StackAnalysis.kinds() = findings.map { it.kind }
    private fun StackAnalysis.exposure(key: String) = exposures.first { it.nutrientKey == key }

    @Test
    fun sameCanonicalIngredientInTwoSupplementsIsAnOverlap() {
        val r = analyzeStack(listOf(
            entry(1, "Tart Cherry Concentrate", ing("tart_cherry", null, 150.0, "ml", false)),
            entry(2, "Tart Cherry Extract", ing("tart_cherry", null, 1200.0, "mg", false)),
            entry(3, "Creatine", ing("creatine", amount = 5000.0)),
        ))
        val overlap = r.findings.single { it.kind == StackFindingKind.OVERLAP }
        assertEquals("tart_cherry", overlap.subject)
        assertEquals(listOf("Tart Cherry Concentrate", "Tart Cherry Extract"), overlap.supplements)
    }

    @Test
    fun singleSourceIsNotAnOverlap() {
        val r = analyzeStack(listOf(entry(1, "Creatine", ing("creatine", amount = 5.0, unit = "g"))))
        assertFalse(StackFindingKind.OVERLAP in r.kinds())
    }

    @Test
    fun exposureSumsAcrossSupplementsInReferenceUnit() {
        // vitamin_c reference unit is mg; 500 mg + 0.5 g = 1000 mg/day.
        val r = analyzeStack(listOf(
            entry(1, "C one", ing("vitamin_c", amount = 500.0)),
            entry(2, "C two", ing("vitamin_c", amount = 0.5, unit = "g")),
        ))
        assertEquals(1000.0, r.exposure("vitamin_c").amountPerDay, 0.001)
        assertEquals("mg", r.exposure("vitamin_c").unit)
        assertEquals(listOf("C one", "C two"), r.exposure("vitamin_c").contributors)
    }

    @Test
    fun intervalSupplementIsAveragedPerDay() {
        // 36 mg iron every 3 days = 12 mg/day, under the 45 mg UL.
        val r = analyzeStack(listOf(entry(1, "Iron", ing("iron", amount = 36.0), everyNDays = 3)))
        assertEquals(12.0, r.exposure("iron").amountPerDay, 0.001)
        assertFalse(StackFindingKind.ABOVE_UPPER_LIMIT in r.kinds())
    }

    @Test
    fun aboveUpperLimitIsReportedWithValues() {
        // zinc UL is 40 mg in NUTRIENT_REFERENCE; stack supplies 50.
        val r = analyzeStack(listOf(entry(1, "Zinc Picolinate", ing("zinc", amount = 50.0))))
        val f = r.findings.single { it.kind == StackFindingKind.ABOVE_UPPER_LIMIT }
        assertTrue(f.message.contains("50.0 mg/day of Zinc"))
        assertTrue(f.message.contains("40.0 mg"))
    }

    @Test
    fun vitaminDInIuIsComparedInIuAndFlaggedAboveItsLimit() {
        val r = analyzeStack(listOf(entry(1, "D3", ing("vitamin_d", amount = 5000.0, unit = "IU"))))
        assertEquals("IU", r.exposure("vitamin_d").unit)
        assertTrue(StackFindingKind.ABOVE_UPPER_LIMIT in r.kinds())
    }

    @Test
    fun nonConvertibleUnitIsFlaggedAndExcludedNeverGuessed() {
        // A reference in mg cannot absorb a "pill" amount.
        val r = analyzeStack(listOf(entry(1, "B complex", ing("iron", amount = 1.0, unit = "pill"))))
        assertTrue(StackFindingKind.UNIT_NOT_COMPARABLE in r.kinds())
        assertTrue(r.exposures.isEmpty())
    }

    @Test
    fun compoundOnlyAmountMarksExposureApproximate() {
        val r = analyzeStack(listOf(entry(1, "Magnesium Complex", ing("magnesium", amount = 400.0, elemental = false))))
        assertTrue(r.exposure("magnesium").approximate)
        assertTrue(StackFindingKind.COMPOUND_AMOUNT_USED in r.kinds())
    }

    @Test
    fun keyWithReferenceButNoNutrientMappingIsFlagged() {
        // canonical vitamin_c exists in the reference, but nutrient_key is null.
        val r = analyzeStack(listOf(entry(1, "Vitamin C (Swanson)", ing("vitamin_c", nutrient = null, amount = 500.0))))
        assertTrue(StackFindingKind.UNMAPPED_NUTRIENT in r.kinds())
        assertTrue(r.exposures.isEmpty())
    }

    @Test
    fun herbalWithNoReferenceIsNotFlaggedAsUnmapped() {
        val r = analyzeStack(listOf(entry(1, "Nettle", ing("stinging_nettle_root", nutrient = null, amount = 500.0))))
        assertFalse(StackFindingKind.UNMAPPED_NUTRIENT in r.kinds())
    }

    @Test
    fun asNeededSupplementsHaveNoPerDayExposure() {
        val r = analyzeStack(listOf(entry(1, "Glycine", ing("glycine", amount = 3000.0), asNeeded = true)))
        assertTrue(r.exposures.isEmpty())
    }

    @Test
    fun lowConfidenceCompositionIsFlaggedPerSupplement() {
        val r = analyzeStack(listOf(
            entry(1, "Inferred", ing("zinc", amount = 10.0), confidence = "low"),
            entry(2, "Entered", ing("copper", amount = 1.0), confidence = "manual"),
        ))
        val f = r.findings.filter { it.kind == StackFindingKind.LOW_CONFIDENCE_COMPOSITION }
        assertEquals(listOf("Inferred"), f.map { it.subject })
    }

    @Test
    fun resultCarriesProvenance() {
        val p = analyzeStack(emptyList()).provenance
        assertEquals("computed", p.origin)
        assertEquals("supplement_stack", p.algorithm)
        assertEquals("1", p.algorithmVersion)
    }
}
