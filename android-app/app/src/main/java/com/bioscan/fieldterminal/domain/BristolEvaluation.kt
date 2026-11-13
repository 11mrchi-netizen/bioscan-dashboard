package com.bioscan.fieldterminal.domain

// Phase A4 (Analysis Layer). Evaluation Method Spec, Category 8 (digestive/
// Bristol half): a rolling 14-day distribution, not a daily value -- a
// single BSFS reading is nearly meaningless per the spec; the pattern
// across days is the signal (Lewis & Heaton 1997: change in stool form
// correlates with change in whole-gut transit time at r=-0.65). This
// category was never given the spec's six-state vocabulary (no
// NO_DATA/BUILDING/STABLE/...) -- it gets its own pattern labels instead,
// same precedent as Category 7's flags/cadence labels.
//
// Non-medical guard, per the spec's own explicit instruction: these labels
// are descriptive only, never phrased as a condition. See BristolCard in
// ui/screens/status/AnalysisScreen.kt for the on-screen disclaimer.
enum class BristolPattern { PredominantlyFirm, PredominantlyLoose, Mixed, Typical }

data class BristolEvaluation(
    val confidence: Confidence,
    val pctHard: Double?,
    val pctNormal: Double?,
    val pctLoose: Double?,
    val pattern: BristolPattern?,
)

private const val WINDOW_DAYS = 14

