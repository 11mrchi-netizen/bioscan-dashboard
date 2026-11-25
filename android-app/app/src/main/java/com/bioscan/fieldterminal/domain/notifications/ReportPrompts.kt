package com.bioscan.fieldterminal.domain.notifications

import java.time.LocalDate
import java.time.LocalTime

// Weekly and monthly reports (docs/reports): the nightly analysis run writes one `period_report` row in analysis_results
// after each Monday-to-Sunday week and each calendar month. The phone announces each new one once and opens it on the web
// (Lab › Reports), where the whole report is drawn. Pure, so it is unit-tested.

/** The web app (GitHub Pages), where Lab › Reports lives. */
const val WEB_APP_URL = "https://11mrchi-netizen.github.io/bioscan-dashboard/"

/** The web address of one report, or of the Reports page when [key] is null. */
fun reportWebUrl(key: String?): String =
    if (key == null) "${WEB_APP_URL}#/lab/reports" else "${WEB_APP_URL}#/lab/reports?key=${java.net.URLEncoder.encode(key, "UTF-8")}"

/** One stored report as the phone needs it: which period, and the counts and first suggestion for the notification. */
data class ReportBrief(
    val key: String,
    val grain: String,
    val start: String,
    val end: String,
    val better: Int,
    val worse: Int,
    val notFollowed: Int,
    val firstSuggestion: String?,
)

/** The notification_log dedupe key of a report's announcement. */
fun reportDedupeKey(key: String): String = "report:$key"

/** Announcements wait for the day: not before 08:00 or after 21:30 local. */
val REPORT_PROMPT_FROM: LocalTime = LocalTime.of(8, 0)
val REPORT_PROMPT_UNTIL: LocalTime = LocalTime.of(21, 30)

/**
 * The report to announce now, or null: the newest not announced yet, written for a period that ended at most 7 days ago
 * (an old report filled in later is not news), and only in the day's window. A week and a month that end together (the
 * month ending on a Sunday) are announced one after the other, the week first.
 */
fun reportToAnnounce(reports: List<ReportBrief>, shownKeys: Set<String>, today: LocalDate, now: LocalTime): ReportBrief? {
    if (now.isBefore(REPORT_PROMPT_FROM) || now.isAfter(REPORT_PROMPT_UNTIL)) return null
    return reports
        .filter { reportDedupeKey(it.key) !in shownKeys }
        .filter { r -> runCatching { !LocalDate.parse(r.end).isBefore(today.minusDays(7)) }.getOrDefault(false) }
        .sortedWith(compareByDescending<ReportBrief> { it.end }.thenBy { if (it.grain == "week") 0 else 1 })
        .firstOrNull()
}

/** "Week 16–22 Nov" / "November 2026". */
fun reportTitle(r: ReportBrief): String {
    val start = runCatching { LocalDate.parse(r.start) }.getOrNull() ?: return "Your report"
    val end = runCatching { LocalDate.parse(r.end) }.getOrNull() ?: return "Your report"
    fun mon(d: LocalDate) = d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return if (r.grain == "month") "${start.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${start.year} report"
    else if (start.month == end.month) "Week ${start.dayOfMonth}–${end.dayOfMonth} ${mon(end)} report"
    else "Week ${start.dayOfMonth} ${mon(start)}–${end.dayOfMonth} ${mon(end)} report"
}

/** "2 better, 1 worse, 1 not followed. Sleep averaged 6.3 h: …" */
fun reportBody(r: ReportBrief): String {
    val counts = listOfNotNull(
        r.better.takeIf { it > 0 }?.let { "$it better" },
        r.worse.takeIf { it > 0 }?.let { "$it worse" },
        r.notFollowed.takeIf { it > 0 }?.let { "$it not followed" },
    )
    val head = if (counts.isEmpty()) "Everything steady against the period before." else counts.joinToString(", ") + " than the ${if (r.grain == "week") "week" else "month"} before."
    return listOfNotNull(head, r.firstSuggestion).joinToString(" ")
}
