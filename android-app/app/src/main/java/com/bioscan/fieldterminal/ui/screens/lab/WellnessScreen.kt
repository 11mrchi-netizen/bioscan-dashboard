package com.bioscan.fieldterminal.ui.screens.lab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.WellnessRepository
import com.bioscan.fieldterminal.data.WellnessVoice
import com.bioscan.fieldterminal.data.model.NewWellnessRow
import com.bioscan.fieldterminal.data.model.WellnessRow
import com.bioscan.fieldterminal.domain.breath.formatDuration
import com.bioscan.fieldterminal.domain.wellness.PROGRAMME_STOPSTART
import com.bioscan.fieldterminal.domain.wellness.STOPSTART_SCRIPT_VERSION
import com.bioscan.fieldterminal.domain.wellness.StopStartCue
import com.bioscan.fieldterminal.domain.wellness.StopStartHistoryItem
import com.bioscan.fieldterminal.domain.wellness.StopStartPhase
import com.bioscan.fieldterminal.domain.wellness.StopStartPlan
import com.bioscan.fieldterminal.domain.wellness.StopStartProgress
import com.bioscan.fieldterminal.domain.wellness.StopStartRun
import com.bioscan.fieldterminal.domain.wellness.WellnessLog
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTIconButton
import com.bioscan.fieldterminal.ui.components.FTIcons
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.Toasts
import com.bioscan.fieldterminal.ui.components.ftDomainWash
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Lab › TESTS › WELLNESS: guided stop-start training, the first stage of the sexual-wellness programme. The page shows
// the next session (its length follows the last ones), the session is paced by the clock and two taps, and each one is
// saved to wellness_sessions. Counts and ratings only; nothing typed. The voice is recorded audio where a recording exists
// and the phone's speech engine otherwise (data/WellnessVoice.kt).
@Composable
fun WellnessScreen(onBack: () -> Unit) {
    val repo = remember { WellnessRepository(SupabaseClientProvider.client) }
    var recent by remember { mutableStateOf<List<WellnessRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var localId by remember { mutableStateOf(-1L) }
    var running by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(reload) {
        try {
            recent = repo.recent(PROGRAMME_STOPSTART)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    val history = recent.orEmpty().map { StopStartHistoryItem(it.completed, it.cyclesDone, it.lateStops) }
    val plan = StopStartProgress.nextPlan(history)

    fun save(row: NewWellnessRow) {
        val placeholder = WellnessRow(
            localId--, row.startedAt, row.programmeKey, row.stageKey, row.durationS, row.completed,
            row.cyclesPlanned, row.cyclesDone, row.peakArousal, row.lateStops, row.felt,
        )
        recent = (listOf(placeholder) + recent.orEmpty()).take(12)
        Toasts.undoable(
            message = "Session saved",
            commit = { repo.add(row); reload++ },
            onUndo = { recent = recent?.filterNot { it.id == placeholder.id } },
        )
    }

    if (running) {
        StopStartSession(plan = plan, onSave = ::save, onEnd = { running = false })
        return
    }

    PinnedPage(onBack = onBack, title = "WELLNESS", subtitle = "STOP-START TRAINING · GUIDED · PRIVATE", accent = FT.DomainLabs) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val done = history.count { it.completed }
            FTCard(title = "STOP-START", trailing = "STAGE 1") {
                Text(
                    "Raise arousal to about 7 out of 10, stop, let it settle to about 4, then start again. Each go is a cycle. " +
                        "Practised steadily, it trains the sense of where the point of no return is.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text("NEXT SESSION · ${plan.cycles} CYCLES · ABOUT ${approxMinutes(plan)} MIN", style = FTType.MonoCaption, color = FT.TextMuted)
                Text(
                    if (StopStartProgress.stageMet(history)) "STAGE MET · $done SESSIONS DONE"
                    else "$done OF ${StopStartProgress.TARGET_SESSIONS} SESSIONS TOWARDS THE STAGE",
                    style = FTType.MonoCaption, color = FT.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                FTButton("START", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Primary, onClick = { running = true })
            }
            FTCard(title = "BEFORE YOU START") {
                Text(
                    "Choose a private, relaxed moment. Stop if anything hurts. If a problem with erections, ejaculation or pain " +
                        "lasts, a doctor or pelvic-floor physiotherapist can help; this is training, not treatment.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
            }
            Text("RECENT", style = FTType.LabelCaps, color = FT.TextSecondary)
            when {
                recent == null && failed -> Text("Could not load sessions.", style = FTType.BodySmall, color = FT.TextSecondary)
                recent.isNullOrEmpty() -> Text("No sessions yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                else -> recent.orEmpty().take(5).forEach { r ->
                    FTCard(title = r.startedAt.take(10) + " · " + formatDuration(r.durationS.toDouble()) + " MIN") {
                        Text(
                            listOfNotNull(
                                if (r.completed) null else "ended early",
                                "${r.cyclesDone} of ${r.cyclesPlanned} cycles",
                                r.peakArousal?.let { "peak $it/10" },
                                if (r.lateStops > 0) "${r.lateStops} late" else null,
                                r.felt?.let { "felt $it/5" },
                            ).joinToString(" · "),
                            style = FTType.BodySmall, color = FT.TextSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun approxMinutes(plan: StopStartPlan): Int =
    ((plan.settleS + plan.cooldownS + plan.cycles * (plan.minPauseS + 90)) / 60.0).toInt().coerceAtLeast(1)

@Composable
private fun StopStartSession(plan: StopStartPlan, onSave: (NewWellnessRow) -> Unit, onEnd: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val voice = remember { WellnessVoice(context, STOPSTART_SCRIPT_VERSION) }
    DisposableEffect(Unit) { onDispose { voice.release() } }

    var run by remember { mutableStateOf(StopStartRun(plan)) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    var paused by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val startedAt = remember { LocalDateTime.now() }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            elapsed += (now - last) / 1e9
            last = now
            run = run.tick(elapsed)
        }
    }
    // Each new cue is said once, then marked said.
    LaunchedEffect(run.cue) {
        run.cue?.let {
            voice.say(it)
            run = run.spoken()
        }
    }

    fun save(felt: Int?) {
        if (saved) return
        val row = WellnessLog.record(run, elapsed, startedAt, felt) ?: return
        saved = true
        onSave(row)
    }
    val end = { save(null); onEnd() }
    BackHandler(onBack = end)

    val shownCue = when (run.phase) {
        StopStartPhase.SETTLE -> StopStartCue.SETTLE
        StopStartPhase.BUILD -> if (run.cyclesDone == 0) StopStartCue.BUILD else StopStartCue.RESUME
        StopStartPhase.PAUSE -> if (run.canResume(elapsed)) StopStartCue.PAUSE_READY else StopStartCue.PAUSE_WAIT
        StopStartPhase.COOLDOWN -> StopStartCue.COOLDOWN
        StopStartPhase.DONE -> StopStartCue.DONE
    }
    val phaseSecondsLeft = when (run.phase) {
        StopStartPhase.SETTLE -> plan.settleS - (elapsed - run.phaseStartS)
        StopStartPhase.COOLDOWN -> plan.cooldownS - (elapsed - run.phaseStartS)
        StopStartPhase.PAUSE -> plan.minPauseS - (elapsed - run.phaseStartS)
        else -> 0.0
    }.coerceAtLeast(0.0)

    Column(
        modifier = Modifier.fillMaxSize().background(FT.Base).ftDomainWash(FT.DomainLabs).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FTIconButton(FTIcons.Close, "End session", end)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("STOP-START", style = FTType.SectionTitle, color = FT.TextPrimary, maxLines = 1)
                Text(
                    if (run.finished) "DONE" else "CYCLE ${(run.cyclesDone + 1).coerceAtMost(plan.cycles)} OF ${plan.cycles} · ${run.phase.label}",
                    style = FTType.MonoCaption, color = FT.TextSecondary,
                )
            }
            Text(formatDuration(elapsed), style = FTType.Value, color = FT.TextSecondary)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(run.phase.label, style = FTType.LabelCaps, color = FT.TextSecondary)
            Spacer(Modifier.height(12.dp))
            if (phaseSecondsLeft > 0 && run.phase != StopStartPhase.BUILD) {
                Text(kotlin.math.ceil(phaseSecondsLeft).toInt().toString(), style = FTType.Value, color = FT.TextPrimary)
                Spacer(Modifier.height(12.dp))
            }
            if (run.phase == StopStartPhase.PAUSE && run.lastWasLate) {
                Text(voice.text(StopStartCue.LATE), style = FTType.BodySmall, color = FT.TextSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
            }
            Text(voice.text(shownCue), style = FTType.Body, color = FT.TextPrimary, textAlign = TextAlign.Center)
        }

        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                run.finished -> {
                    Text("HOW DID IT FEEL? 1 (HARD) TO 5 (EASY)", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { n -> FTButton(n.toString(), modifier = Modifier.weight(1f), onClick = { save(n); onEnd() }) }
                    }
                    FTButton("CLOSE", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Primary, onClick = { save(null); onEnd() })
                }
                run.phase == StopStartPhase.BUILD && choosing -> {
                    Text("HOW HIGH DID IT GET? (0 TO 10)", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
                    listOf(listOf(4, 5, 6, 7), listOf(8, 9, 10)).forEach { rowValues ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowValues.forEach { n ->
                                FTButton(n.toString(), modifier = Modifier.weight(1f), emphasis = if (n == plan.targetArousal) FTButtonEmphasis.Primary else FTButtonEmphasis.Secondary, onClick = {
                                    run = run.stop(n, elapsed)
                                    choosing = false
                                })
                            }
                        }
                    }
                    FTButton("BACK", modifier = Modifier.fillMaxWidth(), onClick = { choosing = false })
                }
                run.phase == StopStartPhase.BUILD -> {
                    FTButton("STOP", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Primary, onClick = { choosing = true })
                }
                run.phase == StopStartPhase.PAUSE -> {
                    FTButton("RESUME", modifier = Modifier.fillMaxWidth(), enabled = run.canResume(elapsed), emphasis = FTButtonEmphasis.Primary, onClick = { run = run.resume(elapsed) })
                }
                else -> Unit
            }
            if (!run.finished) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FTButton("END", modifier = Modifier.weight(1f), onClick = end)
                    FTButton(if (paused) "CONTINUE" else "PAUSE", modifier = Modifier.weight(1f), onClick = { paused = !paused })
                }
            }
        }
    }
}
