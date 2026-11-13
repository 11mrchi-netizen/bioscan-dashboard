package com.bioscan.fieldterminal.ui.screens.shoes

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bioscan.fieldterminal.data.PersonalRecordsRepository
import com.bioscan.fieldterminal.data.ShoeDraft
import com.bioscan.fieldterminal.data.ShoesRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.shoePhotoBitmap
import com.bioscan.fieldterminal.data.shoePhotoFrom
import com.bioscan.fieldterminal.domain.RecordEvent
import com.bioscan.fieldterminal.domain.RecordKey
import com.bioscan.fieldterminal.domain.RecordKind
import com.bioscan.fieldterminal.domain.Shoe
import com.bioscan.fieldterminal.domain.ShoeAssignment
import com.bioscan.fieldterminal.domain.ShoeRecord
import com.bioscan.fieldterminal.domain.ShoeRun
import com.bioscan.fieldterminal.domain.ShoeStats
import com.bioscan.fieldterminal.domain.ShoeUse
import com.bioscan.fieldterminal.domain.ShoeWear
import com.bioscan.fieldterminal.domain.UnknownGear
import com.bioscan.fieldterminal.domain.assignShoe
import com.bioscan.fieldterminal.domain.formatRecordTime
import com.bioscan.fieldterminal.domain.levels.formatPace
import com.bioscan.fieldterminal.domain.shoe
import com.bioscan.fieldterminal.domain.shoeStats
import com.bioscan.fieldterminal.domain.unknownGear
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTChevron
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.StatTileSpec
import com.bioscan.fieldterminal.ui.components.TileGrid
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.util.PendingCameraCapture
import com.bioscan.fieldterminal.util.createCameraCaptureUri
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Shoes (docs/zepp-integration/03-stress-days-and-shoes.md, domain/Shoes.kt). Setup › TRAINING SETUP › SHOES lists the
// shoes and the Zepp codes no shoe has yet (each matched with a tap); a run's SUMMARY shows its shoes (an unknown code
// is matched from there too); a shoe's page shows its km, wear, the records set in it and its runs.

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")
private val SHORT_DAY = DateTimeFormatter.ofPattern("d MMM")

/** Shoes, runs and the records set over all runs. */
data class ShoesData(val shoes: List<Shoe>, val runs: List<ShoeRun>, val records: List<RecordEvent>) {
    fun stats(shoe: Shoe): ShoeStats = shoeStats(shoe, runs, shoes, records)
    val unknown: List<UnknownGear> get() = unknownGear(runs, shoes)
}

suspend fun loadShoesData(): ShoesData {
    val repo = ShoesRepository(SupabaseClientProvider.client)
    val shoes = repo.loadShoes()
    val runs = repo.loadRuns()
    // The personal records (v_personal_records): each time one moved, past its first. The last of each still stands.
    val records = catching {
        val sets = PersonalRecordsRepository(SupabaseClientProvider.client).loadSets()
        val lastOf = sets.groupBy { it.key }.mapValues { (_, v) -> v.maxBy { it.date } }
        sets.mapNotNull { s ->
            val key = RecordKey.of(s.key) ?: return@mapNotNull null
            val prev = s.previousValue ?: return@mapNotNull null
            val sid = s.sessionId ?: return@mapNotNull null
            val unit = when (key.kind) {
                RecordKind.TIME -> "time"
                RecordKind.KM -> "km"
                RecordKind.METRES -> "m"
            }
            RecordEvent(if (key.kind == RecordKind.TIME) "Fastest ${key.label}" else key.label, sid, s.date, s.value, unit, prev, lastOf[s.key] === s)
        }
    }.getOrDefault(emptyList())
    return ShoesData(shoes, runs, records)
}

private fun km(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
private fun shortCode(code: String) = code.take(8) + "…"
private fun wearColor(w: ShoeWear) = when (w) {
    ShoeWear.FRESH -> FT.Ok
    ShoeWear.WORN -> FT.Warn
    ShoeWear.DUE -> FT.Bad
}

private fun recordValue(r: ShoeRecord): String = when (r.unit) {
    "km" -> "${km(r.value)} km"
    "m" -> "${r.value.roundToInt()} m"
    "min/km" -> "${formatPace(r.value)} /km"
    "time" -> formatRecordTime(r.value)
    else -> "%.1f %s".format(r.value, r.unit)
}

private fun recordPrevious(r: ShoeRecord): String = when (r.unit) {
    "km" -> "${km(r.previous)} km"
    "m" -> "${r.previous.roundToInt()} m"
    "min/km" -> formatPace(r.previous)
    "time" -> formatRecordTime(r.previous)
    else -> "%.1f".format(r.previous)
}

// ---- The icon -------------------------------------------------------------------------------------------------------

/** The shoe's photo in a circle; without one, its initials. [unknown]: a code no shoe has yet. */
@Composable
fun ShoeIcon(shoe: Shoe?, size: Dp, unknown: Boolean = false) {
    val bitmap = remember(shoe?.photoJpeg) { shoePhotoBitmap(shoe?.photoJpeg)?.asImageBitmap() }
    Box(
        Modifier.size(size).clip(CircleShape).background(FT.Glass, CircleShape).border(FT.BorderWidth, if (unknown) FT.Warn else FT.GlassBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = shoe?.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else {
            val initials = when {
                unknown || shoe == null -> "?"
                else -> shoe.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
            }
            Text(initials, style = if (size >= 72.dp) FTType.MetricMedium else FTType.LabelCaps, color = if (unknown) FT.Warn else FT.TextSecondary)
        }
    }
}

@Composable
private fun WearBar(stats: ShoeStats, modifier: Modifier = Modifier) {
    val share = stats.wearShare.coerceIn(0.0, 1.0).toFloat()
    val shape = RoundedCornerShape(3.dp)
    Box(modifier.fillMaxWidth().height(6.dp).clip(shape).background(FT.GlassBorder, shape)) {
        if (share > 0f) Box(Modifier.fillMaxWidth(share).height(6.dp).background(wearColor(stats.wear), shape))
    }
}

// ---- Setup › SHOES --------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShoesCard(onOpenShoe: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val repo = remember { ShoesRepository(SupabaseClientProvider.client) }
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<ShoesData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<ShoeFormRequest?>(null) }
    var showRetired by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        scope.launchSafe(onError = { error = describeLoadFailure(it) }) { data = loadShoesData(); error = null }
    }

    FTCard(
        title = "SHOES",
        info = "Pick the shoes on the watch or in the Zepp app before a run, and Zepp tags the run with a code for them. " +
            "Zepp sends only the code, no name, so say here once which shoes each code is. A shoe's distance is the km " +
            "it had when you added it plus every run in it; it's due once it reaches its retirement distance. Bike " +
            "rides carry no code.",
    ) {
        error?.let { Text("Couldn't load shoes: $it", style = FTType.BodySmall, color = FT.Critical) }
        val d = data
        if (d == null) {
            if (error == null) Text("Loading…", style = FTType.BodySmall, color = FT.TextMuted)
            return@FTCard
        }
        val active = d.shoes.filter { !it.retired }
        val retired = d.shoes.filter { it.retired }
        if (d.shoes.isEmpty()) Text("No shoes yet. Add them, or name a code Zepp has sent below.", style = FTType.BodySmall, color = FT.TextSecondary)
        active.forEach { ShoeRow(d.stats(it)) { onOpenShoe(it.id) } }
        if (retired.isNotEmpty()) {
            Text(
                (if (showRetired) "HIDE RETIRED" else "RETIRED (${retired.size})"), style = FTType.LabelCaps, color = FT.TextSecondary,
                modifier = Modifier.ftClickable { showRetired = !showRetired }.padding(vertical = 6.dp),
            )
            if (showRetired) retired.forEach { ShoeRow(d.stats(it)) { onOpenShoe(it.id) } }
        }
        FTButton("ADD SHOES", modifier = Modifier.fillMaxWidth()) { form = ShoeFormRequest(null, null, null) }

        val unknown = d.unknown
        if (unknown.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("CODES FROM ZEPP TO MATCH", style = FTType.Micro, color = FT.TextMuted)
            unknown.forEach { g ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShoeIcon(null, 36.dp, unknown = true)
                        Column(Modifier.weight(1f)) {
                            Text("Code ${shortCode(g.gearId)}", style = FTType.RowTitle, color = FT.TextPrimary)
                            Text(
                                "${g.runs} runs · ${km(g.km)} km · ${g.first.format(SHORT_DAY)} – ${g.last.format(SHORT_DAY)}" +
                                    if (g.trailRuns > 0) " · ${g.trailRuns} on trail" else "",
                                style = FTType.Caption, color = FT.TextSecondary,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        active.forEach { s ->
                            ShoeChip("IS ${s.name.uppercase()}") {
                                scope.launchSafe(onError = { error = describeLoadFailure(it) }) { repo.matchCode(s.id, g.gearId); reload++ }
                            }
                        }
                        ShoeChip("NEW SHOES", primary = true) {
                            form = ShoeFormRequest(null, g.gearId, if (g.trailRuns * 2 > g.runs) ShoeUse.TRAIL else ShoeUse.ROAD)
                        }
                    }
                }
            }
        }
    }

    form?.let { req ->
        ShoeFormSheet(req, onDismiss = { form = null }, onSaved = { form = null; reload++ })
    }
}

@Composable
private fun ShoeRow(stats: ShoeStats, onClick: () -> Unit) {
    val s = stats.shoe
    Row(
        Modifier.fillMaxWidth().ftClickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShoeIcon(s, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.name, style = FTType.RowTitle, color = FT.TextPrimary)
            Text(
                "${ShoeUse.of(s.use).label} · ${km(stats.totalKm)} of ${s.retireKm.roundToInt()} km · " + if (s.retired) "Retired" else stats.wear.label,
                style = FTType.Caption, color = FT.TextSecondary,
            )
            if (!s.retired) WearBar(stats)
        }
        FTChevron(tint = FT.TextSecondary)
    }
}

@Composable
private fun ShoeChip(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Text(
        text, style = FTType.LabelCaps, color = if (primary) FT.PlatinumInk else FT.TextSecondary,
        modifier = Modifier
            .border(FT.BorderWidth, if (primary) Color.Transparent else FT.GlassBorder, shape)
            .background(if (primary) FT.PlatinumSolid else Color.Transparent, shape)
            .ftClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ---- The form -------------------------------------------------------------------------------------------------------

/** Edit [shoe], or add one (with a Zepp [code] and [use] to start from). */
data class ShoeFormRequest(val shoe: Shoe?, val code: String?, val use: ShoeUse?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoeFormSheet(req: ShoeFormRequest, onDismiss: () -> Unit, onSaved: () -> Unit, onDeleted: () -> Unit = onSaved) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ShoesRepository(SupabaseClientProvider.client) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val s = req.shoe
    var name by remember { mutableStateOf(s?.name.orEmpty()) }
    var use by remember { mutableStateOf(s?.use?.let { ShoeUse.of(it) } ?: req.use ?: ShoeUse.ROAD) }
    var code by remember { mutableStateOf(s?.zeppGearId ?: req.code) }
    var startKm by remember { mutableStateOf(s?.startKm?.takeIf { it > 0 }?.let { km(it) }.orEmpty()) }
    var retireKm by remember { mutableStateOf((s?.retireKm ?: 700.0).roundToInt().toString()) }
    var retired by remember { mutableStateOf(s?.retiredOn != null) }
    var notes by remember { mutableStateOf(s?.notes.orEmpty()) }
    var photo by remember { mutableStateOf(s?.photoJpeg) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    fun takeFrom(uri: Uri?) {
        if (uri == null) return
        val p = shoePhotoFrom(context, uri)
        if (p != null) photo = p else error = "That photo couldn't be read."
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        PendingCameraCapture.clear(context)
        if (ok) takeFrom(pendingUri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) createCameraCaptureUri(context).also { pendingUri = it; takePicture.launch(it) } else error = "Camera permission denied"
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> takeFrom(uri) }

    val start = startKm.replace(',', '.').trim().ifEmpty { "0" }.toDoubleOrNull()
    val retire = retireKm.trim().toDoubleOrNull()
    val ready = name.isNotBlank() && start != null && start in 0.0..10000.0 && retire != null && retire in 100.0..5000.0

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (s == null) "ADD SHOES" else "EDIT SHOES", style = FTType.SectionTitle, color = FT.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShoeIcon(Shoe(0, name.ifBlank { "?" }, photoJpeg = photo), 72.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FTButton("TAKE PHOTO") {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            createCameraCaptureUri(context).also { pendingUri = it; takePicture.launch(it) }
                        } else cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FTButton("CHOOSE") { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        if (photo != null) FTButton("REMOVE") { photo = null }
                    }
                }
            }
            Label("NAME")
            FieldTextField(name, { name = it }, "e.g. Pegasus 41")
            Label("FOR")
            TextChipRow(ShoeUse.entries.map { it.label.uppercase() }, use.label.uppercase(), perRow = 4) { picked ->
                use = ShoeUse.entries.firstOrNull { it.label.uppercase() == picked } ?: use
            }
            Label("ZEPP CODE")
            if (code != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(code!!, style = FTType.Micro, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                    Text("CLEAR", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.ftClickable { code = null }.padding(6.dp))
                }
            } else {
                Text("None yet: match one from Setup › SHOES or from a run in these shoes.", style = FTType.Caption, color = FT.TextMuted)
            }
            Label("KM ALREADY ON THEM WHEN ADDED")
            FieldTextField(startKm, { startKm = it }, "0", keyboardType = KeyboardType.Decimal)
            Label("RETIRE AT KM")
            FieldTextField(retireKm, { retireKm = it }, "700", keyboardType = KeyboardType.Number)
            Label("STATUS")
            TextChipRow(listOf("IN USE", "RETIRED"), if (retired) "RETIRED" else "IN USE", perRow = 2) { retired = it == "RETIRED" }
            Label("NOTE (OPTIONAL)")
            FieldTextField(notes, { notes = it }, "", singleLine = false)

            error?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Critical) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton("CANCEL", modifier = Modifier.weight(1f), onClick = onDismiss)
                FTButton(if (saving) "SAVING…" else "SAVE", enabled = ready && !saving, emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) {
                    saving = true
                    val draft = ShoeDraft(
                        name = name, use = use.code, zeppGearId = code, startKm = start ?: 0.0, retireKm = retire ?: 700.0,
                        startedOn = s?.startedOn ?: LocalDate.now(),
                        retiredOn = if (retired) (s?.retiredOn ?: LocalDate.now()) else null,
                        notes = notes, photoJpeg = photo,
                    )
                    scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) { repo.save(s?.id, draft); onSaved() }
                }
            }
            if (s != null) {
                if (!confirmDelete) {
                    Text("DELETE", style = FTType.LabelCaps, color = FT.Critical, modifier = Modifier.ftClickable { confirmDelete = true }.padding(vertical = 6.dp))
                } else {
                    Text("Delete ${s.name}? Its runs stay; they lose these shoes.", style = FTType.BodySmall, color = FT.TextSecondary)
                    FTButton("DELETE FOR GOOD", modifier = Modifier.fillMaxWidth()) {
                        scope.launchSafe(onError = { error = describeLoadFailure(it) }) { repo.delete(s.id); onDeleted() }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = FTType.Micro, color = FT.TextMuted)

// ---- A run's shoes (the run SUMMARY) ---------------------------------------------------------------------------------

/**
 * The run's shoes, right of the distance: the photo and the name, a tap opens the shoe's page. A code no shoe has
 * yet, or no shoes, opens the sheet to say which shoes they were. Shows nothing for a session the shoes don't count.
 */
@Composable
fun RunShoeBadge(sessionId: Long, onOpenShoe: (Long) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<ShoesData?>(null) }
    var sheet by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId, reload) { scope.launchSafe(onError = { }) { data = loadShoesData() } }
    val d = data ?: return
    val run = d.runs.firstOrNull { it.sessionId == sessionId } ?: return
    val a = assignShoe(run, d.shoes)
    val shoe = a.shoe
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.ftClickable { if (shoe != null) onOpenShoe(shoe.id) else sheet = true }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when (a) {
                        is ShoeAssignment.Unknown -> "UNKNOWN SHOES"
                        ShoeAssignment.None -> "+ SHOES"
                        else -> shoe!!.name.uppercase()
                    },
                    style = FTType.LabelCaps, color = if (a is ShoeAssignment.None) FT.TextSecondary else FT.TextPrimary, textAlign = TextAlign.End, maxLines = 2,
                )
                if (shoe != null) d.stats(shoe).let { Text("${km(it.totalKm)} km", style = FTType.Caption, color = FT.TextSecondary) }
                if (a is ShoeAssignment.Unknown) Text("Tap to name", style = FTType.Caption, color = FT.Warn)
            }
            if (a !is ShoeAssignment.None) ShoeIcon(shoe, 48.dp, unknown = a is ShoeAssignment.Unknown)
        }
        if (shoe != null) {
            Text("CHANGE", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.ftClickable { sheet = true }.padding(horizontal = 4.dp, vertical = 2.dp))
        }
    }
    if (sheet) RunShoesSheet(run, d, onDismiss = { sheet = false }, onDone = { sheet = false; reload++ })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RunShoesSheet(run: ShoeRun, data: ShoesData, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repo = remember { ShoesRepository(SupabaseClientProvider.client) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var error by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<ShoeFormRequest?>(null) }
    val a = assignShoe(run, data.shoes)
    val active = data.shoes.filter { !it.retired }
    val code = run.gearId
    val codeShoe = code?.let { c -> data.shoes.firstOrNull { it.zeppGearId == c } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SHOES FOR THIS RUN", style = FTType.SectionTitle, color = FT.TextPrimary)
            fun act(block: suspend () -> Unit) = scope.launchSafe(onError = { error = describeLoadFailure(it) }) { block(); onDone() }

            if (code != null && codeShoe == null) {
                Text(
                    "Zepp tagged this run with shoes code ${shortCode(code)} and no shoe has it yet. Say which shoes it is, " +
                        "and every run with this code counts for them.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    active.forEach { s -> ShoeChip("IS ${s.name.uppercase()}") { act { repo.matchCode(s.id, code); if (run.chosenShoeId != null) repo.choose(run.sessionId, null) } } }
                    ShoeChip("NEW SHOES", primary = true) { form = ShoeFormRequest(null, code, if (run.trail) ShoeUse.TRAIL else ShoeUse.ROAD) }
                }
            } else if (code != null) {
                Text("Zepp: ${codeShoe!!.name} (code ${shortCode(code)}).", style = FTType.BodySmall, color = FT.TextSecondary)
            } else {
                Text("No shoes were picked on the watch for this run.", style = FTType.BodySmall, color = FT.TextSecondary)
            }

            Text("JUST THIS RUN", style = FTType.Micro, color = FT.TextMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                active.forEach { s ->
                    val on = a is ShoeAssignment.Chosen && a.shoe.id == s.id
                    ShoeChip(s.name.uppercase(), primary = on) { if (!on) act { repo.choose(run.sessionId, s.id) } }
                }
                if (code == null) ShoeChip("NEW SHOES") { form = ShoeFormRequest(null, null, if (run.trail) ShoeUse.TRAIL else ShoeUse.ROAD) }
            }
            if (run.chosenShoeId != null) {
                FTButton(if (code != null) "USE ZEPP'S SHOES" else "NO SHOES", modifier = Modifier.fillMaxWidth()) { act { repo.choose(run.sessionId, null) } }
            }
            error?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Critical) }
            Spacer(Modifier.height(8.dp))
        }
    }
    form?.let { req -> ShoeFormSheet(req, onDismiss = { form = null }, onSaved = { form = null; onDone() }) }
}

// ---- A shoe's page --------------------------------------------------------------------------------------------------

@Composable
fun ShoeScreen(shoeId: Long, onBack: () -> Unit, onOpenSession: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<ShoesData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { scope.launchSafe(onError = { error = describeLoadFailure(it) }) { data = loadShoesData() } }
    val shoe = data?.shoes?.firstOrNull { it.id == shoeId }

    com.bioscan.fieldterminal.ui.screens.detail.DetailScreen(shoe?.name?.uppercase() ?: "SHOES", onBack, eyebrow = "SHOES", ranged = false, domain = FT.Domain.Load) {
        error?.let { Text("Couldn't load: $it", style = FTType.BodySmall, color = FT.Critical) }
        val d = data
        if (d == null) return@DetailScreen
        if (shoe == null) {
            EmptyLine("These shoes are gone.")
            return@DetailScreen
        }
        val st = remember(d, shoe) { d.stats(shoe) }

        FTCard(title = "DISTANCE") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShoeIcon(shoe, 96.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(km(st.totalKm), style = FTType.DisplayMetric, color = FT.TextPrimary)
                        Text("KM", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text(
                        "of ${shoe.retireKm.roundToInt()} km · " + if (shoe.retired) "Retired ${shoe.retiredOn!!.format(DAY)}" else st.wear.label,
                        style = FTType.BodySmall, color = if (shoe.retired) FT.TextSecondary else wearColor(st.wear),
                    )
                    WearBar(st)
                }
            }
            val line = listOfNotNull(
                ShoeUse.of(shoe.use).label,
                shoe.startKm.takeIf { it > 0 }?.let { "${km(it)} km on them when added" },
                st.first?.let { f -> "runs ${f.format(SHORT_DAY)} – ${st.last!!.format(DAY)}" },
            ).joinToString(" · ")
            Text(line, style = FTType.Caption, color = FT.TextSecondary)
            TileGrid(
                listOfNotNull(
                    StatTileSpec("RUNS", "${st.runs.size}", secondary = "${km(st.runKm)} km run in them"),
                    st.longest?.let { StatTileSpec("LONGEST", km(it.distanceKm), "km", secondary = it.date.format(DAY)) },
                    st.runs.takeIf { it.isNotEmpty() }?.let { StatTileSpec("AVERAGE RUN", km(st.runKm / it.size), "km") },
                    st.records.count { it.standing }.takeIf { it > 0 }?.let { StatTileSpec("RECORDS HELD", "$it", secondary = "set in these shoes") },
                ),
            )
            shoe.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            FTButton("EDIT", modifier = Modifier.fillMaxWidth()) { editing = true }
        }

        FTCard(
            title = "RECORDS SET IN THEM",
            info = "A run in these shoes that set one of your personal records (ME › PERFORMANCE › RECORDS): the fastest 1K to 100K " +
                "inside a run, the longest run, the longest trail run or the biggest climb.",
        ) {
            if (st.records.isEmpty()) EmptyLine("No records set in these shoes yet.")
            st.records.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().ftClickable { onOpenSession(r.sessionId) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = FTType.RowTitle, color = FT.TextPrimary)
                        Text(
                            "${r.date.format(DAY)} · beat ${recordPrevious(r)}" + if (r.standing) " · still your best" else "",
                            style = FTType.Caption, color = FT.TextSecondary,
                        )
                    }
                    Text(recordValue(r), style = FTType.MetricMedium, color = FT.TextPrimary)
                    FTChevron(tint = FT.TextSecondary)
                }
            }
        }

        FTCard(title = "RUNS") {
            if (st.runs.isEmpty()) EmptyLine("No runs in these shoes yet.")
            st.runs.take(30).forEach { r ->
                Row(
                    Modifier.fillMaxWidth().ftClickable { onOpenSession(r.sessionId) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(r.date.format(DAY), style = FTType.Body, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                    if (r.trail) Text("TRAIL", style = FTType.Micro, color = FT.TextMuted)
                    Text("${km(r.distanceKm)} km", style = FTType.Body, color = FT.TextSecondary)
                    FTChevron(tint = FT.TextSecondary)
                }
            }
            if (st.runs.size > 30) Text("and ${st.runs.size - 30} earlier runs", style = FTType.Caption, color = FT.TextMuted)
        }
    }
    if (editing && shoe != null) {
        ShoeFormSheet(ShoeFormRequest(shoe, null, null), onDismiss = { editing = false }, onSaved = { editing = false; reload++ }, onDeleted = { editing = false; onBack() })
    }
}
