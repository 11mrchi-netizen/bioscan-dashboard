package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegimenFormsTest {
    @Test
    fun theFormFixesTheRouteUnlessItIsOpen() {
        assertEquals("oral", routeFor("capsule", "subcutaneous"))
        assertEquals("nasal", routeFor("nasal_spray", null))
        assertEquals("sublingual", routeFor("liquid", "sublingual"))
        assertEquals("oral", routeFor("liquid", "intramuscular")) // not offered for a liquid: its default
        assertEquals("subcutaneous", routeFor("injection", null))
        assertEquals("intramuscular", routeFor("injection", "intramuscular"))
    }

    @Test
    fun loggingStepsFollowTheForm() {
        assertEquals(0.5, doseStep("softgel", false, 2.0), 1e-9) // one softgel of a 2-softgel dose
        assertEquals(1.0, doseStep("tablet", true, 0.5), 1e-9)   // half a tablet of a half-tablet dose
        assertEquals(2.0, doseStep("tablet", false, 0.5), 1e-9)  // unscored: a whole tablet only
        assertEquals(SUPPLEMENT_QTY_STEP, doseStep("powder", false, 5.0), 1e-9)
        assertEquals("3 softgels", unitsWords(1.0, 3.0, "softgel"))
        assertEquals("½ tablet", unitsWords(1.0, 0.5, "tablet"))
    }

    @Test
    fun labelServingsConvertToTheDose() {
        assertEquals(4.0, servingsPerDose(4.0, "capsule", 1.0, "Veggie Capsule(s)")!!, 1e-9)
        assertEquals(1.0, servingsPerDose(3.0, "capsule", 3.0, "Capsules")!!, 1e-9)
        assertEquals(1.0, servingsPerDose(5.0, "g", 5.0, "Gram(s)")!!, 1e-9)
        assertNull(servingsPerDose(1.0, "softgel", 2.0, "Tablets"))
        val perDose = doseIngredients(listOf(DoseIngredient("Fish oil", emptySet(), 1000.0, "mg")), 3.0)
        assertEquals(3000.0, perDose.single().amount, 1e-9)
        assertEquals("Tadalafil", activeIngredient(listOf(DoseIngredient("Lactose", emptySet(), 1.0, "mg"), DoseIngredient("Tadalafil", emptySet(), 2.5, "mg")), "Tadalafil")!!.name)
    }
}
