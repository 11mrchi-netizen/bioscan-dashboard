package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Analysis review phase B (docs/analysis-review/README.md): the evening check-in. One question per exposure the
// relationship engine asks about, each answered explicitly, so a quiet evening is a real zero. Before this, a day counted
// as sober and caffeine-free whenever it had any food log at all, and as unknown otherwise.
//
// null = not answered; 0 / false = none. Stress is a rating, not an exposure, so "Nothing else" never invents one.
data class EveningCheckin(
    val date: LocalDate,
    val drinks: Int? = null,
    val caffeineAfter14: Boolean? = null,
    val lateMeal: Boolean? = null,
    val stress: Int? = null,
    val lateScreen: Boolean? = null,
    // Scoring system (FUEL › gut): 0 (rough) to 4 (comfortable), and the symptoms ticked that day. An empty list is an
    // explicit none; null is not answered. Bowel movement times come from stool_log, not from here.
    val gutComfort: Int? = null,
    val gutSymptoms: List<String>? = null,
)

/** The gut symptoms the check-in offers, in the order shown (the database checks the same list). */
val GUT_SYMPTOMS = listOf("bloating", "cramps", "reflux", "urgency", "gas", "nausea")

// The reminder fires at 22:25 (the owner's choice); the sheet is also reachable from Today the next morning.
val EVENING_CHECKIN_TIME: LocalTime = LocalTime.of(22, 25)

// Until 04:00 the small hours still belong to the evening before, so a check-in answered at 00:40 is about yesterday.
fun checkinDateFor(now: LocalDateTime): LocalDate =
    if (now.toLocalTime().isBefore(LocalTime.of(4, 0))) now.toLocalDate().minusDays(1) else now.toLocalDate()

// "Nothing else": every exposure the person did not mark becomes an explicit none. Late screen use is not asked at
// 22:25 (it hasn't happened yet), so it stays unanswered unless set.
fun EveningCheckin.withNothingElse(): EveningCheckin = copy(
    drinks = drinks ?: 0,
    caffeineAfter14 = caffeineAfter14 ?: false,
    lateMeal = lateMeal ?: false,
    // Symptoms are an exposure-like "anything happen?" list: unticked means none. Comfort is a rating and is never invented.
    gutSymptoms = gutSymptoms ?: emptyList(),
)

// Complete when the three evening exposures have an answer (stress and late screen are optional).
val EveningCheckin.isComplete: Boolean
    get() = drinks != null && caffeineAfter14 != null && lateMeal != null

// The 22:25 reminder is skipped once tonight's exposures are answered.
fun eveningCheckinNeeded(saved: EveningCheckin?): Boolean = saved == null || !saved.isComplete

// One line for the Today card: "2 drinks · no caffeine after 14:00 · no late meal · stress 3".
fun EveningCheckin.summary(): String = listOfNotNull(
    drinks?.let { if (it == 0) "no drinks" else if (it == 1) "1 drink" else "$it drinks" },
    caffeineAfter14?.let { if (it) "caffeine after 14:00" else "no caffeine after 14:00" },
    lateMeal?.let { if (it) "ate after 21:00" else "no late meal" },
    stress?.let { "stress $it" },
    gutComfort?.let { "gut $it/4" },
    gutSymptoms?.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString(" · ").ifEmpty { "nothing answered yet" }
