package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class InjectablesTest {
    private val sub = regimenSpec("morning", null, "", doseAmount = 250.0, doseUnit = "mcg", route = "subcutaneous", slots = listOf("morning"), schedule = ScheduleJson("daily"))

    @Test
    fun theSiteRestedLongestIsSuggested() {
        val sites = sitesFor(sub)
        assertTrue(sites.all { it.subcutaneous })
        assertEquals("abdomen_upper_left", nextSite(sites, emptyList())!!.key)
        val history = sites.mapIndexed { i, s -> s.key to Instant.parse("2026-10-01T08:00:00Z").plusSeconds(86400L * i) }
        // All used: the oldest use wins.
        assertEquals("abdomen_upper_left", nextSite(sites, history)!!.key)
        assertEquals("abdomen_upper_right", nextSite(sites, history.drop(1) + ("abdomen_upper_left" to Instant.parse("2026-11-01T08:00:00Z")))!!.key)
        assertTrue(sitesFor(sub.copy(route = "intramuscular")).none { it.key.startsWith("abdomen") })
        assertTrue(sub.injectable)
    }

    @Test
    fun aMixedVialGivesConcentrationDosesLeftAndTheUnitCrossCheck() {
        // 5 mg mixed with 2 ml: 2.5 mg/ml. A 250 mcg dose is 0.1 ml = 10 units on a U-100 syringe.
        val vial = Vial(5.0, "mg", 2.0, null, LocalDate.of(2026, 11, 1), 28, 2)
        val s = vialState(vial, 250.0, "mcg", usedSinceOpened = 750.0, prescribedUnits = 10.0)
        assertEquals(2.5, s.perMl, 1e-9)
        assertEquals(4.25, s.remaining, 1e-9)
        assertEquals(17, s.dosesLeft)
        assertEquals(10.0, s.doseUnits!!, 1e-9)
        assertFalse(s.unitsMismatch)
        assertEquals(LocalDate.of(2026, 11, 29), s.discardOn)
        // Prescribed 20 units against a mix that gives 10: flagged.
        assertTrue(vialState(vial, 250.0, "mcg", 0.0, prescribedUnits = 20.0).unitsMismatch)
        // IU cannot be compared with mg: no doses-left count, no cross-check.
        val iu = vialState(vial, 2.0, "IU", 0.0, prescribedUnits = 10.0)
        assertEquals(null, iu.dosesLeft)
        assertFalse(iu.unitsMismatch)
    }
}
