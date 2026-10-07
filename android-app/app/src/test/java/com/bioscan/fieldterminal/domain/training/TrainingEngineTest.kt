package com.bioscan.fieldterminal.domain.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// All numbers here are placeholders chosen to exercise the rules; no program content.
class TrainingEngineTest {
    private val plates = listOf(1.25, 2.5, 5.0, 10.0, 15.0, 20.0)

    // ---- PlateMath ----

    @Test
    fun loadStepIsTwiceTheSmallestPlate() = assertEquals(2.5, loadStepKg(plates), 1e-9)

    @Test
    fun smallFractionalLoadsUseTheSmallPlates() {
        val l = loadableFor(42.5, 20.0, plates)
        assertEquals(listOf(10.0, 1.25), l.platesPerSideKg)
        assertEquals(42.5, l.totalKg, 1e-9)
        assertEquals(47.5, loadableFor(47.5, 20.0, plates).totalKg, 1e-9)
    }

    @Test
    fun heavyLoadsUseFewPlates() {
        val l = loadableFor(100.0, 20.0, plates)
        assertEquals(listOf(20.0, 20.0), l.platesPerSideKg)
        assertEquals(100.0, l.totalKg, 1e-9)
    }

    @Test
    fun nearestRoundingBreaksTiesDownAndNeverOverloadsSilently() {
        assertEquals(40.0, loadableFor(41.0, 20.0, plates).totalKg, 1e-9)
        assertEquals(40.0, loadableFor(41.25, 20.0, plates).totalKg, 1e-9) // exact tie
        assertEquals(42.5, loadableFor(41.26, 20.0, plates).totalKg, 1e-9)
        assertEquals(42.5, loadableFor(41.0, 20.0, plates, Rounding.Up).totalKg, 1e-9)
        assertEquals(40.0, loadableFor(42.0, 20.0, plates, Rounding.Down).totalKg, 1e-9)
    }

    @Test
    fun belowTheBarIsTheBarAlone() {
        val l = loadableFor(15.0, 20.0, plates)
        assertTrue(l.platesPerSideKg.isEmpty())
        assertEquals(20.0, l.totalKg, 1e-9)
    }

    @Test
    fun anotherBarChangesTheComposition() {
        val l = loadableFor(45.0, 25.0, plates) // trap bar
        assertEquals(listOf(10.0), l.platesPerSideKg)
    }

    @Test
    fun differentPlateSetsChangeTheStep() = assertEquals(5.0, loadStepKg(listOf(2.5, 5.0, 10.0)), 1e-9)

    @Test
    fun addedWeightRoundsToASinglePlateStep() {
        assertEquals(8.75, roundToStep(8.9, 1.25), 1e-9)
        assertEquals(10.0, roundToStep(9.4, 1.25), 1e-9)
    }

    @Test
    fun poundConversionIsExact() {
        assertEquals(2.26796185, lbToKg(5.0), 1e-8)
        assertEquals(5.0, kgToLb(lbToKg(5.0)), 1e-9)
    }

    // ---- MaxEstimator ----

    private val today = LocalDate.of(2026, 10, 6)
    private fun d(daysAgo: Long) = today.minusDays(daysAgo)

    @Test
    fun loggedPercentImpliesTheMaxUsedInTheMostRecentSession() {
        val sets = listOf(
            DatedSet(d(12), 4, 47.5, 85.0), // older session is ignored
            DatedSet(d(1), 10, 20.0),
            DatedSet(d(1), 5, 42.5, 75.0),
            DatedSet(d(1), 5, 42.5, 75.0),
        )
        val e = estimateMaxFromHistory(sets, today)!!
        assertEquals(MaxSource.ImpliedLoggedPercent, e.source)
        assertEquals(42.5 / 0.75, e.kg, 1e-9)
        assertEquals(d(1), e.asOf)
        assertEquals(2, e.basedOnSets)
    }

    @Test
    fun withoutLoggedPercentTheBestEpleySetWins() {
        val sets = listOf(
            DatedSet(d(5), 5, 100.0),   // 116.67
            DatedSet(d(9), 12, 80.0),   // 112.0
            DatedSet(d(3), 20, 40.0),   // reps > 12: ignored
        )
        val e = estimateMaxFromHistory(sets, today)!!
        assertEquals(MaxSource.Estimate, e.source)
        assertEquals(100.0 * (1 + 5 / 30.0), e.kg, 1e-9)
    }

    @Test
    fun nothingUsableMeansNoEstimateNotAGuess() {
        assertNull(estimateMaxFromHistory(emptyList(), today))
        assertNull(estimateMaxFromHistory(listOf(DatedSet(d(2), 20, 40.0)), today))
        assertNull(estimateMaxFromHistory(listOf(DatedSet(d(200), 5, 100.0)), today)) // outside the window
    }

    @Test
    fun weightedCalisthenicsSetsAreLiftedByBodyweight() {
        val sets = withBodyweight(listOf(DatedSet(d(1), 5, 20.0)), 76.0)
        assertEquals(96.0, sets.single().weightKg, 1e-9)
    }

    @Test
    fun forcedProgressionOnlyWhenTheBlockWasCompleted() {
        assertEquals(58.5, forcedProgression(56.0, 2.5, true), 1e-9)
        assertEquals(56.0, forcedProgression(56.0, 2.5, false), 1e-9)
    }

    // ---- Prescription resolver ----

    private val ctx = ResolveContext(oneRmKg = 100.0, trainingMaxKg = 90.0, maxReps = 15, bodyweightKg = 76.0)
    private fun item(load: LoadSpec, weighted: Boolean = false) =
        PrescriptionItem("A", 3..5, 5..5, load, weightedCalisthenics = weighted)

    @Test
    fun percentOfOneRmResolvesToALoadableBarbell() {
        val r = resolveLoad(item(LoadSpec.Pct1rm(70.0)), ctx) as ResolvedLoad.Barbell
        assertEquals(70.0, r.targetKg, 1e-9)
        assertEquals(70.0, r.loadable.totalKg, 1e-9)
    }

    @Test
    fun percentOfTrainingMaxUsesTheTrainingMax() {
        val r = resolveLoad(item(LoadSpec.PctTm(80.0)), ctx) as ResolvedLoad.Barbell
        assertEquals(72.0, r.targetKg, 1e-9)
        assertEquals(72.5, r.loadable.totalKg, 1e-9) // 72.0 is 0.5 from 72.5 and 2.0 from 70
    }

    @Test
    fun missingInputsAreExplicit() {
        assertEquals(ResolvedLoad.Unresolved(Missing.OneRm), resolveLoad(item(LoadSpec.Pct1rm(70.0)), ResolveContext()))
        assertEquals(ResolvedLoad.Unresolved(Missing.TrainingMax), resolveLoad(item(LoadSpec.PctTm(80.0)), ResolveContext(oneRmKg = 100.0)))
        assertEquals(ResolvedLoad.Unresolved(Missing.MaxReps), resolveLoad(item(LoadSpec.PctMaxReps(60.0)), ResolveContext()))
        assertEquals(
            ResolvedLoad.Unresolved(Missing.Bodyweight),
            resolveLoad(item(LoadSpec.Pct1rm(70.0), weighted = true), ResolveContext(oneRmKg = 100.0)),
        )
    }

    @Test
    fun weightedMovementAtOrBelowBodyweightSwitchesToMaxReps() {
        // 70% of a 100 kg total = 70 kg <= 76 kg bodyweight: 70% of 15 reps = 10.5 -> 11
        val r = resolveLoad(item(LoadSpec.Pct1rm(70.0), weighted = true), ctx) as ResolvedLoad.MaxRepsFraction
        assertEquals(11, r.reps)
    }

    @Test
    fun weightedMovementAboveBodyweightResolvesToAddedLoad() {
        // 85% of 100 = 85 kg total; 9 kg added, rounded to the 1.25 kg step (8.75)
        val r = resolveLoad(item(LoadSpec.Pct1rm(85.0), weighted = true), ctx) as ResolvedLoad.Added
        assertEquals(85.0, r.totalKg!!, 1e-9)
        assertEquals(8.75, r.addedKg, 1e-9)
    }

    @Test
    fun addedLoadConventionAppliesThePercentToTheAddedMaxOnly() {
        // The user's own convention: 75% of a 26.7 kg added max = 20.0 kg added, no bodyweight switch.
        val c = ctx.copy(oneRmKg = 26.7, weightedBase = PercentBase.AddedLoad)
        val r = resolveLoad(item(LoadSpec.Pct1rm(75.0), weighted = true), c) as ResolvedLoad.Added
        assertEquals(20.0, r.addedKg, 1e-9)
        assertEquals(96.0, r.totalKg!!, 1e-9)
        // still resolves without a bodyweight on record
        assertNull((resolveLoad(item(LoadSpec.Pct1rm(75.0), weighted = true), c.copy(bodyweightKg = null)) as ResolvedLoad.Added).totalKg)
    }

    @Test
    fun percentOfMaxRepsRoundsHalfUp() {
        assertEquals(8, (resolveLoad(item(LoadSpec.PctMaxReps(50.0)), ctx) as ResolvedLoad.MaxRepsFraction).reps) // 7.5
        assertEquals(9, (resolveLoad(item(LoadSpec.PctMaxReps(60.0)), ctx) as ResolvedLoad.MaxRepsFraction).reps) // 9.0
    }

    @Test
    fun selfRegulatedAndOverriddenLoadsPassThrough() {
        assertEquals(ResolvedLoad.WorkUp(2, 3), resolveLoad(item(LoadSpec.WorkUpRm(2, 3)), ResolveContext()))
        val fixed = resolveLoad(item(LoadSpec.Fixed(62.5)), ResolveContext()) as ResolvedLoad.FixedKg
        assertEquals(62.5, fixed.loadable.totalKg, 1e-9)
    }

    @Test
    fun aWholeWeekResolvesFromMaxSnapshotsPerSlot() {
        val maxes = mapOf("press" to 60.0, "squat" to 120.0, "pull" to 100.0)
        val items = listOf(
            PrescriptionItem("press", 3..5, 5..5, LoadSpec.Pct1rm(70.0)),
            PrescriptionItem("squat", 3..5, 5..5, LoadSpec.Pct1rm(70.0)),
            PrescriptionItem("pull", 3..5, 5..5, LoadSpec.Pct1rm(70.0), weightedCalisthenics = true),
        )
        val resolved = resolveItems(items) { slot -> ctx.copy(oneRmKg = maxes[slot]) }
        assertEquals(42.0, (resolved[0].load as ResolvedLoad.Barbell).targetKg, 1e-9)
        assertEquals(42.5, (resolved[0].load as ResolvedLoad.Barbell).loadable.totalKg, 1e-9) // 42.0 is 0.5 from 42.5, 2.0 from 40
        assertEquals(84.0, (resolved[1].load as ResolvedLoad.Barbell).targetKg, 1e-9)
        assertTrue(resolved[2].load is ResolvedLoad.MaxRepsFraction)
    }
}
