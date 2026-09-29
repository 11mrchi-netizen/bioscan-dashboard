package com.bioscan.fieldterminal.domain.achievement

import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.Provenance
import java.time.OffsetDateTime

// DAV-290: canonical achievement/record shape, generic across every domain
// this app tracks -- built so it can accept a record from Health Connect,
// Zepp, a Notion import, or a future manual entry alike, not fitted to one
// source. Kotlin model only this milestone: no persistence table yet (see
// docs/user-profile-milestone/01-canonical-contracts-audit.md, decision 3) --
// the archive milestone's problem once real historical import needs
// somewhere to write these. DAV-296's User page consumes mock data shaped
// like this so no UI rewrite is needed once real records exist.
enum class AchievementDomain { RUNNING, STRENGTH, CONDITIONING, MOUNTAIN }

data class Achievement(
    val domain: AchievementDomain,
    // Free-text metric id, e.g. "fastest_1km", "longest_run_km",
    // "heaviest_squat_kg", "best_estimated_1rm_kg", "itra_race_ranking" --
    // deliberately not a closed enum: new record types shouldn't need a code
    // change to this file, only a new value here.
    val metric: String,
    val activityRef: String?,
    val value: Double,
    val unit: String,
    val occurredAt: OffsetDateTime,
    val provenance: Provenance,
    // 0..1, only when the source estimate itself carries one (e.g. an
    // estimated 1RM); null when the value is a direct measurement.
    val confidence: Double? = null,
    val comparisonContext: String? = null,
)

// The "is this a new record" rule mirrors exactly what
// domain/comparison/PersonalComparison.kt's personalBest() already applies
// (max for HIGHER_BETTER, min for LOWER_BETTER) -- same question, wider
// scope (all-time history here vs. a 28-day rolling baseline there), so the
// rule has to match rather than drift into a second definition of "best."
fun isNewRecord(candidate: Double, priorValues: List<Double>, directionality: Directionality): Boolean {
    val best = when (directionality) {
        Directionality.HIGHER_BETTER -> priorValues.maxOrNull()
        Directionality.LOWER_BETTER -> priorValues.minOrNull()
        Directionality.OPTIMAL_RANGE, Directionality.TARGET_VALUE, Directionality.NON_DIRECTIONAL -> return false
    } ?: return true // no prior values -- the first observation is trivially a record
    return when (directionality) {
        Directionality.HIGHER_BETTER -> candidate > best
        Directionality.LOWER_BETTER -> candidate < best
        else -> false
    }
}
