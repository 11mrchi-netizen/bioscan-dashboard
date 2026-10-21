package com.bioscan.fieldterminal.domain.form

import kotlin.math.roundToInt

// What the report says about each metric: fixed rules, each with its reason and source. Published work gives directions
// (a shin well ahead at landing is the overstride sign) more than cut-offs; where this app draws a line of its own it says
// so ("app cut-off"). The main reading is the person's own change between analyses, as everywhere else in the app.

enum class FindingState { TYPICAL, WORTH_A_LOOK, INFO }

data class FormFinding(
    val key: String,
    val title: String,
    val value: String,
    val state: FindingState,
    val reason: String,
    val source: String,
)

/** The watch's values for the same run, when the clip was filmed during a recorded run. */
data class WatchRunValues(val cadenceSpm: Double?, val groundContactMs: Double?, val verticalRatioPct: Double?)

/** App cut-offs (not from a study): a shin more than this far forward at landing, a knee straighter than this. */
const val SHIN_FORWARD_LOOK_DEG = 10.0
const val KNEE_STRAIGHT_LOOK_DEG = 10.0
const val TRUNK_LEAN_LOOK_DEG = 20.0
const val STEP_VARIATION_LOOK = 0.06

private const val SOUZA = "Souza 2016, video running analysis"
private const val LIEBERMAN = "Lieberman et al. 2015"
private const val ALTMAN = "Altman & Davis 2012"
private const val HEIDERSCHEIT = "Heiderscheit et al. 2011"
private const val TENG = "Teng & Powers 2014"

private fun deg(v: Double) = "${v.roundToInt()}°"
private fun spread(s: MetricSummary, unit: String) = if (s.n >= 3) " (± ${(s.spread / 2).roundToInt()}$unit)" else ""

fun formFindings(m: FormMetrics, watch: WatchRunValues? = null): List<FormFinding> {
    val out = mutableListOf<FormFinding>()

    m.cadenceSpm?.let { c ->
        val vs = watch?.cadenceSpm?.let { " · watch ${it.roundToInt()}" }.orEmpty()
        out += FormFinding(
            "cadence", "CADENCE", "${c.roundToInt()} spm$vs", FindingState.INFO,
            "Steps per minute. At the same speed, 5–10 % more steps lowers the load at the hip and knee.",
            HEIDERSCHEIT,
        )
    }
    m.shinAngleAtLanding?.let { s ->
        val look = s.median > SHIN_FORWARD_LOOK_DEG
        out += FormFinding(
            "shin_angle", "SHIN AT LANDING", deg(s.median) + spread(s, "°"),
            if (look) FindingState.WORTH_A_LOOK else FindingState.TYPICAL,
            if (look) "The shin is well ahead of vertical at landing, the usual sign of overstriding: the foot lands far in front " +
                "of the knee and brakes. A slightly higher cadence or landing closer under the body brings it back."
            else "The shin is near vertical at landing: the foot lands under the knee, not far ahead of it.",
            "$SOUZA; $LIEBERMAN; over ${SHIN_FORWARD_LOOK_DEG.roundToInt()}° is an app cut-off",
        )
    }
    m.ankleAheadOfHip?.let { a ->
        out += FormFinding(
            "ankle_ahead", "FOOT AHEAD OF HIPS", "${(a.median * 100).roundToInt()} % of leg length", FindingState.INFO,
            "How far in front of the hips the ankle lands. Smaller is a landing closer under the body; it moves with the shin angle.",
            LIEBERMAN,
        )
    }
    m.kneeFlexionAtLanding?.let { k ->
        val look = k.median < KNEE_STRAIGHT_LOOK_DEG
        out += FormFinding(
            "knee_landing", "KNEE AT LANDING", deg(k.median) + spread(k, "°"),
            if (look) FindingState.WORTH_A_LOOK else FindingState.TYPICAL,
            if (look) "The knee is nearly straight at landing, which goes with landing ahead of the body; a softer knee takes the load."
            else "The knee is already bent at landing, as it usually is in running.",
            "$SOUZA; under ${KNEE_STRAIGHT_LOOK_DEG.roundToInt()}° is an app cut-off",
        )
    }
    m.peakKneeFlexion?.let { k ->
        out += FormFinding(
            "knee_peak", "KNEE BEND IN STANCE", deg(k.median), FindingState.INFO,
            "The most the knee bends while the foot is on the ground. Read it against your own earlier clips.", SOUZA,
        )
    }
    m.footStrike?.let { fs ->
        val a = m.footStrikeAngle!!
        out += FormFinding(
            "foot_strike", "FOOT STRIKE", "${fs.label} (${deg(a.median)})", FindingState.INFO,
            "Which part of the foot lands first, from the foot's angle at landing against its angle flat on the ground. " +
                "No pattern is right in itself; changing it moves load between the knee and the calf and Achilles.",
            "$ALTMAN; an estimate from the video's heel and toe points",
        )
    }
    m.contactTimeMs?.let { c ->
        val vs = watch?.groundContactMs?.let { " · watch ${it.roundToInt()} ms" }.orEmpty()
        out += FormFinding(
            "contact_time", "CONTACT TIME", "${c.median.roundToInt()} ms$vs", FindingState.INFO,
            "From landing to toe-off of the near foot. Recreational runners usually sit between 200 and 300 ms; it shortens with speed.",
            "From the clip's frames; checked against the watch when the run was recorded",
        )
    }
    m.hipExtensionAtToeOff?.let { h ->
        out += FormFinding(
            "hip_extension", "HIP EXTENSION AT TOE-OFF", deg(h.median), FindingState.INFO,
            "How far the thigh is behind vertical as the foot leaves the ground. Read it against your own earlier clips.", SOUZA,
        )
    }
    m.trunkLean?.let { l ->
        val look = l.median < 0 || l.median > TRUNK_LEAN_LOOK_DEG
        out += FormFinding(
            "trunk_lean", "TRUNK LEAN", deg(l.median), if (look) FindingState.WORTH_A_LOOK else FindingState.TYPICAL,
            when {
                l.median < 0 -> "Leaning back: the knee takes more of the landing."
                l.median > TRUNK_LEAN_LOOK_DEG -> "A strong forward lean: it eases the knee but loads the hip and back."
                else -> "A slight forward lean, which shifts some load from the knee to the hip."
            },
            "$TENG; outside 0–${TRUNK_LEAN_LOOK_DEG.roundToInt()}° is an app cut-off",
        )
    }
    m.verticalOscillation?.let { v ->
        val vs = watch?.verticalRatioPct?.let { " · watch vertical ratio ${"%.1f".format(it)} %" }.orEmpty()
        out += FormFinding(
            "vertical_oscillation", "BOUNCE", "${"%.1f".format(v.median * 100)} % of leg length$vs", FindingState.INFO,
            "How far the hips rise and fall each stride. Read it against your own earlier clips and the watch.", SOUZA,
        )
    }
    m.stepTimeVariation?.let { cv ->
        if (cv > STEP_VARIATION_LOOK) out += FormFinding(
            "step_variation", "STEP TIMING", "varies ${(cv * 100).roundToInt()} %", FindingState.WORTH_A_LOOK,
            "Step times vary more than usual in steady running: a changing pace, or steps the analyser misread.",
            "Over ${(STEP_VARIATION_LOOK * 100).roundToInt()} % is an app cut-off",
        )
    }
    return out
}
