package com.bioscan.fieldterminal.ui.screens.health

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.HealthConcernsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.WhyContext
import com.bioscan.fieldterminal.domain.health.CheckAnswers
import com.bioscan.fieldterminal.domain.health.CheckArea
import com.bioscan.fieldterminal.domain.health.CheckContext
import com.bioscan.fieldterminal.domain.health.CheckCourse
import com.bioscan.fieldterminal.domain.health.CheckIntensity
import com.bioscan.fieldterminal.domain.health.CheckLevel
import com.bioscan.fieldterminal.domain.health.CheckOutcome
import com.bioscan.fieldterminal.domain.health.CheckSince
import com.bioscan.fieldterminal.domain.health.EMERGENCY_NUMBER
import com.bioscan.fieldterminal.domain.health.FlagLevel
import com.bioscan.fieldterminal.domain.health.HealthVocab
import com.bioscan.fieldterminal.domain.health.RegionShape
import com.bioscan.fieldterminal.domain.health.TrackAs
import com.bioscan.fieldterminal.domain.health.assess
import com.bioscan.fieldterminal.domain.health.buildSymptomCheck
import com.bioscan.fieldterminal.domain.health.questionsFor
import com.bioscan.fieldterminal.ui.components.BodyMap
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FlowField
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowPickGrid
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import androidx.compose.foundation.background
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.ZonedDateTime
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Health › SYMPTOM CHECK (domain/health/SymptomCheck.kt): for the small things, apart from injuries and illnesses.
// 1 what and where (muscles and joints can name the spot on the body map); 2 how strong, since when, which way it is
// going, and the area's yes/no questions, red flags first, some prefilled from last night's sleep and the last days'
// training; 3 the result: red flags first (URGENT with a call button), then the patterns that fit with the answers behind
// each and the one thing worth doing, and what to track it as when it is more than a small thing. The check is kept the
// moment the result is shown. WHY? on the result asks the assistant for a second opinion (opt-in, off by default).

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SymptomCheckSheet(onDismiss: () -> Unit, onSaved: () -> Unit, onTrackIllness: (Set<String>, List<Long>) -> Unit, onTrackInjury: () -> Unit) {
    val context = LocalContext.current
    val vocab = remember { HealthConcernsRepository.vocab(context) }
    val repo = remember { HealthConcernsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var area by remember { mutableStateOf<CheckArea?>(null) }
    var symptoms by remember { mutableStateOf(emptySet<String>()) }
    var region by remember { mutableStateOf<RegionShape?>(null) }
    var intensity by remember { mutableStateOf<CheckIntensity?>(null) }
    var since by remember { mutableStateOf<CheckSince?>(null) }
    var course by remember { mutableStateOf(CheckCourse.SAME) }
    val answers = remember { mutableStateMapOf<String, Boolean>() }
    var hints by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var ctx by remember { mutableStateOf<CheckContext?>(null) }
    var outcome by remember { mutableStateOf<CheckOutcome?>(null) }
    var signalIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { ctx = catching { repo.loadCheckContext() }.getOrNull() }
    val a = area
    val questions = if (a == null || symptoms.isEmpty()) emptyList() else questionsFor(a, symptoms)
    // Prefill from the data each time the questions change, never over an answer given.
    LaunchedEffect(questions.map { it.id }, ctx) {
        val p = ctx?.prefill(questions).orEmpty()
        p.forEach { (id, v) -> if (id !in answers) answers[id] = v.first }
        hints = p.mapValues { it.value.second }
    }

    val o = outcome
    val muscles = a == CheckArea.MUSCLES
    val questionPages = questions.chunked(3)
    val ready = a != null && symptoms.isNotEmpty() && intensity != null && since != null
    // WHERE, WHAT, (the body map for muscles and joints), HOW, then the questions three to a page, red flags first;
    // SEE WHAT FITS saves and turns the flow into its result.
    val pages = if (o != null) listOf(FlowPage("WHAT FITS")) else buildList {
        add(FlowPage("WHERE", ready = a != null))
        add(FlowPage("WHAT", ready = symptoms.isNotEmpty()))
        if (muscles) add(FlowPage("ON THE BODY"))
        add(FlowPage("HOW", ready = intensity != null && since != null))
        questionPages.indices.forEach { add(FlowPage(if (questionPages.size > 1) "QUESTIONS ${it + 1}" else "QUESTIONS")) }
    }
    val flow = rememberFlowState()
    FTFlow(
        title = "SYMPTOM CHECK", pages = pages, state = flow, onClose = onDismiss,
        canSave = ready, saving = saving, saveLabel = "SEE WHAT FITS", wash = FT.Domain.Cardio,
        error = error?.let { "Couldn't save: $it" },
        onSave = save@{
            val area0 = a ?: return@save
            val answered = CheckAnswers(area0, symptoms, intensity ?: return@save, since ?: return@save, course, answers.toMap(), region?.code, region?.side)
            val result = assess(answered)
            saving = true
            scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                signalIds = repo.saveCheck(buildSymptomCheck(answered, result, ZonedDateTime.now()))
                outcome = result
            }
        },
        bottomBar = if (o == null) null else { _ ->
            Row(Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (o.track) {
                    TrackAs.ILLNESS -> FTButton("TRACK AS ILLNESS", modifier = Modifier.weight(1f)) { onTrackIllness(symptoms, signalIds) }
                    TrackAs.INJURY -> FTButton("TRACK AS INJURY", modifier = Modifier.weight(1f), onClick = onTrackInjury)
                    null -> Unit
                }
                FTButton("DONE", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f), onClick = onSaved)
            }
        },
    ) { page ->
        if (o != null && a != null) {
            Outcome(o, a, symptoms, vocab::symptomLabel)
            return@FTFlow
        }
        val label = pages.getOrNull(page)?.label.orEmpty()
        when {
            label == "WHERE" -> {
                FlowQuestion("Where is it?", "For the small things: an ache, a headache, a stuffed nose. A fixed set of questions, red flags first. Not a diagnosis.")
                TextChipRow(CheckArea.entries.map { it.label.uppercase() }, a?.label?.uppercase(), perRow = 3) { picked ->
                    area = CheckArea.entries.firstOrNull { it.label.uppercase() == picked }
                    symptoms = emptySet(); answers.clear(); region = null
                }
            }
            label == "WHAT" && a != null -> {
                FlowQuestion("What is it?")
                FlowPickGrid(a.symptoms.map { it to vocab.symptomLabel(it) }, symptoms, { code -> symptoms = if (code in symptoms) symptoms - code else symptoms + code })
            }
            label == "ON THE BODY" -> {
                FlowQuestion("Where on the body?", "Optional: tap the spot.")
                BodyMap(selected = region, onTap = { region = it }, height = 300.dp)
                region?.let { Text(vocab.regionLabel(it.code, it.side), style = FTType.RowTitle, color = FT.TextPrimary) }
            }
            label == "HOW" -> {
                FlowField("HOW STRONG") {
                    TextChipRow(CheckIntensity.entries.map { it.label }, intensity?.label, perRow = 3) { picked -> intensity = CheckIntensity.entries.firstOrNull { it.label == picked } }
                }
                FlowField("SINCE") {
                    TextChipRow(CheckSince.entries.map { it.label }, since?.label, perRow = 2) { picked -> since = CheckSince.entries.firstOrNull { it.label == picked } }
                }
                FlowField("SINCE IT STARTED, IT IS") {
                    TextChipRow(CheckCourse.entries.map { it.label }, course.label, perRow = 3) { picked -> course = CheckCourse.entries.firstOrNull { it.label == picked } ?: course }
                }
            }
            label.startsWith("QUESTIONS") -> {
                val first = pages.indexOfFirst { it.label.startsWith("QUESTIONS") }
                questionPages.getOrNull(page - first).orEmpty().forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(q.text, style = FTType.BodySmall, color = if (q.flag?.level == FlagLevel.URGENT) FT.TextPrimary else FT.TextSecondary)
                        hints[q.id]?.let { Text("From your data: $it", style = FTType.Caption, color = FT.TextMuted) }
                        TextChipRow(listOf("NO", "YES"), answers[q.id]?.let { if (it) "YES" else "NO" }, perRow = 2) { picked ->
                            if (picked == null) answers.remove(q.id) else answers[q.id] = picked == "YES"
                        }
                    }
                }
            }
        }
    }
}

private fun HealthVocab.symptomLabel(code: String): String = symptom(code)?.label ?: code.removePrefix("sym.")

@Composable
private fun Outcome(o: CheckOutcome, area: CheckArea, symptoms: Set<String>, label: (String) -> String) {
    val context = LocalContext.current
    val why = WhyContext(
        card = "SYMPTOM CHECK",
        words = listOf("AREA" to area.label, "SYMPTOMS" to symptoms.joinToString { label(it) }, "RESULT" to o.level.name) +
            o.patterns.map { "FITS" to it.pattern.label },
        shown = mapOf("next" to o.next) + o.flags.mapIndexed { i, f -> "flag$i" to f.text },
    )
    if (o.flags.isNotEmpty()) {
        FTCard(title = if (o.level == CheckLevel.URGENT) "ACT NOW" else "WORTH A CLINICIAN'S VIEW") {
            o.flags.forEach { f -> Text(f.text, style = FTType.BodySmall, color = if (f.level == FlagLevel.URGENT) FT.Bad else FT.Warn) }
            if (o.level == CheckLevel.URGENT) {
                FTButton("CALL $EMERGENCY_NUMBER", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$EMERGENCY_NUMBER")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }
    FTCard(
        title = "WHAT FITS",
        info = "Patterns your answers point to, each with the answers behind it. A fixed set of rules, the same answers always give the same result. Not a diagnosis: WHY? asks the assistant for a second opinion when it is on.",
        why = why,
    ) {
        if (o.patterns.isEmpty()) Text("Nothing points one way.", style = FTType.BodySmall, color = FT.TextSecondary)
        o.patterns.forEach { p ->
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.pattern.label.uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary)
                Text(p.because.joinToString("; "), style = FTType.Caption, color = FT.TextMuted)
                Text(p.pattern.advice, style = FTType.BodySmall, color = FT.TextSecondary)
            }
        }
        Text(o.next, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.padding(top = 6.dp))
    }
}
