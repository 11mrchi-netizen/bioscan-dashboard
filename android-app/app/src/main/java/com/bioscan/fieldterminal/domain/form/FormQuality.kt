package com.bioscan.fieldterminal.domain.form

import com.bioscan.fieldterminal.domain.median
import kotlin.math.abs
import kotlin.math.hypot

// How good the clip is, judged apart from the running (motion-analysis-ai does the same): a POOR clip says why and what to
// change, and gets no form findings, since a bad clip would only be read as bad running.

enum class ClipQuality { GOOD, USABLE, POOR }

enum class QualityIssue(val blocking: Boolean, val text: String) {
    NO_PERSON(true, "The runner was found in under 70 % of the frames. Keep the whole body in frame, in good light."),
    PERSON_PARTLY(false, "The runner was missed in some frames; the steps in them were left out."),
    LEG_HIDDEN(true, "The leg nearer the camera is hard to see. Tighter or shorter clothing, more light, a plainer background."),
    LEG_UNCLEAR(false, "The near leg is not always clear; angles may be a few degrees off."),
    TOO_FEW_STEPS(true, "Fewer than three steps of the near foot were found. Film 10–20 s of steady running."),
    FEW_STEPS(false, "Fewer than six steps of the near foot: the medians rest on few steps. Film a little longer."),
    FRAME_RATE_TOO_LOW(true, "Under 25 frames a second: too few to follow a running stride."),
    NO_CONTACT_TIME(false, "Under 100 frames a second: contact and flight time are left out. Slow motion (120 or 240 fps) adds them."),
    NO_FOOT_DETAIL(false, "Under 50 frames a second the moment of landing is too coarse: landing angles, foot strike and hip extension are left out. Film at 60 fps or more."),
    NOT_SIDE_ON(true, "This does not look like a side view: the camera should be square to the direction of running."),
    ANGLED(false, "The camera looks a little off square to the running direction; angles are projections and may read a few degrees off."),
    FEET_CUT_OFF(true, "The feet leave the frame. Move the camera back or lower so both feet stay in view."),
    LABEL_SWAPS(false, "Left and right were mixed up in many frames and corrected; check the key frames."),
    UNSTEADY(false, "Step times vary a lot: an unsteady pace, or steps the analyser misread."),
    REAR_NOT_YET(true, "The rear view is not analysed yet; film from the side."),
}

data class QualityReport(
    val level: ClipQuality,
    val issues: List<QualityIssue>,
    /** Share of frames with a runner found. */
    val detectedShare: Double,
    /** Median visibility of the near leg's knee, ankle, heel and toe. */
    val nearLegVisibility: Double,
    val measuredSteps: Int,
    val fps: Double,
    val swappedShare: Double,
    /** Shoulder separation across the picture as a share of trunk length: near 0 side-on, near 1 facing the camera. */
    val shoulderSpreadRatio: Double?,
)

const val DETECTED_MIN = 0.7
const val DETECTED_GOOD = 0.9
const val NEAR_VISIBILITY_MIN = 0.5
const val NEAR_VISIBILITY_GOOD = 0.7
const val STEPS_MIN = 3
const val STEPS_GOOD = 6
const val SIDE_ON_MAX_SPREAD = 0.5
const val SQUARE_MAX_SPREAD = 0.3
const val FEET_CUT_MAX_SHARE = 0.2
const val SWAPS_WARN_SHARE = 0.15
const val UNSTEADY_VARIATION = 0.08

fun assessQuality(
    t: PoseTracks,
    setup: FormSetup,
    measuredSide: BodySide,
    measuredSteps: Int,
    stepTimeVariation: Double?,
): QualityReport {
    val issues = mutableListOf<QualityIssue>()
    if (setup.view == FormView.REAR) issues += QualityIssue.REAR_NOT_YET
    val frames = t.size.coerceAtLeast(1)
    val detectedShare = t.detected.count { it }.toDouble() / frames
    when {
        detectedShare < DETECTED_MIN -> issues += QualityIssue.NO_PERSON
        detectedShare < DETECTED_GOOD -> issues += QualityIssue.PERSON_PARTLY
    }
    val leg = legOf(measuredSide)
    val seenFrames = (0 until t.size).filter { t.detected[it] }
    val visibility = seenFrames.flatMap { i -> listOf(leg.knee, leg.ankle, leg.heel, leg.toe).map { t.visibility[it][i] } }
    val nearVisibility = if (visibility.isEmpty()) 0.0 else median(visibility)
    when {
        nearVisibility < NEAR_VISIBILITY_MIN -> issues += QualityIssue.LEG_HIDDEN
        nearVisibility < NEAR_VISIBILITY_GOOD -> issues += QualityIssue.LEG_UNCLEAR
    }
    when {
        t.fps < MIN_FPS_ANGLES -> issues += QualityIssue.FRAME_RATE_TOO_LOW
        t.fps < MIN_FPS_FOOT -> { issues += QualityIssue.NO_FOOT_DETAIL; issues += QualityIssue.NO_CONTACT_TIME }
        t.fps < MIN_FPS_CONTACT -> issues += QualityIssue.NO_CONTACT_TIME
    }
    when {
        measuredSteps < STEPS_MIN -> issues += QualityIssue.TOO_FEW_STEPS
        measuredSteps < STEPS_GOOD -> issues += QualityIssue.FEW_STEPS
    }
    // Side-on: the shoulders nearly overlap across the picture.
    val spreads = seenFrames.mapNotNull { i ->
        val dx = abs(t.x[Landmark.LEFT_SHOULDER][i] - t.x[Landmark.RIGHT_SHOULDER][i])
        val sx = (t.x[Landmark.LEFT_SHOULDER][i] + t.x[Landmark.RIGHT_SHOULDER][i]) / 2
        val sy = (t.y[Landmark.LEFT_SHOULDER][i] + t.y[Landmark.RIGHT_SHOULDER][i]) / 2
        val trunk = hypot(sx - hipMidX(t, i), sy - hipMidY(t, i))
        if (dx.isNaN() || trunk.isNaN() || trunk <= 0) null else dx / trunk
    }
    val spread = if (spreads.isEmpty()) null else median(spreads)
    if (setup.view == FormView.SIDE && spread != null) {
        when {
            spread > SIDE_ON_MAX_SPREAD -> issues += QualityIssue.NOT_SIDE_ON
            spread > SQUARE_MAX_SPREAD -> issues += QualityIssue.ANGLED
        }
    }
    // Feet out of frame: the near foot's points pinned to the bottom edge, or not seen.
    val bottom = t.heightPx * 0.985
    val cut = seenFrames.count { i -> listOf(leg.heel, leg.toe).any { j -> t.y[j][i].isNaN() || t.y[j][i] >= bottom } }
    if (seenFrames.isNotEmpty() && cut.toDouble() / seenFrames.size > FEET_CUT_MAX_SHARE) issues += QualityIssue.FEET_CUT_OFF
    val swappedShare = t.swapsFixed.toDouble() / frames
    if (swappedShare > SWAPS_WARN_SHARE) issues += QualityIssue.LABEL_SWAPS
    if (stepTimeVariation != null && stepTimeVariation > UNSTEADY_VARIATION) issues += QualityIssue.UNSTEADY

    val level = when {
        issues.any { it.blocking } -> ClipQuality.POOR
        issues.any { it != QualityIssue.NO_CONTACT_TIME && it != QualityIssue.NO_FOOT_DETAIL } -> ClipQuality.USABLE
        else -> ClipQuality.GOOD
    }
    return QualityReport(level, issues, detectedShare, nearVisibility, measuredSteps, t.fps, swappedShare, spread)
}
