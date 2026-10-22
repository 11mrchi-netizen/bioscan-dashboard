package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.LiftReading
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.analysis.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class HealthspanTest {

    private val today = LocalDate.of(2026, 4, 15)

    private fun lab(value: Double, unit: String, age: Long = 10) = DatedValue(value, today.minusDays(age), unit)

    private fun pheno(accel: Double, observed: LocalDate = today.minusDays(10), available: Boolean = true) = BiologicalAgeResult(
        model = AgingModel.PHENOAGE, modelVersion = "levine-2018-v1",
        chronologicalAgeYears = 45.0, biologicalAge = if (available) 45.0 + accel else null,
        ageAcceleration = if (available) accel else null,
        confidence = Confidence(9, 9),
        provenance = Provenance.USER_ENTERED, observedAt = observed,
        unavailableReason = if (!available) "Missing albumin" else null,
    )

    private fun good() = HealthspanInputs(
        asOf = today, sex = "male",
        vo2max = lab(48.0, "mL/kg/min"), vo2Percentile = 65.0,
        phenoAge = pheno(-3.0),
        bodyWeightKg = bw80,
        lifts = listOf(lift("barbell_squat", 140.0), lift("barbell_deadlift", 160.0), lift("chest_press", 80.0)),
        waistCm = lab(82.0, "cm"), heightCm = lab(178.0, "cm"),
        sleepMeanHours = 7.5, sleepNights = 14, sri = SriEvaluation(82.0, Confidence(30, 14)),
        stepsMedian = 9200.0, stepsDays = 14, stepsBand = "active",
        triglyceridesMgDl = lab(90.0, "mg/dL"), hdlMgDl = lab(55.0, "mg/dL"),
        hba1cPct = lab(5.2, "%"), fastingGlucoseMgDl = lab(88.0, "mg/dL"),
    )

    @Test
    fun goodInputsAssessAllFactorsAndComputeCompositeScore() {
        val s = healthspanSummary(good())
        assertEquals(8, s.assessed)
        assertEquals(8, s.favorable)
        assertFalse(s.preliminary)
        assertTrue(s.score != null && s.score!! in 70..100)
    }

    @Test
    fun lipidsScoreIsContinuousAndRespectsSexAndTarget() {
        fun f(tg: Double, hdl: Double) = healthspanSummary(good().copy(triglyceridesMgDl = lab(tg, "mg/dL"), hdlMgDl = lab(hdl, "mg/dL"))).of(HealthspanFactorId.METABOLIC)
        val optimal = f(90.0, 65.0)
        assertEquals(FactorStatus.FAVORABLE, optimal.status)
        assertTrue(optimal.score!! >= 90.0)

        val border = f(160.0, 42.0)
        assertEquals(FactorStatus.NEUTRAL, border.status)
        assertTrue(border.score!! in 50.0..74.0)

        val bad = f(250.0, 30.0)
        assertEquals(FactorStatus.OPPORTUNITY, bad.status)
        assertTrue(bad.score!! < 50.0)
    }

    @Test
    fun glucosePrefersHbA1cOverFastingGlucose() {
        val both = healthspanSummary(good().copy(hba1cPct = lab(5.2, "%"), fastingGlucoseMgDl = lab(120.0, "mg/dL"))).of(HealthspanFactorId.GLYCAEMIC)
        assertEquals(FactorStatus.FAVORABLE, both.status)
        assertTrue(both.reading!!.startsWith("HbA1c"))

        val stale = healthspanSummary(good().copy(hba1cPct = lab(6.0, "%", age = 600), fastingGlucoseMgDl = lab(90.0, "mg/dL")))
        assertEquals(FactorStatus.FAVORABLE, stale.of(HealthspanFactorId.GLYCAEMIC).status)
        assertTrue(stale.of(HealthspanFactorId.GLYCAEMIC).reading!!.startsWith("Fasting glucose"))

        val none = healthspanSummary(good().copy(hba1cPct = null, fastingGlucoseMgDl = null)).of(HealthspanFactorId.GLYCAEMIC)
        assertEquals(FactorStatus.UNASSESSED, none.status)
        assertEquals(2, none.missingInputs.size)
    }

    @Test
    fun glucoseScoresAreContinuous() {
        fun s(mgdl: Double) = healthspanSummary(good().copy(hba1cPct = null, fastingGlucoseMgDl = lab(mgdl, "mg/dL"))).of(HealthspanFactorId.GLYCAEMIC)
        assertEquals(FactorStatus.FAVORABLE, s(85.0).status)
        assertTrue(s(85.0).score!! >= 90.0)

        assertEquals(FactorStatus.NEUTRAL, s(110.0).status)
        assertTrue(s(110.0).score!! in 50.0..74.0)

        assertEquals(FactorStatus.OPPORTUNITY, s(130.0).status)
        assertTrue(s(130.0).score!! < 50.0)
    }

    @Test
    fun bodyCompositionUsesWaistToHeightContinuousScore() {
        fun s(waist: Double) = healthspanSummary(good().copy(waistCm = lab(waist, "cm"))).of(HealthspanFactorId.BODY_COMPOSITION)
        assertEquals(FactorStatus.FAVORABLE, s(80.0).status)
        assertTrue(s(80.0).score!! >= 90.0)

        assertEquals(FactorStatus.NEUTRAL, s(98.0).status)
        assertTrue(s(98.0).score!! in 50.0..74.0)

        assertEquals(FactorStatus.OPPORTUNITY, s(115.0).status)
        assertTrue(s(115.0).score!! < 50.0)

        assertEquals(FactorStatus.UNASSESSED, s(20.0).status)
    }

    @Test
    fun sleepScoresBothDurationAndRegularity() {
        fun s(h: Double, sri: Double) = healthspanSummary(good().copy(sleepMeanHours = h, sri = SriEvaluation(sri, Confidence(30, 14)))).of(HealthspanFactorId.SLEEP)
        assertEquals(FactorStatus.FAVORABLE, s(7.5, 85.0).status)
        assertTrue(s(7.5, 85.0).score!! >= 90.0)

        assertEquals(FactorStatus.NEUTRAL, s(6.2, 70.0).status)
        assertTrue(s(6.2, 70.0).score!! in 50.0..74.0)

        assertEquals(FactorStatus.OPPORTUNITY, s(5.0, 40.0).status)
        assertTrue(s(5.0, 40.0).score!! < 50.0)

        val noSri = healthspanSummary(good().copy(sri = SriEvaluation(null, Confidence(8, 14)))).of(HealthspanFactorId.SLEEP)
        assertEquals(FactorStatus.UNASSESSED, noSri.status)
    }

    @Test
    fun activityFollowsStepMedianContinuousScale() {
        fun s(steps: Double) = healthspanSummary(good().copy(stepsMedian = steps)).of(HealthspanFactorId.ACTIVITY)
        assertEquals(FactorStatus.FAVORABLE, s(11000.0).status)
        assertTrue(s(11000.0).score!! >= 90.0)

        assertEquals(FactorStatus.NEUTRAL, s(6000.0).status)
        assertTrue(s(6000.0).score!! in 50.0..74.0)

        assertEquals(FactorStatus.OPPORTUNITY, s(2000.0).status)
        assertTrue(s(2000.0).score!! < 50.0)

        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(good().copy(stepsDays = 5)).of(HealthspanFactorId.ACTIVITY).status)
    }

    @Test
    fun cardioNeedsAFreshReadingAndAFittingTable() {
        val c = healthspanSummary(good().copy(vo2Percentile = 65.0)).of(HealthspanFactorId.CARDIO_FITNESS)
        assertEquals(FactorStatus.NEUTRAL, c.status)
        assertEquals(65.0, c.score!!, 0.1)

        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(good().copy(vo2Percentile = null)).of(HealthspanFactorId.CARDIO_FITNESS).status)
        val old = good().copy(vo2max = DatedValue(52.0, today.minusDays(200), "mL/kg/min"))
        assertEquals(FactorStatus.UNASSESSED, healthspanSummary(old).of(HealthspanFactorId.CARDIO_FITNESS).status)
    }

    @Test
    fun phenoAgeUsesRelativeAcceleration() {
        fun s(a: Double) = healthspanSummary(good().copy(phenoAge = pheno(a))).of(HealthspanFactorId.BIOLOGICAL_AGE)
        assertEquals(FactorStatus.FAVORABLE, s(-5.0).status)
        assertTrue(s(-5.0).score!! >= 75.0)

        assertEquals(FactorStatus.NEUTRAL, s(1.0).status)
        assertTrue(s(1.0).score!! in 50.0..74.0)

        assertEquals(FactorStatus.OPPORTUNITY, s(6.0).status)
        assertTrue(s(6.0).score!! < 50.0)

        val unavailable = healthspanSummary(good().copy(phenoAge = pheno(0.0, available = false))).of(HealthspanFactorId.BIOLOGICAL_AGE)
        assertEquals(FactorStatus.UNASSESSED, unavailable.status)
        assertEquals("Missing albumin", unavailable.missingInputs.single())
    }

    private fun lift(slug: String, e1rm: Double, age: Long = 20) = LiftReading(slug, e1rm, today.minusDays(age))
    private fun strengthOf(i: HealthspanInputs) = healthspanSummary(i).of(HealthspanFactorId.STRENGTH)
    private val bw80 = DatedValue(80.0, today.minusDays(10), "kg")

    @Test
    fun strengthIsMedianContinuousScoreAcrossLifts() {
        val fav = strengthOf(good().copy(bodyWeightKg = bw80, lifts = listOf(lift("barbell_squat", 150.0), lift("barbell_deadlift", 180.0), lift("chest_press", 110.0))))
        assertEquals(FactorStatus.FAVORABLE, fav.status)
        assertTrue(fav.score!! >= 75.0)

        val opp = strengthOf(good().copy(bodyWeightKg = bw80, lifts = listOf(lift("barbell_squat", 50.0), lift("barbell_deadlift", 60.0), lift("chest_press", 30.0))))
        assertEquals(FactorStatus.OPPORTUNITY, opp.status)
        assertTrue(opp.score!! < 50.0)
    }

    @Test
    fun gripIsShownButNeverScored() {
        val without = healthspanSummary(good())
        val with = healthspanSummary(good().copy(grip = GripReading(46.5, "right", today.minusDays(3))))
        assertEquals(without.assessed, with.assessed)
        assertEquals(without.score, with.score)
        val note = with.of(HealthspanFactorId.STRENGTH).notes.last()
        assertTrue(note.startsWith("Latest grip: ") && note.contains("does not change the score"))
    }

    @Test
    fun latestGripIsTheStrongerHandOnTheLatestDate() {
        val d1 = today.minusDays(10)
        val d2 = today.minusDays(2)
        val g = latestGrip(listOf(GripReading(50.0, "right", d1), GripReading(44.0, "left", d2), GripReading(46.0, "right", d2)))!!
        assertEquals(46.0, g.kg, 0.0)
        assertEquals(d2, g.date)
        assertNull(latestGrip(emptyList()))
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
        val conflict = latestLab(obs + LabObservation("Triglyceride", 95.0, "mg/dL", LocalDate.of(2026, 6, 1)))!!
        assertTrue(conflict.conflicting)
        assertNull(latestLab(obs) { it == "HDL" })
    }

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
