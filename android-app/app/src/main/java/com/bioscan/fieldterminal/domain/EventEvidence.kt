package com.bioscan.fieldterminal.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZonedDateTime

// Repeatable, timed interventions (an informal N-of-1 layer beside the pre-registered experiments). Pure rules the sheets
// share; the evidence itself is computed by supabase/functions/_shared/analysis-core/eventEvidence.ts and only read here.
// An expectation names an outcome and a window in hours after the event, declared before the first event and then locked
// by the database (intervention_expectations_lock), so the window can't be re-chosen after seeing data.

/** A daily stored series an expectation can name (a row of metric-series.json read from the packaged asset). */
data class OutcomeSeries(val id: String, val label: String, val unit: String)

/** The daily series on a local date (sleep, HRV, ratings, ...); per-event and UTC-instant series are left out. */
fun parseOutcomeSeries(text: String): List<OutcomeSeries> =
    runCatching {
        Json.parseToJsonElement(text).jsonObject["series"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val kind = o["time"]?.jsonObject?.get("kind")?.jsonPrimitive?.content
            val cadence = o["cadence"]?.jsonPrimitive?.content
            if (kind != "local_date" || cadence != "daily") return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val label = o["label"]?.jsonPrimitive?.content ?: return@mapNotNull null
            OutcomeSeries(id, label, o["unit"]?.jsonPrimitive?.content ?: "")
        }
    }.getOrNull() ?: emptyList()

data class WindowPreset(val label: String, val startH: Double, val endH: Double)

// For an event in the evening, "that night" is the 12 hours after it; the others are read from the event time.
val WINDOW_PRESETS: List<WindowPreset> = listOf(
    WindowPreset("That night", 0.0, 12.0),
    WindowPreset("Within 2 h", 0.0, 2.0),
    WindowPreset("Next 24 h", 0.0, 24.0),
    WindowPreset("+6 to +36 h", 6.0, 36.0),
)

const val MAX_WINDOW_HOURS = 24.0 * 14

fun windowText(startH: Double, endH: Double): String {
    fun h(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else v.toString()
    return if (startH == 0.0) "within ${h(endH)} h" else "+${h(startH)} to +${h(endH)} h"
}

fun validWindow(startH: Double, endH: Double): Boolean = startH >= 0.0 && endH > startH && endH - startH <= MAX_WINDOW_HOURS

enum class ExpectationRole(val key: String, val label: String) {
    PRIMARY("primary", "Expected"),
    SECONDARY("secondary", "Also expected"),
    WATCH("watch", "Watching"),
}

enum class ExpectedDirection(val key: String, val label: String, val arrow: String) {
    DOWN("down", "lower", "↓"),
    UP("up", "higher", "↑"),
    NONE("none", "no change", "="),
}

/** One expectation being drafted in the sheet. */
data class ExpectationDraft(
    val outcomeKind: String,
    val outcomeKey: String,
    val label: String,
    val role: ExpectationRole,
    val direction: ExpectedDirection,
    val startH: Double,
    val endH: Double,
    val aggregation: String = "mean",
)

/** The first problem with the drafts, or null. Exactly one primary, windows sane, no outcome twice with the same window. */
fun validateExpectations(drafts: List<ExpectationDraft>): String? {
    if (drafts.isEmpty()) return "Add what you expect it to change."
    if (drafts.count { it.role == ExpectationRole.PRIMARY } != 1) return "Choose exactly one main expected outcome."
    drafts.firstOrNull { !validWindow(it.startH, it.endH) }?.let { return "${it.label}: the window must start at or after the event and last at most 14 days." }
    val keys = drafts.map { Triple(it.outcomeKind, it.outcomeKey, it.startH to it.endH) }
    if (keys.toSet().size != keys.size) return "The same outcome and window is listed twice."
    return null
}

enum class TrackedScale(val key: String, val label: String, val min: Double, val max: Double, val whole: Boolean) {
    BRISTOL("ordinal_1_7", "1 to 7 scale (e.g. Bristol stool)", 1.0, 7.0, true),
    RATING("rating_0_10", "0 to 10 rating", 0.0, 10.0, false),
    COUNT("count", "Count", 0.0, 1000.0, true),
    YES_NO("yes_no", "Yes / no", 0.0, 1.0, true),
}

fun TrackedScale.accepts(value: Double): Boolean = value in min..max && (!whole || value == Math.floor(value))

fun parseTrackedValue(scale: TrackedScale, text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { scale.accepts(it) }

/** The local calendar day and minute of the day an event or value is stored under (phone time zone). */
data class LocalStamp(val date: String, val minute: Int)

fun localStamp(now: ZonedDateTime): LocalStamp = LocalStamp(now.toLocalDate().toString(), now.hour * 60 + now.minute)

// ---- reading the published evidence ----

fun ladderLabel(ladder: String): String = when (ladder) {
    "ready" -> "READY TO TEST PROPERLY"
    "emerging" -> "EMERGING"
    "early_signal" -> "EARLY SIGNAL"
    else -> "ANECDOTE"
}

/** What the next rung needs, so one night never reads as an answer. */
fun ladderHint(ladder: String, nWithData: Int): String = when (ladder) {
    "ready" -> "Enough events to test it properly with a randomised experiment."
    "emerging" -> "${15 - nWithData} more with data before a randomised test is worth setting up."
    "early_signal" -> "${7 - nWithData} more with data before this is more than an early signal."
    else -> "${3 - nWithData.coerceAtMost(3)} more with data before anything can be read from it."
}

fun observedLabel(observed: String): String = when (observed) {
    "as_expected" -> "in the expected direction"
    "opposite" -> "the opposite way"
    "unexpected_change" -> "a change where none was expected"
    "no_difference" -> "no clear difference"
    "too_few" -> "too few events"
    else -> "unclear"
}

// ---- from "ready to test properly" to a registered experiment ----

/** Either the formal-experiment outcome the intervention's main expectation maps to, or why it can't be tested formally yet. */
data class PromotionTarget(val metric: String?, val reason: String?)

/**
 * The formal randomised experiments compare a fixed set of daily outcomes (EXPERIMENT_METRICS). A stored series with the same
 * id (hrv, rhr, sleep_hours, energy...) can be promoted as it is; anything else (respiratory rate, SpO2, a tracked outcome)
 * is explained rather than offered as a dead button.
 */
fun promotionTarget(outcomeKind: String, outcomeKey: String, label: String): PromotionTarget = when {
    outcomeKind == "tracked" -> PromotionTarget(null, "$label is an outcome you record yourself, which the formal experiments can't compare yet.")
    EXPERIMENT_METRICS.any { it.first == outcomeKey } -> PromotionTarget(outcomeKey, null)
    else -> PromotionTarget(null, "$label isn't an outcome the formal experiments compare yet; the repeated events stay the evidence for now.")
}
