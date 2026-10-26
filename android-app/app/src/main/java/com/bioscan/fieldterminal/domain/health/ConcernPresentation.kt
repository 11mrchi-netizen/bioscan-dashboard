package com.bioscan.fieldterminal.domain.health

import com.bioscan.fieldterminal.data.model.ConcernStateDto
import com.bioscan.fieldterminal.data.model.DurationRange
import com.bioscan.fieldterminal.data.model.EpisodeDto
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.StateWord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Health concerns: published states and statuses in the app's one vocabulary (contract R1, R5). Amber (Warning) is kept
// for WORSENING (owner decision 7.2.2); a resolution or an easing is a favourable change; unobserved is Unavailable, never
// read as fine. Finding statuses keep their rung's words and never a cause verb.

fun concernStateWord(state: String): StateWord = when (state) {
    "worsening" -> StateWord(MetricState.Warning, "WORSENING")
    "improving" -> StateWord(MetricState.Optimal, "IMPROVING")
    "stable" -> StateWord(MetricState.Neutral, "STABLE")
    "active" -> StateWord(MetricState.Neutral, "ACTIVE")
    "resolved" -> StateWord(MetricState.Neutral, "RESOLVED")
    else -> StateWord(MetricState.Unavailable, "UNOBSERVED")
}

/** Open concerns lead the list: worsening, active, stable, improving; then unobserved, then resolved. */
fun concernStateOrder(state: String): Int = when (state) {
    "worsening" -> 0
    "active" -> 1
    "stable" -> 2
    "improving" -> 3
    "unobserved" -> 4
    else -> 5
}

fun isConcernOpen(state: String): Boolean = state in setOf("active", "improving", "stable", "worsening")

fun findingStatusWord(status: String, weakening: Boolean = false): StateWord = when (status) {
    "possible" -> StateWord(MetricState.Neutral, if (weakening) "POSSIBLE · WEAKENING" else "POSSIBLE · EXPLORATORY")
    "persistent" -> StateWord(MetricState.Neutral, if (weakening) "PERSISTENT · WEAKENING" else "PERSISTENT")
    "emerging" -> StateWord(MetricState.Building, "HINT · RE-CHECKING")
    "observed" -> StateWord(MetricState.Neutral, "RECURRING")
    "no_pattern" -> StateWord(MetricState.Neutral, "NO PATTERN")
    "faded" -> StateWord(MetricState.Neutral, "FADED")
    else -> StateWord(MetricState.Building, "NOT ENOUGH YET")
}

/** Findings worth a card: patterns first, then recurrence; the rest are listed compactly. */
fun findingRank(status: String): Int = when (status) {
    "persistent" -> 0
    "possible" -> 1
    "observed" -> 2
    "emerging" -> 3
    "faded" -> 4
    "no_pattern" -> 5
    else -> 6
}

fun trialWord(state: String, word: String?): StateWord = when {
    state == "interim" -> StateWord(MetricState.Building, "INTERIM")
    state == "not_started" -> StateWord(MetricState.Building, "NOT STARTED")
    state == "insufficient" || word == "insufficient" -> StateWord(MetricState.Building, "NOT ENOUGH TO READ")
    word == "lower" -> StateWord(MetricState.Neutral, "LOWER SINCE")
    word == "higher" -> StateWord(MetricState.Neutral, "HIGHER SINCE")
    else -> StateWord(MetricState.Neutral, "NO CLEAR CHANGE")
}

fun durationText(d: DurationRange): String = if (d.min == d.max) "${d.min} d" else "${d.min}–${d.max} d"

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

fun shortDate(iso: String?, today: LocalDate = LocalDate.now()): String {
    if (iso.isNullOrBlank()) return "–"
    val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    return d.format(if (d.year == today.year) DAY_MONTH else DAY_MONTH_YEAR)
}

fun episodeLine(e: EpisodeDto, today: LocalDate = LocalDate.now()): String {
    val span = if (e.open) "since ${shortDate(e.start, today)}" else "${shortDate(e.start, today)} – ${shortDate(e.end, today)}"
    val peak = e.peakSeverity?.let { " · peak $it/10" } ?: ""
    return "$span · ${durationText(e.durationDays)}$peak"
}

val IMPACT_LABELS = listOf("FULL TRAINING", "REDUCED", "MODIFIED / STOPPED", "DAILY LIFE")

/** One point per day for the severity line: null where unobserved (the line breaks there), 0 for explicit symptom-free. */
data class SeverityPoint(val date: LocalDate, val severity: Int?, val observed: Boolean, val recalled: Boolean)

fun severityPoints(s: ConcernStateDto, days: Int): List<SeverityPoint> {
    val d = s.daily
    val n = d.dates.size
    return (maxOf(0, n - days) until n).map { i ->
        val obs = d.observed.getOrElse(i) { 0 }
        SeverityPoint(
            date = LocalDate.parse(d.dates[i]),
            severity = if (obs == 0) null else d.severity.getOrNull(i) ?: if (d.present.getOrElse(i) { 0 } == 1) null else 0,
            observed = obs != 0,
            recalled = obs == 2,
        )
    }
}

/** Days since onset against severity, per episode (the recovery overlay), oldest episode first. */
fun recoveryCurves(s: ConcernStateDto, maxEpisodes: Int = 6): List<Pair<EpisodeDto, List<Pair<Int, Int>>>> {
    val index = s.daily.dates.withIndex().associate { (i, d) -> d to i }
    return s.episodes.takeLast(maxEpisodes).map { e ->
        val start = LocalDate.parse(e.start)
        val pts = mutableListOf<Pair<Int, Int>>()
        var day = 0
        while (day <= 60) {
            val i = index[start.plusDays(day.toLong()).toString()] ?: break
            val sev = s.daily.severity.getOrNull(i)
            if (s.daily.observed.getOrElse(i) { 0 } != 0 && sev != null) pts += day to sev
            if (e.end != null && !start.plusDays(day.toLong()).isBefore(LocalDate.parse(e.end))) break
            day++
        }
        e to pts
    }
}

/** "Check-ins on 62% of the last 90 days · 24 symptom-free" for a card's "?". */
fun qualityLine(s: ConcernStateDto): String {
    val q = s.quality
    val pct = Math.round(q.coverage90 * 100)
    val miss = q.informativeMissingness?.let { if (it > 2) " Check-ins are ${"%.1f".format(it)}× as frequent during flare-ups as between them." else "" } ?: ""
    return "DATA: ${q.word}. Check-ins on $pct% of the last 90 days; ${q.symptomFreeObservedDays90} symptom-free days outside flare-ups.$miss"
}
