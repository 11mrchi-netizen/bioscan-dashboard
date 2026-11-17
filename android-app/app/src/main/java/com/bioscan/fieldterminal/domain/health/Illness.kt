package com.bioscan.fieldterminal.domain.health

import com.bioscan.fieldterminal.data.model.EpisodeMarkInsert
import com.bioscan.fieldterminal.data.model.HealthObservationInsert
import com.bioscan.fieldterminal.data.model.HealthSignalInsert
import com.bioscan.fieldterminal.data.model.NewHealthConcernRow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// Illnesses and infections (docs/health-concerns/README.md, "Illness, infections and the symptom check"): logged as a
// concern of their own, not through the body map. What kind it is (a cold, a stomach bug, COVID), when it started, the
// symptoms and how bad, a temperature, a test, a clinician, medication, and what it does to training. The first check-in
// is written with it, so the score's ceiling is graded from day one (domain/score/IllnessRead.kt); a start before today
// is an episode start mark on that day.

data class IllnessType(val code: String, val label: String, val infection: Boolean, val symptoms: List<String>)

val ILLNESS_TYPES: List<IllnessType> = listOf(
    IllnessType("cold", "Cold", true, listOf("sym.congestion", "sym.sneezing", "sym.sore_throat", "sym.cough", "sym.headache")),
    IllnessType("flu_like", "Flu-like", true, listOf("sym.fever", "sym.chills", "sym.muscle_aches", "sym.fatigue", "sym.cough", "sym.headache")),
    IllnessType("covid", "COVID-19", true, listOf("sym.fever", "sym.cough", "sym.sore_throat", "sym.fatigue", "sym.loss_smell", "sym.congestion")),
    IllnessType("stomach_bug", "Stomach bug", true, listOf("sym.nausea", "sym.vomiting", "sym.diarrhoea", "sym.stomach_cramps", "sym.fever")),
    IllnessType("sinus", "Sinus infection", true, listOf("sym.sinus_pressure", "sym.congestion", "sym.headache", "sym.fever")),
    IllnessType("throat", "Throat infection", true, listOf("sym.sore_throat", "sym.fever", "sym.headache")),
    IllnessType("ear", "Ear infection", true, listOf("sym.earache", "sym.fever")),
    IllnessType("chest", "Chest infection", true, listOf("sym.cough", "sym.chest_tightness", "sym.breathless", "sym.fever", "sym.fatigue")),
    IllnessType("urinary", "Urinary infection", true, listOf("sym.urinary", "sym.fever")),
    IllnessType("skin", "Skin infection", true, listOf("sym.rash", "sym.fever")),
    IllnessType("eye", "Eye infection", true, listOf("sym.eye_irritation")),
    IllnessType("allergy", "Allergy", false, listOf("sym.sneezing", "sym.congestion", "sym.eye_irritation", "sym.rash")),
    IllnessType("migraine", "Migraine", false, listOf("sym.headache", "sym.nausea", "sym.dizziness")),
    IllnessType("other", "Other illness", false, emptyList()),
)

fun illnessType(code: String?): IllnessType? = ILLNESS_TYPES.firstOrNull { it.code == code }

/** A test taken: what for and what it said. */
enum class TestResult(val label: String) { POSITIVE("POSITIVE"), NEGATIVE("NEGATIVE") }

data class IllnessDraft(
    val type: IllnessType,
    val title: String,
    val start: LocalDate,
    val symptoms: Set<String>,
    /** 0-10, the worst of it now. */
    val severity: Int,
    /** 0 full participation, 1 reduced, 2 training modified or stopped, 3 daily life affected. */
    val impact: Int?,
    val temperatureC: Double? = null,
    val test: String? = null,
    val testResult: TestResult? = null,
    val sawClinician: Boolean = false,
    val diagnosis: String? = null,
    val medication: String? = null,
    val note: String? = null,
)

data class IllnessPlan(val checkIn: CheckInPlan, val startMark: EpisodeMarkInsert?)

/** The concern, its first check-in with a signal per symptom, and a start mark when it began before today. */
fun buildIllness(d: IllnessDraft, now: ZonedDateTime, newId: () -> String = { UUID.randomUUID().toString() }): IllnessPlan {
    require(d.severity in 0..10) { "severity must be 0-10" }
    val today = checkInDate(now)
    val cid = newId()
    val oid = newId()
    val symptoms = d.symptoms.ifEmpty { setOf("sym.other") }
    val concern = NewHealthConcernRow(
        id = cid, title = d.title.trim().take(80).ifBlank { d.type.label }, kind = "systemic", onsetType = "unknown",
        regions = emptyList(), symptoms = symptoms.toList(), excludesFromBaselines = true,
        category = if (d.type.infection) "infection" else "illness", illnessType = d.type.code,
        labelDiagnosis = d.diagnosis?.trim()?.take(120)?.ifBlank { null },
    )
    val care = buildJsonObject {
        d.medication?.trim()?.takeIf { it.isNotEmpty() }?.let { put("medication", JsonPrimitive(it.take(200))) }
        if (d.sawClinician) put("saw_clinician", JsonPrimitive(true))
        d.test?.let { put("test", JsonPrimitive(it)) }
        d.testResult?.let { put("test_result", JsonPrimitive(it.name.lowercase())) }
    }
    val obs = HealthObservationInsert(
        id = oid, observedAt = now.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), localDate = today.toString(),
        utcOffsetMin = now.offset.totalSeconds / 60, concernId = cid, scope = "concern", symptomFree = false,
        functionImpact = d.impact, onset = true, note = d.note?.trim()?.ifBlank { null }, care = care.takeIf { it.isNotEmpty() },
    )
    val signals = symptoms.map { s ->
        HealthSignalInsert(
            observationId = oid, concernId = cid, symptom = s, severity = d.severity,
            temperatureC = d.temperatureC?.takeIf { it in 34.0..43.0 && (s == "sym.fever" || s == symptoms.first()) },
        )
    }
    val mark = if (d.start.isBefore(today)) EpisodeMarkInsert(cid, "start", d.start.toString(), "started before it was logged") else null
    return IllnessPlan(CheckInPlan(listOf(concern), listOf(obs), signals), mark)
}
