package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

// Adherence over a date window, derived purely from the existing roster +
// supplement_log rows (nothing new is stored). "As-needed" supplements have no
// schedule, so adherence is undefined for them (fraction == null), never 0.
data class SupplementAdherence(val takenDays: Int, val scheduledDays: Int) {
    val fraction: Double?
        get() = if (scheduledDays <= 0) null else (takenDays.toDouble() / scheduledDays).coerceAtMost(1.0)
}

// The window is clipped to the supplement's own [startDate, endDate]. Daily
// supplements schedule every day; interval supplements (every_n_days >= 2)
// schedule ceil(days / n) doses, the first at the window start. A day counts as
// taken when any log row falls on it.
fun supplementAdherence(
    timeOfDay: String,
    everyNDays: Int?,
    startDate: LocalDate,
    endDate: LocalDate?,
    takenDates: Set<LocalDate>,
    from: LocalDate,
    to: LocalDate,
): SupplementAdherence {
    if (timeOfDay.equals("as-needed", ignoreCase = true)) return SupplementAdherence(0, 0)
    val windowStart = maxOf(from, startDate)
    val windowEnd = if (endDate != null) minOf(to, endDate) else to
    if (windowEnd.isBefore(windowStart)) return SupplementAdherence(0, 0)

    val days = (ChronoUnit.DAYS.between(windowStart, windowEnd) + 1).toInt()
    val scheduled = if (everyNDays == null || everyNDays <= 1) days else ceil(days.toDouble() / everyNDays).toInt()
    val taken = takenDates.count { !it.isBefore(windowStart) && !it.isAfter(windowEnd) }
    return SupplementAdherence(takenDays = taken, scheduledDays = scheduled)
}
