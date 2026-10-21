package com.bioscan.fieldterminal.domain

// Running page, VOLUME: every run in one of three groups, so a week's kilometres split by what kind of running they were.
//  - TRAIL: tagged trail, or recorded by Zepp as a trail run (sport 7).
//  - QUALITY: the hard sessions -- tempo, threshold, intervals, hills, races.
//  - EASY: easy, recovery and long runs.
// A tagged run type always decides. Most runs carry no type (the tag only exists since Sep 2026), so an untyped road run
// is placed from what it recorded: a climb of HILLS_MIN_M_PER_KM or more is a hill session; otherwise heart rate decides,
// QUALITY when QUALITY_Z4_SHARE or more of its time was in zone 4 or above (the run's own histogram, or its average heart
// rate when there is no histogram), in the person's own zones as of that date. Checked read-only against the 14 runs
// tagged so far: the rule put 11 of the 11 tagged road runs in the same group (Oct 2026; heart rate alone missed both
// hill sessions, which is why the climb comes first).

enum class RunCategory(val label: String) { EASY("EASY"), TRAIL("TRAIL"), QUALITY("QUALITY") }

/** Why a run sits in its group: its tag, or what the rule read from it. */
enum class RunCategoryBasis { TAG, TRAIL, CLIMB, HEART_RATE_TIME, AVERAGE_HEART_RATE, NO_HEART_RATE }

data class RunClassification(val category: RunCategory, val basis: RunCategoryBasis)

const val QUALITY_Z4_SHARE = 0.20
const val HILLS_MIN_M_PER_KM = 25.0

private val QUALITY_RUN_TYPES = setOf("tempo", "threshold", "intervals", "hills", "race")
private val EASY_RUN_TYPES = setOf("easy", "long", "recovery")

/** Zepp's sport code for a trail run. */
const val ZEPP_TRAIL_SPORT = "7"

fun isTrailRun(routeType: String?, zeppSportType: String?): Boolean = routeType == "trail" || zeppSportType == ZEPP_TRAIL_SPORT

/** Seconds at or above [floorBpm] as a share of all the histogram's seconds; null for an empty histogram. */
fun shareAtOrAbove(histogram: Map<Int, Double>, floorBpm: Double): Double? {
    val total = histogram.values.sum()
    if (total <= 0.0) return null
    return histogram.entries.filter { it.key >= floorBpm }.sumOf { it.value } / total
}

/**
 * The group a run belongs to. [z4FloorBpm] is where zone 4 starts for this run (its date's anchors, the person's method);
 * [hrHistogram] is seconds per bpm.
 */
fun classifyRun(
    routeType: String?,
    runType: String?,
    zeppSportType: String?,
    distanceKm: Double,
    elevationGainM: Double?,
    hrHistogram: Map<Int, Double>?,
    avgHr: Double?,
    z4FloorBpm: Double?,
): RunClassification {
    if (isTrailRun(routeType, zeppSportType)) return RunClassification(RunCategory.TRAIL, RunCategoryBasis.TRAIL)
    val type = runType?.lowercase()
    if (type in QUALITY_RUN_TYPES) return RunClassification(RunCategory.QUALITY, RunCategoryBasis.TAG)
    if (type in EASY_RUN_TYPES) return RunClassification(RunCategory.EASY, RunCategoryBasis.TAG)
    if (elevationGainM != null && distanceKm > 0 && elevationGainM / distanceKm >= HILLS_MIN_M_PER_KM) {
        return RunClassification(RunCategory.QUALITY, RunCategoryBasis.CLIMB)
    }
    if (z4FloorBpm != null) {
        hrHistogram?.let { shareAtOrAbove(it, z4FloorBpm) }?.let { share ->
            val category = if (share >= QUALITY_Z4_SHARE) RunCategory.QUALITY else RunCategory.EASY
            return RunClassification(category, RunCategoryBasis.HEART_RATE_TIME)
        }
        if (avgHr != null && avgHr > 0) {
            val category = if (avgHr >= z4FloorBpm) RunCategory.QUALITY else RunCategory.EASY
            return RunClassification(category, RunCategoryBasis.AVERAGE_HEART_RATE)
        }
    }
    return RunClassification(RunCategory.EASY, RunCategoryBasis.NO_HEART_RATE)
}
