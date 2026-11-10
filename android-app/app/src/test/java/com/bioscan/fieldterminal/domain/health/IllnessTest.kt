package com.bioscan.fieldterminal.domain.health

import com.bioscan.fieldterminal.domain.score.IllnessEpisode
import com.bioscan.fieldterminal.domain.score.IllnessCheckIn
import com.bioscan.fieldterminal.domain.score.illnessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class IllnessTest {
    private val now = ZonedDateTime.of(2026, 11, 10, 20, 0, 0, 0, ZoneId.of("Asia/Taipei"))
    private var n = 0
    private fun id() = "id${n++}"

    @Test
    fun anInfectionIsAConcernWithItsFirstCheckInAndAStartMark() {
        val cold = illnessType("cold")!!
        val plan = buildIllness(
            IllnessDraft(cold, "", LocalDate.of(2026, 11, 8), setOf("sym.congestion", "sym.sore_throat"), 3, impact = 1,
                test = "covid", testResult = TestResult.NEGATIVE, medication = "paracetamol"),
            now, ::id,
        )
        val c = plan.checkIn.concerns.single()
        assertEquals("Cold", c.title)
        assertEquals("infection", c.category)
        assertEquals("cold", c.illnessType)
        assertEquals("systemic", c.kind)
        val o = plan.checkIn.observations.single()
        assertEquals(c.id, o.concernId)
        assertEquals(1, o.functionImpact)
        assertEquals("\"negative\"", o.care!!["test_result"].toString())
        assertEquals(2, plan.checkIn.signals.size)
        assertTrue(plan.checkIn.signals.all { it.severity == 3 && it.concernId == c.id })
        assertEquals("2026-11-08", plan.startMark!!.date)
        // Logged today: no mark.
        assertNull(buildIllness(IllnessDraft(cold, "x", LocalDate.of(2026, 11, 10), emptySet(), 2, null), now, ::id).startMark)
    }

    @Test
    fun theIllnessTypesUseOnlyKnownSymptoms() {
        val codes = parseHealthVocab(File("../../supabase/functions/_shared/health-vocab/health-vocab.json").readText()).symptoms.map { it.code }.toSet()
        ILLNESS_TYPES.flatMap { it.symptoms }.forEach { assertTrue(it, it in codes) }
    }

    @Test
    fun aColdAboveTheNeckBarelyHoldsChargeAndTheFluHoldsItDown() {
        val d = LocalDate.of(2026, 11, 10)
        val cold = illnessState(IllnessEpisode("Cold", d, listOf(IllnessCheckIn(d, 0, null, 3, setOf("sym.congestion", "sym.sneezing")))), d)!!
        val flu = illnessState(IllnessEpisode("Flu", d, listOf(IllnessCheckIn(d, 2, 38.6, 6, setOf("sym.fever", "sym.muscle_aches")))), d)!!
        assertEquals(85.0, cold.ceiling)
        assertEquals(40.0, flu.ceiling)
    }
}
