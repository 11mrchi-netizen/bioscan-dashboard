package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.domain.routine.DailyLine
import com.bioscan.fieldterminal.domain.routine.LogStatus
import com.bioscan.fieldterminal.domain.routine.RIBBON_SLOTS
import com.bioscan.fieldterminal.domain.routine.RibbonDay
import com.bioscan.fieldterminal.domain.routine.RibbonKind
import com.bioscan.fieldterminal.domain.routine.RoutineActivity
import com.bioscan.fieldterminal.domain.routine.RoutineLog
import com.bioscan.fieldterminal.domain.routine.adherence
import com.bioscan.fieldterminal.domain.routine.changeWords
import com.bioscan.fieldterminal.domain.routine.compareWindows
import com.bioscan.fieldterminal.domain.routine.minutesWords
import com.bioscan.fieldterminal.domain.routine.practicesInWindow
import com.bioscan.fieldterminal.domain.routine.ribbonTotals
import com.bioscan.fieldterminal.domain.routine.scheduleWords
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

// Me › ROUTINE as the web draws it: the same cards as RoutinePage.kt (the last 28 days as ribbons, TIME, PLACES, PHONE,
// PRACTICES, the last 4 weeks against the 4 before, REGULARITY), each line's 28-day average formatted as the phone shows it.

/** Each kind of time's token on the ribbon (FT.Metric in RoutinePage.kt). */
fun ribbonHue(k: RibbonKind): String = when (k) {
    RibbonKind.TRAINING -> "m-time-training"
    RibbonKind.LOGGED -> "m-time-logged"
    RibbonKind.ASLEEP -> "m-sleep-duration"
    RibbonKind.MOVE -> "m-commute"
    RibbonKind.HOME -> "m-time-home"
    RibbonKind.WORK -> "m-time-work"
    RibbonKind.OUT -> "m-time-out"
    RibbonKind.UNKNOWN -> "ft-line"
}

/** Each line's one hue token (lineColor in RoutinePage.kt). */
fun routineLineHue(key: String): String = when {
    key.startsWith("activity:") -> "m-time-logged"
    key.startsWith("session:") -> "m-time-training"
    key.startsWith("trip:") -> "m-active-travel"
    key == "outdoor_daylight" -> "m-daylight"
    key == "commute" -> "m-commute"
    key == "sleep" -> "m-sleep-duration"
    key == "phone:bed" -> "m-phone-sleep"
    key == "phone:night" -> "m-night-screen"
    key.startsWith("phone:") -> "m-time-phone"
    key == "place:home" -> "m-time-home"
    key == "place:work" -> "m-time-work"
    key.startsWith("place:") -> "m-time-out"
    key == "reg:bedtime" || key == "reg:wake" -> "m-sleep-score"
    key == "reg:similarity" -> "m-time-home"
    else -> "m-time-out"
}

fun routineHours(h: Double): String = if (h < 1) "${(h * 60).roundToInt()} min" else "%.1f h".format(h)

/** A line's unit and how its value reads ("1 h 05", "± 22 min", "64 %"). */
class RoutineLineFormat(val unit: String, val format: (Double) -> String)

fun routineLineFormat(key: String): RoutineLineFormat = when {
    key.startsWith("place:") -> RoutineLineFormat("h", ::routineHours)
    key == "phone:night" -> RoutineLineFormat("unlocks") { "%.1f".format(it) }
    key == "reg:bedtime" || key == "reg:wake" -> RoutineLineFormat("min") { "± ${it.roundToInt()} min" }
    key == "reg:similarity" -> RoutineLineFormat("%") { "${it.roundToInt()} %" }
    key == "reg:places" -> RoutineLineFormat("places") { "%.1f".format(it) }
    else -> RoutineLineFormat("min", ::minutesWords)
}

private fun lineRows(lines: List<DailyLine>, through: LocalDate): List<MeItem> =
    lines.sortedByDescending { it.average(through) ?: -1.0 }.map { l ->
        MeRow(
            l.label.uppercase(), value = l.average(through)?.let(routineLineFormat(l.key).format) ?: "—",
            sub = "${l.source.label} · ${l.daysIn(through)} OF 28 DAYS", hue = routineLineHue(l.key),
        )
    }

private fun linesSection(title: String, lines: List<DailyLine>, through: LocalDate, info: String, empty: String, extra: List<MeItem> = emptyList()) =
    MeSection(title, (if (lines.isEmpty()) listOf(MeNote(empty)) else listOf(MeNote("28-day average a day")) + lineRows(lines, through)) + extra, info)

fun routineMeView(
    today: LocalDate,
    ribbons: List<RibbonDay>,
    time: List<DailyLine>,
    places: List<DailyLine>,
    phone: List<DailyLine>,
    regularity: List<DailyLine>,
    activities: List<RoutineActivity>,
    logs: List<RoutineLog>,
    apps: List<Triple<String, String, Double>>,
): MeViewDoc {
    val through = today.minusDays(1)
    val sections = mutableListOf<MeSection>()

    sections += MeSection(
        "THE LAST 28 DAYS",
        if (ribbons.all { d -> d.spans.all { it.kind == RibbonKind.UNKNOWN } }) listOf(MeNote("Nothing recorded yet."))
        else {
            val totals = ribbonTotals(ribbons)
            listOf(MeRibbons(
                "The last 28 days", RIBBON_SLOTS,
                ribbons.map { d -> MeRibbonDay(d.date, d.spans.map { Triple(it.from, it.to, ribbonHue(it.kind)) }) },
                RibbonKind.entries.map { k -> MeLegend(k.label, ribbonHue(k), routineHours((totals[k] ?: 0.0) / 60 / ribbons.size)) },
                caption = "Legend: hours a day, on average over these 28 days.",
            ))
        },
        info = "Each day from midnight to midnight, in 15-minute pieces: training, time you logged, sleep, on the move, work, " +
            "elsewhere, home, and grey where nothing recorded. Where two overlap, the first in that list shows.",
    )
    sections += linesSection(
        "TIME", time, through,
        info = "A daily average over the last 28 complete days: what you logged, training from the watch, walking and cycling " +
            "in trips, commuting door to door, time outdoors in daylight, phone video and social apps, sleep.",
        empty = "Nothing yet: log time on Lab › ROUTINE, or turn on Locations and Phone use in Setup.",
    )
    sections += linesSection(
        "PLACES", places, through,
        info = "Hours a day by kind of place, over the days Locations recorded at least half of. Places are named on the phone.",
        empty = "No recorded days yet. Turn on Locations in Setup › Profile & goals › Data capture.",
    )
    sections += linesSection(
        "PHONE", phone, through,
        info = "From Android's usage data, on the days it was read: screen time, phone use in the hour before sleep, unlocks " +
            "between falling asleep and waking, and time in video, social and game apps.",
        empty = "No phone days yet. Turn on Phone use in Setup › Profile & goals › Data capture.",
        extra = if (apps.isEmpty()) emptyList() else listOf(MeNote("Most used · 28 days")) + apps.map { (label, category, minutes) ->
            MeRow(label.uppercase(), value = "${minutesWords(minutes / 28)} a day", sub = category.uppercase())
        },
    )

    val practices = practicesInWindow(activities, logs, today)
    sections += MeSection(
        "PRACTICES",
        if (practices.isEmpty()) listOf(MeNote("No practices yet. Add one on Lab › ROUTINE."))
        else practices.map { a ->
            val kept = adherence(a, logs, today)
            val done = logs.filter { it.activityId == a.id && it.status == LogStatus.DONE && it.date.isAfter(today.minusDays(28)) }
            MeRow(
                a.name.uppercase() + if (a.archived) " · REMOVED" else "",
                value = if (kept.scheduled > 0) "${kept.done}/${kept.scheduled}" else "${done.size}×",
                sub = listOfNotNull(scheduleWords(a.days).uppercase(), "${minutesWords(done.sumOf { it.length }).uppercase()} IN 28 DAYS", kept.streak.takeIf { it > 1 }?.let { "$it IN A ROW" }).joinToString(" · "),
                hue = "m-time-logged",
            )
        },
        info = "Each practice of the last 28 days: the scheduled days it was done, the time logged and its streak. Practices are added and changed on Lab › ROUTINE.",
    )

    val changes = (time + places + phone + regularity).mapNotNull { l -> compareWindows(l, through)?.let { l to it } }
    sections += MeSection(
        "THE LAST 4 WEEKS AGAINST THE 4 BEFORE",
        if (changes.isEmpty()) listOf(MeNote("Needs 7 recorded days in each of the two 28-day windows; it fills in as history builds up."))
        else changes.sortedByDescending { (_, c) -> abs(c.relative ?: 0.0) }.map { (l, c) ->
            val f = routineLineFormat(l.key)
            MeRow(l.label.uppercase(), value = f.format(c.recent), sub = changeWords(c, f.format, sameWithin = if (f.unit == "min") 1.0 else 0.05), hue = routineLineHue(l.key))
        },
        info = "Each line's daily average over the last 28 days beside the 28 before, where both have at least 7 recorded days. What changed, not why.",
    )
    sections += linesSection(
        "REGULARITY", regularity, through,
        info = "How regular the days are: the spread of bedtimes and wake times over each 7 nights (lower is more regular), how " +
            "much a day's hours by place look like the same weekday in the 4 weeks before, and places a day.",
        empty = "Builds up with sleep and Locations data.",
    )
    return MeViewDoc("ROUTINE", sections, range = "28D")
}
