package com.bioscan.fieldterminal.domain.form

import com.bioscan.fieldterminal.domain.median

// The running form analyser end to end, from the pose extractor's clip to the report: clean tracks, the near foot's
// contacts, the metrics, the clip's quality and the findings (docs/running-form/README.md).

const val FORM_ANALYSER_VERSION = "1.0.0"

/** What the runner tells the app before the clip is read. */
data class FormSetup(
    val view: FormView = FormView.SIDE,
    val surface: FormSurface = FormSurface.TREADMILL,
    /** The side of the body facing the camera, when the runner says; else the leg MediaPipe sees better. */
    val sideFacingCamera: BodySide? = null,
    val treadmillSpeedKmh: Double? = null,
)

data class FormReport(
    val setup: FormSetup,
    val quality: QualityReport,
    /** The leg measured in detail: the one nearer the camera. */
    val measuredSide: BodySide,
    /** +1 running toward the right of the picture, -1 toward the left. */
    val direction: Int,
    /** Null for a POOR clip: no reading from a clip that cannot be read. */
    val metrics: FormMetrics?,
    val findings: List<FormFinding>,
    val contacts: List<FootContact>,
    val analyserVersion: String = FORM_ANALYSER_VERSION,
)

/** The far foot's landings count toward cadence only when it is seen this well and found about as often as the near one. */
const val FAR_LEG_MIN_VISIBILITY = 0.5

fun analyseRunningForm(clip: PoseClip, setup: FormSetup, watch: WatchRunValues? = null): FormReport =
    analyseTracks(buildTracks(clip), setup, watch)

fun analyseTracks(tracks: PoseTracks, setup: FormSetup, watch: WatchRunValues? = null): FormReport {
    // MediaPipe's own left and right can be reversed for a whole clip; the near leg is the one it sees better, whatever
    // its label.
    val near = nearSide(tracks)
    val direction = travelDirection(tracks)
    val nearContacts = detectContacts(tracks, near, direction)
    val far = near.other
    val farLeg = legOf(far)
    val farSeen = (0 until tracks.size).filter { tracks.detected[it] }
        .flatMap { i -> listOf(farLeg.ankle, farLeg.heel, farLeg.toe).map { tracks.visibility[it][i] } }
    val farContacts = if (farSeen.isNotEmpty() && median(farSeen) >= FAR_LEG_MIN_VISIBILITY) detectContacts(tracks, far, direction) else emptyList()
    val bothFeet = farContacts.isNotEmpty() && kotlin.math.abs(farContacts.size - nearContacts.size) <= 1
    val landings = if (bothFeet) (nearContacts + farContacts).map { it.landingMs } else null

    val metrics = computeFormMetrics(tracks, near, nearContacts, landings, direction)
    val quality = assessQuality(tracks, setup, near, nearContacts.size, metrics.stepTimeVariation)
    val readable = quality.level != ClipQuality.POOR
    return FormReport(
        setup = setup,
        quality = quality,
        measuredSide = setup.sideFacingCamera ?: near,
        direction = direction,
        metrics = if (readable) metrics else null,
        findings = if (readable) formFindings(metrics, watch) else emptyList(),
        contacts = nearContacts + if (bothFeet) farContacts else emptyList(),
    )
}
