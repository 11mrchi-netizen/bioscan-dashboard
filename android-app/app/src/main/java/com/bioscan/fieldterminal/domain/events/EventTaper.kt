package com.bioscan.fieldterminal.domain.events

import com.bioscan.fieldterminal.domain.training.generate.WeekKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Building a training block toward an event (docs/fuel-planning/README.md, phase 9). The block ends in the event's week;
// the last weeks before it taper. A meta-analysis of tapering (Bosquet et al. 2007) found the best results with 8-14 days
// of reduced volume (by 41-60 %) with intensity and frequency kept: two weeks for an A race or anything over 90
// minutes, one for a B race, none for a C race (trained through). The planner's lighter weeks are its deload weeks,
// which also lower intensity, so the page says to keep a few short efforts at race pace.

/** Calendar weeks from [start] (the block's first day) to the week holding [eventDate]; 0 when the event is before it. */
fun weeksToEvent(start: LocalDate, eventDate: LocalDate): Int {
    if (eventDate.isBefore(start)) return 0
    return (ChronoUnit.DAYS.between(start, eventDate) / 7).toInt() + 1
}

fun taperWeeks(event: AthleteEvent): Int = when {
    event.priority == EventPriority.C -> 0
    event.priority == EventPriority.A || (event.plannedDurationMin ?: 0.0) > 90 -> 2
    else -> 1
}

/** [timeline] with its last [taper] weeks made lighter weeks; a test week there becomes one too. */
fun withTaper(timeline: List<WeekKind>, taper: Int): List<WeekKind> {
    if (taper <= 0 || timeline.isEmpty()) return timeline
    val from = (timeline.size - taper).coerceAtLeast(1)
    return timeline.mapIndexed { i, k -> if (i >= from) WeekKind.Deload else k }
}

/** Events a block can build toward: open A and B events at least 3 weeks after [start], soonest first. */
fun buildableEvents(events: List<AthleteEvent>, start: LocalDate): List<AthleteEvent> =
    events.filter { it.isOpen && it.priority != EventPriority.C && weeksToEvent(start, it.date) >= 3 }.sortedBy { it.date }
