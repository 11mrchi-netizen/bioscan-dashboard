package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.LiftReading
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

    // 80 kg lifter. squat: novice 60, intermediate 100, advanced 140. deadlift: 80, 120, 160. chest_press: 40, 60, 100.
    private fun lift(slug: String, e1rm: Double, age: Long = 20) = LiftReading(slug, e1rm, today.minusDays(age))
    private fun strengthOf(i: HealthspanInputs) = healthspanSummary(i).of(HealthspanFactorId.STRENGTH)
    private val bw80 = DatedValue(80.0, today.minusDays(10), "kg")

    @Test
    fun strengthIsTheMedianLiftLevel() {
        val fav = strengthOf(good().copy(bodyWeightKg = bw80, lifts = listOf(lift("barbell_squat", 150.0), lift("barbell_deadlift", 130.0), lift("chest_press", 70.0))))
        assertEquals(FactorStatus.FAVORABLE, fav.status) // advanced, intermediate, intermediate
        val neutral = strengthOf(good().copy(bodyWeightKg = bw80, lifts = listOf(lift("barbell_squat", 70.0), lift("barbell_deadlift", 90.0), lift("chest_press", 45.0))))
        assertEquals(FactorStatus.NEUTRAL, neutral.status)
        val opp = strengthOf(good().copy(bodyWeightKg = bw80, lifts = listOf(lift("barbell_squat", 50.0), lift("barbell_deadlift", 60.0), lift("chest_press", 30.0))))
        assertEquals(FactorStatus.OPPORTUNITY, opp.status)
        assertEquals(8, healthspanSummary(good().copy(bodyWeightKg = bw80, lifts = listOf(lift("barbell_squat", 150.0), lift("barbell_deadlift", 130.0), lift("chest_press", 70.0)))).assessed)
    }

    @Test
    fun evenCountUsesTheLowerMiddleLift() {
        // advanced, intermediate, novice, novice -> lower median is novice
        val f = strengthOf(good().copy(bodyWeightKg = bw80, lifts = listOf(
            lift("barbell_squat", 150.0), lift("barbell_deadlift", 130.0), lift("chest_press", 45.0), lift("military_press", 30.0),
        )))
        assertEquals(FactorStatus.NEUTRAL, f.status)
    }

    @Test
    fun strengthNeedsThreeRecentLiftsWithAStandardAndAFreshBodyWeight() {
        val three = listOf(lift("barbell_squat", 150.0), lift("barbell_deadlift", 130.0), lift("chest_press", 70.0))
        assertEquals(FactorStatus.UNASSESSED, strengthOf(good().copy(bodyWeightKg = bw80, lifts = three.take(2))).status)
        // a lift older than ~6 months does not count
        assertEquals(FactorStatus.UNASSESSED, strengthOf(good().copy(bodyWeightKg = bw80, lifts = three.take(2) + lift("chest_press", 70.0, age = 200))).status)
        // a lift with no standard does not count
        assertEquals(FactorStatus.UNASSESSED, strengthOf(good().copy(bodyWeightKg = bw80, lifts = three.take(2) + lift("cable_fly", 70.0))).status)
        val noWeight = strengthOf(good().copy(bodyWeightKg = null, lifts = three))
        assertEquals(FactorStatus.UNASSESSED, noWeight.status)
        assertTrue(noWeight.missingInputs.any { it.startsWith("body weight") })
        val staleWeight = strengthOf(good().copy(bodyWeightKg = DatedValue(80.0, today.minusDays(120), "kg"), lifts = three))
        assertEquals(FactorStatus.UNASSESSED, staleWeight.status)
        assertTrue(staleWeight.missingInputs.any { it.startsWith("body weight from the last 90 days") })
    }

    @Test
    fun strengthAlwaysSaysThatTheStandardsAreLifterStandards() {
        val f = strengthOf(good())
        assertEquals(FactorStatus.UNASSESSED, f.status)
        assertTrue(f.notes.first().startsWith("Lifter standards"))
    }

    @Test
    fun gripIsShownButNeverScored() {
        val without = healthspanSummary(good())
        val with = healthspanSummary(good().copy(grip = GripReading(46.5, "right", today.minusDays(3))))
        assertEquals(without.assessed, with.assessed)
        assertEquals(without.grade, with.grade)
        val note = with.of(HealthspanFactorId.STRENGTH).notes.last()
        assertTrue(note.startsWith("Latest grip: ") && note.contains("does not change the grade"))
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
