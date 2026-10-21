package com.bioscan.fieldterminal.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.HealthConcernsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.ConcernOverviewDto
import com.bioscan.fieldterminal.data.model.HealthConcernRow
import com.bioscan.fieldterminal.data.model.RecentSessionRow
import com.bioscan.fieldterminal.data.model.RegionRef
import com.bioscan.fieldterminal.domain.health.CheckInDraft
import com.bioscan.fieldterminal.domain.health.ConcernAnswer
import com.bioscan.fieldterminal.domain.health.ConcernChange
import com.bioscan.fieldterminal.domain.health.HealthVocab
import com.bioscan.fieldterminal.domain.health.IMPACT_LABELS
import com.bioscan.fieldterminal.domain.health.NewConcernSpec
import com.bioscan.fieldterminal.domain.health.NewProblem
import com.bioscan.fieldterminal.domain.health.NewSignal
import com.bioscan.fieldterminal.domain.health.RegionShape
import com.bioscan.fieldterminal.domain.health.buildCheckIn
import com.bioscan.fieldterminal.domain.health.checkInDate
import com.bioscan.fieldterminal.domain.health.isConcernOpen
import com.bioscan.fieldterminal.domain.health.recallStart
import com.bioscan.fieldterminal.domain.health.shortDate
import com.bioscan.fieldterminal.ui.components.BodyMap
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Health concerns (plan §5.1): the quick check-in. A normal day is one tap (NOTHING); a day with an active concern is a
// tap per concern (GONE / BETTER / SAME / WORSE, severity prefilled with the last one); something new goes through the
// body map with at most two follow-up questions. "That's everything" makes every other concern a symptom-free day
// instead of a missing one; NOTHING SINCE covers days without a check-in (up to a week). The rows it writes are built by
// domain/health/CheckIn.kt. Opened from the 21:00 reminder, Status quick log, Log's +, and Health › CONCERNS.

enum class CheckInStart { Overview, NewBody, NewGeneral }

private val CHANGE_LABELS = listOf("GONE", "BETTER", "SAME", "WORSE")
private val SEVERITY_OPTIONS = (0..10).map { it.toString() }
private val TIMING = listOf("during_session" to "DURING", "after_session" to "AFTER", "next_morning" to "NEXT MORNING", "at_rest" to "AT REST", "night" to "NIGHT")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckInSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    start: CheckInStart = CheckInStart.Overview,
    /** Open straight on one concern (from its page). */
    focusConcernId: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val repo = remember { HealthConcernsRepository(SupabaseClientProvider.client) }
    val vocab = remember { HealthConcernsRepository.vocab(context) }
    val scope = rememberCoroutineScope()
    val today = remember { checkInDate(ZonedDateTime.now()) }

    var concerns by remember { mutableStateOf<List<HealthConcernRow>>(emptyList()) }
    var overview by remember { mutableStateOf<Map<String, ConcernOverviewDto>>(emptyMap()) }
    var lastCheckin by remember { mutableStateOf<LocalDate?>(null) }
    var sessions by remember { mutableStateOf<List<RecentSessionRow>>(emptyList()) }
    val answers = remember { mutableStateMapOf<String, ConcernAnswer>() }
    val problems = remember { mutableStateListOf<NewProblem>() }
    var nothingElse by remember { mutableStateOf(true) }
    var recallFrom by remember { mutableStateOf<LocalDate?>(null) }
    var step by remember { mutableStateOf(if (start == CheckInStart.Overview) null else start) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { concerns = repo.loadConcerns() }
        runCatching { overview = repo.loadOverview()?.concerns?.associateBy { it.concernId } ?: emptyMap() }
        runCatching { lastCheckin = repo.lastCheckinDate() }
        runCatching { sessions = repo.loadRecentSessions() }
    }

    // Concerns asked about: open ones, the focused one, and new ones without a published state yet.
    val active = concerns.filter { c ->
        c.archivedAt == null && (c.id == focusConcernId || overview[c.id]?.let { isConcernOpen(it.state) || (it.state == "unobserved" && it.currentEpisode != null) }
            ?: (c.legacySource == null && c.createdAt?.take(10)?.let { LocalDate.parse(it) >= today.minusDays(14) } == true))
    }

    fun save(draft: CheckInDraft) {
        saving = true
        error = null
        scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }, onDone = { saving = false }) {
            repo.save(buildCheckIn(draft, ZonedDateTime.now()))
            repo.refresh()
            onSaved()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = step
            if (current != null) {
                NewProblemStep(
                    vocab = vocab,
                    general = current == CheckInStart.NewGeneral,
                    concerns = concerns.filter { it.archivedAt == null },
                    sessions = sessions,
                    onCancel = { step = null },
                    onAdd = { problems += it; step = null },
                )
                return@Column
            }

            Text("HEALTH CHECK-IN", style = FTType.SectionTitle, color = FT.DomainHeart)
            Text(today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), style = FTType.RowTitle, color = FT.TextPrimary)
            Text(
                if (active.isEmpty()) "Anything bothering you today? A symptom-free day counts too: comparisons need good days as well as bad ones."
                else "One tap per concern. THAT'S EVERYTHING marks anything else as symptom-free today.",
                style = FTType.BodySmall, color = FT.TextSecondary,
            )

            active.forEach { c ->
                val o = overview[c.id]
                val a = answers[c.id]
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.title.uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary)
                    o?.lastSeverity?.let { Text("Last: $it/10 on ${shortDate(o.lastObserved)}", style = FTType.Caption, color = FT.TextMuted) }
                    TextChipRow(CHANGE_LABELS, a?.change?.name?.uppercase(), perRow = 4) { picked ->
                        if (picked == null) answers.remove(c.id) else {
                            val change = ConcernChange.entries.first { it.name.uppercase() == picked }
                            answers[c.id] = ConcernAnswer(
                                concernId = c.id, change = change,
                                severity = if (change == ConcernChange.Gone) null else a?.severity ?: o?.lastSeverity ?: 3,
                                impact = a?.impact, symptom = c.symptoms.firstOrNull() ?: "sym.pain",
                            )
                        }
                    }
                    if (a != null && a.change != ConcernChange.Gone) {
                        Text("SEVERITY 0-10", style = FTType.Micro, color = FT.TextMuted)
                        TextChipRow(SEVERITY_OPTIONS, a.severity?.toString(), perRow = 6) { picked -> answers[c.id] = a.copy(severity = picked?.toInt()) }
                        Text("TRAINING", style = FTType.Micro, color = FT.TextMuted)
                        TextChipRow(IMPACT_LABELS, a.impact?.let { IMPACT_LABELS[it] }, perRow = 2) { picked -> answers[c.id] = a.copy(impact = picked?.let { IMPACT_LABELS.indexOf(it) }) }
                    }
                }
            }

            problems.forEachIndexed { i, p ->
                val label = p.newConcern?.title ?: concerns.firstOrNull { it.id == p.concernId }?.title ?: "New"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("+ ${label.uppercase()} · ${p.signals.joinToString { "${vocab.symptom(it.symptom)?.label ?: it.symptom} ${it.severity}/10" }}", style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                    FTButton(label = "REMOVE", emphasis = FTButtonEmphasis.Tertiary) { problems.removeAt(i) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton(label = "+ BODY", modifier = Modifier.weight(1f)) { step = CheckInStart.NewBody }
                FTButton(label = "+ GENERAL", modifier = Modifier.weight(1f)) { step = CheckInStart.NewGeneral }
            }

            val gapFrom = recallStart(lastCheckin, today)
            if (gapFrom != null) {
                TextChipRow(listOf("NOTHING SINCE ${shortDate(gapFrom.toString()).uppercase()}"), if (recallFrom != null) "NOTHING SINCE ${shortDate(gapFrom.toString()).uppercase()}" else null, perRow = 1) { picked ->
                    recallFrom = if (picked != null) gapFrom else null
                }
            }
            if (active.isNotEmpty() || problems.isNotEmpty()) {
                TextChipRow(listOf("THAT'S EVERYTHING"), if (nothingElse) "THAT'S EVERYTHING" else null, perRow = 1) { picked -> nothingElse = picked != null }
            }

            error?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Critical) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (answers.isEmpty() && problems.isEmpty()) {
                    FTButton(label = if (saving) "SAVING..." else "NOTHING", emphasis = FTButtonEmphasis.Primary, enabled = !saving, modifier = Modifier.weight(1f)) {
                        save(CheckInDraft(nothingElse = true, recallFrom = recallFrom))
                    }
                } else {
                    FTButton(label = if (saving) "SAVING..." else "SAVE", emphasis = FTButtonEmphasis.Primary, enabled = !saving, modifier = Modifier.weight(1f)) {
                        save(CheckInDraft(answers = answers.values.toList(), problems = problems.toList(), nothingElse = nothingElse, recallFrom = recallFrom))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewProblemStep(
    vocab: HealthVocab,
    general: Boolean,
    concerns: List<HealthConcernRow>,
    sessions: List<RecentSessionRow>,
    onCancel: () -> Unit,
    onAdd: (NewProblem) -> Unit,
) {
    var isGeneral by remember { mutableStateOf(general) }
    var region by remember { mutableStateOf<RegionShape?>(null) }
    var symptom by remember { mutableStateOf<String?>(null) }
    var severity by remember { mutableStateOf<Int?>(null) }
    var impact by remember { mutableStateOf<Int?>(null) }
    val answers = remember { mutableStateMapOf<String, String>() }
    var temperature by remember { mutableStateOf("") }
    var onset by remember { mutableStateOf(false) }
    var mechanism by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf<Long?>(null) }
    var timing by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var titleEdited by remember { mutableStateOf(false) }
    var attachTo by remember { mutableStateOf<String?>(null) }

    val r = region
    // An existing concern for the same region and side (or, for general symptoms, one with that symptom) is offered first.
    val matches = concerns.filter { c ->
        if (isGeneral) c.kind != "anatomical" && symptom != null && symptom in c.symptoms
        else r != null && c.regions.any { it.code == r.code && (it.side == null || it.side == r.side) }
    }
    LaunchedEffect(r?.code, r?.side, symptom, isGeneral) {
        attachTo = matches.firstOrNull()?.id
        if (!titleEdited) title = vocab.defaultTitle(if (isGeneral) null else r?.code, r?.side, symptom)
    }

    Text("SOMETHING NEW", style = FTType.SectionTitle, color = FT.DomainHeart)
    SegmentedToggle(options = listOf(false, true), selected = isGeneral, labelOf = { if (it) "GENERAL" else "BODY" }, onSelect = { isGeneral = it; symptom = null }, accentColor = FT.DomainHeart)

    if (!isGeneral) {
        BodyMap(selected = r, onTap = { region = it })
        Text(r?.let { vocab.regionLabel(it.code, it.side) } ?: "Tap where it bothers you.", style = FTType.RowTitle, color = if (r != null) FT.TextPrimary else FT.TextSecondary)
    }
    val symptomOptions = if (isGeneral) vocab.systemicSymptoms else vocab.localSymptoms
    Text("WHAT IS IT", style = FTType.Micro, color = FT.TextMuted)
    TextChipRow(symptomOptions.map { it.label }, symptomOptions.firstOrNull { it.code == symptom }?.label, perRow = 3) { picked ->
        symptom = symptomOptions.firstOrNull { it.label == picked }?.code
    }
    Text("SEVERITY 0-10", style = FTType.Micro, color = FT.TextMuted)
    TextChipRow(SEVERITY_OPTIONS, severity?.toString(), perRow = 6) { severity = it?.toInt() }
    Text("TRAINING", style = FTType.Micro, color = FT.TextMuted)
    TextChipRow(IMPACT_LABELS, impact?.let { IMPACT_LABELS[it] }, perRow = 2) { picked -> impact = picked?.let { IMPACT_LABELS.indexOf(it) } }

    // Conditional follow-ups: at most two, chosen by the region and the symptom.
    vocab.followupsFor(if (isGeneral) null else r?.code, symptom).forEach { q ->
        Text(q.text.uppercase(), style = FTType.Micro, color = FT.TextMuted)
        when (q.type) {
            "yes_no" -> TextChipRow(listOf("NO", "YES"), answers[q.id]?.let { if (it == "true") "YES" else "NO" }, perRow = 2) { picked ->
                if (picked == null) answers.remove(q.id) else answers[q.id] = (picked == "YES").toString()
            }
            "choice" -> TextChipRow(q.options.map { it.uppercase() }, answers[q.id]?.uppercase(), perRow = q.options.size.coerceAtMost(3)) { picked ->
                if (picked == null) answers.remove(q.id) else answers[q.id] = picked.lowercase()
            }
            "number" -> FieldTextField(temperature, { temperature = it }, "e.g. 38.2", keyboardType = KeyboardType.Decimal)
        }
    }

    if (!isGeneral) {
        Text("STARTED WITH A SPECIFIC EVENT (A KNOCK, A TWIST)?", style = FTType.Micro, color = FT.TextMuted)
        TextChipRow(listOf("NO", "YES"), if (onset) "YES" else "NO", perRow = 2) { onset = it == "YES" }
        if (onset) FieldTextField(mechanism, { mechanism = it }, "What happened (optional)")
        if (sessions.isNotEmpty()) {
            Text("DURING OR AFTER A SESSION?", style = FTType.Micro, color = FT.TextMuted)
            val labels = sessions.map { "${it.type.uppercase()} ${shortDate(it.startTime)}" }
            TextChipRow(labels, sessionId?.let { id -> sessions.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { labels[it] } }, perRow = 2) { picked ->
                sessionId = picked?.let { sessions[labels.indexOf(it)].id }
            }
            if (sessionId != null) TextChipRow(TIMING.map { it.second }, TIMING.firstOrNull { it.first == timing }?.second, perRow = 3) { picked ->
                timing = TIMING.firstOrNull { it.second == picked }?.first
            }
        }
    }

    if (matches.isNotEmpty()) {
        Text("ADD TO", style = FTType.Micro, color = FT.TextMuted)
        val options = matches.map { it.title } + "NEW CONCERN"
        TextChipRow(options, matches.firstOrNull { it.id == attachTo }?.title ?: "NEW CONCERN", perRow = 1) { picked ->
            attachTo = matches.firstOrNull { it.title == picked }?.id
        }
    }
    if (attachTo == null) {
        Text("NAME IT (YOUR WORDS, NO DIAGNOSIS NEEDED)", style = FTType.Micro, color = FT.TextMuted)
        FieldTextField(title, { title = it; titleEdited = true }, "e.g. Right knee, outside")
    }

    val sym = symptom
    val sev = severity
    val ready = sym != null && sev != null && (isGeneral || r != null)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FTButton(label = "BACK", modifier = Modifier.weight(1f)) { onCancel() }
        FTButton(label = "ADD", emphasis = FTButtonEmphasis.Primary, enabled = ready, modifier = Modifier.weight(1f)) {
            if (sym == null || sev == null) return@FTButton
            val signal = NewSignal(
                symptom = sym, region = if (isGeneral) null else r?.code, side = if (isGeneral) null else r?.side, severity = sev,
                details = answers.toMap(), temperatureC = temperature.replace(',', '.').toDoubleOrNull()?.takeIf { it in 34.0..43.0 },
            )
            onAdd(
                NewProblem(
                    concernId = attachTo,
                    newConcern = if (attachTo != null) null else NewConcernSpec(
                        title = title.ifBlank { vocab.defaultTitle(r?.code, r?.side, sym) },
                        kind = if (isGeneral) "systemic" else "anatomical",
                        onsetType = if (onset) "acute" else "unknown",
                        regions = if (isGeneral || r == null) emptyList() else listOf(RegionRef(r.code, r.side)),
                        symptoms = listOf(sym),
                    ),
                    signals = listOf(signal), impact = impact, onset = onset, mechanism = mechanism.takeIf { onset },
                    sessionId = sessionId, timing = timing,
                ),
            )
        }
    }
}
