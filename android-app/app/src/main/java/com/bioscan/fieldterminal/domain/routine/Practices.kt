package com.bioscan.fieldterminal.domain.routine

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

// Routine intelligence R7 (docs/routine-intelligence/README.md): practices and logged time.
//
// A practice is something done on purpose on a schedule (meditation 10 min every morning at 07:00, sauna on Tuesdays and
// Thursdays); a pastime is time spent that no other data shows (TV, reading). Both are logged as blocks of time, from a
// timer or afterwards; a practice can also be marked done (its planned length) or skipped from its reminder. All times
// here are the person's local wall clock (logged times, domain/WallClock.kt).

enum class ActivityKind(val key: String, val label: String) {
    PRACTICE("practice", "PRACTICE"), PASTIME("pastime", "PASTIME");

    companion object {
        fun of(key: String?): ActivityKind = entries.firstOrNull { it.key == key } ?: PASTIME
    }
}

enum class ActivityCategory(val key: String, val label: String) {
    MIND("mind", "MIND"), BODY("body", "BODY"), RECOVERY("recovery", "RECOVERY"), LEARNING("learning", "LEARNING"),
    CREATIVE("creative", "CREATIVE"), SOCIAL("social", "SOCIAL"), SCREEN("screen", "SCREEN"), OUTDOOR("outdoor", "OUTDOOR"),
    CHORES("chores", "CHORES"), OTHER("other", "OTHER");

    companion object {
        fun of(key: String?): ActivityCategory = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

data class RoutineActivity(
    val id: Long,
    val name: String,
    val kind: ActivityKind,
    val category: ActivityCategory = ActivityCategory.OTHER,
    val targetMin: Int? = null,
    /** ISO days of the week (1 = Monday); null = not scheduled. */
    val days: Set<Int>? = null,
    val time: LocalTime? = null,
    val remind: Boolean = false,
    val archived: Boolean = false,
)

enum class LogStatus(val key: String) {
    DONE("done"), SKIPPED("skipped");

    companion object {
        fun of(key: String?): LogStatus = if (key == SKIPPED.key) SKIPPED else DONE
    }
}

enum class LogSource(val key: String) { TIMER("timer"), MANUAL("manual"), REMINDER("reminder") }

data class RoutineLog(
    val id: Long,
    val activityId: Long,
    val date: LocalDate,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val minutes: Double?,
    val status: LogStatus,
    val source: LogSource,
    val note: String? = null,
) {
    /** The block's length: its own minutes, else start to end; a skip counts nothing. */
    val length: Double get() = if (status == LogStatus.SKIPPED) 0.0 else minutes ?: blockMinutes(start, end) ?: 0.0
}

fun blockMinutes(start: LocalDateTime?, end: LocalDateTime?): Double? =
    if (start == null || end == null || end.isBefore(start)) null else Duration.between(start, end).seconds / 60.0

/**
 * The local day a block counts for. Blocks that start between midnight and 04:00 belong to the evening before, as the
 * phone day and the evening check-in do (TV that runs past midnight is that evening's).
 */
fun dayOfBlock(start: LocalDateTime): LocalDate = if (start.toLocalTime().isBefore(LocalTime.of(4, 0))) start.toLocalDate().minusDays(1) else start.toLocalDate()

fun RoutineActivity.isScheduled(): Boolean = kind == ActivityKind.PRACTICE && !archived && !days.isNullOrEmpty()

fun RoutineActivity.scheduledOn(date: LocalDate): Boolean = isScheduled() && date.dayOfWeek.value in days!!

enum class PracticeState(val label: String) { DONE("DONE"), SKIPPED("SKIPPED"), DUE("DUE"), LATER("LATER"), ANYTIME("ANYTIME") }

data class PracticeToday(val activity: RoutineActivity, val state: PracticeState, val minutesDone: Double)

/**
 * Today's practices: those scheduled today, in time order, and the unscheduled ones done today. DONE once anything is
 * logged (a partial length still counts as done), SKIPPED when skipped and nothing done, DUE from its time, else LATER;
 * a scheduled practice with no time is DUE all day.
 */
fun practicesToday(activities: List<RoutineActivity>, logs: List<RoutineLog>, date: LocalDate, now: LocalTime): List<PracticeToday> {
    val todays = logs.filter { it.date == date }.groupBy { it.activityId }
    return activities.filter { it.kind == ActivityKind.PRACTICE && !it.archived }
        .filter { it.scheduledOn(date) || todays[it.id].orEmpty().any { l -> l.status == LogStatus.DONE } || !it.isScheduled() }
        .map { a ->
            val mine = todays[a.id].orEmpty()
            val done = mine.filter { it.status == LogStatus.DONE }
            val state = when {
                done.isNotEmpty() -> PracticeState.DONE
                mine.any { it.status == LogStatus.SKIPPED } -> PracticeState.SKIPPED
                !a.scheduledOn(date) -> PracticeState.ANYTIME
                a.time == null || !now.isBefore(a.time) -> PracticeState.DUE
                else -> PracticeState.LATER
            }
            PracticeToday(a, state, done.sumOf { it.length })
        }
        .sortedWith(compareBy({ it.state == PracticeState.ANYTIME }, { it.activity.time ?: LocalTime.MAX }, { it.activity.name }))
}

/** The practices whose reminder falls in (from, to], today, with nothing logged for them yet. */
fun practicesDue(activities: List<RoutineActivity>, logs: List<RoutineLog>, from: LocalDateTime, to: LocalDateTime): List<RoutineActivity> {
    val date = to.toLocalDate()
    val logged = logs.filter { it.date == date }.map { it.activityId }.toSet()
    return activities.filter { a ->
        val t = a.time ?: return@filter false
        if (!a.remind || !a.scheduledOn(date) || a.id in logged) return@filter false
        val at = date.atTime(t)
        at.isAfter(from) && !at.isAfter(to)
    }
}

/** The key a practice's reminder is shown under, once a day. */
fun practiceReminderKey(activityId: Long, date: LocalDate): String = "practice:$activityId:$date"

data class Adherence(val done: Int, val scheduled: Int, val streak: Int) {
    val share: Double? get() = if (scheduled == 0) null else done.toDouble() / scheduled
}

/**
 * How a scheduled practice is kept over the [days] up to [through]: the scheduled days it was done on, and the streak
 * of scheduled days done in a row back from [through] (today counts only once done, so the morning does not break it).
 */
fun adherence(activity: RoutineActivity, logs: List<RoutineLog>, through: LocalDate, days: Int = 28): Adherence {
    val doneDays = logs.filter { it.activityId == activity.id && it.status == LogStatus.DONE }.map { it.date }.toSet()
    val window = (0 until days).map { through.minusDays(it.toLong()) }.filter { activity.scheduledOn(it) }
    var streak = 0
    for ((i, d) in window.withIndex()) {
        if (d in doneDays) streak++ else if (i == 0 && d == through) continue else break
    }
    return Adherence(window.count { it in doneDays }, window.count { it != through || it in doneDays }, streak)
}

/** "Mon Wed Fri", "Every day", "Weekdays". */
fun scheduleWords(days: Set<Int>?): String = when {
    days.isNullOrEmpty() -> "No schedule"
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }
}

fun minutesWords(min: Double): String {
    val m = min.roundToInt()
    return if (m < 60) "$m min" else "${m / 60} h %02d".format(m % 60)
}

// ---- Time other data already shows ----------------------------------------------------------------------------------

enum class TimeSource(val label: String) { LOGGED("LOGGED"), WATCH("WATCH"), LOCATION("LOCATION"), PHONE("PHONE"), SLEEP("SLEEP") }

/** One line of where a day's time went: logged by the person, or read from other data. */
data class TimeEntry(val key: String, val label: String, val source: TimeSource, val date: LocalDate, val minutes: Double)

/** A recorded session, a trip's legs, a day's mobility and phone use, a night's sleep: what [inferredTime] reads. */
data class InferredInputs(
    /** Recorded training (type, local day, minutes). Walks too, when the watch records them. */
    val sessions: List<Triple<String, LocalDate, Double>> = emptyList(),
    /** Trip legs (mode, local day of the trip, minutes). */
    val legs: List<Triple<String, LocalDate, Double>> = emptyList(),
    /** Estimated minutes outdoors in daylight, by day. */
    val outdoorDaylight: Map<LocalDate, Double> = emptyMap(),
    /** Phone minutes by category, by day. */
    val phoneByCategory: Map<LocalDate, Map<String, Double>> = emptyMap(),
    /** Hours asleep, by the day the night ends on. */
    val sleepHours: Map<LocalDate, Double> = emptyMap(),
    /** Door-to-door commuting minutes (to work and home), by day. */
    val commuteMin: Map<LocalDate, Double> = emptyMap(),
)

private val PHONE_LINES = linkedMapOf("video" to "Video on the phone", "social" to "Social apps", "game" to "Games on the phone", "news" to "News", "audio" to "Listening on the phone")

/**
 * The time other data shows, per day: training by type from the watch; walking and cycling from trips (running in trips
 * is left to the watch's sessions, which already count it); outdoor daylight; phone use by category; sleep.
 */
fun inferredTime(inputs: InferredInputs): List<TimeEntry> {
    val out = mutableListOf<TimeEntry>()
    inputs.sessions.groupBy { it.first to it.second }.forEach { (k, v) ->
        val (type, date) = k
        val label = if (type == "walk") "Walking (watch)" else "Training: ${type.replace('_', ' ')}"
        out += TimeEntry("session:$type", label, TimeSource.WATCH, date, v.sumOf { it.third })
    }
    inputs.legs.filter { it.first == "walking" || it.first == "on_bicycle" }.groupBy { it.first to it.second }.forEach { (k, v) ->
        val (mode, date) = k
        out += TimeEntry("trip:$mode", if (mode == "walking") "Walking outside" else "Cycling", TimeSource.LOCATION, date, v.sumOf { it.third })
    }
    inputs.outdoorDaylight.forEach { (d, m) -> if (m > 0) out += TimeEntry("outdoor_daylight", "Outdoors in daylight", TimeSource.LOCATION, d, m) }
    inputs.phoneByCategory.forEach { (d, cats) ->
        PHONE_LINES.forEach { (cat, label) -> cats[cat]?.takeIf { it >= 1 }?.let { out += TimeEntry("phone:$cat", label, TimeSource.PHONE, d, it) } }
    }
    inputs.sleepHours.forEach { (d, h) -> if (h > 0) out += TimeEntry("sleep", "Asleep", TimeSource.SLEEP, d, h * 60) }
    inputs.commuteMin.forEach { (d, m) -> if (m > 0) out += TimeEntry("commute", "Commuting", TimeSource.LOCATION, d, m) }
    return out
}

/** The logged blocks as time entries, one line per activity and day. */
fun loggedTime(activities: List<RoutineActivity>, logs: List<RoutineLog>): List<TimeEntry> {
    val byId = activities.associateBy { it.id }
    return logs.filter { it.status == LogStatus.DONE }.groupBy { it.activityId to it.date }.mapNotNull { (k, v) ->
        val a = byId[k.first] ?: return@mapNotNull null
        TimeEntry("activity:${a.id}", a.name, TimeSource.LOGGED, k.second, v.sumOf { it.length })
    }
}

data class TimeLine(val key: String, val label: String, val source: TimeSource, val todayMin: Double, val dailyAverageMin: Double, val daysWith: Int)

/**
 * Where the time went: one line per activity or source, with today's minutes and the daily average over the [days]
 * days before today (every day counts, a day without any as zero). Logged lines first, then by average.
 */
fun weekTime(entries: List<TimeEntry>, today: LocalDate, days: Int = 7): List<TimeLine> {
    val from = today.minusDays(days.toLong())
    return entries.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }.groupBy { it.key }.map { (key, e) ->
        val past = e.filter { it.date.isBefore(today) }
        TimeLine(
            key, e.first().label, e.first().source, e.filter { it.date == today }.sumOf { it.minutes },
            past.sumOf { it.minutes } / days, past.map { it.date }.distinct().size,
        )
    }.filter { it.todayMin >= 1 || it.dailyAverageMin >= 1 }
        .sortedWith(compareBy<TimeLine>({ it.source != TimeSource.LOGGED }, { -maxOf(it.dailyAverageMin, it.todayMin) }))
}
