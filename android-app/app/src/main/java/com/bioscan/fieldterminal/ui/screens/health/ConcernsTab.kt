package com.bioscan.fieldterminal.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.HealthConcernsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SymptomCheckRow
import com.bioscan.fieldterminal.data.model.ConcernOverviewDto
import com.bioscan.fieldterminal.data.model.FindingDto
import com.bioscan.fieldterminal.data.model.HealthConcernRow
import com.bioscan.fieldterminal.data.model.HealthOverviewDto
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.health.CHECK_PATTERNS
import com.bioscan.fieldterminal.domain.health.HealthVocab
import com.bioscan.fieldterminal.domain.health.concernStateOrder
import com.bioscan.fieldterminal.domain.health.concernStateWord
import com.bioscan.fieldterminal.domain.health.findingStatusWord
import com.bioscan.fieldterminal.domain.health.illnessType
import com.bioscan.fieldterminal.domain.health.isConcernOpen
import com.bioscan.fieldterminal.domain.health.shortDate
import com.bioscan.fieldterminal.ui.components.BodyMap
import com.bioscan.fieldterminal.ui.components.BodyMark
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.LoadErrorLine
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.metricStateColor
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Health concerns (plan §5): Health › CONCERNS, and the same list on Training › INJURY filtered to concerns of the body.
// Answer first: the figure with every open concern on it, then any safety prompt, then the concerns by state, then the
// patterns found so far (possible and persistent only; everything else stays on each concern's page).

private const val CONCERNS_INFO = "A concern is anything that keeps bothering you, in your own words: a knee, an Achilles, a cough that keeps coming back. " +
    "Each check-in is kept as you entered it; episodes, states and patterns are worked out from them every night and can change as check-ins add up. " +
    "This is personal tracking, not a diagnosis."

fun concernColor(state: String): Color = when (state) {
    "worsening" -> FT.Warn
    "improving" -> FT.Ok
    "unobserved", "resolved" -> FT.TextMuted
    else -> FT.DomainHeart
}

@Composable
fun ConcernsTab(onOpenConcern: (String) -> Unit, anatomicalOnly: Boolean = false) {
    val context = LocalContext.current
    val repo = remember { HealthConcernsRepository(SupabaseClientProvider.client) }
    val vocab = remember { HealthConcernsRepository.vocab(context) }
    val scope = rememberCoroutineScope()
    var concerns by remember { mutableStateOf<List<HealthConcernRow>?>(null) }
    var overview by remember { mutableStateOf<HealthOverviewDto?>(null) }
    var findings by remember { mutableStateOf<List<FindingDto>>(emptyList()) }
    var checkIn by remember { mutableStateOf(false) }
    var newInjury by remember { mutableStateOf(false) }
    var newIllness by remember { mutableStateOf<Pair<Set<String>, List<Long>>?>(null) }
    var symptomCheck by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf<List<SymptomCheckRow>>(emptyList()) }
    var showResolved by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val guard = rememberLoadGuard()
    val checksGuard = rememberLoadGuard(refreshOnUpdates = false)

    LaunchedEffect(guard.attempt, checksGuard.attempt) {
        guard.run {
            parallel(
                { concerns = repo.loadConcerns() },
                { overview = repo.loadOverview() },
                { findings = repo.loadAllFindings() },
                // The small things are their own card: failing to read them must not take the concerns down with it.
                { checksGuard.run { checks = repo.loadChecks() } },
            )
        }
    }

    val list = concerns
    if (list == null) {
        if (guard.error != null) LoadErrorCard(guard) else Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.DomainHeart)
        }
        return
    }
    val byId = overview?.concerns?.associateBy { it.concernId } ?: emptyMap()
    val shown = list.filter { !anatomicalOnly || it.kind != "systemic" }
    val (archived, current) = shown.partition { it.archivedAt != null }
    val sorted = current.sortedWith(compareBy({ concernStateOrder(byId[it.id]?.state ?: "unobserved") }, { it.title }))
    val (open, closed) = sorted.partition { byId[it.id]?.state?.let(::isConcernOpen) ?: (it.legacySource == null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        FTCard(title = if (anatomicalOnly) "INJURIES & PAIN" else "CONCERNS", info = CONCERNS_INFO, emphasis = FTCardEmphasis.Hero) {
            val marks = open.flatMap { c ->
                val color = concernColor(byId[c.id]?.state ?: "active")
                c.regions.map { BodyMark(it.code, it.side, color) }
            }
            BodyMap(marks = marks, height = 220.dp)
            Text(
                when {
                    open.isEmpty() -> "Nothing open."
                    else -> "${open.size} open: " + open.joinToString { it.title }
                },
                style = FTType.BodySmall, color = FT.TextSecondary,
            )
            overview?.lastCheckin?.let { Text("Last check-in ${shortDate(it)}", style = FTType.Caption, color = FT.TextMuted) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton(label = "CHECK IN", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) { checkIn = true }
                FTButton(label = if (refreshing) "UPDATING..." else "UPDATE", enabled = !refreshing, modifier = Modifier.weight(1f)) {
                    refreshing = true
                    scope.launchSafe(onDone = { refreshing = false }) { repo.refresh(); guard.retry() }
                }
            }
            // Three ways in: an injury through the body map, an illness or infection as what it is, and the symptom check
            // for the small things that are neither.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton(label = "+ INJURY", modifier = Modifier.weight(1f)) { newInjury = true }
                if (!anatomicalOnly) FTButton(label = "+ ILLNESS", modifier = Modifier.weight(1f)) { newIllness = emptySet<String>() to emptyList() }
            }
            if (!anatomicalOnly) FTButton(label = "SYMPTOM CHECK · SMALL THINGS", modifier = Modifier.fillMaxWidth()) { symptomCheck = true }
        }

        // Safety prompts are never findings: plain advice to see a clinician, shown above everything else.
        val prompts = (overview?.concerns ?: emptyList()).filter { !it.archived }.flatMap { o -> o.safety.map { o.title to it.text } }
        if (prompts.isNotEmpty()) {
            FTCard(title = "WORTH A CLINICIAN'S VIEW", info = "Shown whenever a check-in meets a fixed rule (very high severity, fever of 39 °C or more, numbness, chest symptoms, pain at night, an illness over ten days). Not a diagnosis.") {
                prompts.forEach { (title, text) -> Text("$title — $text", style = FTType.BodySmall, color = FT.Warn) }
            }
        }

        if (overview == null && list.isNotEmpty()) {
            Text("States and patterns appear after the next analysis run (nightly, or UPDATE above).", style = FTType.BodySmall, color = FT.TextSecondary)
        }

        val unassigned = overview?.unassigned ?: emptyList()
        if (unassigned.isNotEmpty() && !anatomicalOnly) {
            FTCard(title = "NEW, NOT YET A CONCERN", info = "Symptoms logged in a check-in that no concern claims. Log them through + BODY or + GENERAL to start a concern.") {
                unassigned.takeLast(5).forEach { u ->
                    Text("${shortDate(u.date)} · ${vocab.symptom(u.signal.symptom)?.label ?: u.signal.symptom}${u.signal.region?.let { " · " + vocab.regionLabel(it, u.signal.side) } ?: ""}${u.signal.severity?.let { " · $it/10" } ?: ""}", style = FTType.BodySmall, color = FT.TextPrimary)
                }
            }
        }

        if (open.isEmpty() && closed.isEmpty()) {
            Text("No concerns yet. CHECK IN when something bothers you, or tap NOTHING on good days: both count.", style = FTType.BodySmall, color = FT.TextSecondary)
        }
        // Injuries and illnesses apart: they are followed and scored differently.
        val (illnessesOpen, injuriesOpen) = open.partition { it.categoryOrKind != "injury" }
        if (injuriesOpen.isNotEmpty()) Text("INJURIES & PAIN", style = FTType.LabelCaps, color = FT.TextMuted)
        injuriesOpen.forEach { c -> ConcernRow(c, byId[c.id], vocab) { onOpenConcern(c.id) } }
        if (illnessesOpen.isNotEmpty()) Text("ILLNESSES & INFECTIONS", style = FTType.LabelCaps, color = FT.TextMuted)
        illnessesOpen.forEach { c -> ConcernRow(c, byId[c.id], vocab) { onOpenConcern(c.id) } }

        if (!anatomicalOnly) LoadErrorLine(checksGuard, "the recent symptom checks")
        if (!anatomicalOnly && checks.isNotEmpty()) SmallThingsCard(checks, vocab)

        val patterns = findings.filter { (it.status == "possible" || it.status == "persistent") && shown.any { c -> c.id == it.concernId } }
        if (patterns.isNotEmpty()) {
            FTCard(title = "PATTERNS SO FAR", info = "Associations in your own history, never proof of cause. POSSIBLE is exploratory; PERSISTENT has held on new flare-ups since it was first shown.") {
                patterns.forEach { f ->
                    Column(Modifier.fillMaxWidth().ftClickable { onOpenConcern(f.concernId) }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FTStatePill(findingStatusWord(f.status, f.weakening))
                        Text(f.what, style = FTType.BodySmall, color = FT.TextPrimary)
                    }
                }
            }
        }

        if (closed.isNotEmpty() || archived.isNotEmpty()) {
            FTButton(label = if (showResolved) "HIDE RESOLVED" else "RESOLVED AND PAST (${closed.size + archived.size})", emphasis = FTButtonEmphasis.Tertiary) { showResolved = !showResolved }
            if (showResolved) (closed + archived).forEach { c -> ConcernRow(c, byId[c.id], vocab) { onOpenConcern(c.id) } }
        }
    }

    if (checkIn) {
        HealthCheckInSheet(onDismiss = { checkIn = false }, onSaved = { checkIn = false; guard.retry() })
    }
    if (newInjury) {
        HealthCheckInSheet(onDismiss = { newInjury = false }, onSaved = { newInjury = false; guard.retry() }, start = CheckInStart.NewBody)
    }
    newIllness?.let { (symptoms, signals) ->
        IllnessSheet(onDismiss = { newIllness = null }, onSaved = { newIllness = null; guard.retry() }, prefillSymptoms = symptoms, adoptSignalIds = signals)
    }
    if (symptomCheck) {
        SymptomCheckSheet(
            onDismiss = { symptomCheck = false; guard.retry() },
            onSaved = { symptomCheck = false; guard.retry() },
            onTrackIllness = { symptoms, signals -> symptomCheck = false; newIllness = symptoms to signals },
            onTrackInjury = { symptomCheck = false; newInjury = true },
        )
    }
}

@Composable
private fun ConcernRow(c: HealthConcernRow, o: ConcernOverviewDto?, vocab: HealthVocab, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        Modifier.fillMaxWidth().ftClickable(shape = shape, onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.title.uppercase(), style = FTType.CardTitle, color = FT.TextPrimary, modifier = Modifier.weight(1f))
            FTStatePill(concernStateWord(o?.state ?: "unobserved"))
        }
        val where = c.regions.joinToString { vocab.regionLabel(it.code, it.side) }.ifBlank {
            if (c.kind == "systemic") listOfNotNull(c.categoryOrKind.uppercase(), illnessType(c.illnessType)?.label?.uppercase()).joinToString(" · ") else ""
        }
        val parts = listOfNotNull(
            where.takeIf { it.isNotBlank() },
            o?.lastSeverity?.let { "last $it/10" },
            o?.episodes?.takeIf { it > 0 }?.let { "$it episode${if (it == 1) "" else "s"}" },
            o?.findings?.takeIf { it > 0 }?.let { "$it pattern${if (it == 1) "" else "s"}" },
            if (c.onsetType == "acute") "single event" else null,
        )
        Text(parts.joinToString(" · "), style = FTType.Caption, color = FT.TextSecondary)
        o?.stateReason?.takeIf { it.isNotBlank() }?.let { Text(it, style = FTType.Caption, color = FT.TextMuted) }
        if (o != null && o.safety.isNotEmpty()) Text("See the note above.", style = FTType.Caption, color = metricStateColor(MetricState.Warning))
    }
}

// The symptom checks of the last two weeks: small things, kept as answered, never an episode or a ceiling on the score.
@Composable
private fun SmallThingsCard(checks: List<SymptomCheckRow>, vocab: HealthVocab) {
    FTCard(
        title = "SMALL THINGS · 14 DAYS",
        info = "What you logged through SYMPTOM CHECK. They are kept as answered and count for nothing else: no episode, no " +
            "effect on CHARGE. One that keeps coming back is worth tracking as an illness or an injury.",
    ) {
        checks.forEach { c ->
            val a = c.assessment
            fun str(k: String) = (a?.get(k) as? JsonPrimitive)?.content
            val what = c.signals.joinToString { vocab.symptom(it.symptom)?.label ?: it.symptom }
            val fits = (a?.get("patterns") as? JsonArray)?.mapNotNull {
                CHECK_PATTERNS[(it as? JsonPrimitive)?.content]?.label
            }.orEmpty()
            val level = str("level")
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${shortDate(c.date).uppercase()} · ${what.uppercase()}" + (str("intensity")?.let { " · ${it.uppercase()}" } ?: ""), style = FTType.LabelCaps, color = FT.TextPrimary)
                Text(
                    listOfNotNull(
                        fits.firstOrNull()?.let { "Fits: $it" },
                        when (level) { "urgent" -> "red flag"; "clinician" -> "worth a clinician's view"; else -> null },
                        "tracked".takeIf { c.signals.any { it.concernId != null } },
                    ).joinToString(" · ").ifBlank { "Nothing pointed one way" },
                    style = FTType.Caption, color = if (level == "urgent" || level == "clinician") FT.Warn else FT.TextSecondary,
                )
            }
        }
    }
}
