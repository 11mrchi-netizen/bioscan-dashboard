package com.bioscan.fieldterminal.domain.notifications

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// The words of the in-app report page (docs/reports), the same as the web page's console/src/lib/reports.ts. Pure, so
// it is unit-tested; the page itself is ui/screens/status/ReportOverlay.kt.

/** How a line is: drawn in a status colour, never a metric's or a section's. NONE is plain ink. */
enum class ReportTone { OK, WARN, BAD, NONE }

/** One metric line of a stored report (PeriodReport.metrics). */
data class ReportMetric(
    val id: String,
    val label: String,
    val unit: String,
    val section: String,
    val value: Double?,
    val prev: Double?,
    val delta: Double?,
    val days: Int,
    val status: String,
    val perWeek: Boolean,
)

data class ReportRegimen(val id: Long, val name: String, val planned: Int, val taken: Int, val rate: Double?, val status: String)
data class ReportLab(val name: String, val verdict: String, val detail: String)
data class ReportMicro(
    val key: String,
    val label: String,
    val unit: String,
    val value: Double?,
    val food: Double?,
    val supplements: Double?,
    val reference: Double?,
    val upper: Double?,
    val status: String,
    val fromSupplements: List<String>,
)
data class ReportSuggestion(val text: String, val because: String)

/** A whole stored report, as the page needs it. */
data class ReportDetail(
    val key: String,
    val grain: String,
    val label: String,
    val start: String,
    val end: String,
    val better: List<String>,
    val worse: List<String>,
    val working: List<String>,
    val wasted: List<String>,
    val notFollowed: List<String>,
    val excess: List<String>,
    val short: List<String>,
    val suggestions: List<ReportSuggestion>,
    val metrics: List<ReportMetric>,
    val regimen: List<ReportRegimen>,
    val lab: List<ReportLab>,
    val micro: List<ReportMicro>,
)

/** "1,234" / "7.3" / "–". */
fun reportNumber(v: Double?): String = when {
    v == null -> "–"
    abs(v) >= 100 -> "%,d".format(Locale.US, v.roundToLong())
    else -> "%.1f".format(Locale.US, v).removeSuffix(".0")
}

/** "+3.9" / "−1.5" / "±0". */
fun reportSigned(v: Double): String {
    val r = Math.round(v * 10) / 10.0
    return when {
        r == 0.0 -> "±0"
        r > 0 -> "+" + reportNumber(r)
        else -> "−" + reportNumber(-r)
    }
}

/** A metric line's status word and tone: better / worse in status colours, up / down / steady plain. */
fun reportMetricStatus(m: ReportMetric): Pair<String, ReportTone> = when (m.status) {
    "better" -> "better" to ReportTone.OK
    "worse" -> "worse" to ReportTone.BAD
    "up" -> "up" to ReportTone.NONE
    "down" -> "down" to ReportTone.NONE
    "steady" -> "steady" to ReportTone.NONE
    "thin" -> "too few days" to ReportTone.NONE
    else -> "no data" to ReportTone.NONE
}

/** "+3.9 vs 48.1 the week before · 6 days". */
fun reportMetricSub(m: ReportMetric, grain: String): String {
    val before = if (grain == "month") "the month before" else "the week before"
    val head = if (m.prev == null || m.delta == null) "nothing $before" else "${reportSigned(m.delta)} vs ${reportNumber(m.prev)} $before"
    return "$head · " + if (m.perWeek) "per week" else "${m.days} days"
}

fun reportRegimenStatus(r: ReportRegimen): Pair<String, ReportTone> = when (r.status) {
    "followed" -> "followed" to ReportTone.OK
    "partly" -> "partly" to ReportTone.WARN
    "not_followed" -> "not followed" to ReportTone.BAD
    else -> "as needed" to ReportTone.NONE
}

/** "5 of 7 doses · 71%", or the count for an as-needed item. */
fun reportRegimenSub(r: ReportRegimen): String =
    if (r.rate == null) "${r.taken} taken" else "${r.taken} of ${r.planned} doses · ${Math.round(r.rate * 100)}%"

fun reportLabVerdict(l: ReportLab): Pair<String, ReportTone> = when (l.verdict) {
    "working" -> "working" to ReportTone.OK
    "worse" -> "making it worse" to ReportTone.BAD
    "no_effect" -> "no effect" to ReportTone.WARN
    "not_followed" -> "not followed" to ReportTone.WARN
    else -> "too early" to ReportTone.NONE
}

fun reportMicroStatus(m: ReportMicro): Pair<String, ReportTone> = when (m.status) {
    "high" -> "over the safe upper limit" to ReportTone.BAD
    "high_days" -> "over the limit on some days" to ReportTone.WARN
    "low" -> "possibly short" to ReportTone.WARN
    "ok" -> "enough" to ReportTone.OK
    else -> "not tracked" to ReportTone.NONE
}

/** The flagged nutrients first: over the limit, over on some days, possibly short, then the rest. */
fun reportMicroOrder(lines: List<ReportMicro>): List<ReportMicro> {
    val rank = mapOf("high" to 0, "high_days" to 1, "low" to 2, "ok" to 3)
    return lines.filter { it.status != "no_data" }.sortedBy { rank[it.status] ?: 4 }
}

/** "75 mg food + 57 mg supplements (Magnesium) · need 400 · limit 350". */
fun reportMicroSub(m: ReportMicro): String {
    val parts = mutableListOf<String>()
    val food = m.food?.let { "${reportNumber(it)} from food" } ?: "food not tracked"
    val supp = m.supplements?.takeIf { it > 0 }?.let { s ->
        "${reportNumber(s)} from supplements" + if (m.fromSupplements.isNotEmpty()) " (${m.fromSupplements.joinToString(", ")})" else ""
    }
    parts += listOfNotNull(food, supp).joinToString(" + ")
    m.reference?.let { parts += "need ${reportNumber(it)}" }
    m.upper?.let { parts += "limit ${reportNumber(it)}" }
    return parts.joinToString(" · ")
}

/** "2 better, 1 worse than the week before." */
fun reportHeadline(r: ReportDetail): String {
    val counts = listOfNotNull(
        r.better.size.takeIf { it > 0 }?.let { "$it better" },
        r.worse.size.takeIf { it > 0 }?.let { "$it worse" },
    )
    return if (counts.isEmpty()) "Everything steady against the period before."
    else counts.joinToString(", ") + " than the ${if (r.grain == "month") "month" else "week"} before."
}

/** The section names the web page uses (periodReport.ts SECTION_LABELS). */
val REPORT_SECTIONS = linkedMapOf(
    "recovery" to "Recovery", "sleep" to "Sleep", "activity" to "Activity and training", "body" to "Body",
    "wellbeing" to "How you felt", "nutrition" to "Food and water", "gut" to "Gut",
)
