@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.InstantSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class)
package com.bioscan.fieldterminal.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

// ME › PERFORMANCE as a hub of sports. Every recorded activity type becomes a sport tile: Running, Strength and
// Conditioning have their own pages; any other type (cycling, hiking, walking, yoga...) gets the generic sport page, so
// a new activity shows up by itself the first time it is recorded.

enum class SportKind { RUNNING, STRENGTH, CONDITIONING, OTHER }

@Serializable
data class Sport(val key: String, val label: String, val kind: SportKind)

/** One recorded session, as the sport pages read it ([startTime] is a logged wall-clock time; see WallClock). */
@Serializable
data class SportSession(
    val id: Long,
    val type: String?,
    val source: String?,
    val date: LocalDate,
    val durationMin: Double?,
    val distanceKm: Double?,
    val avgHr: Double?,
    val maxHr: Double?,
    val elevationGainM: Double?,
)

/** The sport a session belongs to. Conditioning is the coach's sessions imported from Notion (type "other"). */
fun sportOf(type: String?, source: String?): Sport = when (type?.lowercase()) {
    "run" -> Sport("run", "Running", SportKind.RUNNING)
    "strength" -> Sport("strength", "Strength", SportKind.STRENGTH)
    "other" -> if (source == "notion") Sport("conditioning", "Conditioning", SportKind.CONDITIONING) else Sport("other", "Other workouts", SportKind.OTHER)
    "ride" -> Sport("ride", "Cycling", SportKind.OTHER)
    "hike" -> Sport("hike", "Hiking", SportKind.OTHER)
    "walk" -> Sport("walk", "Walking", SportKind.OTHER)
    "swim" -> Sport("swim", "Swimming", SportKind.OTHER)
    "yoga" -> Sport("yoga", "Yoga", SportKind.OTHER)
    null, "" -> Sport("other", "Other workouts", SportKind.OTHER)
    else -> Sport(type.lowercase(), type.replace('_', ' ').replaceFirstChar { it.uppercase() }, SportKind.OTHER)
}

data class SportSummary(
    val sport: Sport,
    val totalSessions: Int,
    val lastDate: LocalDate,
    /** Sessions, hours and km in the window asked for (the last 4 weeks by default). */
    val sessions4w: Int,
    val hours4w: Double,
    val km4w: Double?,
    /** Hours per Monday-to-Sunday week, oldest first (the last 12 weeks by default). */
    val weeklyHours: List<Double>,
)

private val PINNED = listOf("run", "strength", "conditioning")

/** The Mondays of the weeks from [from]'s week to [to]'s week, oldest first. */
fun weekStarts(from: LocalDate, to: LocalDate): List<LocalDate> {
    val first = from.minusDays((from.dayOfWeek.value - 1).toLong())
    val last = to.minusDays((to.dayOfWeek.value - 1).toLong())
    return generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(last) }.toList()
}

/**
 * A tile per sport, the three main sports first (when they have any session), then the rest by their latest session:
 * the last 4 weeks and 12 weeks of hours.
 */
fun summarizeSports(sessions: List<SportSession>, today: LocalDate): List<SportSummary> =
    summarizeSportsIn(sessions, today.minusDays(27), today, weekStarts(today.minusWeeks(11), today), clipWeeks = false)

/**
 * The same over the window [from]..[to] (both included), with hours for each week in [weeks]; [clipWeeks]: a week only
 * counts its days inside the window (a block that starts on a Wednesday).
 */
fun summarizeSportsIn(sessions: List<SportSession>, from: LocalDate, to: LocalDate, weeks: List<LocalDate>, clipWeeks: Boolean = true): List<SportSummary> =
    sessions.groupBy { sportOf(it.type, it.source) }.map { (sport, list) ->
        val inWindow = list.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val km = inWindow.mapNotNull { it.distanceKm }.takeIf { it.isNotEmpty() }?.sum()
        val weekly = weeks.map { start ->
            list.filter { !it.date.isBefore(start) && it.date.isBefore(start.plusWeeks(1)) && (!clipWeeks || (!it.date.isBefore(from) && !it.date.isAfter(to))) }
                .sumOf { it.durationMin ?: 0.0 } / 60.0
        }
        SportSummary(sport, list.size, list.maxOf { it.date }, inWindow.size, inWindow.sumOf { it.durationMin ?: 0.0 } / 60.0, km, weekly)
    }.sortedWith(compareBy<SportSummary> { PINNED.indexOf(it.sport.key).let { i -> if (i < 0) PINNED.size else i } }.thenByDescending { it.lastDate })
