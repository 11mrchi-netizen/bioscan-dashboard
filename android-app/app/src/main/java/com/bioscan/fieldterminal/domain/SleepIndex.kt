package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.ZoneId

// 09.1 Sleep Index (DAV-234-237). Aggregates this app's own already-real
// sleep evaluations (duration SWC, SRI, respiratory anomaly, stage SWC) into
// one transparent 0-100 index -- an aggregation layer over the existing
// Category 2 evaluations, never a replacement or a second formula for any
// of them (DAV-235's own explicit rule). Named, fixed weights below, not a
// fitted model -- this account's data volume doesn't justify fitting one yet,
// same gate this app's other models already apply (see DAV-63's own 180-day
// minimum) before treating a weight as anything but a documented opinion.
const val SLEEP_INDEX_MODEL_VERSION = "v1"

enum class SleepIndexComponent { DURATION, REGULARITY, RESPIRATORY_STABILITY, STAGE_COMPOSITION }

data class SleepIndexComponentScore(val score: Double, val weight: Double)

data class SleepIndexResult(
    val score: Double?,
    val band: String?,
    // Building until the minimum-component gate clears, Stable once a real
    // score exists -- this composite has no up/down trend semantics of its
    // own (each input component already carries its own ShiftUp/ShiftDown),
    // so ShiftUp/ShiftDown/Unstable are never used here.
    val state: EvalState,
    val confidence: Confidence,
    val components: Map<SleepIndexComponent, SleepIndexComponentScore>,
    val modelVersion: String = SLEEP_INDEX_MODEL_VERSION,
)

private val COMPONENT_WEIGHTS = mapOf(
    SleepIndexComponent.DURATION to 0.35,
    SleepIndexComponent.REGULARITY to 0.30,
    SleepIndexComponent.RESPIRATORY_STABILITY to 0.15,
    SleepIndexComponent.STAGE_COMPOSITION to 0.20,
)
private const val MIN_COMPONENTS_FOR_INDEX = 2
private const val RESPIRATORY_FLAGGED_SCORE = 40.0

// "How close is your recent week to your own established norm" -- a ratio to
// personal 60-day baseline, never a universal target, matching this app's
// within-person-only rule (domain/EvalState.kt's own header comment).
private fun closenessScore(baseline7d: Double?, mean60d: Double?): Double? {
    if (baseline7d == null || mean60d == null || mean60d <= 0) return null
    return (baseline7d / mean60d).coerceIn(0.0, 1.0) * 100.0
}

// Analysis review phase G: one set of cut-offs for the Sleep Index, used by its own word and by Today's recovery (which
// used 70 and 50 against the card's 75 and 55), in the vocabulary every composite shares. The index measures how close
// the week's sleep came to the person's own norms (duration and stages against their 60-day means, capped at 100) and how
// regular it was, so it reads AS EXPECTED near the top, BELOW EXPECTED under 75 and UNUSUALLY LOW under 55. It has no
// ABOVE EXPECTED: by construction a week cannot score better than its own norm.
const val SLEEP_INDEX_AS_EXPECTED = 75.0
const val SLEEP_INDEX_UNUSUALLY_LOW = 55.0

fun sleepIndexBand(score: Double): String = when {
    score >= SLEEP_INDEX_AS_EXPECTED -> "AS EXPECTED"
    score >= SLEEP_INDEX_UNUSUALLY_LOW -> "BELOW EXPECTED"
    else -> "UNUSUALLY LOW"
}

/** The Sleep Index's pull on Today's recovery, from the same cut-offs as its word: none when as expected, down below. */
fun sleepIndexDirection(score: Double): Int = if (score >= SLEEP_INDEX_AS_EXPECTED) 0 else -1

fun computeSleepIndex(
    hoursDaily: List<Pair<LocalDate, Double>>,
    deepMinutesDaily: List<Pair<LocalDate, Double>>,
    respiratoryDaily: List<Pair<LocalDate, Double>>,
    nights: List<SleepNight>,
    zone: ZoneId = ZoneId.systemDefault(),
    asOf: LocalDate = LocalDate.now(),
    durationWindowStart: LocalDate? = null,
    stageWindowStart: LocalDate? = null,
): SleepIndexResult {
    val duration = evaluateSleepDuration(hoursDaily, asOf, durationWindowStart)
    val stage = evaluateSleepStage(deepMinutesDaily, asOf, stageWindowStart)
    val sri = evaluateSri(nights, zone, asOf)
    val respiratory = evaluateRespiratoryAnomaly(respiratoryDaily, asOf)

    val components = mutableMapOf<SleepIndexComponent, SleepIndexComponentScore>()
    if (duration.confidence.met) {
        closenessScore(duration.baseline7d, duration.mean60d)?.let {
            components[SleepIndexComponent.DURATION] = SleepIndexComponentScore(it, COMPONENT_WEIGHTS.getValue(SleepIndexComponent.DURATION))
        }
    }
    if (sri.confidence.met) {
        sri.value?.let {
            components[SleepIndexComponent.REGULARITY] = SleepIndexComponentScore(it, COMPONENT_WEIGHTS.getValue(SleepIndexComponent.REGULARITY))
        }
    }
    if (respiratory.confidence.met) {
        val score = if (respiratory.flagged) RESPIRATORY_FLAGGED_SCORE else 100.0
        components[SleepIndexComponent.RESPIRATORY_STABILITY] = SleepIndexComponentScore(score, COMPONENT_WEIGHTS.getValue(SleepIndexComponent.RESPIRATORY_STABILITY))
    }
    if (stage.confidence.met) {
        closenessScore(stage.baseline7d, stage.mean60d)?.let {
            components[SleepIndexComponent.STAGE_COMPOSITION] = SleepIndexComponentScore(it, COMPONENT_WEIGHTS.getValue(SleepIndexComponent.STAGE_COMPOSITION))
        }
    }

    if (components.size < MIN_COMPONENTS_FOR_INDEX) {
        return SleepIndexResult(null, null, EvalState.Building, Confidence(components.size, MIN_COMPONENTS_FOR_INDEX), components)
    }

    // Renormalized over only the available components -- missingness already
    // degraded confidence above; it shouldn't also silently drag the score
    // toward zero via an unavailable component's absent weight.
    val totalWeight = components.values.sumOf { it.weight }
    val score = components.values.sumOf { it.score * it.weight } / totalWeight
    return SleepIndexResult(score, sleepIndexBand(score), EvalState.Stable, Confidence(components.size, COMPONENT_WEIGHTS.size), components)
}
