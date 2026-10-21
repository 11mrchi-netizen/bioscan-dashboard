package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.notifications.supplementsDueForBucket
import java.time.LocalDate
import java.time.LocalTime

// UX review P1: the Status hub's LABS tile says which supplements are still due today, and + SUPPS opens the add sheet
// with that time-of-day bundle ticked. "Due" is exactly the reminder's rule (supplementsDueForBucket: active, due today
// per every_n_days, not yet taken today), so the tile and the notification never disagree. As-needed items are never due.
val SUPPLEMENT_BUCKETS = listOf("morning", "afternoon", "night")

// The bundle that belongs to this time of day. The cut-offs sit between the reminder defaults (08:00, 13:00, 21:00).
// Before 04:00 there is none: the small hours belong to the night before, so a stack taken at 23:00 is not offered again
// just because the date changed.
fun supplementBucketAt(time: LocalTime): String? = when {
    time.hour < 4 -> null
    time.hour < 11 -> "morning"
    time.hour < 17 -> "afternoon"
    else -> "night"
}

data class SupplementsToday(
    // Items still due per bundle, in SUPPLEMENT_BUCKETS order; bundles with nothing due are left out.
    val dueByBucket: Map<String, Int>,
    // Active roster items with a bundle (as-needed excluded), so "all taken" can be told apart from "nothing scheduled".
    val scheduledCount: Int,
    // The bundle + SUPPS ticks: this time of day's bundle when it still has items due, otherwise the latest earlier bundle
    // that does (a missed morning stack in the afternoon). A later bundle is never ticked ahead of time.
    val suggestedBucket: String?,
    // The next later bundle with items due, for "NEXT: NIGHT · 3".
    val nextBucket: String?,
)

fun supplementsToday(
    supplements: List<SupplementRow>,
    lastTaken: Map<Long, LocalDate>,
    today: LocalDate,
    now: LocalTime,
): SupplementsToday {
    val takenToday = lastTaken.filterValues { it == today }.keys
    val due = SUPPLEMENT_BUCKETS
        .associateWith { supplementsDueForBucket(supplements, it, lastTaken, takenToday, today, skipIfLogged = true).size }
        .filterValues { it > 0 }
    val scheduled = supplements.count { s ->
        s.status.equals("active", ignoreCase = true) &&
            (s.endDate == null || LocalDate.parse(s.endDate) >= today) &&
            SUPPLEMENT_BUCKETS.any { it.equals(s.timeOfDay, ignoreCase = true) }
    }
    val current = supplementBucketAt(now)?.let { SUPPLEMENT_BUCKETS.indexOf(it) } ?: -1
    val nowAndEarlier = SUPPLEMENT_BUCKETS.take(current + 1).reversed()
    val later = SUPPLEMENT_BUCKETS.drop(current + 1)
    return SupplementsToday(
        dueByBucket = due,
        scheduledCount = scheduled,
        suggestedBucket = nowAndEarlier.firstOrNull { it in due },
        nextBucket = later.firstOrNull { it in due },
    )
}

// The LABS tile line: what is due now, else what comes next, else that the day is done.
fun supplementsTodayLine(s: SupplementsToday): String {
    s.suggestedBucket?.let { return "${it.uppercase()} STACK · ${s.dueByBucket.getValue(it)} DUE" }
    s.nextBucket?.let { return "NEXT: ${it.uppercase()} · ${s.dueByBucket.getValue(it)}" }
    return if (s.scheduledCount > 0) "ALL TAKEN TODAY" else "NO SUPPLEMENTS SET"
}
