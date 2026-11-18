package com.bioscan.fieldterminal.ui.screens.lab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.BreathworkStore
import com.bioscan.fieldterminal.domain.breath.BreathPacer
import com.bioscan.fieldterminal.domain.breath.BreathPattern
import com.bioscan.fieldterminal.domain.breath.BreathPresets
import com.bioscan.fieldterminal.domain.breath.BreathStep
import com.bioscan.fieldterminal.domain.breath.BreathStepKind
import com.bioscan.fieldterminal.domain.breath.formatDuration
import com.bioscan.fieldterminal.domain.breath.formatSeconds
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTChipRow
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.FTIconButton
import com.bioscan.fieldterminal.ui.components.FTIcons
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.ftDomainWash
import com.bioscan.fieldterminal.ui.components.ftGlass
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.isActive
import androidx.compose.runtime.withFrameNanos
import kotlin.math.min
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Lab › TESTS › BREATHWORK: pick a preset or one of your own patterns, set the rounds, START. The session is a guide
// circle that fills on the breath in, holds still on a hold and empties on the breath out, with the cue and the seconds
// left in its middle. Nothing is logged: the page only paces the breath. Custom patterns live on the phone
// (data/BreathworkStore.kt).
@Composable
fun BreathworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var customs by remember { mutableStateOf(BreathworkStore.customPatterns(context)) }
    // A preset's rounds as last chosen; a custom pattern keeps its own.
    var presetRounds by remember { mutableStateOf(BreathPresets.all.associate { it.id to (BreathworkStore.presetRounds(context, it.id) ?: it.rounds) }) }
    val presets = BreathPresets.all.map { it.copy(rounds = presetRounds[it.id] ?: it.rounds) }

    var selectedId by rememberSaveable { mutableStateOf(BreathPresets.all.first().id) }
    var runningId by rememberSaveable { mutableStateOf<String?>(null) }
    // null = closed; "" = a new pattern; otherwise the id of the custom pattern being edited.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    fun find(id: String?) = (presets + customs).firstOrNull { it.id == id }
    fun setRounds(p: BreathPattern, rounds: Int) {
        if (p.isCustom) customs = customs.map { if (it.id == p.id) BreathworkStore.save(context, it.copy(rounds = rounds)) else it }
        else {
            presetRounds = presetRounds + (p.id to rounds)
            BreathworkStore.savePresetRounds(context, p.id, rounds)
        }
    }

    val running = find(runningId)
    if (running != null && running.isRunnable) {
        BreathSession(pattern = running, onEnd = { runningId = null })
        return
    }

    PinnedPage(onBack = onBack, title = "BREATHWORK", subtitle = "PRESETS · YOUR PATTERNS · A VISUAL GUIDE", accent = FT.DomainLabs) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("PRESETS")
            presets.forEach { p ->
                PatternCard(p, selected = p.id == selectedId, onSelect = { selectedId = p.id }, onRounds = { setRounds(p, it) }, onStart = { runningId = p.id })
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel("YOUR PATTERNS")
            if (customs.isEmpty()) {
                Text("Build your own: any order of breathing in, holding and breathing out, each as long as you like.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
            customs.forEach { p ->
                PatternCard(
                    p,
                    selected = p.id == selectedId,
                    onSelect = { selectedId = p.id },
                    onRounds = { setRounds(p, it) },
                    onStart = { runningId = p.id },
                    onEdit = { editingId = p.id },
                )
            }
            FTButton("+ NEW PATTERN", modifier = Modifier.fillMaxWidth(), onClick = { editingId = "" })
            Spacer(Modifier.height(24.dp))
        }
    }

    editingId?.let { id ->
        PatternEditorSheet(
            initial = customs.firstOrNull { it.id == id },
            onDismiss = { editingId = null },
            onSave = { p ->
                val saved = BreathworkStore.save(context, p)
                customs = BreathworkStore.customPatterns(context)
                selectedId = saved.id
                editingId = null
            },
            onDelete = { p ->
                BreathworkStore.delete(context, p.id)
                customs = BreathworkStore.customPatterns(context)
                if (selectedId == p.id) selectedId = BreathPresets.all.first().id
                editingId = null
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary)
}

// One pattern. Tapping it opens it: its steps, the rounds and START.
@Composable
private fun PatternCard(
    p: BreathPattern,
    selected: Boolean,
    onSelect: () -> Unit,
    onRounds: (Int) -> Unit,
    onStart: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    FTCard(
        title = p.name.uppercase(),
        trailing = p.rhythm,
        emphasis = if (selected) FTCardEmphasis.Hero else FTCardEmphasis.Supporting,
        onClick = if (selected) null else onSelect,
    ) {
        if (p.purpose.isNotEmpty()) Text(p.purpose, style = FTType.BodySmall, color = FT.TextSecondary)
        if (selected) {
            Spacer(Modifier.height(10.dp))
            StepStrip(p.steps, current = null)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ROUNDS", style = FTType.LabelCaps, color = FT.TextSecondary)
                Spacer(Modifier.weight(1f))
                Stepper(value = p.rounds.toString(), onMinus = { onRounds((p.rounds - 1).coerceAtLeast(1)) }, onPlus = { onRounds((p.rounds + 1).coerceAtMost(200)) }, what = "rounds")
            }
            Text(
                "${formatDuration(p.totalSeconds)} MIN · ${formatSeconds(p.cycleSeconds)} S A BREATH",
                style = FTType.MonoCaption,
                color = FT.TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onEdit != null) FTButton("EDIT", modifier = Modifier.weight(1f), onClick = onEdit)
                FTButton("START", modifier = Modifier.weight(1f), enabled = p.isRunnable, emphasis = FTButtonEmphasis.Primary, onClick = onStart)
            }
        } else if (p.purpose.isEmpty()) {
            Text("${p.rounds} ROUNDS · ${formatDuration(p.totalSeconds)} MIN", style = FTType.MonoCaption, color = FT.TextMuted)
        }
    }
}

// The steps in order, as small labelled cells: "IN 4", "HOLD 7", "OUT 8". The current one (in a session) is platinum.
@Composable
private fun StepStrip(steps: List<BreathStep>, current: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            val on = i == current
            val shape = RoundedCornerShape(FT.RadiusSmall)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (on) Modifier.background(FT.Platinum, shape) else Modifier.border(FT.BorderWidth, FT.Line, shape))
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(step.kind.label, style = FTType.Micro, color = if (on) FT.PlatinumInk else FT.TextSecondary, maxLines = 1)
                Text(formatSeconds(step.seconds), style = FTType.Value, color = if (on) FT.PlatinumInk else FT.TextPrimary)
            }
        }
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, what: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FTIconButton(FTIcons.Minus, "Fewer $what", onMinus)
        Text(value, style = FTType.Value, color = FT.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 36.dp))
        FTIconButton(FTIcons.Plus, "More $what", onPlus)
    }
}

// ---- the session ----

private const val LEAD_IN_SECONDS = 3.0

@Composable
private fun BreathSession(pattern: BreathPattern, onEnd: () -> Unit) {
    // The screen stays on while the guide runs.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onEnd)

    var attempt by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    // Seconds since START, the lead-in included.
    var elapsed by remember(attempt) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(attempt, paused) {
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            elapsed += (now - last) / 1e9
            last = now
            if (elapsed >= LEAD_IN_SECONDS + pattern.totalSeconds) break
        }
    }

    val leadIn = elapsed < LEAD_IN_SECONDS
    val moment = BreathPacer.momentAt(pattern, elapsed - LEAD_IN_SECONDS)
    val finished = moment.finished

    Column(
        modifier = Modifier.fillMaxSize().background(FT.Base).ftDomainWash(FT.DomainLabs).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FTIconButton(FTIcons.Close, "End session", onEnd)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(pattern.name.uppercase(), style = FTType.SectionTitle, color = FT.TextPrimary, maxLines = 1)
                Text(
                    when {
                        finished -> "DONE · ${formatDuration(pattern.totalSeconds)} MIN"
                        leadIn -> "GET READY"
                        else -> "ROUND ${moment.round} OF ${pattern.rounds} · ${formatDuration(remainingSeconds(pattern, elapsed - LEAD_IN_SECONDS))} LEFT"
                    },
                    style = FTType.MonoCaption,
                    color = FT.TextSecondary,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        val cue = when {
            finished -> "Done"
            leadIn -> "Get ready"
            else -> moment.step.kind.cue
        }
        val count = when {
            finished -> ""
            leadIn -> (LEAD_IN_SECONDS - elapsed).let { kotlin.math.ceil(it).toInt().coerceAtLeast(1).toString() }
            else -> kotlin.math.ceil(moment.secondsLeftInStep).toInt().coerceAtLeast(1).toString()
        }
        BreathGuide(
            fill = if (leadIn) 0.0 else moment.fill,
            stepProgress = if (leadIn) elapsed / LEAD_IN_SECONDS else if (finished) 1.0 else moment.stepProgress,
            holding = !leadIn && !finished && (moment.step.kind == BreathStepKind.HOLD_IN || moment.step.kind == BreathStepKind.HOLD_OUT),
            cue = cue,
            count = count,
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
        )
        Spacer(Modifier.weight(1f))

        StepStrip(pattern.steps, current = if (leadIn || finished) null else moment.stepIndex)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (finished) {
                FTButton("AGAIN", modifier = Modifier.weight(1f), onClick = { paused = false; attempt++ })
                FTButton("CLOSE", modifier = Modifier.weight(1f), emphasis = FTButtonEmphasis.Primary, onClick = onEnd)
            } else {
                FTButton("END", modifier = Modifier.weight(1f), onClick = onEnd)
                FTButton(if (paused) "RESUME" else "PAUSE", modifier = Modifier.weight(1f), emphasis = FTButtonEmphasis.Primary, onClick = { paused = !paused })
            }
        }
    }
}

private fun remainingSeconds(pattern: BreathPattern, t: Double) = (pattern.totalSeconds - t.coerceAtLeast(0.0)).coerceAtLeast(0.0)

// The guide: a hairline ring at full size, a glass disc inside it that grows with the breath in and shrinks with the
// breath out, and a thin platinum arc around the ring for how far through the current step you are. On a hold the disc
// stays put and gets a platinum rim, so a hold reads as "still" rather than "stuck".
@Composable
private fun BreathGuide(fill: Double, stepProgress: Double, holding: Boolean, cue: String, count: String, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(1f).semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = cue
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = min(size.width, size.height) / 2f - 6.dp.toPx()
            val minR = outer * 0.38f
            val r = minR + (outer - minR) * fill.toFloat()
            val c = center
            // Full-breath ring.
            drawCircle(FT.Line, radius = outer, center = c, style = Stroke(1.dp.toPx()))
            // The breath.
            drawCircle(
                Brush.radialGradient(
                    0f to FT.PlatinumSolid.copy(alpha = 0.22f),
                    0.8f to FT.PlatinumSolid.copy(alpha = 0.08f),
                    1f to FT.PlatinumSolid.copy(alpha = 0.04f),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
            drawCircle(
                if (holding) FT.PlatinumSolid.copy(alpha = 0.9f) else FT.GlassBorder,
                radius = r,
                center = c,
                style = Stroke((if (holding) 2 else 1).dp.toPx()),
            )
            // Progress through the step.
            val stroke = 3.dp.toPx()
            drawArc(
                color = FT.PlatinumSolid,
                startAngle = -90f,
                sweepAngle = 360f * stepProgress.toFloat().coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(c.x - outer, c.y - outer),
                size = Size(outer * 2, outer * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cue, style = FTType.RowTitle, color = FT.TextPrimary)
            if (count.isNotEmpty()) Text(count, style = FTType.DisplayMetric, color = FT.TextPrimary)
        }
    }
}

// ---- building a pattern ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternEditorSheet(
    initial: BreathPattern?,
    onDismiss: () -> Unit,
    onSave: (BreathPattern) -> Unit,
    onDelete: (BreathPattern) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var steps by remember {
        mutableStateOf(
            initial?.steps ?: listOf(BreathStep(BreathStepKind.INHALE, 4.0), BreathStep(BreathStepKind.EXHALE, 6.0)),
        )
    }
    var rounds by rememberSaveable { mutableIntStateOf(initial?.rounds ?: 10) }
    var confirmDelete by remember { mutableStateOf(false) }
    val draft = BreathPattern(initial?.id ?: "", name.trim().ifEmpty { "My pattern" }, steps, rounds)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (initial == null) "NEW PATTERN" else "EDIT PATTERN", style = FTType.SectionTitle, color = FT.TextPrimary)
            FieldTextField(value = name, onValueChange = { name = it.take(40) }, placeholder = "Name", keyboardType = KeyboardType.Text)

            SectionLabel("STEPS, IN ORDER")
            steps.forEachIndexed { i, step ->
                StepEditor(
                    index = i,
                    step = step,
                    onChange = { new -> steps = steps.toMutableList().also { it[i] = new } },
                    onRemove = if (steps.size > 1) ({ steps = steps.toMutableList().also { it.removeAt(i) } }) else null,
                )
            }
            if (steps.size < 8) {
                FTButton("+ ADD STEP", modifier = Modifier.fillMaxWidth(), onClick = {
                    val nextKind = when (steps.lastOrNull()?.kind) {
                        BreathStepKind.INHALE -> BreathStepKind.HOLD_IN
                        BreathStepKind.HOLD_IN -> BreathStepKind.EXHALE
                        BreathStepKind.EXHALE -> BreathStepKind.HOLD_OUT
                        else -> BreathStepKind.INHALE
                    }
                    steps = steps + BreathStep(nextKind, 4.0)
                })
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("ROUNDS")
                Spacer(Modifier.weight(1f))
                Stepper(rounds.toString(), onMinus = { rounds = (rounds - 1).coerceAtLeast(1) }, onPlus = { rounds = (rounds + 1).coerceAtMost(200) }, what = "rounds")
            }
            Text(
                "${draft.rhythm} · ${formatDuration(draft.totalSeconds)} MIN",
                style = FTType.MonoCaption,
                color = FT.TextMuted,
            )

            if (confirmDelete && initial != null) {
                FTConfirmDestructive(
                    message = "Delete “${initial.name}”? It cannot be brought back.",
                    confirmLabel = "DELETE",
                    onCancel = { confirmDelete = false },
                    onConfirm = { onDelete(initial) },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (initial != null) {
                        FTButton("DELETE", modifier = Modifier.weight(1f), emphasis = FTButtonEmphasis.Destructive, onClick = { confirmDelete = true })
                    } else {
                        FTButton("CANCEL", modifier = Modifier.weight(1f), onClick = onDismiss)
                    }
                    FTButton("SAVE", modifier = Modifier.weight(1f), enabled = draft.isRunnable, emphasis = FTButtonEmphasis.Primary, onClick = { onSave(draft) })
                }
            }
        }
    }
}

@Composable
private fun StepEditor(index: Int, step: BreathStep, onChange: (BreathStep) -> Unit, onRemove: (() -> Unit)?) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(Modifier.fillMaxWidth().ftGlass(shape).padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("STEP ${index + 1}", style = FTType.LabelCaps, color = FT.TextSecondary)
            Spacer(Modifier.weight(1f))
            Stepper(
                value = "${formatSeconds(step.seconds)} s",
                onMinus = { onChange(step.copy(seconds = (step.seconds - 0.5).coerceAtLeast(0.5))) },
                onPlus = { onChange(step.copy(seconds = (step.seconds + 0.5).coerceAtMost(120.0))) },
                what = "seconds",
            )
            if (onRemove != null) FTIconButton(FTIcons.Close, "Remove step ${index + 1}", onRemove)
        }
        FTChipRow(
            items = BreathStepKind.entries,
            selected = step.kind,
            label = { it.label },
            onSelect = { onChange(step.copy(kind = it)) },
            contentPadding = PaddingValues(horizontal = 10.dp),
        )
    }
}
