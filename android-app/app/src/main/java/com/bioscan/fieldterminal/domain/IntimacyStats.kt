package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.PersonProfileRow
import com.bioscan.fieldterminal.data.model.StiTestRow
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

// What ME › INTIMACY shows, worked out from the encounters, the people and the STI tests. Counts and the user's own
// ratings only: nothing here is clinical advice, and nothing recommends when to test.

val LogEncounterRow.day: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()

// A barrier for the whole encounter. "Condom (partly)" is not one.
private val BARRIERS = setOf("Condom", "Internal condom")

/** True with a barrier throughout, false without, null when protection was not recorded (every row copied from the old day rows). */
val LogEncounterRow.barrier: Boolean? get() = protection?.takeIf { it.isNotEmpty() }?.any { it in BARRIERS }

/** How often each value of a tag list appears, most frequent first (ties by name). */
fun tally(rows: List<LogEncounterRow>, pick: (LogEncounterRow) -> List<String>?): List<Pair<String, Int>> =
    rows.flatMap { pick(it).orEmpty() }.groupingBy { it }.eachCount().toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

data class IntimacyStats(
    val encounters: Int,
    val sexual: Int,
    val partners: Int,
    // partners whose first sexual encounter on record falls inside the window
    val newPartners: Int,
    val rounds: Int,
    val orgasms: Int,
    val withBarrier: Int,
    val withoutBarrier: Int,
    val protectionUnknown: Int,
    val avgRating: Double?,
)

/** The window [from]..[to], both included. [all] is the whole history: it decides who is new. */
fun intimacyStats(all: List<LogEncounterRow>, from: LocalDate, to: LocalDate): IntimacyStats {
    val inWindow = all.filter { e -> e.day?.let { !it.isBefore(from) && !it.isAfter(to) } == true }
    val sexual = inWindow.filter { it.sexual }
    val firstSex = all.filter { it.sexual && it.personId != null }.groupBy { it.personId!! }.mapValues { (_, rows) -> rows.mapNotNull { it.day }.minOrNull() }
    val partners = sexual.mapNotNull { it.personId }.toSet()
    return IntimacyStats(
        encounters = inWindow.size,
        sexual = sexual.size,
        partners = partners.size,
        newPartners = partners.count { id -> firstSex[id]?.let { !it.isBefore(from) } == true },
        rounds = sexual.sumOf { it.rounds ?: 1 },
        orgasms = sexual.sumOf { it.orgasmCount ?: 0 },
        withBarrier = sexual.count { it.barrier == true },
        withoutBarrier = sexual.count { it.barrier == false },
        protectionUnknown = sexual.count { it.barrier == null },
        avgRating = inWindow.mapNotNull { it.myRating }.takeIf { it.isNotEmpty() }?.average(),
    )
}

// ---- partners ----

data class PartnerSummary(
    val person: PersonProfileRow,
    val encounters: List<LogEncounterRow>,
) {
    val sexual: Int get() = encounters.count { it.sexual }
    val first: LocalDate? get() = encounters.mapNotNull { it.day }.minOrNull()
    val last: LocalDate? get() = encounters.mapNotNull { it.day }.maxOrNull()
    val avgRating: Double? get() = encounters.mapNotNull { it.myRating }.takeIf { it.isNotEmpty() }?.average()
}

enum class PartnerSort(val label: String) { Recent("RECENT"), Name("NAME"), Most("MOST"), Score("SCORE") }

/** Every person with their encounters, newest encounter first. */
fun partnerSummaries(people: List<PersonProfileRow>, encounters: List<LogEncounterRow>): List<PartnerSummary> {
    val byPerson = encounters.filter { it.personId != null }.groupBy { it.personId!! }
    return people.map { p -> PartnerSummary(p, byPerson[p.id].orEmpty().sortedByDescending { it.date }) }
}

/** The browser's list: a name search, then the chosen order. People never seen sort after those seen, by name. */
fun browsePartners(all: List<PartnerSummary>, query: String, sort: PartnerSort): List<PartnerSummary> {
    val q = query.trim()
    val byName = compareBy<PartnerSummary> { it.person.name.lowercase() }
    val order = when (sort) {
        PartnerSort.Recent -> compareByDescending<PartnerSummary> { it.last ?: LocalDate.MIN }.then(byName)
        PartnerSort.Name -> byName
        PartnerSort.Most -> compareByDescending<PartnerSummary> { it.encounters.size }.then(byName)
        PartnerSort.Score -> compareByDescending<PartnerSummary> { it.person.score ?: Int.MIN_VALUE }.then(byName)
    }
    return all.filter { q.isEmpty() || it.person.name.contains(q, ignoreCase = true) }.sortedWith(order)
}

// ---- STI tests ----

// The choices on the STI test form. Saved as the label (sti_tests.infection / result / site are text).
object StiVocab {
    val infections = listOf(
        "HIV", "Syphilis", "Chlamydia", "Gonorrhoea", "Herpes (HSV)", "HPV", "Hepatitis B", "Hepatitis C", "Trichomoniasis",
        "Mycoplasma genitalium",
    )
    val results = listOf("negative", "positive", "inconclusive", "pending")
    val sites = listOf("Blood", "Urine", "Throat", "Rectal", "Genital swab")
}

// How far back the partners of a positive result are listed. One number for every infection: a starting list to work
// from, not a clinical look-back period.
const val STI_LOOKBACK_DAYS = 90L

data class StiStatus(
    /** The latest test with a result (a pending one does not count), null when there is none. */
    val lastTest: LocalDate?,
    /** The newest row per infection, in the vocabulary's order, then anything else by name. */
    val latest: List<StiTestRow>,
    /** Positive, newest for its infection, and not marked treated. */
    val open: List<StiTestRow>,
    /** Sexual encounters after the last test (all of them when there is none). */
    val partnersSince: Int,
    val withoutBarrierSince: Int,
    val protectionUnknownSince: Int,
)

fun stiStatus(tests: List<StiTestRow>, encounters: List<LogEncounterRow>): StiStatus {
    val dated = tests.mapNotNull { t -> runCatching { LocalDate.parse(t.testDate) }.getOrNull()?.let { it to t } }
    val lastTest = dated.filter { it.second.result != "pending" }.maxOfOrNull { it.first }
    val latest = dated.groupBy { it.second.infection }.map { (_, rows) -> rows.maxWith(compareBy<Pair<LocalDate, StiTestRow>> { it.first }.thenBy { it.second.id }).second }
        .sortedWith(compareBy<StiTestRow> { StiVocab.infections.indexOf(it.infection).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.infection })
    val since = encounters.filter { e -> e.sexual && (lastTest == null || e.day?.isAfter(lastTest) == true) }
    return StiStatus(
        lastTest = lastTest,
        latest = latest,
        open = latest.filter { it.result == "positive" && it.treatedOn == null },
        partnersSince = since.mapNotNull { it.personId }.toSet().size,
        withoutBarrierSince = since.count { it.barrier == false },
        protectionUnknownSince = since.count { it.barrier == null },
    )
}

/** The people with a sexual encounter in the [STI_LOOKBACK_DAYS] up to a test date, most recent first, each once. */
fun partnersBefore(testDate: LocalDate, encounters: List<LogEncounterRow>): List<Long> =
    encounters.filter { e -> e.sexual && e.personId != null && e.day?.let { !it.isAfter(testDate) && !it.isBefore(testDate.minusDays(STI_LOOKBACK_DAYS)) } == true }
        .sortedByDescending { it.date }.map { it.personId!! }.distinct()

// ---- the Log calendar ----

/** What the calendar counts. Null kinds = everything. */
enum class CalendarFilter(val label: String, val kinds: Set<LogEntryKind>?) {
    All("ALL", null),
    Sex("SEX", setOf(LogEntryKind.Intercourse, LogEntryKind.Masturbation, LogEntryKind.Encounter)),
    Training("TRAINING", setOf(LogEntryKind.Exercise)),
    Food("FOOD", setOf(LogEntryKind.Food)),
    Drink("DRINK", setOf(LogEntryKind.Drink)),
    Supps("SUPPS", setOf(LogEntryKind.Supplement)),
    Sleep("SLEEP", setOf(LogEntryKind.Sleep)),
    Wellbeing("WELLBEING", setOf(LogEntryKind.Wellness)),
    Stool("STOOL", setOf(LogEntryKind.Stool)),
    Notes("NOTES", setOf(LogEntryKind.Note)),
}

fun CalendarFilter.matches(entry: LogEntry): Boolean = kinds == null || entry.kind in kinds

/** A month as rows of seven, Monday first; null pads the days outside the month. */
fun monthCells(month: YearMonth): List<LocalDate?> {
    val lead = (month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val days = List<LocalDate?>(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    return days + List<LocalDate?>((7 - days.size % 7) % 7) { null }
}

// ---- place and duration from the location history ----

/** A stay at a place, in local wall-clock time. */
data class VisitSpan(val placeId: String?, val start: LocalDateTime, val end: LocalDateTime) {
    val minutes: Int get() = Duration.between(start, end).toMinutes().toInt()
}

// A stay that ended up to this long before the logged time still counts: an encounter is often logged on the way home.
private const val VISIT_GRACE_MIN = 180L

/**
 * The stay an encounter logged at [at] most likely belongs to: the one that contains it, else the latest one that ended
 * within [VISIT_GRACE_MIN] before it. Stays under 10 minutes are passing through, not a place you were at.
 */
fun suggestVisit(visits: List<VisitSpan>, at: LocalDateTime): VisitSpan? {
    val real = visits.filter { it.minutes >= 10 }
    return real.filter { !at.isBefore(it.start) && !at.isAfter(it.end) }.maxByOrNull { it.start }
        ?: real.filter { it.end.isBefore(at) && Duration.between(it.end, at).toMinutes() <= VISIT_GRACE_MIN }.maxByOrNull { it.end }
}
