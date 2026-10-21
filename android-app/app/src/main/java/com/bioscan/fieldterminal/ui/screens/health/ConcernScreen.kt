package com.bioscan.fieldterminal.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.HealthConcernsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.ConcernStateDto
import com.bioscan.fieldterminal.data.model.FindingDto
import com.bioscan.fieldterminal.data.model.HealthConcernRow
import com.bioscan.fieldterminal.data.model.HealthObservationRow
import com.bioscan.fieldterminal.data.model.TrialReadDto
import com.bioscan.fieldterminal.domain.health.IMPACT_LABELS
import com.bioscan.fieldterminal.domain.health.concernStateWord
import com.bioscan.fieldterminal.domain.health.durationText
import com.bioscan.fieldterminal.domain.health.episodeLine
import com.bioscan.fieldterminal.domain.health.findingRank
import com.bioscan.fieldterminal.domain.health.findingStatusWord
import com.bioscan.fieldterminal.domain.health.qualityLine
import com.bioscan.fieldterminal.domain.health.severityPoints
import com.bioscan.fieldterminal.domain.health.shortDate
import com.bioscan.fieldterminal.ui.components.BodyMap
import com.bioscan.fieldterminal.ui.components.BodyMark
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.components.softLoad
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// Health concerns (plan §5.3-5.6): one concern's page. OVERVIEW leads with the state and why, then the timeline,
// episodes, recurrence, recovery and training impact; CONTEXT puts training and recovery on the same days and, per
// episode, what came before it; PATTERNS holds the findings (patterns first, then what was checked and why nothing is
// shown); TRIALS the deliberate changes. Everything shown is published by analysis-run; the page only reads.

private enum class ConcernTab(val label: String) { Overview("OVERVIEW"), Context("CONTEXT"), Patterns("PATTERNS"), Trials("TRIALS") }
private enum class Range(val label: String, val days: Int) { M3("3M", 92), M6("6M", 183), Y1("1Y", 365) }

@Composable
fun ConcernScreen(concernId: String, onBack: () -> Unit, onOpenSession: (Long) -> Unit) {
    val repo = remember { HealthConcernsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(ConcernTab.Overview) }
    var concern by remember { mutableStateOf<HealthConcernRow?>(null) }
    var state by remember { mutableStateOf<ConcernStateDto?>(null) }
    var findings by remember { mutableStateOf<List<FindingDto>>(emptyList()) }
    var trials by remember { mutableStateOf<List<TrialReadDto>>(emptyList()) }
    var lastEntry by remember { mutableStateOf<HealthObservationRow?>(null) }
    var checkIn by remember { mutableStateOf(false) }
    var trialSheet by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val guard = rememberLoadGuard()

    LaunchedEffect(guard.attempt) {
        guard.run {
            parallel(
                { concern = repo.loadConcerns().firstOrNull { it.id == concernId } },
                { state = repo.loadConcernState(concernId) },
                { findings = repo.loadFindings(concernId) },
                { trials = repo.loadTrialReads().filter { it.concernId == concernId } },
                { lastEntry = softLoad { repo.loadLatestObservations(20).firstOrNull { it.concernId == concernId } } },
            )
        }
    }

    fun act(done: String, block: suspend () -> Unit) {
        scope.launchSafe(onError = { message = "Couldn't save: ${it.message}" }) { block(); repo.refresh(); message = done; guard.retry() }
    }

    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    PinnedPage(
        onBack = onBack,
        title = (concern?.title ?: state?.title ?: "CONCERN").uppercase(),
        subtitle = "STATE · EPISODES · CONTEXT · PATTERNS",
        accent = FT.DomainHeart,
        rail = { SubTabRow(items = ConcernTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it }) },
        scrollState = scroll,
    ) {
        val c = concern
        if (c == null) {
            if (guard.error != null) LoadErrorCard(guard) else Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FT.DomainHeart) }
            return@PinnedPage
        }
        val s = state
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            message?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            if (s == null) {
                Text("This concern's state appears after the next analysis run (nightly, or after a check-in).", style = FTType.BodySmall, color = FT.TextSecondary)
            }
            when (tab) {
                ConcernTab.Overview -> {
                    FTCard(title = "NOW", emphasis = FTCardEmphasis.Hero, methodology = "health_concern_state", helpExtra = s?.let(::qualityLine)) {
                        FTStatePill(concernStateWord(s?.state ?: "unobserved"))
                        s?.stateReason?.let { Text(it, style = FTType.Body, color = FT.TextPrimary, modifier = Modifier.padding(top = 6.dp)) }
                        s?.lastObserved?.let { Text("Last check-in ${shortDate(it)}", style = FTType.Caption, color = FT.TextMuted) }
                        if (c.regions.isNotEmpty()) BodyMap(marks = c.regions.map { BodyMark(it.code, it.side, concernColor(s?.state ?: "active")) }, height = 160.dp)
                        s?.safety?.forEach { Text(it.text, style = FTType.BodySmall, color = FT.Warning, modifier = Modifier.padding(top = 4.dp)) }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FTButton(label = "CHECK IN", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) { checkIn = true }
                        }
                    }
                    if (s != null) {
                        var range by remember { mutableStateOf(Range.M6) }
                        FTCard(title = "TIMELINE") {
                            SegmentedToggle(options = Range.entries, selected = range, labelOf = { it.label }, onSelect = { range = it }, accentColor = FT.DomainHeart)
                            val pts = severityPoints(s, range.days)
                            val sessionDays = s.lanes.dates.withIndex().filter { (i, _) -> (s.lanes.runKm.getOrNull(i) ?: 0.0) > 0 || (s.lanes.strengthSessions.getOrNull(i) ?: 0) > 0 }.map { LocalDate.parse(it.value) }.toSet()
                            SeverityTimeline(pts, s.episodes, sessionDays)
                        }
                        FTCard(title = "EPISODES", info = "An episode starts with a symptomatic day and ends after ${s.resolutionDays} symptom-free days (at least three of them checked in). Days without a check-in make the edges uncertain, so durations are ranges. Correct the edges with MARK below.") {
                            if (s.episodes.isEmpty()) Text("No episode yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                            s.episodes.reversed().forEach { e ->
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Text(episodeLine(e), style = FTType.BodySmall, color = FT.TextPrimary)
                                    val extra = listOfNotNull(
                                        "checked in ${e.observedDays} of ${e.spanDays} days",
                                        e.impactDays.takeIf { it > 0 }?.let { "training affected $it days" },
                                        e.daysPeakToEnd?.let { "$it days from peak to clear" },
                                        if (e.gap || e.boundaryUncertain) "edges uncertain" else null,
                                    )
                                    Text(extra.joinToString(" · "), style = FTType.Caption, color = FT.TextMuted)
                                }
                            }
                        }
                        FTCard(title = "RECURRENCE") {
                            val r = s.recurrence
                            FTMetricRow("Episodes", "${r.episodes}")
                            FTMetricRow("In the last 90 / 365 days", "${r.episodesLast90} / ${r.episodesLast365}")
                            r.medianInterval?.let { FTMetricRow("Typical symptom-free gap", "${Math.round(it)} d") }
                            if (r.intervals.isNotEmpty()) FTMetricRow("Gaps", r.intervals.joinToString { durationText(it) })
                            FTMetricRow("Came back within 30 / 90 days", "${r.within30} / ${r.within90}")
                        }
                        FTCard(title = "RECOVERY") { RecoveryOverlay(s) }
                        FTCard(title = "TRAINING IMPACT", info = "From the TRAINING answer in each check-in: ${IMPACT_LABELS.joinToString(" · ")}.") {
                            val impacts = s.daily.impact.filterNotNull()
                            if (impacts.isEmpty()) Text("No training answers yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                            IMPACT_LABELS.forEachIndexed { level, label ->
                                val n = impacts.count { it == level }
                                if (n > 0) FTMetricRow(label, "$n day${if (n == 1) "" else "s"}")
                            }
                        }
                    }
                    FTCard(title = "MANAGE") {
                        val ren = renaming
                        if (ren != null) {
                            FieldTextField(ren, { renaming = it }, "Name")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FTButton(label = "CANCEL", modifier = Modifier.weight(1f)) { renaming = null }
                                FTButton(label = "SAVE NAME", emphasis = FTButtonEmphasis.Primary, enabled = ren.isNotBlank(), modifier = Modifier.weight(1f)) { renaming = null; act("Renamed.") { repo.rename(c.id, ren) } }
                            }
                        } else FTButton(label = "RENAME") { renaming = c.title }
                        Text("KIND", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            if (c.onsetType == "acute") "A single event (a knock, a twist): tracked and timed, but not searched for training patterns."
                            else "Keeps coming back or built up gradually: searched for patterns.",
                            style = FTType.Caption, color = FT.TextSecondary,
                        )
                        FTButton(label = if (c.onsetType == "acute") "IT KEEPS COMING BACK" else "IT WAS A SINGLE EVENT", emphasis = FTButtonEmphasis.Tertiary) {
                            act("Updated.") { repo.setOnsetType(c.id, if (c.onsetType == "acute") "gradual" else "acute") }
                        }
                        Text("MARK AN EPISODE EDGE (TODAY)", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(top = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FTButton(label = "STARTED TODAY", modifier = Modifier.weight(1f)) { act("Marked.") { repo.addMark(c.id, "start", LocalDate.now()) } }
                            FTButton(label = "OVER TODAY", modifier = Modifier.weight(1f)) { act("Marked.") { repo.addMark(c.id, "end", LocalDate.now()) } }
                        }
                        lastEntry?.let { e ->
                            FTButton(label = "UNDO LAST CHECK-IN (${shortDate(e.localDate)})", emphasis = FTButtonEmphasis.Tertiary) { act("Withdrawn; it stays in the history as withdrawn.") { repo.retract(e) } }
                        }
                        if (confirmArchive) {
                            FTConfirmDestructive(
                                message = "Archive it? It leaves the check-in and the list; its history stays and it can be restored.",
                                confirmLabel = "ARCHIVE",
                                onCancel = { confirmArchive = false },
                                onConfirm = { confirmArchive = false; act("Archived.") { repo.archive(c.id, true) } },
                            )
                        } else FTButton(label = if (c.archivedAt != null) "RESTORE" else "ARCHIVE", emphasis = FTButtonEmphasis.Tertiary) {
                            if (c.archivedAt != null) act("Restored.") { repo.archive(c.id, false) } else confirmArchive = true
                        }
                    }
                }
                ConcernTab.Context -> {
                    if (s == null) return@Column
                    FTCard(title = "TRAINING AND RECOVERY, SAME DAYS", info = "Running, trail running, training load, sleep and HRV against its expected band, on the same days as the timeline. Shown side by side, never combined into a score.") {
                        ContextLanes(s.lanes, LocalDate.now().minusDays(182))
                    }
                    if (s.before.isEmpty()) Text("WHAT CAME BEFORE appears for each episode within the last year.", style = FTType.BodySmall, color = FT.TextSecondary)
                    s.before.reversed().forEach { b ->
                        FTCard(title = "BEFORE ${shortDate(b.episodeStart).uppercase()}", info = "Descriptive only: the two weeks before this episode next to your usual two weeks over the previous three months. It does not say what caused it.") {
                            b.sentences.forEach { Text(it, style = FTType.BodySmall, color = FT.TextPrimary) }
                            b.comparisons.forEach { cmp -> FTMetricRow(cmp.label, "${cmp.value} ${cmp.unit}".trim() + (cmp.typical?.let { " (usual $it)" } ?: "")) }
                            if (b.sessions.isNotEmpty()) Text("THE WEEK BEFORE", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
                            b.sessions.forEach { ses ->
                                Text(
                                    "${shortDate(ses.date)} · ${ses.type}${ses.km?.let { " · %.1f km".format(it) } ?: ""}${ses.minutes?.let { " · ${Math.round(it)} min" } ?: ""}${if (ses.flags.isNotEmpty()) " · " + ses.flags.joinToString(" ").uppercase() else ""} ›",
                                    style = FTType.Caption, color = FT.Info,
                                    modifier = Modifier.fillMaxWidth().ftClickable { onOpenSession(ses.id) }.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                ConcernTab.Patterns -> {
                    if (c.onsetType == "acute") Text("A single event is tracked and timed but not searched for patterns. If it keeps coming back, change its kind under MANAGE.", style = FTType.BodySmall, color = FT.TextSecondary)
                    val sorted = findings.sortedBy { findingRank(it.status) }
                    val (main, rest) = sorted.partition { it.status in setOf("persistent", "possible", "observed", "emerging", "faded") }
                    main.forEach { FindingCard(it, onOpenSession) }
                    if (rest.isNotEmpty()) {
                        FTCard(title = "ALSO CHECKED", info = "Every question asked of this concern, fixed in advance. NOT ENOUGH YET says what is missing; NO PATTERN means no clear difference so far.") {
                            rest.forEach { f ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(f.exposure?.replace('_', ' ')?.uppercase() ?: "", style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                                    FTStatePill(findingStatusWord(f.status))
                                }
                            }
                            rest.firstOrNull { it.status == "insufficient" }?.let { Text(it.nextStep.text, style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                    if (findings.isEmpty() && s != null) Text("Patterns appear after the next analysis run.", style = FTType.BodySmall, color = FT.TextSecondary)
                }
                ConcernTab.Trials -> {
                    trials.forEach { TrialCard(it, onLogged = { guard.retry() }) }
                    if (trials.isEmpty()) Text("No trial yet. A trial changes one thing for a few weeks and compares before and after; it shows what happened, not what the change did.", style = FTType.BodySmall, color = FT.TextSecondary)
                    FTButton(label = "START A TRIAL", emphasis = FTButtonEmphasis.Primary) { trialSheet = true }
                }
            }
        }
    }

    if (checkIn) HealthCheckInSheet(onDismiss = { checkIn = false }, onSaved = { checkIn = false; guard.retry() }, focusConcernId = concernId)
    if (trialSheet) concern?.let { c -> TrialSheet(c.id, c.title, onDismiss = { trialSheet = false }, onSaved = { trialSheet = false; guard.retry() }) }
}
