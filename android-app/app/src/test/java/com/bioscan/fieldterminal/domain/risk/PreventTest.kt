package com.bioscan.fieldterminal.domain.risk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToLong

class PreventTest {
    private val text = File("../../supabase/functions/_shared/risk/prevent-base.json").readText()
    private val defs = parsePrevent(text)
    private val cases = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray

    private fun r3(x: Double) = (x * 1000).roundToLong() / 1000.0

    @Test
    fun theReferenceResultsAreReproducedForBothSexesTenAndThirtyYears() {
        assertEquals(2, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            val inp = o["inputs"]!!.jsonObject
            fun d(k: String) = inp[k]!!.jsonPrimitive.double
            fun b(k: String) = inp[k]!!.jsonPrimitive.boolean
            val inputs = PreventInputs(
                age = d("age"), sex = inp["sex"]!!.jsonPrimitive.content, totalCholMgDl = d("total_c"), hdlMgDl = d("hdl_c"),
                sbp = d("sbp"), bpTreated = b("bp_tx"), statin = b("statin"), diabetes = b("dm"), currentSmoker = b("smoking"),
                egfr = d("egfr"), bmi = d("bmi"),
            )
            val res = (defs.estimate(inputs) as PreventOutcomeOrWhy.Ok).result
            fun expect(model: String, got: Map<PreventOutcome, Double>) {
                val want = o[model] as JsonObject
                for (outcome in PreventOutcome.entries) {
                    assertEquals("${inputs.sex} $model ${outcome.key}", want[outcome.key]!!.jsonPrimitive.double, r3(got.getValue(outcome)), 1e-9)
                }
            }
            expect("base_10yr", res.tenYear)
            expect("base_30yr", res.thirtyYear!!)
        }
    }

    @Test
    fun outsideTheFittedRangeNothingIsShownAndTheReasonIsSaid() {
        val ok = PreventInputs(45.0, "male", 190.0, 50.0, 122.0, false, false, false, false, 95.0, 24.0)
        val young = defs.estimate(ok.copy(age = 28.0))
        assertTrue(young is PreventOutcomeOrWhy.NotApplicable)
        assertEquals(listOf("PREVENT applies from age 30 to 79"), (young as PreventOutcomeOrWhy.NotApplicable).reasons)
        // 30-year risk only up to 59
        assertNull((defs.estimate(ok.copy(age = 65.0)) as PreventOutcomeOrWhy.Ok).result.thirtyYear)
    }

    @Test
    fun aHealthyFortyFiveYearOldIsLowRiskAndRiskRisesWithEachFactor() {
        val base = PreventInputs(45.0, "male", 190.0, 50.0, 122.0, false, false, false, false, 95.0, 24.0)
        val low = (defs.estimate(base) as PreventOutcomeOrWhy.Ok).result.tenYear.getValue(PreventOutcome.TOTAL_CVD)
        assertEquals(PreventCategory.LOW, preventCategory(low))
        for (worse in listOf(base.copy(currentSmoker = true), base.copy(sbp = 150.0), base.copy(diabetes = true), base.copy(hdlMgDl = 35.0))) {
            val r = (defs.estimate(worse) as PreventOutcomeOrWhy.Ok).result.tenYear.getValue(PreventOutcome.TOTAL_CVD)
            assertTrue("$worse", r > low)
        }
    }

    @Test
    fun ckdEpi2021MatchesItsPublishedForm() {
        // preventr's own reference values (tests/testthat/test-helpers.R): 1.5 mg/dL, female, 43 -> 44;
        // 88 umol/L (/ 88.4 = mg/dL), male, 43 -> 96
        assertEquals(44.0, Math.round(ckdEpi2021(1.5, 43.0, "female")).toDouble(), 0.0)
        assertEquals(96.0, Math.round(ckdEpi2021(88 / 88.4, 43.0, "male")).toDouble(), 0.0)
    }
}
