package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.SymptomCheckRow
import com.bioscan.fieldterminal.data.model.ConcernOverviewDto
import com.bioscan.fieldterminal.data.model.FindingDto
import com.bioscan.fieldterminal.data.model.HealthConcernRow
import com.bioscan.fieldterminal.data.model.HealthOverviewDto
import com.bioscan.fieldterminal.domain.health.CHECK_PATTERNS
import com.bioscan.fieldterminal.domain.health.HealthVocab
import com.bioscan.fieldterminal.domain.health.concernStateOrder
import com.bioscan.fieldterminal.domain.health.concernStateWord
import com.bioscan.fieldterminal.domain.health.findingStatusWord
import com.bioscan.fieldterminal.domain.health.illnessType
import com.bioscan.fieldterminal.domain.health.isConcernOpen
import com.bioscan.fieldterminal.domain.health.shortDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

// Me › HEALTH as the web draws it: the same content as ConcernsTab.kt (what is open, the clinician prompts, symptoms no
// concern claims yet, open injuries and illnesses, the small things of the last 14 days, the patterns so far, resolved).
// Each concern row carries its first body region so the 3D body can place it.

private fun concernRow(c: HealthConcernRow, o: ConcernOverviewDto?, vocab: HealthVocab): MeRow {
    val where = c.regions.joinToString { vocab.regionLabel(it.code, it.side) }.ifBlank {
        if (c.kind == "systemic") listOfNotNull(c.categoryOrKind.uppercase(), illnessType(c.illnessType)?.label?.uppercase()).joinToString(" · ") else ""
    }
    val state = o?.state ?: "unobserved"
    return MeRow(
        c.title, value = o?.lastSeverity?.let { "$it/10" },
        tone = when (state) { "worsening" -> Tone.WARN; "improving" -> Tone.OK; else -> null },
        state = concernStateWord(state).word,
        sub = listOfNotNull(
            where.takeIf { it.isNotBlank() },
            o?.episodes?.takeIf { it > 0 }?.let { "$it episode${if (it == 1) "" else "s"}" },
            o?.findings?.takeIf { it > 0 }?.let { "$it pattern${if (it == 1) "" else "s"}" },
            if (c.onsetType == "acute") "single event" else null,
            o?.stateReason?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifEmpty { null },
        region = c.regions.firstOrNull()?.code, side = c.regions.firstOrNull()?.side,
    )
}

fun healthMeView(
    concerns: List<HealthConcernRow>,
    overview: HealthOverviewDto?,
    findings: List<FindingDto>,
    checks: List<SymptomCheckRow>,
    vocab: HealthVocab,
): MeViewDoc {
    val byId = overview?.concerns?.associateBy { it.concernId } ?: emptyMap()
    val (archived, current) = concerns.partition { it.archivedAt != null }
    val sorted = current.sortedWith(compareBy({ concernStateOrder(byId[it.id]?.state ?: "unobserved") }, { it.title }))
    val (open, closed) = sorted.partition { byId[it.id]?.state?.let(::isConcernOpen) ?: (it.legacySource == null) }
    val sections = mutableListOf<MeSection>()

    sections += MeSection("CONCERNS", listOfNotNull(
        MeNote(if (open.isEmpty()) "Nothing open." else "${open.size} open: " + open.joinToString { it.title }, muted = open.isEmpty()),
        overview?.lastCheckin?.let { MeNote("Last check-in ${shortDate(it)}") },
        if (overview == null && concerns.isNotEmpty()) MeNote("States and patterns appear after the next analysis run (nightly, or UPDATE on the phone).") else null,
    ), info = "A concern is anything that keeps bothering you, in your own words. This is personal tracking, not a diagnosis.")

    val prompts = (overview?.concerns ?: emptyList()).filter { !it.archived }.flatMap { o -> o.safety.map { o.title to it.text } }
    if (prompts.isNotEmpty()) sections += MeSection("WORTH A CLINICIAN'S VIEW", prompts.map { (title, text) -> MeRow(title, sub = text, tone = Tone.WARN, state = "SEE A CLINICIAN") })

    val unassigned = overview?.unassigned ?: emptyList()
    if (unassigned.isNotEmpty()) sections += MeSection("NEW, NOT YET A CONCERN", unassigned.takeLast(5).map { u ->
        MeRow(vocab.symptom(u.signal.symptom)?.label ?: u.signal.symptom, value = u.signal.severity?.let { "$it/10" },
            sub = listOfNotNull(shortDate(u.date), u.signal.region?.let { vocab.regionLabel(it, u.signal.side) }).joinToString(" · "),
            region = u.signal.region, side = u.signal.side)
    })

    val (illnessesOpen, injuriesOpen) = open.partition { it.categoryOrKind != "injury" }
    sections += MeSection("INJURIES & PAIN", if (injuriesOpen.isEmpty()) listOf(MeNote("None open.")) else injuriesOpen.map { concernRow(it, byId[it.id], vocab) })
    sections += MeSection("ILLNESSES & INFECTIONS", if (illnessesOpen.isEmpty()) listOf(MeNote("None open.")) else illnessesOpen.map { concernRow(it, byId[it.id], vocab) })

    if (checks.isNotEmpty()) sections += MeSection("SMALL THINGS · 14 DAYS", checks.map { c ->
        val a = c.assessment
        fun str(k: String) = (a?.get(k) as? JsonPrimitive)?.content
        val fits = (a?.get("patterns") as? JsonArray)?.mapNotNull { CHECK_PATTERNS[(it as? JsonPrimitive)?.content]?.label }.orEmpty()
        val level = str("level")
        MeRow(
            c.signals.joinToString { vocab.symptom(it.symptom)?.label ?: it.symptom },
            value = str("intensity"),
            tone = if (level == "urgent" || level == "clinician") Tone.WARN else null,
            sub = (listOf(shortDate(c.date)) + listOfNotNull(
                fits.firstOrNull()?.let { "fits: $it" },
                when (level) { "urgent" -> "red flag"; "clinician" -> "worth a clinician's view"; else -> null },
                "tracked".takeIf { c.signals.any { it.concernId != null } },
            )).joinToString(" · "),
        )
    }, info = "What you logged through SYMPTOM CHECK, kept as answered: no episode, no effect on CHARGE.")

    val patterns = findings.filter { (it.status == "possible" || it.status == "persistent") && concerns.any { c -> c.id == it.concernId } }
    if (patterns.isNotEmpty()) sections += MeSection("PATTERNS SO FAR", patterns.map { f -> MeRow(f.what, state = findingStatusWord(f.status, f.weakening).word) },
        info = "Associations in your own history, never proof of cause.")

    val past = closed + archived
    if (past.isNotEmpty()) sections += MeSection("RESOLVED AND PAST (${past.size})", past.map { concernRow(it, byId[it.id], vocab) })
    return MeViewDoc("HEALTH", sections)
}
