package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.foundation.border
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.domain.training.movementKey
import com.bioscan.fieldterminal.domain.training.generate.GenEquipment
import com.bioscan.fieldterminal.domain.training.PercentBase
import com.bioscan.fieldterminal.data.updateBlockAccessories
import com.bioscan.fieldterminal.data.loadBlockForEnding
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.BlockForEnding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.training.definition.LoadDto
import com.bioscan.fieldterminal.domain.training.definition.NumRange
import com.bioscan.fieldterminal.domain.training.generate.ALL_SESSIONS
import com.bioscan.fieldterminal.domain.training.generate.Accessory
import com.bioscan.fieldterminal.domain.training.generate.accessoryLine
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.FlowField
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowPickGrid
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.FlowTile
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Accessory work (Nov 2026): exercises added to a strength module's sessions on top of the book's slots. Used by the
// planner (DETAILS › ACCESSORY WORK) and by a block under way (Training › Plan › ACCESSORIES). One page per session,
// "every session" first; a page holds up to MAX_PER_SESSION exercises so it never scrolls.

internal const val MAX_PER_SESSION = 5

internal val COMMON_ACCESSORIES = listOf(
    "Curl", "Hammer Curl", "Triceps Pushdown", "Lateral Raise", "Face Pull", "Rear Delt Fly", "Calf Raise", "Leg Curl",
    "Back Extension", "Hanging Leg Raise", "Ab Wheel", "Plank", "Farmer Carry", "Nordic Curl", "Tibialis Raise", "Pallof Press",
)

/** The accessories of one module, edited session by session; [onSave] gets the whole map (empty lists dropped). */
@Composable
internal fun AccessoriesFlow(
    title: String,
    sessions: List<Pair<String, String>>,
    initial: Map<String, List<Accessory>>,
    onClose: () -> Unit,
    onSave: (Map<String, List<Accessory>>) -> Unit,
    saveLabel: String = "DONE",
    saving: Boolean = false,
    error: String? = null,
    intro: String? = null,
) {
    val all = listOf(ALL_SESSIONS to "Every session") + sessions
    var draft by remember { mutableStateOf(initial) }
    // The exercise being edited: session id and index (-1 adds one).
    var editing by remember { mutableStateOf<Pair<String, Int>?>(null) }
    FTFlow(
        title = title, pages = all.map { (_, label) -> FlowPage(label.uppercase()) }, state = rememberFlowState(), onClose = onClose,
        canSave = !saving, saving = saving, saveLabel = saveLabel, wash = FT.DomainTraining, error = error,
        onSave = { onSave(draft.filterValues { it.isNotEmpty() }) },
    ) { page ->
        val (id, label) = all[page]
        val list = draft[id].orEmpty()
        FlowQuestion(
            if (id == ALL_SESSIONS) "Extra work in every session" else "Extra work on ${label}",
            if (page == 0) intro ?: "Added after the program's own exercises. Full in normal and peak weeks, half the sets in deload and taper weeks, left out of test weeks."
            else "On top of the every-session work.",
        )
        if (list.isEmpty()) Text("Nothing added.", style = FTType.BodySmall, color = FT.TextSecondary)
        list.forEachIndexed { i, a ->
            AccessoryRow(a, onEdit = { editing = id to i }, onRemove = { draft = draft + (id to list.filterIndexed { j, _ -> j != i }) })
        }
        if (list.size < MAX_PER_SESSION) FTButton("+ ADD AN EXERCISE", emphasis = FTButtonEmphasis.Secondary) { editing = id to -1 }
        else Text("$MAX_PER_SESSION is the most per session.", style = FTType.Caption, color = FT.TextMuted)
    }
    editing?.let { (id, i) ->
        val current = draft[id].orEmpty()
        AccessoryEditor(
            initial = current.getOrNull(i),
            onClose = { editing = null },
            onSave = { a ->
                draft = draft + (id to if (i in current.indices) current.mapIndexed { j, x -> if (j == i) a else x } else current + a)
                editing = null
            },
        )
    }
}

@Composable
private fun AccessoryRow(a: Accessory, onEdit: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Row(
        Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, shape).ftClickable(shape = shape, onClickLabel = "Edit ${a.exercise}", onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.exercise, style = FTType.BodySmall, color = FT.TextPrimary, maxLines = 1)
            Text(accessoryLine(a) + (a.note?.let { " · $it" } ?: ""), style = FTType.MonoCaption, color = FT.TextSecondary, maxLines = 1)
        }
        Text("REMOVE", style = FTType.Micro, color = FT.TextSecondary, modifier = Modifier.ftClickable(onClickLabel = "Remove ${a.exercise}", onClick = onRemove).padding(6.dp))
    }
}

private val LOAD_KINDS = listOf("rpe" to "RPE", "pct_1rm" to "% OF 1RM", "fixed" to "KG", "bodyweight" to "BODYWEIGHT", "none" to "NO LOAD")

private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

/** One accessory: what (a common one or typed), then sets, reps and load. */
@Composable
internal fun AccessoryEditor(initial: Accessory?, onClose: () -> Unit, onSave: (Accessory) -> Unit) {
    var name by remember { mutableStateOf(initial?.exercise ?: "") }
    var sets by remember { mutableStateOf(initial?.sets ?: 3) }
    var repsLo by remember { mutableStateOf(initial?.reps?.min?.let { fmt(it) } ?: "10") }
    var repsHi by remember { mutableStateOf(initial?.reps?.max?.let { fmt(it) } ?: "12") }
    var kind by remember { mutableStateOf(initial?.load?.kind ?: "rpe") }
    var value by remember {
        mutableStateOf(initial?.load?.let { l -> (l.rpe ?: l.value ?: l.kg)?.let { fmt(it) } } ?: "8")
    }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    val lo = num(repsLo)
    val hi = num(repsHi) ?: lo
    val repsOk = lo == null && repsHi.isBlank() || lo != null && hi != null && lo > 0 && hi >= lo
    val v = num(value)
    val loadOk = when (kind) { "rpe" -> v != null && v in 1.0..10.0; "pct_1rm" -> v != null && v > 0 && v <= 100; "fixed" -> v != null && v >= 0; else -> true }
    val ok = name.isNotBlank() && repsOk && loadOk
    val pages = listOf(FlowPage("EXERCISE", ready = name.isNotBlank()), FlowPage("SETS AND LOAD", ready = repsOk && loadOk))
    FTFlow(
        title = if (initial == null) "ADD AN EXERCISE" else "EDIT ${initial.exercise.uppercase()}", pages = pages, state = rememberFlowState(), onClose = onClose,
        canSave = ok, saveLabel = if (initial == null) "ADD" else "SAVE", wash = FT.DomainTraining,
        onSave = {
            val load = when (kind) {
                "rpe" -> LoadDto("rpe", rpe = v); "pct_1rm" -> LoadDto("pct_1rm", value = v); "fixed" -> LoadDto("fixed", kg = v)
                else -> LoadDto(kind)
            }
            onSave(Accessory(name.trim(), sets, lo?.let { NumRange(it, hi ?: it) }, load, null, note.trim().ifEmpty { null }))
        },
    ) { page ->
        if (page == 0) {
            FlowQuestion("Which exercise?", "Pick a common one or type any name.")
            FieldTextField(name, { name = it }, "Exercise")
            FlowPickGrid(COMMON_ACCESSORIES.map { it to it }, setOf(name.trim()), { name = it }, columns = 2, perPage = 8, single = true)
        } else {
            FlowField("SETS") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..6).forEach { n -> FlowTile("$n", sets == n, Modifier.weight(1f)) { sets = n } }
                }
            }
            FlowField("REPS (OR SECONDS), A RANGE OR ONE NUMBER") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(90.dp)) { FieldTextField(repsLo, { repsLo = it }, "from", keyboardType = KeyboardType.Number) }
                    Text("to", style = FTType.BodySmall, color = FT.TextSecondary)
                    Box(Modifier.width(90.dp)) { FieldTextField(repsHi, { repsHi = it }, "to", keyboardType = KeyboardType.Number) }
                }
            }
            FlowField("LOAD") {
                LOAD_KINDS.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (k, l) -> FlowTile(l, kind == k, Modifier.weight(1f)) { kind = k; if (k == "pct_1rm" && (v ?: 0.0) <= 10) value = "65"; if (k == "rpe" && (v ?: 0.0) > 10) value = "8" } }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
                if (kind in setOf("rpe", "pct_1rm", "fixed")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(110.dp)) { FieldTextField(value, { value = it }, if (kind == "rpe") "RPE" else if (kind == "fixed") "kg" else "%", keyboardType = KeyboardType.Decimal) }
                        Text(
                            when (kind) { "rpe" -> "RPE (1-10)"; "fixed" -> "kg"; else -> "% of the 1RM: needs a max for this exercise" },
                            style = FTType.Caption, color = if (loadOk) FT.TextSecondary else FT.Warn,
                        )
                    }
                }
            }
            FieldTextField(note, { note = it }, "Note (optional): tempo, superset with…")
        }
    }
}

/** Accessory work of a block under way: changes the strength sessions still planned from today. */
@Composable
internal fun BlockAccessoriesSheet(blockId: Long, onDismiss: () -> Unit, onDone: () -> Unit) {
    val supabase = SupabaseClientProvider.client
    val repo = remember { TrainingProgramRepository(supabase) }
    val scope = rememberCoroutineScope()
    var block by remember { mutableStateOf<BlockForEnding?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(blockId) { scope.launchSafe(onError = { error = describeLoadFailure(it) }) { block = loadBlockForEnding(supabase, blockId) } }
    val b = block
    val m = b?.module
    if (b == null || m == null) {
        FTFlow(title = "ACCESSORY WORK", pages = listOf(FlowPage("")), state = rememberFlowState(), onClose = onDismiss, canSave = false, onSave = {}, wash = FT.DomainTraining, error = error) {
            Text(
                when { error != null -> ""; b == null -> "Loading the block…"; else -> "This block has no strength program, so there is nothing to add accessory work to." },
                style = FTType.BodySmall, color = FT.TextSecondary,
            )
        }
        return
    }
    AccessoriesFlow(
        title = "ACCESSORY WORK · ${b.row.name.uppercase()}", sessions = m.sessions.map { it.id to it.label }, initial = b.choice.accessories,
        onClose = onDismiss, saveLabel = "APPLY FROM TODAY", saving = saving, error = error,
        intro = "Changes the ${m.title} sessions still planned from today; past and confirmed sessions stay as they were. Half the sets in deload weeks.",
        onSave = { map ->
            saving = true
            scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                val settings = repo.loadSettings()
                val maxes = repo.loadMaxes()
                val eq = GenEquipment(platesKg = settings.platesKg, weightedBase = if (settings.weightedPercentBase == "total") PercentBase.TotalLoad else PercentBase.AddedLoad)
                updateBlockAccessories(supabase, b, map, LocalDate.now(), eq) { maxes[movementKey(it)]?.entry }
                onDone()
            }
        },
    )
}

private fun fmt(x: Double) = if (x % 1.0 == 0.0) x.toLong().toString() else x.toString()
