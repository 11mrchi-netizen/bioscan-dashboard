package com.bioscan.fieldterminal.domain.notifications

import java.time.Duration
import java.time.Instant

// Reminders for repeatable interventions (docs/intervention-protocol-intelligence). Pure, so the dispatch worker only
// fetches and the decisions are unit-tested.

/** An event as the prompts need it. */
data class PromptEvent(val id: String, val interventionId: String, val occurredAt: Instant, val feeling: Int?)

/** A tracked-outcome expectation: the window [startH, endH] after the event in which the value should be logged. */
data class PromptWindow(val interventionId: String, val outcomeId: String, val startH: Double, val endH: Double)

/** Events worth rating: at least [minAgeH] old (the effect has had a night to show) and at most [maxAgeH] old, still unrated. */
fun unratedEvents(events: List<PromptEvent>, now: Instant, minAgeH: Long = 2, maxAgeH: Long = 36): List<PromptEvent> =
    events.filter { e ->
        e.feeling == null && !e.occurredAt.isAfter(now.minus(Duration.ofHours(minAgeH))) && e.occurredAt.isAfter(now.minus(Duration.ofHours(maxAgeH)))
    }

fun eventRatingNeeded(events: List<PromptEvent>, now: Instant): Boolean = unratedEvents(events, now).isNotEmpty()

/** A draw is wanted for every draw-enabled intervention that has none for today. */
fun interventionsToDraw(drawEnabledIds: List<String>, drawnTodayIds: Set<String>): List<String> =
    drawEnabledIds.filter { it !in drawnTodayIds }

fun eventDrawNeeded(drawEnabledIds: List<String>, drawnTodayIds: Set<String>): Boolean =
    interventionsToDraw(drawEnabledIds, drawnTodayIds).isNotEmpty()

/**
 * Events whose tracked-outcome window has closed (within the last [graceH] hours) with no value logged inside it. A value
 * logged anywhere in [event + startH, event + endH] satisfies the event.
 */
fun followUpsDue(
    events: List<PromptEvent>,
    windows: List<PromptWindow>,
    values: List<Pair<String, Instant>>,
    now: Instant,
    graceH: Long = 24,
): List<Pair<PromptEvent, PromptWindow>> = buildList {
    for (e in events) for (w in windows.filter { it.interventionId == e.interventionId }) {
        val from = e.occurredAt.plusSeconds((w.startH * 3600).toLong())
        val to = e.occurredAt.plusSeconds((w.endH * 3600).toLong())
        if (to.isAfter(now) || to.isBefore(now.minus(Duration.ofHours(graceH)))) continue
        val logged = values.any { (outcomeId, at) -> outcomeId == w.outcomeId && !at.isBefore(from) && at.isBefore(to) }
        if (!logged) add(e to w)
    }
}

/** One dedupe key per event and outcome, so a follow-up is shown at most once. */
fun followUpKey(e: PromptEvent, w: PromptWindow) = "event_followup:${e.id}:${w.outcomeId}"
