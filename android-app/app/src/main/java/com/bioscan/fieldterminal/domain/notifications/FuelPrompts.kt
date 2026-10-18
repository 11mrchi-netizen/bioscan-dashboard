package com.bioscan.fieldterminal.domain.notifications

import com.bioscan.fieldterminal.data.model.NotificationPrefsRow
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.LOADING_RACE_MIN
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.PlannedItem
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt

// Reminders tied to sessions and races rather than to a clock time (docs/fuel-planning/README.md, phase 7).
//
//   fuel_session  3 h before a hard, long or race session of an hour or more: the pre-session meal; 30 min after its
//                 planned end: refuel.
//   fuel_event    for an A or B race over 90 minutes: loading starts (09:00 two days before), the day before (09:00, low
//                 fibre; 19:00, the evening meal), race morning (4 h before: drink; 3 h before: breakfast).
//
// A prompt falling in the quiet window moves to the window's end when that is still at least 30 minutes before what it
// is about, and is dropped otherwise (a pre-session reminder after the session helps nobody). Each prompt has a key, so
// it is shown once however often the worker runs.

data class FuelPrompt(val kind: String, val key: String, val at: LocalDateTime, val title: String, val body: String)

const val FUEL_PROMPT_WINDOW_MIN = 30L

private fun local(z: ZonedDateTime): LocalDateTime = z.toLocalDateTime()

/** Every fuel prompt for [days] (start times in the device zone), before quiet-hour handling. */
fun fuelPrompts(days: List<PlannedDay>, sessionKind: String, eventKind: String): List<Pair<FuelPrompt, LocalDateTime>> {
    val out = mutableListOf<Pair<FuelPrompt, LocalDateTime>>()
    val items = days.flatMap { it.items }.filter { !it.recorded && it.start != null }
    for (item in items) {
        val start = item.start!!
        val end = item.end!!
        val isLoadingRace = item.intensity == Intensity.RACE && item.eventPriority != EventPriority.C && item.durationMin > LOADING_RACE_MIN
        if (item.intensity.isKey && item.durationMin >= 60 && !isLoadingRace) {
            out += FuelPrompt(
                sessionKind, "fuel:${item.sourceId}:pre", local(start.minusHours(3)),
                "Fuel for ${item.title}", "${item.title} at ${start.toLocalTime()}: a carbohydrate-rich meal now (HIGH), easy on fat and fibre.",
            ) to local(start)
        }
        if (item.intensity.isKey && item.durationMin >= 60) {
            out += FuelPrompt(
                sessionKind, "fuel:${item.sourceId}:post", local(end.plusMinutes(30)),
                "Refuel after ${item.title}", "Carbohydrate and some protein in the next hour; drink to replace what you sweated.",
            ) to local(end.plusHours(2))
        }
        if (isLoadingRace) out += racePrompts(item, eventKind)
    }
    return out
}

private fun racePrompts(race: PlannedItem, kind: String): List<Pair<FuelPrompt, LocalDateTime>> {
    val start = race.start!!
    val day = start.toLocalDate()
    val id = race.sourceId
    val hours = (race.durationMin / 60).roundToInt()
    return listOf(
        FuelPrompt(kind, "fuel:$id:load1", day.minusDays(2).atTime(9, 0), "Carbohydrate loading starts", "${race.title} in 2 days: 10-12 g of carbohydrate per kg today and tomorrow. Today's fuel has the meals.") to day.minusDays(2).atTime(21, 0),
        FuelPrompt(kind, "fuel:$id:load2", day.minusDays(1).atTime(9, 0), "Loading, day 2", "Keep the carbohydrate high and the fibre low today: easier on the gut tomorrow.") to day.minusDays(1).atTime(18, 0),
        FuelPrompt(kind, "fuel:$id:eve", day.minusDays(1).atTime(19, 0), "The evening before ${race.title}", "A familiar carbohydrate-rich meal, nothing new. Lay out your fuel for tomorrow (the event's FUEL tab).") to day.minusDays(1).atTime(23, 0),
        FuelPrompt(kind, "fuel:$id:drink", local(start.minusHours(4)), "Race morning: drink", "About 5-7 ml per kg now, with something salty. Breakfast in an hour.") to local(start.minusHours(1)),
        FuelPrompt(kind, "fuel:$id:breakfast", local(start.minusHours(3)), "Race breakfast", "${race.title} in 3 hours (~$hours h race): your breakfast now, 1-4 g carbohydrate per kg, little fat and fibre.") to local(start.minusMinutes(60)),
    )
}

/**
 * The prompts due between [after] (exclusive) and [now] (inclusive), with quiet hours applied: a prompt in the quiet
 * window moves to its end if that is still [FUEL_PROMPT_WINDOW_MIN] minutes before its deadline, else it is dropped.
 * [shownKeys] are prompts already shown.
 */
fun dueFuelPrompts(
    all: List<Pair<FuelPrompt, LocalDateTime>>,
    prefs: NotificationPrefsRow,
    after: LocalDateTime,
    now: LocalDateTime,
    shownKeys: Set<String>,
): List<FuelPrompt> {
    if (!prefs.masterEnabled) return emptyList()
    return all.mapNotNull { (p, deadline) ->
        if (p.key in shownKeys) return@mapNotNull null
        val at = resolveQuietHours(p.at, prefs)
        if (at != p.at && at.plusMinutes(FUEL_PROMPT_WINDOW_MIN).isAfter(deadline)) return@mapNotNull null
        if (at.isAfter(after) && !at.isAfter(now) && now.isBefore(deadline)) p.copy(at = at) else null
    }.sortedBy { it.at }
}
