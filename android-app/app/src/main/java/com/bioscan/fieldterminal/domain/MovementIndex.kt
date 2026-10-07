package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.comparison.personalBaseline
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// 09.3 Movement Index (DAV-242-245). Separates everyday movement volume from
// structured exercise engagement rather than equating step count with
// overall movement quality (DAV-242's own rule). ACTIVE_CALORIES and
// ZONE_MINUTES are declared in the contract for later expansion but never
// resolve today -- this account's real coverage (7% / ~1% of wearable_daily
// rows) is too thin to score honestly, so they always report `unavailable`
// rather than fabricating a value from sparse data.
const val MOVEMENT_INDEX_MODEL_VERSION = "v1"

enum class MovementIndexComponent { STEP_VOLUME, EXERCISE_ENGAGEMENT, ACTIVE_CALORIES, ZONE_MINUTES }

data class MovementIndexComponentScore(val score: Double, val weight: Double)

data class MovementIndexResult(
    val score: Double?,
    val state: EvalState,
    val confidence: Confidence,
    val components: Map<MovementIndexComponent, MovementIndexComponentScore>,
    val unavailableComponents: Set<MovementIndexComponent>,
    val modelVersion: String = MOVEMENT_INDEX_MODEL_VERSION,
)

// Full declared weighting once all four dimensions are real (DAV-242's
// "design the contract for later expansion") -- today's score always
// renormalizes over only the components that actually resolved.
private val COMPONENT_WEIGHTS = mapOf(
    MovementIndexComponent.STEP_VOLUME to 0.40,
    MovementIndexComponent.EXERCISE_ENGAGEMENT to 0.30,
    MovementIndexComponent.ACTIVE_CALORIES to 0.15,
    MovementIndexComponent.ZONE_MINUTES to 0.15,
)

// Unlike Sleep Index's 2-of-4 gate, two of this index's four declared
// dimensions are structurally unavailable at this account's current data
// coverage (not just occasionally missing) -- requiring 2 would make the
// index permanently BUILDING. One real, well-baselined component is enough
// to report, same "real gap flagged, not blocked on it" convention this
// app already applies to Cardio Age's population-median ceiling.
private const val MIN_COMPONENTS_FOR_INDEX = 1
private const val BASELINE_WINDOW_DAYS = 60
private const val RECENT_WINDOW_DAYS = 7L

// "How close is your recent week to your own established volume" -- ratio to
// personal baseline mean, capped at 100 (more isn't scored as unboundedly
// better -- DAV-242's own "avoid equating step count with movement quality"
// rule), same within-person-only philosophy SleepIndex.kt's closenessScore
// uses for duration/stage.
private fun volumeScore(dailyValues: List<Pair<LocalDate, Double>>, asOf: LocalDate): Double? {
    val baseline = personalBaseline(dailyValues, BASELINE_WINDOW_DAYS, asOf) ?: return null
    if (baseline.mean <= 0) return null
    // A real drop to zero recent activity against an established baseline is
    // a real low score, not missing data -- only the baseline itself being
    // ungated (above) means "not enough history to judge," never an empty
    // recent window.
    val recent = dailyValues.filter { !it.first.isAfter(asOf) && ChronoUnit.DAYS.between(it.first, asOf) < RECENT_WINDOW_DAYS }
    val recentMean = if (recent.isEmpty()) 0.0 else recent.sumOf { it.second } / recent.size
    return (recentMean / baseline.mean).coerceIn(0.0, 1.0) * 100.0
}

fun computeMovementIndex(
    stepsDaily: List<Pair<LocalDate, Double>>,
    exerciseSessionStarts: List<OffsetDateTime>,
    asOf: LocalDate = LocalDate.now(),
): MovementIndexResult {
    val components = mutableMapOf<MovementIndexComponent, MovementIndexComponentScore>()

    volumeScore(stepsDaily, asOf)?.let {
        components[MovementIndexComponent.STEP_VOLUME] = MovementIndexComponentScore(it, COMPONENT_WEIGHTS.getValue(MovementIndexComponent.STEP_VOLUME))
    }

    val sessionsPerDay = exerciseSessionStarts
        .groupingBy { it.toLocalDate() }
        .eachCount()
        .map { (date, count) -> date to count.toDouble() }
    volumeScore(sessionsPerDay, asOf)?.let {
        components[MovementIndexComponent.EXERCISE_ENGAGEMENT] = MovementIndexComponentScore(it, COMPONENT_WEIGHTS.getValue(MovementIndexComponent.EXERCISE_ENGAGEMENT))
    }

    val unavailable = setOf(MovementIndexComponent.ACTIVE_CALORIES, MovementIndexComponent.ZONE_MINUTES)

    if (components.size < MIN_COMPONENTS_FOR_INDEX) {
        return MovementIndexResult(null, EvalState.Building, Confidence(components.size, MIN_COMPONENTS_FOR_INDEX), components, unavailable)
    }

    val totalWeight = components.values.sumOf { it.weight }
    val score = components.values.sumOf { it.score * it.weight } / totalWeight
    return MovementIndexResult(score, EvalState.Stable, Confidence(components.size, COMPONENT_WEIGHTS.size), components, unavailable)
}
