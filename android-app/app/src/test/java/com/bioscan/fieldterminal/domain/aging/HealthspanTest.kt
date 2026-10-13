package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.analysis.Provenance
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthspanTest {

    private val today = LocalDate.of(2026, 10, 13)

    private fun pheno(accel: Double, observed: LocalDate = today.minusDays(30), available: Boolean = true) = BiologicalAgeResult(
        model = AgingModel.PHENOAGE, modelVersion = "t", chronologicalAgeYears = 40.0,
        biologicalAge = if (available) 40.0 + accel else null,
        ageAcceleration = if (available) accel else null,
        confidence = Confidence(9, 9), provenance = Provenance("lab_results", null, null),
        observedAt = observed, calculatedAt = OffsetDateTime.now(),
        unavailableReason = if (available) null else "Missing albumin",
    )

    private fun lab(value: Double, unit: String?, age: Long = 60, conflicting: Boolean = false) =
        DatedValue(value, today.minusDays(age), unit, conflicting)

    // Seven assessable factors all favorable; strength has no benchmark.
    private fun good() = HealthspanInputs(
        asOf = today, sex = "male",
        vo2max = DatedValue(52.0, today.minusDays(10), "mL/kg/min"), vo2Percentile = 70.0,
        phenoAge = pheno(-3.0),
        waistCm = lab(80.0, "cm"), heightCm = lab(180.0, "cm"),
        sleepMeanHours = 7.5, sleepNights = 30, sri = SriEvaluation(85.0, Confidence(30, 14)),
        stepsMedian = 11000.0, stepsDays = 30, stepsBand = "active",
        triglyceridesMgDl = lab(90.0, "mg/dL"), hdlMgDl = lab(55.0, "mg/dL"),
        hba1cPct = lab(5.2, "%"), fastingGlucoseMgDl = lab(90.0, "mg/dL"),
    )

    private fun HealthspanSummary.of(id: HealthspanFactorId) = factors.single { it.id == id }

    @Test
    fun allFavorableIsGradeAOverSevenAssessed() {
        val s = healthspanSummary(good())
        assertEquals(7, s.assessed)
        assertEquals(7, s.favorable)
        assertEquals("A", s.grade)
        assertFalse(s.preliminary)
        assertEquals(FactorStatus.UNASSESSED, s.of(HealthspanFactorId.STRENGTH).status)
    }

    @Test
    fun belowTheFloorIsPreliminaryAndHasNoLetter() {
        val s = healthspanSummary(HealthspanInputs(asOf = today, sex = "male", waistCm = lab(80.0, "cm"), heightCm = lab(180.0, "cm"),
            triglyceridesMgDl = lab(90.0, "mg/dL"), hdlMgDl = lab(55.0, "mg/dL"), fastingGlucoseMgDl = lab(90.0, "mg/dL"),
            stepsMedian = 11000.0, stepsDays = 30, stepsBand = "active"))
        assertEquals(4, s.assessed)
        assertTrue(s.preliminary)
        assertNull(s.grade)
    }

    @Test
    fun gradeBandsFollowTheShareFavorable() {
        // good() has 7 assessed. Turn factors to opportunity one by one: 7/7 A, 5/7 B, 3/7 C, 2/7 D.
        fun grade(i: HealthspanInputs) = healthspanSummary(i).grade
        val two = good().copy(stepsBand = "sedentary", fastingGlucoseMgDl = lab(130.0, "mg/dL"), hba1cPct = null) // 5/7 = .71
        assertEquals("B", grade(two))
        val four = two.copy(waistCm = lab(120.0, "cm"), hdlMgDl = lab(30.0, "mg/dL"), triglyceridesMgDl = lab(200.0, "mg/dL")) // 3/7 = .43
        assertEquals("C", grade(four))
        val five = four.copy(vo2Percentile = 10.0) // 2/7 = .29
        assertEquals("D", grade(five))
    }

    @Test
    fun staleLabIsUnassessedNotUsed() {
        val s = healthspanSummary(good().copy(triglyceridesMgDl = lab(90.0, "mg/dL", age = 500)))
        val f = s.of(HealthspanFactorId.METABOLIC)
        assertEquals(FactorStatus.UNASSESSED, f.status)
        assertTrue(f.missingInputs.single().startsWith("triglycerides from the last year"))
        assertNotNull(f.action)
    }

    @Test
    fun unitMismatchMissingUnitAndConflictsAreNeverAssumed() {
        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(good().copy(hdlMgDl = lab(1.4, "mmol/L"))).of(HealthspanFactorId.METABOLIC).status)
        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(good().copy(hdlMgDl = lab(55.0, null))).of(HealthspanFactorId.METABOLIC).status)
        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(good().copy(hdlMgDl = lab(55.0, "mg/dL", conflicting = true))).of(HealthspanFactorId.METABOLIC).status)
    }

    @Test
    fun hdlLimitNeedsSex() {
        val s = healthspanSummary(good().copy(sex = null))
        assertEquals(FactorStatus.UNASSESSED, s.of(HealthspanFactorId.METABOLIC).status)
        // 45 mg/dL is low for a woman (limit 50) but not for a man (limit 40).
        assertEquals(FactorStatus.FAVORABLE, healthspanSummary(good().copy(hdlMgDl = lab(45.0, "mg/dL"))).of(HealthspanFactorId.METABOLIC).status)
        assertEquals(FactorStatus.NEUTRAL, healthspanSummary(good().copy(sex = "female", hdlMgDl = lab(45.0, "mg/dL"))).of(HealthspanFactorId.METABOLIC).status)
    }

    @Test
    fun lipidsCountFlags() {
        assertEquals(FactorStatus.OPPORTUNITY, healthspanSummary(good().copy(triglyceridesMgDl = lab(150.0, "mg/dL"), hdlMgDl = lab(39.0, "mg/dL"))).of(HealthspanFactorId.METABOLIC).status)
        assertEquals(FactorStatus.NEUTRAL, healthspanSummary(good().copy(triglyceridesMgDl = lab(150.0, "mg/dL"))).of(HealthspanFactorId.METABOLIC).status)
    }

    @Test
    fun hba1cWinsWhenValidElseFastingGlucose() {
        assertEquals(FactorStatus.NEUTRAL, healthspanSummary(good().copy(hba1cPct = lab(6.0, "%"))).of(HealthspanFactorId.GLYCAEMIC).status)
        val stale = healthspanSummary(good().copy(hba1cPct = lab(6.0, "%", age = 600), fastingGlucoseMgDl = lab(90.0, "mg/dL")))
        assertEquals(FactorStatus.FAVORABLE, stale.of(HealthspanFactorId.GLYCAEMIC).status)
        assertTrue(stale.of(HealthspanFactorId.GLYCAEMIC).reading!!.startsWith("Fasting glucose"))
        val none = healthspanSummary(good().copy(hba1cPct = null, fastingGlucoseMgDl = null)).of(HealthspanFactorId.GLYCAEMIC)
        assertEquals(FactorStatus.UNASSESSED, none.status)
        assertEquals(2, none.missingInputs.size)
    }

    @Test
    fun glucoseCutoffsFollowTheClassification() {
        fun s(mgdl: Double) = healthspanSummary(good().copy(hba1cPct = null, fastingGlucoseMgDl = lab(mgdl, "mg/dL"))).of(HealthspanFactorId.GLYCAEMIC).status
        assertEquals(FactorStatus.FAVORABLE, s(99.0))
        assertEquals(FactorStatus.NEUTRAL, s(100.0))
        assertEquals(FactorStatus.NEUTRAL, s(125.0))
        assertEquals(FactorStatus.OPPORTUNITY, s(126.0))
    }

    @Test
    fun bodyCompositionUsesWaistToHeight() {
        fun s(waist: Double) = healthspanSummary(good().copy(waistCm = lab(waist, "cm"))).of(HealthspanFactorId.BODY_COMPOSITION).status
        assertEquals(FactorStatus.FAVORABLE, s(89.0)) // 0.494
        assertEquals(FactorStatus.NEUTRAL, s(99.0)) // 0.55
        assertEquals(FactorStatus.OPPORTUNITY, s(108.0)) // 0.60
        assertEquals(FactorStatus.UNASSESSED, s(20.0)) // implausible ratio is not used
    }

    @Test
    fun sleepNeedsBothInputsAndScoresThem() {
        fun s(h: Double, sri: Double) = healthspanSummary(good().copy(sleepMeanHours = h, sri = SriEvaluation(sri, Confidence(30, 14)))).of(HealthspanFactorId.SLEEP).status
        assertEquals(FactorStatus.FAVORABLE, s(7.0, 80.0))
        assertEquals(FactorStatus.NEUTRAL, s(6.0, 85.0))
        assertEquals(FactorStatus.NEUTRAL, s(7.5, 70.0))
        assertEquals(FactorStatus.OPPORTUNITY, s(6.0, 70.0))
        val noSri = healthspanSummary(good().copy(sri = SriEvaluation(null, Confidence(8, 14)))).of(HealthspanFactorId.SLEEP)
        assertEquals(FactorStatus.UNASSESSED, noSri.status)
        val fewNights = healthspanSummary(good().copy(sleepNights = 9)).of(HealthspanFactorId.SLEEP)
        assertEquals(FactorStatus.UNASSESSED, fewNights.status)
    }

    @Test
    fun activityFollowsTheStepBands() {
        fun s(band: String) = healthspanSummary(good().copy(stepsBand = band)).of(HealthspanFactorId.ACTIVITY).status
        assertEquals(FactorStatus.FAVORABLE, s("highly_active"))
        assertEquals(FactorStatus.NEUTRAL, s("somewhat_active"))
        assertEquals(FactorStatus.OPPORTUNITY, s("low_active"))
        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(good().copy(stepsDays = 5)).of(HealthspanFactorId.ACTIVITY).status)
    }

    @Test
    fun cardioNeedsAFreshReadingAndAFittingTable() {
        assertEquals(FactorStatus.NEUTRAL, healthspanSummary(good().copy(vo2Percentile = 30.0)).of(HealthspanFactorId.CARDIO_FITNESS).status)
        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(good().copy(vo2Percentile = null)).of(HealthspanFactorId.CARDIO_FITNESS).status)
        val old = good().copy(vo2max = DatedValue(52.0, today.minusDays(200), "mL/kg/min"))
        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(old).of(HealthspanFactorId.CARDIO_FITNESS).status)
    }

    @Test
    fun phenoAgeUsesAccelerationAndRespectsAvailability() {
        fun s(a: Double) = healthspanSummary(good().copy(phenoAge = pheno(a))).of(HealthspanFactorId.BIOLOGICAL_AGE).status
        assertEquals(FactorStatus.FAVORABLE, s(-2.0))
        assertEquals(FactorStatus.NEUTRAL, s(0.0))
        assertEquals(FactorStatus.OPPORTUNITY, s(2.0))
        val unavailable = healthspanSummary(good().copy(phenoAge = pheno(0.0, available = false))).of(HealthspanFactorId.BIOLOGICAL_AGE)
        assertEquals(FactorStatus.UNASSESSED, unavailable.status)
        assertEquals("Missing albumin", unavailable.missingInputs.single())
        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(good().copy(phenoAge = pheno(-3.0, observed = today.minusDays(400)))).of(HealthspanFactorId.BIOLOGICAL_AGE).status)
    }

    @Test
    fun strengthPathExistsOnceABenchmarkDoes() {
        assertEquals(FactorStatus.FAVORABLE, healthspanSummary(good().copy(strengthPercentile = 80.0)).of(HealthspanFactorId.STRENGTH).status)
    }

    @Test
    fun latestLabPicksTheNewestDateAndFlagsConflicts() {
        val obs = listOf(
            LabObservation("Triglyceride", 120.0, "mg/dL", LocalDate.of(2026, 1, 1)),
            LabObservation("Triglyceride", 90.0, "mg/dL", LocalDate.of(2026, 6, 1)),
            LabObservation("Triglyceride", 90.0, "MG/DL", LocalDate.of(2026, 6, 1)),
        )
        val v = latestLab(obs) { it == "Triglyceride" }!!
        assertEquals(90.0, v.value, 0.0)
        assertEquals(LocalDate.of(2026, 6, 1), v.date)
        assertFalse(v.conflicting)
        val conflict = latestLab(obs + LabObservation("Triglyceride", 95.0, "mg/dL", LocalDate.of(2026, 6, 1))) { it == "Triglyceride" }!!
        assertTrue(conflict.conflicting)
        assertNull(latestLab(obs) { it == "HDL" })
    }

    // The grade is for the User profile page only. Anything else importing it would reintroduce a cross-metric score.
    @Test
    fun onlyTheProfileScreensUseHealthspan() {
        val allowed = setOf("Healthspan.kt", "HealthspanRepository.kt", "HealthspanCard.kt", "UserProfileScreen.kt", "AgingProfileScreen.kt")
        val token = Regex("""\bHealthspan(Summary|Card|Repository|Inputs|Factor)\b|\bhealthspanSummary\(""")
        val root = File("src/main/java")
        assertTrue("run from the app module", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { token.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
