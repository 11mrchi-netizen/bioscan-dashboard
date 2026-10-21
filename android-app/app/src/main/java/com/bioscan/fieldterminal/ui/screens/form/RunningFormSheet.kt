package com.bioscan.fieldterminal.ui.screens.form

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.RunningFormRepository
import com.bioscan.fieldterminal.data.SavedFormAnalysis
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.form.ExtractedClip
import com.bioscan.fieldterminal.data.form.extractPoseClip
import com.bioscan.fieldterminal.data.form.framesAt
import com.bioscan.fieldterminal.domain.form.ArgbFrame
import com.bioscan.fieldterminal.domain.form.BodySide
import com.bioscan.fieldterminal.domain.form.ClipQuality
import com.bioscan.fieldterminal.domain.form.FindingState
import com.bioscan.fieldterminal.domain.form.FormFinding
import com.bioscan.fieldterminal.domain.form.FormReport
import com.bioscan.fieldterminal.domain.form.FormSetup
import com.bioscan.fieldterminal.domain.form.FormSurface
import com.bioscan.fieldterminal.domain.form.Landmark
import com.bioscan.fieldterminal.domain.form.PoseTracks
import com.bioscan.fieldterminal.domain.form.RunChoice
import com.bioscan.fieldterminal.domain.form.WatchRunValues
import com.bioscan.fieldterminal.domain.form.analyseRunningForm
import com.bioscan.fieldterminal.domain.form.formFindings
import com.bioscan.fieldterminal.domain.form.formMetricValues
import com.bioscan.fieldterminal.domain.form.legOf
import com.bioscan.fieldterminal.domain.form.runForClip
import com.bioscan.fieldterminal.domain.form.sinceLastTime
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.FTDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Lab › Tests › RUNNING FORM ANALYSER (docs/running-form/README.md): the filming guide, the runner's choices and past
// analyses, then a picked clip read on the phone (pose, then the analysis), then the report: the clip's quality, the step
// shown on two key frames with the skeleton drawn, the run it was filmed during (the watch's values beside the clip's),
// the findings with their reasons, sources and last time's value, and SAVE.

private enum class Facing(val label: String, val side: BodySide?) { LEFT("LEFT", BodySide.LEFT), RIGHT("RIGHT", BodySide.RIGHT), UNSURE("NOT SURE", null) }

private sealed interface Stage {
    data object Setup : Stage
    data class Reading(val progress: Double, val analysing: Boolean = false) : Stage
    data class Report(val report: FormReport, val frames: Map<Long, ArgbFrame>, val clip: ExtractedClip) : Stage
    data class Failed(val message: String) : Stage
}

private val FILMING = listOf(
    "A treadmill is best; outdoors, a flat straight path and several passes.",
    "Phone fixed (tripod or wedged), at hip height, square to the running direction, 3–4 m away. Landscape, no zoom.",
    "Both feet in frame the whole time.",
    "Slow motion (120 or 240 fps) adds contact time; 60 fps gives the landing; 30 fps only cadence and posture.",
    "Tight or short clothing, good light, 10–20 s of steady running at the pace you want judged.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunningFormSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var surface by rememberSaveable { mutableStateOf(FormSurface.TREADMILL) }
    var facing by rememberSaveable { mutableStateOf(Facing.UNSURE) }
    var speed by rememberSaveable { mutableStateOf("") }
    var stage by remember { mutableStateOf<Stage>(Stage.Setup) }
    var job by remember { mutableStateOf<Job?>(null) }
    var picking by remember { mutableStateOf(false) }
    val repo = remember { RunningFormRepository(SupabaseClientProvider.client) }
    // Past analyses for the list and the "last time" lines; empty (not an error) until the table exists.
    var history by remember { mutableStateOf<List<SavedFormAnalysis>>(emptyList()) }
    var historyKey by remember { mutableStateOf(0) }
    LaunchedEffect(historyKey) {
        history = try { repo.loadAll() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    fun read(uri: Uri) {
        val setup = FormSetup(
            surface = surface,
            sideFacingCamera = facing.side,
            treadmillSpeedKmh = if (surface == FormSurface.TREADMILL) speed.replace(',', '.').toDoubleOrNull() else null,
        )
        job = scope.launch {
            stage = Stage.Reading(0.0)
            try {
                var shown = 0
                val extracted = extractPoseClip(context, uri) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != shown) { shown = pct; stage = Stage.Reading(p) }
                }
                stage = Stage.Reading(1.0, analysing = true)
                val report = withContext(Dispatchers.Default) { analyseRunningForm(extracted.clip, setup) }
                stage = Stage.Report(report, emptyMap(), extracted)
                // The key frames are decoded again (a second, pose-free pass) only for the step shown.
                report.keyStep?.let { step ->
                    val times = setOf(extracted.fileTimesUs[step.landingIndex], extracted.fileTimesUs[step.toeOffIndex])
                    val frames = framesAt(context, uri, extracted.rotation, times)
                    stage = Stage.Report(report, frames, extracted)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stage = Stage.Failed(describeLoadFailure(e))
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        picking = false
        if (uri != null) read(uri)
    }

    ModalBottomSheet(
        onDismissRequest = { if (!picking && !com.bioscan.fieldterminal.util.ExternalActivityGuard.active) { job?.cancel(); onDismiss() } },
        sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("RUNNING FORM ANALYSER", style = FTType.SectionTitle, color = FT.DomainLabs)
            when (val s = stage) {
                Stage.Setup -> {
                    SetupStage(
                        surface, { surface = it }, facing, { facing = it }, speed, { speed = it },
                        onPick = {
                            picking = true
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                    )
                    if (history.isNotEmpty()) PastAnalyses(history, repo, onDeleted = { historyKey++ })
                }
                is Stage.Reading -> ReadingStage(s) { job?.cancel(); stage = Stage.Setup }
                is Stage.Report -> ReportStage(s, repo, history, onSaved = { historyKey++ }, onAnother = { stage = Stage.Setup }, onDone = onDismiss)
                is Stage.Failed -> {
                    Text("The clip could not be read: ${s.message}", style = FTType.BodySmall, color = FT.Critical)
                    FTButton("TRY ANOTHER CLIP", emphasis = FTButtonEmphasis.Primary) { stage = Stage.Setup }
                }
            }
        }
    }
}

@Composable
private fun SmallLabel(text: String) = Text(text, style = FTType.LabelCaps, color = FT.TextMuted)

@Composable
private fun SetupStage(
    surface: FormSurface, onSurface: (FormSurface) -> Unit,
    facing: Facing, onFacing: (Facing) -> Unit,
    speed: String, onSpeed: (String) -> Unit,
    onPick: () -> Unit,
) {
    Text(
        "Film a short side-on clip of your running and pick it here. The phone reads where your foot lands, the knee and shin " +
            "at landing, trunk lean and bounce and, in slow motion, contact time. The video stays on the phone.",
        style = FTType.BodySmall, color = FT.TextSecondary,
    )
    FTCard(title = "FILMING") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FILMING.forEach { Text("· $it", style = FTType.BodySmall, color = FT.TextSecondary) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallLabel("WHERE")
        SegmentedToggle(FormSurface.entries, surface, { if (it == FormSurface.TREADMILL) "TREADMILL" else "OUTDOORS" }, onSurface, accentColor = FT.DomainLabs)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallLabel("SIDE FACING THE CAMERA")
        SegmentedToggle(Facing.entries, facing, { it.label }, onFacing, accentColor = FT.DomainLabs)
    }
    if (surface == FormSurface.TREADMILL) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallLabel("TREADMILL SPEED KM/H (OPTIONAL)")
            FieldTextField(speed, onSpeed, "e.g. 11.5", keyboardType = KeyboardType.Decimal)
        }
    }
    FTButton("PICK A VIDEO", emphasis = FTButtonEmphasis.Primary, onClick = onPick)
}

@Composable
private fun ReadingStage(s: Stage.Reading, onCancel: () -> Unit) {
    Text(
        if (s.analysing) "ANALYSING THE STEPS" else "READING THE CLIP · ${(s.progress * 100).roundToInt()} %",
        style = FTType.LabelCaps, color = FT.TextPrimary,
    )
    LinearProgressIndicator(progress = { s.progress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = FT.DomainLabs, trackColor = FT.GlassTrack)
    Text("Every frame is read on the phone; a 10 s slow-motion clip takes about a minute.", style = FTType.Caption, color = FT.TextMuted)
    FTButton("CANCEL", emphasis = FTButtonEmphasis.Tertiary, onClick = onCancel)
}

private fun qualityColor(q: ClipQuality) = when (q) {
    ClipQuality.GOOD -> FT.Emerald
    ClipQuality.USABLE -> FT.Warning
    ClipQuality.POOR -> FT.Critical
}

@Composable
private fun ReportStage(
    s: Stage.Report,
    repo: RunningFormRepository,
    history: List<SavedFormAnalysis>,
    onSaved: () -> Unit,
    onAnother: () -> Unit,
    onDone: () -> Unit,
) {
    val r = s.report
    val q = r.quality
    val scope = rememberCoroutineScope()
    // The clip's own recording time, on the phone's clock; sessions store wall-clock times, so the two compare directly.
    val clipAt = remember(r) { s.clip.recordedAt?.atZone(ZoneId.systemDefault())?.toLocalDateTime() }
    val day = clipAt?.toLocalDate() ?: LocalDate.now()
    var runs by remember(r) { mutableStateOf<List<RunChoice>>(emptyList()) }
    var runId by remember(r) { mutableStateOf<Long?>(null) }
    var watch by remember(r) { mutableStateOf<WatchRunValues?>(null) }
    var saving by remember(r) { mutableStateOf(false) }
    var saved by remember(r) { mutableStateOf(false) }
    var error by remember(r) { mutableStateOf<String?>(null) }
    LaunchedEffect(r) {
        runs = try { repo.runsOn(day) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        runId = runForClip(clipAt, runs)
    }
    LaunchedEffect(runId) {
        watch = runId?.let { id -> try { repo.watchValues(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
    }
    val findings = remember(r, watch) { r.metrics?.let { formFindings(it, watch) }.orEmpty() }
    val values = remember(r) { r.metrics?.let(::formMetricValues).orEmpty() }
    val previous = history.firstOrNull { it.surface == r.setup.surface }
    val setupLine = listOfNotNull(
        "SIDE",
        if (r.setup.surface == FormSurface.TREADMILL) "TREADMILL" else "OUTDOORS",
        r.setup.treadmillSpeedKmh?.let { "%.1f KM/H".format(it) },
        "${q.fps.roundToInt()} FPS",
        "${q.measuredSteps} STEPS",
        "${r.measuredSide.name} LEG",
    ).joinToString(" · ")
    Text(setupLine, style = FTType.MonoCaption, color = FT.TextMuted)

    FTCard(title = "CLIP QUALITY") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(q.level.name, style = FTType.LabelCaps, color = qualityColor(q.level))
            if (q.issues.isEmpty()) Text("Runner in view, near leg clear, enough steps.", style = FTType.BodySmall, color = FT.TextSecondary)
            q.issues.forEach { Text("· ${it.text}", style = FTType.BodySmall, color = if (it.blocking) FT.Critical else FT.TextSecondary) }
        }
    }

    val step = r.keyStep
    if (step != null && r.metrics != null) {
        val landing = s.frames[s.clip.fileTimesUs[step.landingIndex]]
        val toeOff = s.frames[s.clip.fileTimesUs[step.toeOffIndex]]
        val stepMetrics = r.metrics.steps.firstOrNull { it.landingMs == step.landingMs }
        FTCard(title = "ONE STEP", info = "The middle step of the measured leg, with the points the analyser read. If the lines do not sit on the body, the numbers below are not to be trusted.") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (landing == null && toeOff == null) Text("Loading the frames…", style = FTType.Caption, color = FT.TextMuted)
                landing?.let {
                    KeyFrame(it, r.tracks, step.landingIndex, r.measuredLabel)
                    Text(
                        listOfNotNull(
                            "LANDING",
                            stepMetrics?.kneeFlexionAtLandingDeg?.let { v -> "KNEE ${v.roundToInt()}°" },
                            stepMetrics?.shinAngleAtLandingDeg?.let { v -> "SHIN ${v.roundToInt()}°" },
                        ).joinToString(" · "),
                        style = FTType.LabelCaps, color = FT.TextSecondary,
                    )
                }
                toeOff?.let {
                    KeyFrame(it, r.tracks, step.toeOffIndex, r.measuredLabel)
                    Text(
                        listOfNotNull("TOE-OFF", stepMetrics?.hipExtensionAtToeOffDeg?.let { v -> "HIP EXTENSION ${v.roundToInt()}°" }).joinToString(" · "),
                        style = FTType.LabelCaps, color = FT.TextSecondary,
                    )
                }
            }
        }
    }

    if (r.metrics != null) {
        FTCard(title = "THE RUN", info = "If the clip was filmed during a run the watch recorded, pick it: the watch's cadence, contact time and vertical ratio then show beside the clip's.") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallLabel("RECORDED ON ${day.format(DateTimeFormatter.ofPattern("d MMM yyyy")).uppercase()}")
                FTDropdown(
                    listOf("none" to "NOT A RECORDED RUN") + runs.map { it.id.toString() to it.label },
                    runId?.toString() ?: "none", "Run",
                ) { k -> runId = k.toLongOrNull() }
                if (runId != null && watch == null) Text("No watch values for this run.", style = FTType.Caption, color = FT.TextMuted)
            }
        }
    }

    if (findings.isNotEmpty()) {
        FTCard(title = "FINDINGS", info = "Each line says what was measured, what it usually means and where that comes from. Where the app draws a line of its own it says so. Compare with your own earlier clips more than with the ranges.") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                findings.forEach { f ->
                    val now = values[f.key]?.median
                    FindingRow(f, since = now?.let { sinceLastTime(f.key, it, previous?.values, previous?.date) })
                }
            }
        }
    }
    error?.let { Text(it, style = FTType.Caption, color = FT.Critical) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.metrics != null) {
            FTButton(
                when { saved -> "SAVED"; saving -> "SAVING…"; else -> "SAVE" },
                enabled = !saved && !saving, emphasis = FTButtonEmphasis.Primary,
            ) {
                saving = true
                error = null
                scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                    repo.save(r, day, runId)
                    saved = true
                    onSaved()
                }
            }
        }
        FTButton("ANALYSE ANOTHER", emphasis = FTButtonEmphasis.Secondary, onClick = onAnother)
        FTButton("DONE", emphasis = FTButtonEmphasis.Tertiary, onClick = onDone)
    }
}

/** Past analyses, newest first: date, where, quality and the main values; each can be deleted after a confirm. */
@Composable
private fun PastAnalyses(history: List<SavedFormAnalysis>, repo: RunningFormRepository, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    FTCard(title = "PAST ANALYSES") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            history.take(12).forEach { a ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOf(
                                a.date.format(DateTimeFormatter.ofPattern("d MMM yyyy")).uppercase(),
                                if (a.surface == FormSurface.TREADMILL) "TREADMILL" else "OUTDOORS",
                                "${a.fps.roundToInt()} FPS",
                                a.quality.uppercase(),
                            ).joinToString(" · "),
                            style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f),
                        )
                        if (confirming != a.id) FTButton("DELETE", emphasis = FTButtonEmphasis.Tertiary) { confirming = a.id }
                    }
                    Text(
                        listOfNotNull(
                            a.values["cadence"]?.let { "${it.median.roundToInt()} spm" },
                            a.values["shin_angle"]?.let { "shin ${it.median.roundToInt()}°" },
                            a.values["knee_landing"]?.let { "knee ${it.median.roundToInt()}°" },
                            a.values["contact_time"]?.let { "${it.median.roundToInt()} ms" },
                            a.footStrike?.lowercase(),
                        ).joinToString(" · ").ifEmpty { "—" },
                        style = FTType.Caption, color = FT.TextSecondary,
                    )
                    if (confirming == a.id) FTConfirmDestructive(
                        message = "Delete this analysis? It cannot be brought back.", confirmLabel = "DELETE", busy = busy,
                        onCancel = { confirming = null },
                    ) {
                        busy = true
                        scope.launchSafe(onError = { busy = false }, onDone = { busy = false; confirming = null }) {
                            repo.delete(a.id)
                            onDeleted()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingRow(f: FormFinding, since: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.title, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f))
            Text(f.value, style = FTType.Value, color = FT.TextPrimary)
        }
        when (f.state) {
            FindingState.WORTH_A_LOOK -> Text("WORTH A LOOK", style = FTType.LabelCaps, color = FT.Warning)
            FindingState.TYPICAL -> Text("TYPICAL", style = FTType.LabelCaps, color = FT.Emerald)
            FindingState.INFO -> Unit
        }
        Text(f.reason, style = FTType.BodySmall, color = FT.TextSecondary)
        since?.let { Text(it, style = FTType.Caption, color = FT.TextPrimary) }
        Text(f.source, style = FTType.Micro, color = FT.TextMuted)
    }
}

/** A frame with the analyser's points: the measured leg bright, the other leg, trunk and near arm faint. */
@Composable
private fun KeyFrame(frame: ArgbFrame, tracks: PoseTracks, index: Int, measured: BodySide) {
    val image = remember(frame) { Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888).asImageBitmap() }
    val near = legOf(measured)
    val far = legOf(measured.other)
    val arm = if (measured == BodySide.LEFT) listOf(Landmark.LEFT_SHOULDER, Landmark.LEFT_ELBOW, Landmark.LEFT_WRIST)
    else listOf(Landmark.RIGHT_SHOULDER, Landmark.RIGHT_ELBOW, Landmark.RIGHT_WRIST)
    Canvas(Modifier.fillMaxWidth().aspectRatio(frame.width.toFloat() / frame.height)) {
        drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        val k = size.width / frame.width
        fun p(j: Int): Offset? {
            val x = tracks.x[j][index]; val y = tracks.y[j][index]
            return if (x.isNaN() || y.isNaN()) null else Offset((x * k).toFloat(), (y * k).toFloat())
        }
        fun mid(a: Int, b: Int): Offset? { val pa = p(a); val pb = p(b); return if (pa == null || pb == null) null else (pa + pb) / 2f }
        fun chain(points: List<Offset?>, color: Color, width: Float) {
            points.zipWithNext().forEach { (a, b) -> if (a != null && b != null) drawLine(color, a, b, width, StrokeCap.Round) }
        }
        val faint = Color.White.copy(alpha = 0.55f)
        val bright = FT.DomainLabs
        chain(listOf(mid(Landmark.LEFT_SHOULDER, Landmark.RIGHT_SHOULDER), mid(Landmark.LEFT_HIP, Landmark.RIGHT_HIP)), faint, 3.dp.toPx())
        chain(arm.map { p(it) }, faint, 2.dp.toPx())
        chain(listOf(far.hip, far.knee, far.ankle, far.heel, far.toe, far.ankle).map { p(it) }, faint, 2.dp.toPx())
        chain(listOf(near.hip, near.knee, near.ankle, near.heel, near.toe, near.ankle).map { p(it) }, bright, 3.5.dp.toPx())
        listOf(near.hip, near.knee, near.ankle, near.heel, near.toe).mapNotNull { p(it) }.forEach { drawCircle(bright, 4.dp.toPx(), it) }
    }
}
