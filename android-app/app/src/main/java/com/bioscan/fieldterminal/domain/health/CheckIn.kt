package com.bioscan.fieldterminal.domain.health

import com.bioscan.fieldterminal.data.model.HealthObservationInsert
import com.bioscan.fieldterminal.data.model.HealthSignalInsert
import com.bioscan.fieldterminal.data.model.NewHealthConcernRow
import com.bioscan.fieldterminal.data.model.RegionRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// Health concerns (docs/health-concerns/01-plan.md §2.2, §5): what one check-in writes. Pure, so the rules are tested
// (CheckInTest) and the sheet only collects answers.
//
//   * Each active concern answered gets its own check-in row: GONE is an explicit symptom-free answer, BETTER / SAME /
//     WORSE carry a severity (prefilled with the last one) and the change.
//   * Each new problem is a check-in about its concern (a new concern is created first), marked as an onset when the
//     person says it started with a specific event (a knock, a twist).
//   * "That's everything" (on by default) adds one sweep saying nothing else bothers the person, so every other concern
//     counts as symptom-free that day rather than unobserved. NOTHING alone is just that sweep.
//   * NOTHING SINCE <date> makes the sweep cover the missed days too (at most seven, as the table allows); those days
//     are marked as recalled.
// A check-in in the small hours belongs to the day before, as the evening check-in does (EveningCheckin.kt).

enum class ConcernChange(val db: String?) { Gone("better"), Better("better"), Same("same"), Worse("worse") }

data class ConcernAnswer(
    val concernId: String,
    val change: ConcernChange,
    /** 0-10; ignored for GONE. */
    val severity: Int?,
    /** 0 full participation, 1 reduced, 2 training modified or stopped, 3 daily life affected. */
    val impact: Int?,
    val note: String? = null,
    /** The concern's main symptom (its first), so a cold's answer is not filed as pain. */
    val symptom: String = "sym.pain",
)

data class NewSignal(
    val symptom: String,
    val region: String? = null,
    val side: String? = null,
    val severity: Int,
    val details: Map<String, String> = emptyMap(),
    val temperatureC: Double? = null,
)

data class NewConcernSpec(
    val title: String,
    val kind: String,
    val onsetType: String,
    val regions: List<RegionRef>,
    val symptoms: List<String>,
)

data class NewProblem(
    /** An existing concern, or null with [newConcern] set. */
    val concernId: String?,
    val newConcern: NewConcernSpec?,
    val signals: List<NewSignal>,
    val impact: Int?,
    val onset: Boolean,
    val mechanism: String? = null,
    val sessionId: Long? = null,
    val timing: String? = null,
    val note: String? = null,
)

data class CheckInDraft(
    val answers: List<ConcernAnswer> = emptyList(),
    val problems: List<NewProblem> = emptyList(),
    val nothingElse: Boolean = true,
    /** Set when the person says nothing bothered them since the last check-in. */
    val recallFrom: LocalDate? = null,
)

data class CheckInPlan(
    val concerns: List<NewHealthConcernRow>,
    val observations: List<HealthObservationInsert>,
    val signals: List<HealthSignalInsert>,
)

const val CHECKIN_ROLLOVER_HOUR = 4
const val MAX_RECALL_DAYS = 7L

/** The local day a check-in is about: before 04:00 it is still the evening before. */
fun checkInDate(now: ZonedDateTime): LocalDate =
    if (now.hour < CHECKIN_ROLLOVER_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()

/** The earliest day "nothing since" may cover: the day after the last check-in, but no more than seven days back. */
fun recallStart(lastCheckin: LocalDate?, today: LocalDate): LocalDate? {
    if (lastCheckin == null) return null
    val from = lastCheckin.plusDays(1)
    if (!from.isBefore(today)) return null
    val floor = today.minusDays(MAX_RECALL_DAYS)
    return if (from.isBefore(floor)) floor else from
}

fun validSeverity(v: Int?): Boolean = v == null || v in 0..10

/** The 21:00 reminder is skipped once a check-in (NOTHING included) is saved for the day. */
fun healthCheckinNeeded(lastCheckin: LocalDate?, today: LocalDate): Boolean = lastCheckin == null || lastCheckin.isBefore(today)

/** Every row one check-in writes, in insert order (concerns, then check-ins, then their signals). */
fun buildCheckIn(draft: CheckInDraft, now: ZonedDateTime, newId: () -> String = { UUID.randomUUID().toString() }): CheckInPlan {
    val date = checkInDate(now)
    val stamp = now.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    val offset = now.offset.totalSeconds / 60
    val concerns = mutableListOf<NewHealthConcernRow>()
    val observations = mutableListOf<HealthObservationInsert>()
    val signals = mutableListOf<HealthSignalInsert>()
    fun obs(concernId: String?, scope: String, free: Boolean, impact: Int?, change: String?, onset: Boolean = false,
            mechanism: String? = null, sessionId: Long? = null, timing: String? = null, note: String? = null,
            recall: String = "today", recallFrom: String? = null): String {
        val id = newId()
        observations += HealthObservationInsert(
            id = id, observedAt = stamp, localDate = date.toString(), utcOffsetMin = offset, concernId = concernId, scope = scope,
            symptomFree = free, recall = recall, recallFrom = recallFrom, functionImpact = impact, changeVsLast = change,
            onset = onset, mechanism = mechanism?.takeIf { it.isNotBlank() }, sessionId = sessionId, timing = timing,
            note = note?.takeIf { it.isNotBlank() },
        )
        return id
    }

    for (a in draft.answers) {
        require(validSeverity(a.severity)) { "severity must be 0-10" }
        if (a.change == ConcernChange.Gone) {
            obs(a.concernId, "concern", free = true, impact = 0, change = a.change.db, note = a.note)
        } else {
            val id = obs(a.concernId, "concern", free = false, impact = a.impact, change = a.change.db, note = a.note)
            signals += HealthSignalInsert(observationId = id, concernId = a.concernId, symptom = a.symptom, severity = a.severity)
        }
    }
    for (p in draft.problems) {
        val cid = p.concernId ?: run {
            val spec = requireNotNull(p.newConcern) { "a new problem needs a concern" }
            val id = newId()
            concerns += NewHealthConcernRow(
                id = id, title = spec.title.trim().take(80).ifBlank { "Symptom" }, kind = spec.kind, onsetType = spec.onsetType,
                regions = spec.regions, symptoms = spec.symptoms, excludesFromBaselines = spec.kind == "systemic",
            )
            id
        }
        val oid = obs(cid, if (p.sessionId != null) "session" else "concern", free = false, impact = p.impact, change = null,
            onset = p.onset, mechanism = p.mechanism, sessionId = p.sessionId, timing = p.timing, note = p.note)
        for (s in p.signals) {
            require(validSeverity(s.severity)) { "severity must be 0-10" }
            signals += HealthSignalInsert(
                observationId = oid, concernId = cid, symptom = s.symptom, region = s.region, side = s.side, severity = s.severity,
                details = JsonObject(s.details.mapValues { (_, v) -> v.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(v) }),
                temperatureC = s.temperatureC,
            )
        }
    }
    if (draft.nothingElse || (draft.answers.isEmpty() && draft.problems.isEmpty())) {
        val from = draft.recallFrom?.takeIf { it.isBefore(date) && !it.isBefore(date.minusDays(MAX_RECALL_DAYS)) }
        obs(null, "sweep", free = true, impact = null, change = null,
            recall = if (from != null) "since_last" else "today", recallFrom = from?.toString())
    }
    return CheckInPlan(concerns, observations, signals)
}

/** The retraction of one check-in: a new row that replaces it and withdraws it (the table never deletes). */
fun retraction(original: com.bioscan.fieldterminal.data.model.HealthObservationRow, now: ZonedDateTime, newId: () -> String = { UUID.randomUUID().toString() }): HealthObservationInsert =
    HealthObservationInsert(
        id = newId(),
        observedAt = now.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        localDate = original.localDate,
        concernId = original.concernId,
        scope = original.scope,
        symptomFree = original.symptomFree,
        recall = original.recall,
        recallFrom = original.recallFrom,
        supersedes = original.id,
        retracted = true,
    )
