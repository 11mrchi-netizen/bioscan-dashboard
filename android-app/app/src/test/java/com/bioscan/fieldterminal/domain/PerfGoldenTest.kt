package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.buildTrainingSessionLoads
import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Performance pass, Stage 0. Pins the exact current output of the health functions that the pass will touch (cache,
// threading, recomposition, hot-loop rewrites), so any later change that moves a number fails here. Inputs are synthetic
// and deterministic; expected values come from the pre-pass implementation. PERF lines print timings for the
// before/after table in docs/performance/measurements.md; they are not asserted tightly.
class PerfGoldenTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val asOf = LocalDate.of(2026, 10, 13)

    // ---------- runClusterIds ----------

    private fun run(id: Long, start: String, type: String = "run") = ExerciseSessionRow(id = id, type = type, startTime = start)

    @Test
    fun runClusterIncludesRunsWithinFiveMinutesTruncatedThenTheRunItself() {
        val a = run(1, "2026-10-10T10:00:00+00:00")
        val sessions = listOf(
            a,
            run(2, "2026-10-10T10:05:59+00:00"), // 5 min 59 s truncates to 5: included
            run(3, "2026-10-10T10:06:00+00:00"), // 6 min: out
            run(4, "2026-10-10T10:00:00+00:00", type = "walk"), // not a run
            run(5, "2026-10-10T09:55:30+00:00"), // 4 min 30 s before: in
            run(6, "2026-10-11T10:00:00+00:00"),
        )
        assertEquals(listOf(2L, 5L, 1L), runClusterIds(sessions, a))
        assertEquals(listOf(6L), runClusterIds(sessions, sessions[5]))
    }

    @Test
    fun batchClustersEqualTheOneByOneClustersOnRandomData() {
        val rnd = java.util.Random(20261013)
        val base = java.time.OffsetDateTime.of(2026, 3, 1, 6, 0, 0, 0, ZoneOffset.UTC)
        val sessions = (0 until 400).map { i ->
            // runs bunched in 0-8 minute clusters (the HC/Zepp duplicates), a few non-runs, mixed offsets
            val at = base.plusMinutes((i / 3) * 90L + rnd.nextInt(9)).plusSeconds(rnd.nextInt(60).toLong())
                .withOffsetSameInstant(ZoneOffset.ofHours(if (i % 5 == 0) 8 else 0))
            run(i + 1L, at.toString(), type = if (i % 11 == 0) "walk" else "run")
        }.shuffled(rnd)
        val targets = sessions.filter { it.type == "run" }
        val one = targets.map { runClusterIds(sessions, it) }
        val batch = runClusterIdsBatch(sessions, targets)
        assertEquals(one, batch)
    }

    // ---------- computeHrvReadinessSeries ----------

    @Test
    fun hrvReadinessSeriesMatchesTheHandComputedZScores() {
        val z = computeHrvReadinessSeries(listOf(50.0, 52.0, 48.0, 51.0, 49.0, null, 53.0))
        assertNull(z[0]); assertNull(z[1]); assertNull(z[2]) // fewer than 3 prior valid days
        assertEquals(0.61237, z[3]!!, 1e-4) // prior 50,52,48: mean 50, population sd 1.63299
        assertEquals(-0.84515, z[4]!!, 1e-4) // prior 50,52,48,51
        assertNull(z[5]) // the day itself is null
        assertEquals(2.12132, z[6]!!, 1e-4) // null prior dropped: 50,52,48,51,49
        assertEquals(0.0, computeHrvReadinessSeries(listOf(10.0, 10.0, 10.0, 10.0))[3]!!, 0.0) // sd 0 -> 0
    }

    // ---------- aggregateMealsByDay ----------

    @Test
    fun mealsAreGroupedByTheFirstTenCharactersOfTheTimestampAndSummed() {
        val meals = listOf(
            MealRow(loggedAt = "2026-10-12T23:30:00+00:00", calories = 100.0, proteinG = 10.0, fatG = 1.0, carbsG = 5.0, fiberG = 1.0),
            MealRow(loggedAt = "2026-10-11T08:00:00+00:00", calories = 200.0, proteinG = 20.0, sugarG = 3.0, sodiumMg = 50.0),
            MealRow(loggedAt = "2026-10-12T07:00:00+00:00", calories = null, proteinG = 5.0),
        )
        val out = aggregateMealsByDay(meals)
        assertEquals(listOf("2026-10-11", "2026-10-12"), out.map { it.date })
        assertEquals(DailyNutrition("2026-10-11", 200.0, 20.0, 0.0, 0.0, 1, 0.0, 3.0, 50.0), out[0])
        assertEquals(DailyNutrition("2026-10-12", 100.0, 15.0, 1.0, 5.0, 2, 1.0, 0.0, 0.0), out[1])
    }

    // ---------- evaluateRespiratoryAnomaly ----------

    private fun rr(lastTwo: Pair<Double, Double>, days: Int = 14): List<Pair<LocalDate, Double>> =
        (days - 1 downTo 0).map { d ->
            val date = asOf.minusDays(d.toLong())
            date to when (d) { 0 -> lastTwo.second; 1 -> lastTwo.first; else -> 15.0 }
        }

    @Test
    fun respiratoryAnomalyNeedsBothOfTheLastTwoNightsOutsideTwoSd() {
        val both = evaluateRespiratoryAnomaly(rr(20.0 to 20.0), asOf)
        assertTrue(both.flagged)
        assertEquals(15.7142857, both.baseline!!, 1e-6)
        assertFalse(evaluateRespiratoryAnomaly(rr(15.0 to 20.0), asOf).flagged)
        val short = evaluateRespiratoryAnomaly(rr(20.0 to 20.0, days = 13), asOf)
        assertFalse(short.flagged)
        assertNull(short.baseline)
        assertEquals(Confidence(13, 14), short.confidence)
    }

    // ---------- evaluateSubjective ----------

    private fun ratings(days: Int, f: (Int) -> Double) = (days - 1 downTo 0).map { asOf.minusDays(it.toLong()) to f(it) }

    @Test
    fun subjectiveStatesAndConfidence() {
        assertEquals(EvalState.NoData, evaluateSubjective(emptyList(), asOf).state)
        assertEquals(EvalState.Building, evaluateSubjective(ratings(10) { 6.0 }, asOf).state)
        val stable = evaluateSubjective(ratings(30) { 6.0 }, asOf)
        assertEquals(EvalState.Stable, stable.state)
        assertEquals(6.0, stable.median7d!!, 0.0)
        assertEquals(Confidence(7, 5), stable.confidence)
        val up = evaluateSubjective(ratings(30) { if (it < 7) 9.0 else 6.0 }, asOf)
        assertEquals(EvalState.ShiftUp, up.state)
        assertEquals(9.0, up.median7d!!, 0.0)
        assertEquals(6.0, up.medianBaseline30d!!, 0.0)
        val unstable = evaluateSubjective(ratings(30) { if (it % 2 == 0) 2.0 else 8.0 }, asOf)
        assertEquals(EvalState.Unstable, unstable.state)
    }

    // ---------- buildTrainingSessionLoads ----------

    @Test
    fun trainingLoadUsesRpeThenTrimpAndSkipsWhatItCannotScore() {
        val rows = listOf(
            TrainingLoadSessionRow("2026-10-10T07:00:00+08:00", durationMin = 60.0, rpe = 7),
            TrainingLoadSessionRow("2026-10-11T07:00:00+08:00", durationMin = null, rpe = 5),
            TrainingLoadSessionRow("2026-10-12T07:00:00+08:00", durationMin = 45.0, avgHr = 150.0, maxHr = 185.0),
            TrainingLoadSessionRow("2026-10-13T07:00:00+08:00", durationMin = 30.0), // no rpe, no hr
        )
        val out = buildTrainingSessionLoads(rows, latestRestingHr = 50.0)
        assertEquals(2, out.size)
        assertEquals(LocalDate.of(2026, 10, 10) to 420.0, out[0])
        assertEquals(LocalDate.of(2026, 10, 12), out[1].first)
        assertEquals(trimp(EnduranceSessionInput(45.0, null, 150.0, 185.0, null, null, null), 50.0)!!, out[1].second, 1e-9)
        assertEquals(1, buildTrainingSessionLoads(rows, latestRestingHr = null).size) // no resting HR: trimp row dropped
    }

    // ---------- evaluateSri ----------

    private fun nights(count: Int, shift: (Int) -> Pair<Long, Long>) = (count - 1 downTo 0).map { i ->
        val date = asOf.minusDays(i.toLong())
        val (bedShift, wakeShift) = shift(i)
        val bed = date.minusDays(1).atTime(22, 0).toInstant(ZoneOffset.UTC).plusSeconds(bedShift * 60)
        val wake = date.atTime(6, 0).toInstant(ZoneOffset.UTC).plusSeconds(wakeShift * 60)
        SleepNight(date, bed, wake)
    }

    @Test
    fun sriForAPerfectlyRegularScheduleIsJustUnderOneHundred() {
        // Not exactly 100: the window's first morning has no earlier night to carry the previous evening's sleep (edge effect).
        val r = evaluateSri(nights(20) { 0L to 0L }, utc, asOf)
        assertEquals(99.12280701754383, r.value!!, 1e-9)
        assertEquals(Confidence(20, 14), r.confidence)
    }

    @Test
    fun sriNeedsFourteenNightsAndAGapFreeRun() {
        val few = evaluateSri(nights(13) { 0L to 0L }, utc, asOf)
        assertNull(few.value)
        assertEquals(Confidence(13, 14), few.confidence)
        // 8 nights, a 4-day hole, 8 more: no gap-free run reaches 14
        val all = nights(20) { 0L to 0L }
        val holed = all.filterIndexed { i, _ -> i < 8 || i >= 12 }
        assertNull(evaluateSri(holed, utc, asOf).value)
    }

    private val irregular: (Int) -> Pair<Long, Long> = { i -> ((i * 37L) % 90 - 45) to ((i * 53L) % 60 - 30) }

    @Test
    fun sriGoldenValueForADeterministicIrregularSchedule() {
        val r = evaluateSri(nights(40, irregular), utc, asOf)
        assertNotNull(r.value)
        assertEquals(SRI_GOLDEN_IRREGULAR, r.value!!, 1e-9)
    }

    // ---------- timings for the before/after table (printed, loosely asserted) ----------

    private inline fun bestOf(n: Int, block: () -> Unit): Double {
        var best = Double.MAX_VALUE
        repeat(n) {
            val t0 = System.nanoTime()
            block()
            best = minOf(best, (System.nanoTime() - t0) / 1_000_000.0)
        }
        return best
    }

    @Test
    fun timingEvaluateSriOnSixtyNights() {
        val data = nights(60, irregular)
        evaluateSri(data, utc, asOf) // warm up
        val ms = bestOf(5) { evaluateSri(data, utc, asOf) }
        println("PERF evaluateSri 60 nights: best of 5 = ${"%.2f".format(ms)} ms")
        assertTrue(ms < 5_000.0)
    }

    @Test
    fun timingRunClustersOverThreeHundredRuns() {
        val base = java.time.OffsetDateTime.of(2026, 1, 1, 7, 0, 0, 0, ZoneOffset.UTC)
        val sessions = (0 until 300).map { i -> run(i + 1L, base.plusHours(i * 7L).toString()) }
        runClusterIds(sessions, sessions[0])
        val ms = bestOf(3) { sessions.forEach { runClusterIds(sessions, it) } }
        println("PERF runClusterIds 300 runs (all clusters, one by one): best of 3 = ${"%.1f".format(ms)} ms")
        runClusterIdsBatch(sessions, sessions)
        val batchMs = bestOf(3) { runClusterIdsBatch(sessions, sessions) }
        println("PERF runClusterIdsBatch 300 runs (all clusters): best of 3 = ${"%.1f".format(batchMs)} ms")
        assertTrue(ms < 20_000.0 && batchMs < 20_000.0)
    }

    companion object {
        // Recorded from the pre-pass implementation (see sriGoldenValueForADeterministicIrregularSchedule).
        const val SRI_GOLDEN_IRREGULAR = 91.68447293447295
    }
}
