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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.HealthConcernsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.FindingDto
import com.bioscan.fieldterminal.data.model.TrialReadDto
import com.bioscan.fieldterminal.domain.health.findingStatusWord
import com.bioscan.fieldterminal.domain.health.shortDate
import com.bioscan.fieldterminal.domain.health.trialWord
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// Health concerns (plan §3.8, §5.5-5.6): a finding answers five things in a fixed order (what, evidence, what else could
// explain it, how uncertain, next step) with its evidence links; a trial shows its hypothesis, the before and after, and
// no verdict until its read date (contract R7). Words come from the engine (healthFindings.ts, healthTrials.ts).

private const val FINDING_INFO = "Found in your own history by comparing flare-ups with comparable symptom-free days (same month, same kind of day), " +
    "or sessions of one kind with others. The list of things checked was fixed in advance, every result is corrected for how many were checked, " +
    "and a pattern is only shown after it holds on a later re-check with new data. An association, never proof of cause."

@Composable
fun FindingCard(f: FindingDto, onOpenSession: ((Long) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    FTCard(title = f.exposure?.replace('_', ' ')?.uppercase() ?: "RECURRENCE", info = FINDING_INFO, methodology = "health_finding", helpExtra = "DATA: ${f.dataQuality}") {
        FTStatePill(findingStatusWord(f.status, f.weakening))
        Text(f.what, style = FTType.Body, color = FT.TextPrimary, modifier = Modifier.padding(top = 6.dp))
        Section("EVIDENCE", f.evidence.text)
        if (f.explanations.isNotEmpty()) Section("WHAT ELSE COULD EXPLAIN IT", f.explanations.joinToString("\n") { "· $it" })
        Section("HOW SURE", f.uncertainty)
        Section("NEXT STEP", f.nextStep.text)
        val links = f.evidence.links
        if (links.dates.isNotEmpty() || links.sessions.isNotEmpty()) {
            FTButton(label = if (open) "HIDE EVIDENCE" else "SHOW EVIDENCE (${links.dates.size + links.sessions.size})", emphasis = FTButtonEmphasis.Tertiary) { open = !open }
            if (open) {
                if (links.dates.isNotEmpty()) Text("Flare-ups: " + links.dates.joinToString { shortDate(it) }, style = FTType.Caption, color = FT.TextSecondary)
                links.sessions.forEach { id ->
                    Text(
                        "Session #$id ›", style = FTType.Caption, color = FT.Info,
                        modifier = Modifier.fillMaxWidth().then(if (onOpenSession != null) Modifier.ftClickable { onOpenSession(id) } else Modifier).padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, text: String) {
    if (text.isBlank()) return
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = FTType.Micro, color = FT.TextMuted)
        Text(text, style = FTType.BodySmall, color = FT.TextSecondary)
    }
}

@Composable
fun TrialCard(t: TrialReadDto, onLogged: () -> Unit) {
    val repo = remember { HealthConcernsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var logged by remember { mutableStateOf<String?>(null) }
    FTCard(
        title = "TRIAL · ${t.name.uppercase()}",
        methodology = "health_trial_read",
        info = "A before-and-after read of a change you chose, read once on its date and then kept. It shows what happened, not what the change did: the explanations list what else changed.",
    ) {
        FTStatePill(trialWord(t.state, t.word))
        Text(t.hypothesis, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        Text(t.summary, style = FTType.Body, color = FT.TextPrimary, modifier = Modifier.padding(top = 6.dp))
        Text("Before ${shortDate(t.pre.from)} – ${shortDate(t.pre.to)}: ${t.pre.value ?: "–"} · check-ins ${Math.round(t.pre.coverage * 100)}%", style = FTType.Caption, color = FT.TextSecondary)
        Text("Since ${shortDate(t.post.from)}: ${t.post.value ?: "–"} · check-ins ${Math.round(t.post.coverage * 100)}%", style = FTType.Caption, color = FT.TextSecondary)
        t.effect?.let { e ->
            Text("${if (e.kind == "ratio") "Ratio" else "Difference"} ${e.value ?: "∞"} (95% interval ${e.lo} to ${e.hi ?: "∞"}) ${e.unit}", style = FTType.Caption, color = FT.TextSecondary)
        }
        t.adherence.share?.let { Text("Kept on ${Math.round(it * 100)}% of ${t.adherence.days} logged days", style = FTType.Caption, color = FT.TextSecondary) }
        if (t.explanations.isNotEmpty()) Section("WHAT ELSE COULD EXPLAIN IT", t.explanations.joinToString("\n") { "· $it" })
        if (t.state == "interim") {
            Text("KEPT THE CHANGE TODAY?", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(top = 8.dp))
            TextChipRow(listOf("YES", "PARTLY", "NO"), logged, perRow = 3) { picked ->
                if (picked == null) return@TextChipRow
                logged = picked
                scope.launchSafe { repo.logAdherence(t.interventionId, LocalDate.now(), when (picked) { "YES" -> "yes"; "PARTLY" -> "partial"; else -> "no" }); onLogged() }
            }
            Text("Read on ${shortDate(t.evaluateOn)}; until then the numbers are interim and carry no verdict.", style = FTType.Caption, color = FT.TextMuted)
        }
    }
}

private val OUTCOMES = listOf(
    "flare_rate_per_exposure" to "FLARE-UPS PER KM RUN",
    "mean_severity" to "MEAN SEVERITY",
    "symptom_free_share" to "SYMPTOM-FREE DAYS",
    "impact_days" to "DAYS TRAINING WAS AFFECTED",
)

/** Registers a trial: what changes, the hypothesis and the outcome, fixed before it starts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrialSheet(concernId: String, concernTitle: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { HealthConcernsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var hypothesis by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf(OUTCOMES[0].first) }
    var weeks by remember { mutableStateOf(6) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("START A TRIAL", style = FTType.SectionTitle, color = FT.DomainHeart)
            Text(
                "Change one thing for a few weeks and compare $concernTitle before and after. Fixed now, before any data: what changes, what you expect, and what is measured.",
                style = FTType.BodySmall, color = FT.TextSecondary,
            )
            Text("WHAT CHANGES", style = FTType.Micro, color = FT.TextMuted)
            FieldTextField(name, { name = it }, "e.g. At most one trail run a week")
            Text("WHAT YOU EXPECT", style = FTType.Micro, color = FT.TextMuted)
            FieldTextField(hypothesis, { hypothesis = it }, "e.g. Fewer knee flare-ups per km run", singleLine = false)
            Text("MEASURED BY", style = FTType.Micro, color = FT.TextMuted)
            TextChipRow(OUTCOMES.map { it.second }, OUTCOMES.first { it.first == outcome }.second, perRow = 2) { picked ->
                OUTCOMES.firstOrNull { it.second == picked }?.let { outcome = it.first }
            }
            Text("LENGTH", style = FTType.Micro, color = FT.TextMuted)
            TextChipRow(listOf("4 WEEKS", "6 WEEKS", "8 WEEKS"), "$weeks WEEKS", perRow = 3) { picked -> picked?.let { weeks = it.substringBefore(' ').toInt() } }
            Text("Compared with the same number of weeks before it starts (at least four). Check in on good days too, or the read will say there is not enough to compare.", style = FTType.Caption, color = FT.TextMuted)
            error?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Critical) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton(label = "CANCEL", modifier = Modifier.weight(1f)) { onDismiss() }
                FTButton(label = if (saving) "SAVING..." else "START TODAY", emphasis = FTButtonEmphasis.Primary, enabled = !saving && name.isNotBlank() && hypothesis.length >= 3, modifier = Modifier.weight(1f)) {
                    saving = true
                    scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }, onDone = { saving = false }) {
                        val unit = if (outcome == "flare_rate_per_exposure") "run_km" else "days"
                        repo.createTrial(concernId, name, hypothesis, outcome, unit, LocalDate.now(), weeks, preDays = (weeks * 7).coerceAtLeast(28))
                        repo.refresh()
                        onSaved()
                    }
                }
            }
        }
    }
}

/** Lab › N=1: the health patterns (possible and persistent) and trials, beside the evidence board. Loads on its own. */
@Composable
fun HealthN1Section() {
    val repo = remember { HealthConcernsRepository(SupabaseClientProvider.client) }
    var findings by remember { mutableStateOf<List<FindingDto>>(emptyList()) }
    var trials by remember { mutableStateOf<List<TrialReadDto>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(reload) {
        findings = runCatching { repo.loadAllFindings() }.getOrDefault(emptyList()).filter { it.status == "possible" || it.status == "persistent" }
        trials = runCatching { repo.loadTrialReads() }.getOrDefault(emptyList())
    }
    if (findings.isEmpty() && trials.isEmpty()) return
    Text("HEALTH PATTERNS AND TRIALS", style = FTType.LabelCaps, color = FT.DomainHeart)
    findings.forEach { FindingCard(it) }
    trials.forEach { TrialCard(it, onLogged = { reload++ }) }
}
