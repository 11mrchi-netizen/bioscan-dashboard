package com.bioscan.fieldterminal.domain.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class RunningFormTest {

    private fun analyse(spec: RunnerSpec, setup: FormSetup = FormSetup()) = analyseRunningForm(SyntheticRunner(spec).clip(), setup)

    private fun assertLandingsMatch(found: List<Double>, truth: List<Double>, toleranceMs: Double) {
        assertTrue("found ${found.size} of ${truth.size}", found.size >= truth.size - 2)
        for (f in found) {
            val nearest = truth.minBy { abs(it - f) }
            assertTrue("landing $f vs $nearest", abs(f - nearest) <= toleranceMs)
        }
    }

    @Test
    fun aSlowMotionSideClipReadsBackItsOwnRunner() {
        val spec = RunnerSpec(fps = 120.0)
        val truth = SyntheticRunner(spec).truth()
        val r = analyse(spec)
        assertNotEquals(ClipQuality.POOR, r.quality.level)
        assertEquals(BodySide.LEFT, r.measuredSide)
        assertEquals(1, r.direction)
        val m = r.metrics!!
        val near = r.contacts.filter { it.side == BodySide.LEFT }
        assertLandingsMatch(near.map { it.landingMs }, truth.nearLandingsMs, toleranceMs = 12.0)
        assertLandingsMatch(near.map { it.toeOffMs }, truth.nearToeOffsMs, toleranceMs = 12.0)
        assertEquals(truth.contactMs, m.contactTimeMs!!.median, 12.0)
        assertEquals(172.0, m.cadenceSpm!!, 1.5)
        assertEquals(truth.kneeFlexionAtLandingDeg, m.kneeFlexionAtLanding!!.median, 3.0)
        assertEquals(truth.shinAtLandingDeg, m.shinAngleAtLanding!!.median, 2.5)
        assertEquals(spec.landAhead, m.ankleAheadOfHip!!.median, 0.03)
        assertEquals(FootStrike.REARFOOT, m.footStrike)
        assertEquals(spec.strikeDeg, m.footStrikeAngle!!.median, 4.0)
        assertEquals(spec.trunkLeanDeg, m.trunkLean!!.median, 1.0)
        assertEquals(truth.hipExtensionAtToeOffDeg, m.hipExtensionAtToeOff!!.median, 3.0)
        assertEquals(spec.oscillation, m.verticalOscillation!!.median, 0.015)
        assertTrue(r.findings.any { it.key == "shin_angle" })
    }

    @Test
    fun thirtyFramesASecondKeepsCadenceAndPostureAndLeavesTheLandingOut() {
        val spec = RunnerSpec(fps = 30.0)
        val r = analyse(spec)
        val m = r.metrics!!
        assertNull(m.contactTimeMs)
        assertNull(m.footStrike)
        assertNull(m.hipExtensionAtToeOff)
        assertNull(m.kneeFlexionAtLanding)
        assertNull(m.shinAngleAtLanding)
        assertEquals(172.0, m.cadenceSpm!!, 2.5)
        assertEquals(spec.trunkLeanDeg, m.trunkLean!!.median, 1.0)
        assertNotNull(m.peakKneeFlexion)
        assertTrue(QualityIssue.NO_CONTACT_TIME in r.quality.issues)
        assertTrue(QualityIssue.NO_FOOT_DETAIL in r.quality.issues)
        assertEquals(ClipQuality.GOOD, r.quality.level)
    }

    @Test
    fun sixtyFramesASecondReadsTheLandingWithinAFewDegrees() {
        val spec = RunnerSpec(fps = 60.0)
        val truth = SyntheticRunner(spec).truth()
        val m = analyse(spec).metrics!!
        assertEquals(truth.kneeFlexionAtLandingDeg, m.kneeFlexionAtLanding!!.median, 4.0)
        assertEquals(truth.shinAtLandingDeg, m.shinAngleAtLanding!!.median, 3.0)
        assertEquals(FootStrike.REARFOOT, m.footStrike)
        assertNull(m.contactTimeMs)
    }

    @Test
    fun noisierPointsStillReadClose() {
        val spec = RunnerSpec(fps = 120.0, noisePx = 4.0)
        val truth = SyntheticRunner(spec).truth()
        val r = analyse(spec)
        val m = r.metrics!!
        assertEquals(172.0, m.cadenceSpm!!, 2.0)
        assertEquals(truth.contactMs, m.contactTimeMs!!.median, 20.0)
        assertEquals(truth.shinAtLandingDeg, m.shinAngleAtLanding!!.median, 3.0)
        assertEquals(truth.kneeFlexionAtLandingDeg, m.kneeFlexionAtLanding!!.median, 4.0)
    }

    @Test
    fun runningLeftOrRightInThePictureReadsTheSame() {
        val right = analyse(RunnerSpec(fps = 60.0, direction = 1)).metrics!!
        val left = analyse(RunnerSpec(fps = 60.0, direction = -1)).metrics!!
        assertEquals(right.shinAngleAtLanding!!.median, left.shinAngleAtLanding!!.median, 1.0)
        assertEquals(right.trunkLean!!.median, left.trunkLean!!.median, 0.5)
        assertEquals(right.footStrikeAngle!!.median, left.footStrikeAngle!!.median, 1.5)
    }

    @Test
    fun theRightLegNearTheCameraIsMeasured() {
        val r = analyse(RunnerSpec(fps = 60.0, nearSide = BodySide.RIGHT))
        assertEquals(BodySide.RIGHT, r.measuredSide)
        assertNotNull(r.metrics)
    }

    @Test
    fun swappedLabelsArePutBack() {
        val rnd = Random(3)
        val n = (12.0 * 60).toInt()
        val swapped = (0 until n).filter { rnd.nextDouble() < 0.08 }.toSet()
        val clean = analyse(RunnerSpec(fps = 60.0)).metrics!!
        val spec = RunnerSpec(fps = 60.0, swappedFrames = swapped)
        val tracks = buildTracks(SyntheticRunner(spec).clip())
        assertTrue("fixed ${tracks.swapsFixed} of ${swapped.size}", abs(tracks.swapsFixed - swapped.size) <= 2)
        val m = analyse(spec).metrics!!
        assertEquals(clean.shinAngleAtLanding!!.median, m.shinAngleAtLanding!!.median, 1.0)
        assertEquals(clean.cadenceSpm!!, m.cadenceSpm!!, 1.0)
    }

    @Test
    fun aClipWhoseFirstFrameIsSwappedFollowsTheRest() {
        val tracks = buildTracks(SyntheticRunner(RunnerSpec(fps = 60.0, swappedFrames = setOf(0))).clip())
        assertEquals(1, tracks.swapsFixed)
    }

    @Test
    fun forefootLandingUnderTheBodyAndOverstridingAreToldApart() {
        val forefoot = analyse(RunnerSpec(strikeDeg = -6.0, landAhead = 0.18)).metrics!!
        assertEquals(FootStrike.FOREFOOT, forefoot.footStrike)
        val overstride = analyse(RunnerSpec(landAhead = 0.50, toeOffBehind = 0.35))
        val shin = overstride.findings.first { it.key == "shin_angle" }
        assertEquals(FindingState.WORTH_A_LOOK, shin.state)
        val under = analyse(RunnerSpec(landAhead = 0.18)).findings.first { it.key == "shin_angle" }
        assertEquals(FindingState.TYPICAL, under.state)
    }

    @Test
    fun poorClipsSayWhyAndGetNoFindings() {
        fun poor(spec: RunnerSpec, setup: FormSetup = FormSetup()): FormReport {
            val r = analyse(spec, setup)
            assertEquals(ClipQuality.POOR, r.quality.level)
            assertNull(r.metrics)
            assertTrue(r.findings.isEmpty())
            return r
        }
        assertTrue(QualityIssue.FEET_CUT_OFF in poor(RunnerSpec(fps = 60.0, floorY = 1100.0)).quality.issues)
        assertTrue(QualityIssue.TOO_FEW_STEPS in poor(RunnerSpec(fps = 60.0, seconds = 1.0)).quality.issues)
        assertTrue(QualityIssue.NOT_SIDE_ON in poor(RunnerSpec(fps = 60.0, shoulderSpread = 0.8)).quality.issues)
        assertTrue(QualityIssue.REAR_NOT_YET in poor(RunnerSpec(fps = 60.0), FormSetup(view = FormView.REAR)).quality.issues)
        val dropped = (0 until 720).filter { it % 5 < 2 }.toSet()
        assertTrue(QualityIssue.NO_PERSON in poor(RunnerSpec(fps = 60.0, droppedFrames = dropped)).quality.issues)
    }

    @Test
    fun theFilterKeepsSlowMovementInPlaceAndRemovesJitter() {
        val fs = 120.0
        val slow = DoubleArray(600) { sin(2 * PI * 2.0 * it / fs) }
        val jitter = DoubleArray(600) { sin(2 * PI * 40.0 * it / fs) }
        val fSlow = lowPassZeroPhase(slow, fs, 12.0)
        val fJitter = lowPassZeroPhase(jitter, fs, 12.0)
        // No lag: the 2 Hz wave keeps its peaks where they were (zero phase) and nearly all its size.
        for (i in 100 until 500) assertEquals(slow[i], fSlow[i], 0.02)
        assertTrue((100 until 500).maxOf { abs(fJitter[it]) } < 0.1)
        // A constant stays a constant, to the ends.
        lowPassZeroPhase(DoubleArray(50) { 5.0 }, fs, 12.0).forEach { assertEquals(5.0, it, 1e-9) }
    }

    @Test
    fun shortGapsAreFilledAndLongOnesAreNot() {
        val t = DoubleArray(10) { it * 10.0 }
        val v = doubleArrayOf(0.0, 1.0, Double.NaN, Double.NaN, 4.0, 5.0, Double.NaN, Double.NaN, Double.NaN, 9.0)
        val filled = fillGaps(v, t, maxGapMs = 30.0)
        assertEquals(2.0, filled[2], 1e-9)
        assertEquals(3.0, filled[3], 1e-9)
        assertTrue(filled[6].isNaN())
    }

    @Test
    fun footStrikeFollowsAltmanAndDavis() {
        assertEquals(FootStrike.REARFOOT, footStrikeOf(8.2))
        assertEquals(FootStrike.MIDFOOT, footStrikeOf(8.1))
        assertEquals(FootStrike.MIDFOOT, footStrikeOf(-1.6))
        assertEquals(FootStrike.FOREFOOT, footStrikeOf(-1.7))
    }

    @Test
    fun watchValuesShowBesideTheClip() {
        val m = analyse(RunnerSpec(fps = 120.0)).metrics!!
        val f = formFindings(m, WatchRunValues(cadenceSpm = 170.0, groundContactMs = 245.0, verticalRatioPct = 7.9))
        assertTrue(f.first { it.key == "cadence" }.value.contains("watch 170"))
        assertTrue(f.first { it.key == "contact_time" }.value.contains("watch 245 ms"))
    }
}
