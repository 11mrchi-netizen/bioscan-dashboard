package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.RunningHistory
import com.bioscan.fieldterminal.domain.RecordHistory
import com.bioscan.fieldterminal.domain.RecordKey
import com.bioscan.fieldterminal.domain.SportSession
import com.bioscan.fieldterminal.domain.formatRecordValue
import com.bioscan.fieldterminal.domain.levels.formatPace
import com.bioscan.fieldterminal.domain.summarizeSportsIn
import com.bioscan.fieldterminal.domain.weekStarts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Me › PERFORMANCE as the web draws it: the same content as PerformanceHub (PerformanceScreens.kt) over the last 52 weeks
// (the web picks its range from those): hours by sport each week, VO2max and lactate threshold, the headline records, and a
// row per sport for the last 13 weeks (the phone's default window).

// The phone's sport colours (PerformanceScreens.kt SPORT_COLORS), as design tokens.
private val SPORT_HUES = listOf("ft-domain-load", "ok", "m-commute", "m-vo2max", "m-hrv", "warn", "ft-muted")
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private fun hours(h: Double): String = if (h < 1) "${(h * 60).roundToInt()} min" else "%.1f h".format(Locale.ENGLISH, h)

fun performanceMeView(sessions: List<SportSession>, running: RunningHistory?, records: List<RecordHistory>, today: LocalDate): MeViewDoc {
    val sections = mutableListOf<MeSection>()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val from52 = monday.minusWeeks(51)
    val weeks = weekStarts(from52, today)
    val sports = summarizeSportsIn(sessions, from52, today, weeks)
    if (sports.isEmpty()) {
        sections += MeSection("ALL SPORTS", listOf(MeNote("No sessions recorded yet. Workouts from your watch and logged sessions show up here, one row per sport.")))
        return MeViewDoc("PERFORMANCE", sections)
    }
    val shown = sports.take(SPORT_HUES.size)
    sections += MeSection("ALL SPORTS", listOf(
        MeBars("HOURS A WEEK BY SPORT", "h", weeks, shown.mapIndexed { i, s -> MeSeriesName(s.sport.label, SPORT_HUES[i]) to s.weeklyHours }),
    ), info = "Hours per Monday-to-Sunday week by sport. Walking counts only walks your watch recorded as workouts.")

    val r = running
    if (r != null && r.vo2MaxSeries.isNotEmpty()) {
        val last = r.vo2MaxSeries.maxBy { it.first }
        sections += MeSection("VO2MAX", listOf(
            MeTile("VO2max", "%.1f".format(Locale.ENGLISH, last.second), "ml/kg/min", sub = last.first.format(DAY), hue = "m-vo2max"),
            MeChart("VO2MAX", "ml/kg/min", 1, listOf(MeSeries("VO2max", "m-vo2max", r.vo2MaxSeries.sortedBy { it.first }))),
        ), info = "Your watch's estimate of maximal oxygen uptake, from Health Connect. A slow-moving wearable estimate, not a lab test.")
    }
    if (r != null && (r.ltPaceSeries.isNotEmpty() || r.ltHrSeries.isNotEmpty())) {
        val items = mutableListOf<MeItem>()
        r.ltPaceSeries.maxByOrNull { it.first }?.let { items += MeTile("Threshold pace", formatPace(it.second), "/km", sub = it.first.format(DAY), hue = "m-pace") }
        r.ltHrSeries.maxByOrNull { it.first }?.let { items += MeTile("Threshold heart rate", "${it.second.roundToInt()}", "bpm", sub = it.first.format(DAY), hue = "m-heart-rate") }
        if (r.ltPaceSeries.isNotEmpty()) items += MeChart("THRESHOLD PACE", "/km", 2, listOf(MeSeries("Pace", "m-pace", r.ltPaceSeries.sortedBy { it.first })), caption = "lower is faster", format = "pace")
        if (r.ltHrSeries.isNotEmpty()) items += MeChart("THRESHOLD HEART RATE", "bpm", 0, listOf(MeSeries("Heart rate", "m-heart-rate", r.ltHrSeries.sortedBy { it.first })))
        sections += MeSection("LACTATE THRESHOLD", items, info = "Zepp's lactate threshold, recomputed on qualifying runs: about the fastest pace you can hold for an hour, and the heart rate at it.")
    }

    val headline = listOf(RecordKey.K5, RecordKey.K10, RecordKey.HALF, RecordKey.LONGEST, RecordKey.LONGEST_TRAIL, RecordKey.CLIMB)
        .mapNotNull { k -> records.firstOrNull { it.key == k }?.current?.let { k to it } }
    if (headline.isNotEmpty()) sections += MeSection("RECORDS", headline.map { (k, s) -> MeTile(k.label.uppercase(), formatRecordValue(k, s.value), sub = s.date.format(DAY_YEAR)) })

    val from13 = monday.minusWeeks(12)
    val window = summarizeSportsIn(sessions, from13, today, weekStarts(from13, today))
    sections += MeSection("SPORTS · LAST 13 WEEKS", window.map { s ->
        MeRow(
            s.sport.label,
            value = if (s.sessions4w == 0) null else hours(s.hours4w),
            sub = (listOf(if (s.sessions4w == 0) "nothing in the last 13 weeks" else listOfNotNull("${s.sessions4w} sessions", s.km4w?.let { "%.0f km".format(Locale.ENGLISH, it) }).joinToString(" · ")) +
                "last ${s.lastDate.format(DAY)} · ${s.totalSessions} in all").joinToString(" · "),
        )
    })
    return MeViewDoc("PERFORMANCE", sections)
}
