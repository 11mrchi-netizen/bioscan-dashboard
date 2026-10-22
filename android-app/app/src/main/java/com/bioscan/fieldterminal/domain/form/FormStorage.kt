package com.bioscan.fieldterminal.domain.form

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

// Saving and comparing analyses (supabase/migrations/20261030_running_form_analyses.sql): the metric summaries keyed the
// same way as the findings, the clip's own recording time, which recorded run it was filmed during, and how a value moved
// since the last analysis of the same kind.

/** The stored metric summaries, keyed as the findings are (foot strike's angle under "foot_strike"). */
fun formMetricValues(m: FormMetrics): Map<String, MetricSummary> = buildMap {
    m.cadenceSpm?.let { put("cadence", MetricSummary(it, 0.0, m.steps.size)) }
    m.contactTimeMs?.let { put("contact_time", it) }
    m.flightTimeMs?.let { put("flight_time", it) }
    m.dutyFactor?.let { put("duty_factor", it) }
    m.kneeFlexionAtLanding?.let { put("knee_landing", it) }
    m.peakKneeFlexion?.let { put("knee_peak", it) }
    m.shinAngleAtLanding?.let { put("shin_angle", it) }
    m.ankleAheadOfHip?.let { put("ankle_ahead", it) }
    m.footStrikeAngle?.let { put("foot_strike", it) }
    m.hipExtensionAtToeOff?.let { put("hip_extension", it) }
    m.trunkLean?.let { put("trunk_lean", it) }
    m.verticalOscillation?.let { put("vertical_oscillation", it) }
    m.stepTimeVariation?.let { put("step_variation", MetricSummary(it, 0.0, m.steps.size)) }
}

/** Units and rounding for a stored value, for "last time" lines. */
private fun formatted(key: String, v: Double): String = when (key) {
    "cadence" -> "${v.roundToInt()} spm"
    "contact_time", "flight_time" -> "${v.roundToInt()} ms"
    "ankle_ahead" -> "${(v * 100).roundToInt()} %"
    "vertical_oscillation" -> "%.1f %%".format(v * 100)
    "duty_factor", "step_variation" -> "${(v * 100).roundToInt()} %"
    else -> "${v.roundToInt()}°"
}

/**
 * Smallest change worth calling a change, per metric: about the analyser's own error on the synthetic runner (a few
 * degrees, a couple of steps a minute), so noise between clips is not read as progress.
 */
private fun meaningfulChange(key: String): Double = when (key) {
    "cadence" -> 3.0
    "contact_time", "flight_time" -> 15.0
    "ankle_ahead" -> 0.04
    "vertical_oscillation" -> 0.01
    "duty_factor", "step_variation" -> 0.03
    else -> 3.0
}

/** "Last time 12° (6 Oct)", with "about the same" when the change is within the analyser's error; null without a previous value. */
fun sinceLastTime(key: String, now: Double, previous: Map<String, MetricSummary>?, previousOn: java.time.LocalDate?): String? {
    val before = previous?.get(key)?.median ?: return null
    val day = previousOn?.format(DateTimeFormatter.ofPattern("d MMM")) ?: "last time"
    val same = abs(now - before) < meaningfulChange(key)
    return "Last time ${formatted(key, before)} ($day)" + if (same) ", about the same" else ""
}

/** The recording time a phone writes into a video (METADATA_KEY_DATE, e.g. "20261019T071530.000Z"), as an instant. */
fun parseVideoDate(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
        val parsed = runCatching { LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern(pattern)).atZone(ZoneId.of("UTC")).toInstant() }.getOrNull()
        // Phones without a clock set write 1904 or 1970; those are no date.
        if (parsed != null) return parsed.takeIf { it.isAfter(Instant.parse("2000-01-01T00:00:00Z")) }
    }
    return null
}

/** A recorded run that could be the one filmed: its id, wall-clock start (as sessions store it) and length. */
data class RunChoice(val id: Long, val start: LocalDateTime, val durationMin: Double?, val label: String)

/** The run the clip was filmed during: the one whose time contains the clip's (wall clock), within 10 minutes either side. */
fun runForClip(clipAt: LocalDateTime?, runs: List<RunChoice>): Long? {
    if (clipAt == null) return null
    return runs.filter { r ->
        val end = r.start.plusMinutes(((r.durationMin ?: 60.0)).roundToInt().toLong())
        !clipAt.isBefore(r.start.minusMinutes(10)) && !clipAt.isAfter(end.plusMinutes(10))
    }.minByOrNull { abs(Duration.between(it.start, clipAt).toMinutes()) }?.id
}
