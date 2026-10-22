package com.bioscan.fieldterminal.domain.fuel

import kotlin.math.max
import kotlin.math.min

// Carbohydrate during a session (Jeukendrup 2014, Sports Med 44:S25; Thomas, Erdman & Burke 2016): none under 45 minutes;
// a little, or a mouth rinse, up to 75; 30-60 g/h up to 2.5 h; above that up to 90 g/h, which needs glucose and fructose
// together (two gut transporters) and a trained gut. The upper end is capped at what the person tolerates (a setting,
// 60 g/h by default until they have trained to more). Hard sessions and races sit at the top of their range.

const val DEFAULT_TOLERATED_G_PER_H = 60.0
const val MAX_G_PER_H = 90.0

enum class SessionFuelKind(val label: String) {
    NONE("NOTHING NEEDED"),
    RINSE("A LITTLE, OR A MOUTH RINSE"),
    STEADY("30-60 G/H"),
    HIGH("60-90 G/H"),
}

data class SessionFuel(
    val kind: SessionFuelKind,
    val gPerHourLow: Double,
    val gPerHourHigh: Double,
    val durationMin: Double,
    val note: String,
) {
    val totalLowG: Double get() = gPerHourLow * durationMin / 60
    val totalHighG: Double get() = gPerHourHigh * durationMin / 60
    val midG: Double get() = (totalLowG + totalHighG) / 2
}

fun sessionFuel(durationMin: Double, intensity: Intensity, toleratedGPerHour: Double = DEFAULT_TOLERATED_G_PER_H): SessionFuel {
    val cap = toleratedGPerHour.coerceIn(30.0, MAX_G_PER_H)
    val hard = intensity == Intensity.HARD || intensity == Intensity.RACE
    return when {
        durationMin < 45 -> SessionFuel(SessionFuelKind.NONE, 0.0, 0.0, durationMin, "Under 45 minutes the body's own stores cover it.")
        durationMin < 75 -> SessionFuel(
            SessionFuelKind.RINSE, 0.0, if (hard) 30.0 else 15.0, durationMin,
            "45-75 minutes: a little carbohydrate, or a sports-drink mouth rinse, helps hard efforts.",
        )
        durationMin <= 150 -> {
            val lo = if (hard) 45.0 else 30.0
            SessionFuel(SessionFuelKind.STEADY, min(lo, cap), min(60.0, cap), durationMin, "1-2.5 hours: 30-60 g of carbohydrate an hour, from the start.")
        }
        else -> {
            val hi = min(MAX_G_PER_H, cap)
            val lo = min(60.0, hi)
            val note = if (hi > 60) {
                "Over 2.5 hours: up to $hi g an hour, from glucose and fructose together (about 2:1 or 1:0.8)."
            } else {
                "Over 2.5 hours: up to 90 g an hour helps once the gut is trained to it; ${cap.toInt()} g an hour is your tolerated ceiling."
            }
            SessionFuel(SessionFuelKind.HIGH, lo, max(lo, hi), durationMin, note)
        }
    }
}
