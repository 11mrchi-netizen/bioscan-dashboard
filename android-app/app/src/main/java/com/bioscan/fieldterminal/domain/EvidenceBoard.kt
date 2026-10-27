package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.EventCurvePayload
import com.bioscan.fieldterminal.data.model.LedgerEntry
import com.bioscan.fieldterminal.data.model.RegisteredQuestion
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// Analysis review phase D (P3, the evidence ladder): every tested question drawn to scale -- the estimate, its 95%
// interval, the no-effect zone and the smallest effect the data could detect -- grouped by outcome and kept in the
// catalogue's order, never sorted by p. Nulls stay on the board: a narrow interval inside the zone is an answer
// (NEGLIGIBLE), a wide one says keep logging (UNCLEAR, with roughly how many more days would settle it). The verdict
// word sits beside each row so nothing depends on colour, and every row carries its rung (ASSOCIATED).

enum class Verdict(val word: String) { Clear("CLEAR"), Negligible("NEGLIGIBLE"), Unclear("UNCLEAR"), NotYet("NOT YET") }

fun verdictOf(state: String): Verdict = when (state) {
    "clear" -> Verdict.Clear
    "negligible" -> Verdict.Negligible
    "unclear" -> Verdict.Unclear
    // not_yet, and rows from an older engine until tonight's analysis replaces them.
    else -> Verdict.NotYet
}

data class EvidenceProgress(val have: Int, val need: Int, val unit: String) {
    val fraction: Float get() = if (need <= 0) 1f else (have.toFloat() / need).coerceIn(0f, 1f)
    val text: String get() = "$have of about $need $unit"
}

data class EvidenceRowView(
    val id: String,
    val label: String,
    val verdict: Verdict,
    val rung: String,
    val unit: String,
    val estimate: Double?,
    val lo: Double?,
    val hi: Double?,
    // Half-width of the no-effect zone and the smallest detectable effect, same unit.
    val zone: Double?,
    val mde: Double?,
    // "-9.1% (95% interval -15.2% to -3.0%) · 14 days with it, 120 without"
    val sizeLine: String?,
    val progress: EvidenceProgress?,
    val summary: String,
    val adjustments: List<String>,
    val checks: List<String>,
    val warning: String?,
    val notes: List<String>,
    // Phase E: why the verdict can differ from the drawn interval, the pooled estimate, and the registration line.
    val verdictIntervalLine: String? = null,
    val pooledLine: String? = null,
    val registeredLine: String? = null,
)

data class EvidenceGroup(val outcome: String, val title: String, val unit: String, val rows: List<EvidenceRowView>, val scale: Double)

// The catalogue's order (supabase/functions/_shared/analysis-core/hypotheses.ts); unknown ids follow, by label.
val CATALOGUE_ORDER = listOf(
    "alcohol_hrv", "alcohol_rhr", "alcohol_sleep", "caffeine_pm_sleep", "late_meal_sleep", "late_meal_hrv", "stress_sleep",
    "evening_training_sleep", "training_hrv", "recovery_hrv", "sleep_hrv", "steps_hrv", "sleep_energy",
    // Routine intelligence R4 (catalogue 2.1.0): phone use, places and commutes.
    "screen_bed_sleep", "screen_bed_hrv", "night_phone_energy", "screen_pm_social_sleep", "outdoor_daylight_sleep",
    "home_day_energy", "travel_day_hrv", "travel_day_rhr", "active_commute_hrv", "commute_long_sleep",
)

private val GROUPS = listOf("hrv" to "HRV", "rhr" to "RESTING HEART RATE", "sleep" to "SLEEP", "energy" to "ENERGY")

private fun signed(v: Double, unit: String): String {
    val sign = if (v >= 0) "+" else "-"
    val a = abs(v)
    return when (unit) {
        "%" -> "$sign${"%.1f".format(a)}%"
        "min" -> "$sign${"%.0f".format(a)} min"
        "bpm" -> "$sign${"%.1f".format(a)} bpm"
        else -> "$sign${"%.2f".format(a)} $unit"
    }
}

fun evidenceRow(row: RelationshipResultRow): EvidenceRowView {
    val p = row.payload
    val e = p.effect
    val verdict = verdictOf(row.state)
    val unit = e?.unit ?: ""
    val days = when {
        p.nOn != null && p.nOff != null -> "${p.nOn} days with it, ${p.nOff} without"
        else -> "${p.n} days"
    }
    val sizeLine = if (e != null && verdict != Verdict.NotYet) {
        "${signed(e.value, unit)} (95% interval ${signed(e.lo, unit)} to ${signed(e.hi, unit)}) · $days"
    } else {
        null
    }
    val progress = p.readiness?.takeIf { verdict == Verdict.NotYet || verdict == Verdict.Unclear }?.let { EvidenceProgress(it.have, it.need, it.unit) }
    val checks = buildList {
        p.eValue?.takeIf { verdict == Verdict.Clear }?.let {
            add("E-value ${"%.1f".format(it.point)} (interval edge ${"%.1f".format(it.ci)}): a hidden factor would need a link this strong with both the exposure and the outcome to explain it away.")
        }
        p.negativeControl?.let { nc ->
            add(
                if (nc.clear) "Negative control: the outcome already differed before the exposure (${signed(nc.value, nc.unit)})."
                else "Negative control: no difference before the exposure (${signed(nc.value, nc.unit)}, interval ${signed(nc.lo, nc.unit)} to ${signed(nc.hi, nc.unit)}).",
            )
        }
        p.mde?.let { add("Smallest effect these days could detect: ${signed(it, unit).removePrefix("+")}.") }
    }
    val vi = p.verdictInterval
    val verdictIntervalLine = if (vi != null && e != null && verdict != Verdict.NotYet && (vi.lo < e.lo - 1e-9 || vi.hi > e.hi + 1e-9)) {
        "The verdict uses a wider interval (${signed(vi.lo, unit)} to ${signed(vi.hi, unit)}) because this question is re-checked every night; " +
            "it narrows as days accumulate (${"%.0f".format(vi.information * 100)}% of a full year's information so far)."
    } else {
        null
    }
    val pooledLine = p.pooled?.let { pl ->
        "Pooled with ${pl.members - 1} related question${if (pl.members == 2) "" else "s"} about the same exposure: ${signed(pl.value, pl.unit)} " +
            "(${signed(pl.lo, pl.unit)} to ${signed(pl.hi, pl.unit)}); about ${"%.0f".format(pl.probabilityBeyondZone * 100)}% likely beyond the no-effect zone under that model."
    }
    val registeredLine = p.registeredOn?.let { on -> "Registered $on · tested only on the ${p.daysSince ?: 0} days since" }
    return EvidenceRowView(
        id = row.key,
        label = p.label.ifBlank { row.key },
        verdict = verdict,
        rung = p.rung.uppercase(),
        unit = unit,
        estimate = e?.value?.takeIf { verdict != Verdict.NotYet },
        lo = e?.lo?.takeIf { verdict != Verdict.NotYet },
        hi = e?.hi?.takeIf { verdict != Verdict.NotYet },
        zone = p.noEffect,
        mde = p.mde,
        sizeLine = sizeLine,
        progress = progress,
        summary = p.summary,
        adjustments = p.adjustments,
        checks = checks,
        warning = if (p.negativeControl?.clear == true) "The outcome already differed before the exposure, so something else may drive both." else null,
        notes = p.notes,
        verdictIntervalLine = verdictIntervalLine,
        pooledLine = pooledLine,
        registeredLine = registeredLine,
    )
}

// The shared axis of one group: wide enough for every interval, zone and detectable effect, at least three zones wide.
private fun scaleOf(rows: List<EvidenceRowView>): Double {
    var s = 0.0
    for (r in rows) {
        listOfNotNull(r.lo?.let(::abs), r.hi?.let(::abs), r.mde, r.zone?.times(1.5)).forEach { s = max(s, it) }
    }
    val zone = rows.mapNotNull { it.zone }.maxOrNull() ?: 0.0
    return max(s * 1.1, zone * 3).takeIf { it > 0 } ?: 1.0
}

fun evidenceBoard(rows: List<RelationshipResultRow>, registered: List<RelationshipResultRow> = emptyList()): List<EvidenceGroup> {
    val order = { id: String -> CATALOGUE_ORDER.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
    val views = rows.sortedWith(compareBy({ order(it.key) }, { it.payload.label })).map { it to evidenceRow(it) }
    val catalogue = GROUPS.mapNotNull { (key, title) ->
        val inGroup = views.filter { (row, _) -> row.payload.outcome == key }.map { it.second }
        if (inGroup.isEmpty()) null else EvidenceGroup(key, title, inGroup.firstNotNullOfOrNull { it.unit.ifBlank { null } } ?: "", inGroup, scaleOf(inGroup))
    }
    // Phase E: questions registered from a hint, tested only on days after registration, in their own group. Their
    // units differ, so each row keeps its own axis.
    val mine = registered.sortedBy { it.payload.registeredOn ?: "" }.map(::evidenceRow)
    return if (mine.isEmpty()) catalogue else catalogue + EvidenceGroup("registered", "QUESTIONS YOU REGISTERED", "", mine, scaleOf(mine))
}

// ---- Phase E: the ledger and registration ----

data class VerdictRun(val verdict: Verdict, val from: LocalDate, val to: LocalDate, val nights: Int)

// The ledger as runs of the same verdict, newest first.
fun verdictRuns(entries: List<LedgerEntry>): List<VerdictRun> {
    val sorted = entries.mapNotNull { e -> runCatching { LocalDate.parse(e.asOf) }.getOrNull()?.let { it to verdictOf(e.verdict) } }.sortedBy { it.first }
    val runs = mutableListOf<VerdictRun>()
    for ((day, v) in sorted) {
        val last = runs.lastOrNull()
        if (last != null && last.verdict == v) runs[runs.size - 1] = last.copy(to = day, nights = last.nights + 1)
        else runs.add(VerdictRun(v, day, day, 1))
    }
    return runs.reversed()
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

// "CLEAR since 14 Oct (12 nights) · UNCLEAR 2 Sep to 13 Oct (40 nights)"
fun verdictHistoryLine(runs: List<VerdictRun>, max: Int = 3): String? {
    if (runs.isEmpty()) return null
    return runs.take(max).mapIndexed { i, r ->
        val nights = "${r.nights} night${if (r.nights == 1) "" else "s"}"
        if (i == 0) "${r.verdict.word} since ${r.from.format(SHORT_DATE)} ($nights)"
        else "${r.verdict.word} ${r.from.format(SHORT_DATE)} to ${r.to.format(SHORT_DATE)} ($nights)"
    }.joinToString(" · ")
}

// Exposure and outcome pairs the catalogue already asks about: registering them again would only repeat the question.
private val CATALOGUE_PAIRS = setOf(
    "alcohol_any:hrv", "alcohol_any:rhr", "alcohol_any:sleep_hours", "caffeine_pm_any:sleep_hours", "late_meal:sleep_hours",
    "late_meal:hrv", "stress_high:sleep_hours", "evening_training:sleep_hours", "training_day:hrv",
)

fun defaultZone(outcome: String): Double = when (outcome) {
    "hrv" -> 2.0
    "rhr" -> 1.0
    else -> 10.0
}

// A case-crossover hint can be registered when it points somewhere (more or less often before), the catalogue doesn't
// already ask it, and it isn't registered yet.
fun canRegister(exposure: String, outcome: String, verdict: String, registered: List<RegisteredQuestion>): Boolean =
    (verdict == "more_often" || verdict == "less_often") &&
        "$exposure:$outcome" !in CATALOGUE_PAIRS &&
        registered.none { it.exposure == exposure && it.outcome == outcome && it.withdrawnAt == null }

// The board's one-line tally, e.g. "2 clear · 1 negligible · 5 unclear · 5 not yet".
fun evidenceTally(groups: List<EvidenceGroup>): String {
    val all = groups.flatMap { it.rows }
    return Verdict.entries.mapNotNull { v -> all.count { it.verdict == v }.takeIf { it > 0 }?.let { "$it ${v.word.lowercase()}" } }.joinToString(" · ")
}

// Analysis review phase D, P2: what last evening brought, said with the board's own evidence. For each exposure the
// check-in recorded, the question about it that reads CLEAR (with its event curve when there is one).
data class Contributor(val exposure: String, val title: String, val line: String)

private val EXPOSURE_TITLES = mapOf(
    "alcohol" to "Drinks last evening",
    "caffeine_pm" to "Caffeine after 14:00 yesterday",
    "late_meal" to "A late meal last evening",
    "stress" to "A high-stress day yesterday",
)

private val EXPOSURE_QUESTIONS = mapOf(
    "alcohol" to listOf("alcohol_hrv", "alcohol_rhr", "alcohol_sleep"),
    "caffeine_pm" to listOf("caffeine_pm_sleep"),
    "late_meal" to listOf("late_meal_sleep", "late_meal_hrv"),
    "stress" to listOf("stress_sleep"),
)

fun contributors(lastEvening: EveningCheckin?, relationships: List<RelationshipResultRow>, curves: List<EventCurvePayload>): List<Contributor> {
    if (lastEvening == null) return emptyList()
    val present = buildList {
        if ((lastEvening.drinks ?: 0) > 0) add("alcohol")
        if (lastEvening.caffeineAfter14 == true) add("caffeine_pm")
        if (lastEvening.lateMeal == true) add("late_meal")
        if ((lastEvening.stress ?: 0) >= 4) add("stress")
    }
    return present.mapNotNull { exposure ->
        val clear = EXPOSURE_QUESTIONS[exposure].orEmpty().mapNotNull { id -> relationships.firstOrNull { it.key == id && it.state == "clear" } }
        val first = clear.firstOrNull() ?: return@mapNotNull null
        val curve = curves.firstOrNull { it.id == first.key && it.state == "ready" && it.peak != null }
        Contributor(exposure, EXPOSURE_TITLES[exposure] ?: exposure, curve?.summary ?: first.payload.summary)
    }
}
