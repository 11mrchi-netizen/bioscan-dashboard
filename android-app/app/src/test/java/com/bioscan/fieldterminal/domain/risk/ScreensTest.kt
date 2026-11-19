package com.bioscan.fieldterminal.domain.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensTest {
    private fun m(v: Double) = Dated(v, Source.MEASURED)
    private fun lab(v: Double) = Dated(v, Source.LABS)
    private val answered = HealthBackground(
        smoking = "never", secondhandSmoke = false, diabetesDiagnosed = false, bpMeds = false, lipidMeds = false,
        highGlucoseHistory = false, familyDiabetes = "none", vegFruitDaily = true, hearing = "good", vision = "corrected",
        headInjury = false, education = "tertiary",
    )
    private val full = ScreenInputs(
        age = 42, sex = "male", background = answered, bmi = m(23.4), waistCm = m(84.0), sbp = m(118.0), dbp = m(76.0),
        totalCholMgDl = lab(185.0), hdlMgDl = lab(58.0), ldlMgDl = lab(110.0), fastingGlucoseMgDl = lab(88.0), hba1cPct = null,
        egfr = lab(98.0), activityMinPerWeek = m(260.0), sleepHours = m(7.4), alcoholGramsPerWeek = m(40.0), phq9 = Dated(3.0, Source.ANSWERED),
        ucla3 = Dated(4.0, Source.ANSWERED), pm25 = m(12.0), currentYear = 2026,
    )

    @Test
    fun le8PointTablesFollowTheAdvisory() {
        assertEquals(100, le8Activity(150.0)); assertEquals(90, le8Activity(120.0)); assertEquals(60, le8Activity(60.0)); assertEquals(0, le8Activity(0.0))
        assertEquals(100, le8Sleep(7.0)); assertEquals(90, le8Sleep(9.5)); assertEquals(70, le8Sleep(6.5)); assertEquals(40, le8Sleep(10.5)); assertEquals(20, le8Sleep(4.2)); assertEquals(0, le8Sleep(3.0))
        assertEquals(100, le8Bmi(24.9)); assertEquals(70, le8Bmi(29.9)); assertEquals(30, le8Bmi(34.0)); assertEquals(15, le8Bmi(38.0)); assertEquals(0, le8Bmi(41.0))
        assertEquals(60, le8NonHdl(140.0, false)); assertEquals(40, le8NonHdl(140.0, true)); assertEquals(0, le8NonHdl(230.0, true))
        assertEquals(100, le8Glucose(false, 92.0, null)); assertEquals(60, le8Glucose(false, null, 6.0)); assertEquals(30, le8Glucose(true, null, 7.5)); assertNull(le8Glucose(true, 130.0, null))
        assertEquals(100, le8Bp(118.0, 76.0, false)); assertEquals(75, le8Bp(125.0, 78.0, false)); assertEquals(50, le8Bp(118.0, 82.0, false)); assertEquals(5, le8Bp(145.0, 85.0, true)); assertEquals(0, le8Bp(165.0, 85.0, false))
        assertEquals(100, le8Nicotine("never", null, false, 2026)); assertEquals(75, le8Nicotine("former", 2019, false, 2026)); assertEquals(50, le8Nicotine("former", 2024, false, 2026))
        assertEquals(30, le8Nicotine("former", 2024, true, 2026)); assertEquals(0, le8Nicotine("current", null, true, 2026)); assertNull(le8Nicotine("former", null, false, 2026))
    }

    @Test
    fun le8WithoutDietIsSevenOfEightAndSaysWhatIsMissing() {
        val r = le8(full)
        assertFalse(r.complete)
        assertEquals(7, r.assessed.size)
        assertEquals(100, r.score)
        assertEquals("High", r.band)
        assertEquals("a diet questionnaire (risk profile R4)", r.metrics.first { it.metric == Le8Metric.DIET }.missing)
        val noBackground = le8(full.copy(background = HealthBackground()))
        assertEquals("cholesterol medicine (health background)", noBackground.metrics.first { it.metric == Le8Metric.LIPIDS }.missing)
        assertNull(noBackground.metrics.first { it.metric == Le8Metric.NICOTINE }.points)
    }

    @Test
    fun findriscScoresEachItemAsPublished() {
        val r = findrisc(full)
        assertTrue(r.complete)
        assertEquals(0, r.score)
        assertEquals("Low", r.band)
        val high = findrisc(full.copy(age = 58, bmi = m(31.0), waistCm = m(104.0), activityMinPerWeek = m(60.0),
            background = answered.copy(vegFruitDaily = false, bpMeds = true, highGlucoseHistory = true, familyDiabetes = "first")))
        assertEquals(3 + 3 + 4 + 2 + 1 + 2 + 5 + 5, high.score)
        assertEquals("Very high", high.band)
        val female = findrisc(full.copy(sex = "female", waistCm = m(85.0)))
        assertEquals(3, female.items.first { it.label == "Waist" }.points)
        val partial = findrisc(full.copy(background = HealthBackground()))
        assertFalse(partial.complete)
        assertEquals(partial.score + 1 + 2 + 5 + 5, partial.maxPossible)
    }

    @Test
    fun brainFactorsAreFourteenEachPresentAbsentOrUnknownWithWhatIsNeeded() {
        val f = brainFactors(full)
        assertEquals(14, f.size)
        assertEquals(listOf("Air pollution"), f.filter { it.state == FactorState.PRESENT }.map { it.label })
        val worse = brainFactors(full.copy(phq9 = Dated(12.0, Source.ANSWERED), ldlMgDl = lab(140.0), sbp = m(134.0), alcoholGramsPerWeek = m(200.0)))
        assertTrue(listOf("Depression", "High LDL cholesterol", "High blood pressure", "Excessive alcohol").all { l -> worse.first { it.label == l }.state == FactorState.PRESENT })
        val unknown = brainFactors(full.copy(background = HealthBackground(), phq9 = null))
        val dep = unknown.first { it.label == "Depression" }
        assertEquals(FactorState.UNKNOWN, dep.state)
        assertEquals("a PHQ-9", dep.needs)
    }

    @Test
    fun diabetesComesFromTheDiagnosisOrTheLabsOrIsUnknown() {
        assertEquals(false, full.diabetes)
        assertEquals(true, full.copy(fastingGlucoseMgDl = lab(130.0)).diabetes)
        assertEquals(true, full.copy(background = answered.copy(diabetesDiagnosed = true)).diabetes)
        assertNull(full.copy(background = HealthBackground(), fastingGlucoseMgDl = null).diabetes)
    }

    @Test
    fun preventRunsOnTheGatheredInputsOrSaysWhatIsMissing() {
        val defs = parsePrevent(java.io.File("../../supabase/functions/_shared/risk/prevent-base.json").readText())
        val ok = com.bioscan.fieldterminal.data.preventFor(defs, full) as PreventOutcomeOrWhy.Ok
        assertEquals(PreventCategory.LOW, preventCategory(ok.result.tenYear.getValue(PreventOutcome.TOTAL_CVD)))
        val missing = com.bioscan.fieldterminal.data.preventFor(defs, full.copy(background = HealthBackground(), egfr = null)) as PreventOutcomeOrWhy.NotApplicable
        assertEquals(listOf("needs eGFR or creatinine, BP medicine, cholesterol medicine, smoking"), missing.reasons)
    }
}
