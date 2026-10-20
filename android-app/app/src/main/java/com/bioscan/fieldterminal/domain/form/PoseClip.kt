package com.bioscan.fieldterminal.domain.form

// Running form analyser (docs/running-form/README.md): a clip as the pose extractor hands it over. Points are in pixels of
// the upright video frame (x to the right, y down), so angles drawn on the picture are the angles measured here.

/** Which way the camera looks at the runner. */
enum class FormView { SIDE, REAR }

enum class FormSurface { TREADMILL, OVERGROUND }

enum class BodySide { LEFT, RIGHT; val other: BodySide get() = if (this == LEFT) RIGHT else LEFT }

/** One body point in pixels with MediaPipe's visibility (0 to 1, how likely the point is in view and not hidden). */
data class PosePoint(val x: Double, val y: Double, val visibility: Double = 1.0)

/** A frame's time from the start of the clip and MediaPipe's 33 points, or null when no person was found. */
data class PoseFrame(val timeMs: Double, val points: List<PosePoint>?)

data class PoseClip(val frames: List<PoseFrame>, val widthPx: Int, val heightPx: Int)

// MediaPipe Pose Landmarker's 33 points, the ones the analyser reads.
object Landmark {
    const val COUNT = 33
    const val NOSE = 0
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32

    /** Left and right partners, the pairs MediaPipe can swap. */
    val PAIRS: List<Pair<Int, Int>> = listOf(
        LEFT_EAR to RIGHT_EAR, LEFT_SHOULDER to RIGHT_SHOULDER, LEFT_ELBOW to RIGHT_ELBOW, LEFT_WRIST to RIGHT_WRIST,
        LEFT_HIP to RIGHT_HIP, LEFT_KNEE to RIGHT_KNEE, LEFT_ANKLE to RIGHT_ANKLE, LEFT_HEEL to RIGHT_HEEL,
        LEFT_FOOT_INDEX to RIGHT_FOOT_INDEX,
    )
}

/** The points of one leg. */
data class LegIndices(val hip: Int, val knee: Int, val ankle: Int, val heel: Int, val toe: Int) {
    val all: List<Int> get() = listOf(hip, knee, ankle, heel, toe)
}

fun legOf(side: BodySide): LegIndices = when (side) {
    BodySide.LEFT -> LegIndices(Landmark.LEFT_HIP, Landmark.LEFT_KNEE, Landmark.LEFT_ANKLE, Landmark.LEFT_HEEL, Landmark.LEFT_FOOT_INDEX)
    BodySide.RIGHT -> LegIndices(Landmark.RIGHT_HIP, Landmark.RIGHT_KNEE, Landmark.RIGHT_ANKLE, Landmark.RIGHT_HEEL, Landmark.RIGHT_FOOT_INDEX)
}
