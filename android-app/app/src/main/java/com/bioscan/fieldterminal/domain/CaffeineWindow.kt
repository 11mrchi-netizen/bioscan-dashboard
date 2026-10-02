package com.bioscan.fieldterminal.domain

import java.time.LocalDateTime
import kotlin.math.log2
import kotlin.math.pow

// DAV-254/255 — Caffeine Window pharmacokinetic model.
//
// Standard elimination half-life = 5h (population mean; real range 3–7h).
// Not yet personalized — a future refinement once enough individual data
// accumulates. Residual at time t = sum(dose_i * 0.5^(Δh_i / HALF_LIFE_H)).
//
// Clearance threshold = 50 mg — below a pharmacologically active dose
// (~100 mg) and roughly the point where most people stop noticing effects.
// Not a hard biological ceiling; used only for the cutoff advisory.
//
// Reference dose for cutoff = 200 mg — typical large coffee or double
// espresso. Cutoff is advisory, not authoritative.
//
// ponytail: population half-life, fixed bedtime default; personalize when
// enough per-user data exists to fit individual clearance rate.

const val CAFFEINE_HALF_LIFE_H = 5.0
const val CAFFEINE_WINDOW_MODEL_VERSION = "v1"
private const val CLEARANCE_THRESHOLD_MG = 50.0
private const val REFERENCE_DOSE_MG = 200.0

data class CaffeineEvent(
    val loggedAt: LocalDateTime,
    val doseMg: Double,
    val isEstimated: Boolean,
)

data class CaffeineWindowResult(
    val events: List<CaffeineEvent>,
    val totalDoseMg: Double,
    val residualNowMg: Double,
    val residualAtBedtimeMg: Double?,   // null when bedtime is in the past
    val cutoffHour: Int?,               // null when no events today
    val modelVersion: String = CAFFEINE_WINDOW_MODEL_VERSION,
)

private fun residualAt(events: List<CaffeineEvent>, at: LocalDateTime): Double =
    events.sumOf { e ->
        val h = java.time.Duration.between(e.loggedAt, at).toMinutes() / 60.0
        if (h < 0.0) 0.0 else e.doseMg * 0.5.pow(h / CAFFEINE_HALF_LIFE_H)
    }

fun computeCaffeineWindow(
    events: List<CaffeineEvent>,
    targetBedtimeH: Int = 23,
): CaffeineWindowResult {
    val now = LocalDateTime.now()
    val bedtime = now.toLocalDate().atTime(targetBedtimeH, 0).let {
        if (it.isBefore(now)) it.plusDays(1) else it
    }

    val residualNow = residualAt(events, now)
    val residualAtBedtime = if (bedtime.isAfter(now)) residualAt(events, bedtime) else null

    // Latest hour you can take a REFERENCE_DOSE_MG such that total residual
    // at bedtime stays at or below CLEARANCE_THRESHOLD_MG.
    // Analytic: dose * 0.5^(Δh / 5) = headroom → Δh = 5 * log2(dose/headroom)
    val cutoffHour: Int? = if (events.isEmpty()) null else {
        val headroom = CLEARANCE_THRESHOLD_MG - (residualAtBedtime ?: 0.0)
        if (headroom <= 0.0) {
            0  // already over threshold at bedtime — no more caffeine
        } else if (headroom >= REFERENCE_DOSE_MG) {
            targetBedtimeH  // full reference dose clears regardless
        } else {
            val hoursNeeded = CAFFEINE_HALF_LIFE_H * log2(REFERENCE_DOSE_MG / headroom)
            val latestHour = (targetBedtimeH - hoursNeeded).toInt()
            latestHour.coerceIn(0, 23)
        }
    }

    return CaffeineWindowResult(
        events = events,
        totalDoseMg = events.sumOf { it.doseMg },
        residualNowMg = residualNow,
        residualAtBedtimeMg = residualAtBedtime,
        cutoffHour = cutoffHour,
        modelVersion = CAFFEINE_WINDOW_MODEL_VERSION,
    )
}
