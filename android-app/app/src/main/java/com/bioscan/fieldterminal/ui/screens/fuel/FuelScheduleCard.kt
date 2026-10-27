package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.FuelScheduleRepository
import com.bioscan.fieldterminal.data.FuelingProduct
import com.bioscan.fieldterminal.data.FuelingRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementFuelOption
import com.bioscan.fieldterminal.domain.fuel.FuelBlock
import com.bioscan.fieldterminal.domain.fuel.FuelForm
import com.bioscan.fieldterminal.domain.fuel.expandSchedule
import com.bioscan.fieldterminal.domain.fuel.scheduleTotals
import com.bioscan.fieldterminal.domain.fuel.scheduleWarnings
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

// The pre-race fuel schedule (docs/race-fuel-rework/plan.md): what to take, in which form, how much and how often, for a race
// or a long run. Seeded from the planner or started empty, edited block by block, saved as you go. Water and water with
// electrolytes are blocks like any other; a pre-workout or a tablet can come from the supplement catalog.

private fun num(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()

/** "0:30", "1:05", "-0:30": minutes from the start as a clock. */
internal fun clock(min: Int): String = (if (min < 0) "-" else "") + "${abs(min) / 60}:%02d".format(abs(min) % 60)

private fun amount(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)

/** One line saying what a block is: "Gel · 25 g carbs · 10 × every 30 min from 0:30". */
internal fun blockSummary(b: FuelBlock): String {
    val per = listOfNotNull(
        b.carbsG.takeIf { it > 0 }?.let { "${amount(it)} g carbs" },
        b.fluidMl.takeIf { it > 0 }?.let { "${amount(it)} ml" },
        b.sodiumMg.takeIf { it > 0 }?.let { "${amount(it)} mg Na" },
        b.caffeineMg.takeIf { it > 0 }?.let { "${amount(it)} mg caffeine" },
    ).joinToString(" · ").ifBlank { "no nutrients entered" }
    val when_ = if (b.count > 1) "${b.count} × every ${b.intervalMin} min from ${clock(b.startMin)}" else "once at ${clock(b.startMin)}"
    return "$per · $when_"
}

@Composable
fun FuelScheduleCard(
    kind: String,
    refKey: String,
    durationMin: Int,
    toleratedGPerHour: Double,
    seed: (() -> List<FuelBlock>)?,
    modifier: Modifier = Modifier,
) {
    val repo = remember { FuelScheduleRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val blocks = remember(kind, refKey) { mutableStateListOf<FuelBlock>() }
    var loaded by remember(kind, refKey) { mutableStateOf(false) }
    var exists by remember(kind, refKey) { mutableStateOf(false) }
    var seededFrom by remember(kind, refKey) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<FuelBlock?>(null) }
    var isNew by remember { mutableStateOf(false) }

    LaunchedEffect(kind, refKey) {
        try {
            repo.load(kind, refKey)?.let { row ->
                blocks.clear(); blocks.addAll(row.blocks); exists = true; seededFrom = row.seededFrom
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
        loaded = true
    }

    fun persist(from: String?) {
        scope.launch {
            try {
                repo.save(kind, refKey, durationMin, blocks.toList(), from ?: seededFrom)
                exists = true
                if (from != null) seededFrom = from
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describeLoadFailure(e)
            }
        }
    }

    FTCard(title = "FUEL SCHEDULE", modifier = modifier) {
        when {
            !loaded -> Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
            !exists && blocks.isEmpty() -> {
                Text(
                    "What you will take and when: gels, drink mixes, water, water with electrolytes, a pre-workout. After the race, the debrief checks what you took against it.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
                seed?.let { s ->
                    FTButton("START FROM THE PLAN", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth()) {
                        blocks.clear(); blocks.addAll(s()); persist("plan")
                    }
                }
                FTButton("START EMPTY", modifier = Modifier.fillMaxWidth()) { persist("manual") }
            }
            else -> {
                if (blocks.isEmpty()) Text("No blocks yet. Add what you will take.", style = FTType.BodySmall, color = FT.TextSecondary)
                blocks.sortedBy { it.startMin }.forEach { b ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).ftClickable { editing = b; isNew = false },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("${b.label.uppercase()} · ${b.fuelForm.label}", style = FTType.LabelCaps, color = FT.DomainFuel)
                        Text(blockSummary(b), style = FTType.BodySmall, color = FT.TextPrimary)
                    }
                }
                val t = scheduleTotals(blocks.toList(), durationMin)
                if (blocks.isNotEmpty()) {
                    Text(
                        "TOTAL ${t.carbsG.roundToInt()} G CARBS" + (t.carbsPerHour?.let { " (${it.roundToInt()} G/H)" } ?: "") +
                            " · ${"%.1f".format(t.fluidMl / 1000)} L · ${t.sodiumMg.roundToInt()} MG NA" +
                            if (t.caffeineMg > 0) " · ${t.caffeineMg.roundToInt()} MG CAFFEINE" else "",
                        style = FTType.LabelCaps, color = FT.TextPrimary,
                    )
                    Text("${expandSchedule(blocks.toList()).size} intakes over ${clock(durationMin)}", style = FTType.MonoCaption, color = FT.TextSecondary)
                    scheduleWarnings(blocks.toList(), durationMin, toleratedGPerHour).forEach { Text("· $it", style = FTType.Caption, color = FT.Warning) }
                }
                FTButton("ADD A BLOCK", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth()) {
                    editing = FuelBlock(UUID.randomUUID().toString().take(8), "", FuelForm.GEL.key, startMin = 30, intervalMin = 30, count = 1)
                    isNew = true
                }
            }
        }
        error?.let { Text(it, style = FTType.Caption, color = FT.Critical) }
    }

    editing?.let { b ->
        FuelBlockSheet(
            initial = b,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { saved ->
                val i = blocks.indexOfFirst { it.id == saved.id }
                if (i >= 0) blocks[i] = saved else blocks.add(saved)
                editing = null
                persist(null)
            },
            onDelete = {
                blocks.removeAll { it.id == b.id }
                editing = null
                persist(null)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuelBlockSheet(initial: FuelBlock, isNew: Boolean, onDismiss: () -> Unit, onSave: (FuelBlock) -> Unit, onDelete: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var form by remember { mutableStateOf(initial.fuelForm) }
    var label by remember { mutableStateOf(initial.label) }
    var carbs by remember { mutableStateOf(initial.carbsG.takeIf { it > 0 }?.let(::amount).orEmpty()) }
    var fluid by remember { mutableStateOf(initial.fluidMl.takeIf { it > 0 }?.let(::amount).orEmpty()) }
    var sodium by remember { mutableStateOf(initial.sodiumMg.takeIf { it > 0 }?.let(::amount).orEmpty()) }
    var caffeine by remember { mutableStateOf(initial.caffeineMg.takeIf { it > 0 }?.let(::amount).orEmpty()) }
    var start by remember { mutableStateOf(initial.startMin.toString()) }
    var every by remember { mutableStateOf(initial.intervalMin.toString()) }
    var count by remember { mutableStateOf(initial.count.toString()) }
    var fuelingId by remember { mutableStateOf(initial.fuelingProductId) }
    var supplementId by remember { mutableStateOf(initial.supplementProductId) }
    var catalog by remember { mutableStateOf<List<FuelingProduct>>(emptyList()) }
    var supplements by remember { mutableStateOf<List<SupplementFuelOption>>(emptyList()) }

    LaunchedEffect(Unit) {
        catalog = runCatching { FuelingRepository(SupabaseClientProvider.client).loadProducts() }.getOrDefault(emptyList())
        supplements = runCatching { FuelScheduleRepository(SupabaseClientProvider.client).supplementOptions() }.getOrDefault(emptyList())
    }

    // A form's usual shape, applied when the form is picked on a block with nothing entered yet.
    fun applyDefaults(f: FuelForm) {
        if (carbs.isNotBlank() || fluid.isNotBlank() || sodium.isNotBlank() || caffeine.isNotBlank()) return
        when (f) {
            FuelForm.WATER -> { fluid = "250"; if (label.isBlank()) label = "Water" }
            FuelForm.ELECTROLYTE_DRINK -> { fluid = "250"; sodium = "100"; if (label.isBlank()) label = "Water with electrolytes" }
            FuelForm.GEL -> { carbs = "25"; sodium = "50" }
            else -> Unit
        }
    }

    val options = remember(catalog, supplements) {
        catalog.map { "f:${it.product.id}" to "${it.product.name} (${it.product.kind.replace('_', ' ')})" } +
            supplements.map { "s:${it.id}" to "${it.name} (supplement)" }
    }
    val selectedKey = fuelingId?.let { "f:$it" } ?: supplementId?.let { "s:$it" }

    fun pick(key: String) {
        if (key.startsWith("f:")) {
            val p = catalog.firstOrNull { "f:${it.product.id}" == key } ?: return
            fuelingId = p.product.id; supplementId = null
            label = p.product.name
            form = FuelForm.of(p.product.kind)
            val v = p.latest
            carbs = v?.carbsG?.let(::amount).orEmpty(); fluid = v?.fluidMl?.let(::amount).orEmpty()
            sodium = v?.sodiumMg?.let(::amount).orEmpty(); caffeine = v?.caffeineMg?.let(::amount).orEmpty()
        } else {
            val s = supplements.firstOrNull { "s:${it.id}" == key } ?: return
            supplementId = s.id; fuelingId = null
            label = s.name
            form = when (s.servingForm?.lowercase()) { "tablet", "capsule" -> FuelForm.TABLET; "powder" -> FuelForm.PREWORKOUT; else -> form }
            carbs = s.carbsG?.let(::amount).orEmpty(); fluid = ""
            sodium = s.sodiumMg?.let(::amount).orEmpty(); caffeine = s.caffeineMg?.let(::amount).orEmpty()
        }
    }

    val startMin = start.trim().toIntOrNull()
    val intervalMin = every.trim().toIntOrNull()
    val n = count.trim().toIntOrNull()
    val valid = label.isNotBlank() && startMin != null && startMin >= -240 && n != null && n in 1..200 &&
        (n == 1 || (intervalMin != null && intervalMin in 1..600))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isNew) "ADD A BLOCK" else "EDIT BLOCK", style = FTType.SectionTitle, color = FT.DomainFuel)
            Column {
                SheetLabel("FORM")
                TextChipRow(FuelForm.entries.map { it.label }, form.label, perRow = 3) { picked ->
                    FuelForm.entries.firstOrNull { it.label == picked }?.let { form = it; applyDefaults(it) }
                }
            }
            if (options.isNotEmpty()) {
                Column {
                    SheetLabel("FROM YOUR CATALOG (OPTIONAL)")
                    FTDropdown(options, selectedKey, "Pick a product to fill the amounts") { pick(it) }
                }
            }
            Column { SheetLabel("NAME"); FieldTextField(label, { label = it }, "e.g. Maurten gel") }
            Text("In ONE intake:", style = FTType.Caption, color = FT.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { SheetLabel("CARBS G"); FieldTextField(carbs, { carbs = it }, "0", keyboardType = KeyboardType.Decimal) }
                Column(Modifier.weight(1f)) { SheetLabel("FLUID ML"); FieldTextField(fluid, { fluid = it }, "0", keyboardType = KeyboardType.Decimal) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { SheetLabel("SODIUM MG"); FieldTextField(sodium, { sodium = it }, "0", keyboardType = KeyboardType.Decimal) }
                Column(Modifier.weight(1f)) { SheetLabel("CAFFEINE MG"); FieldTextField(caffeine, { caffeine = it }, "0", keyboardType = KeyboardType.Decimal) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { SheetLabel("FIRST AT (MIN)"); FieldTextField(start, { start = it }, "30", keyboardType = KeyboardType.Number) }
                Column(Modifier.weight(1f)) { SheetLabel("EVERY (MIN)"); FieldTextField(every, { every = it }, "30", keyboardType = KeyboardType.Number) }
                Column(Modifier.weight(1f)) { SheetLabel("HOW MANY"); FieldTextField(count, { count = it }, "1", keyboardType = KeyboardType.Number) }
            }
            Text("Minutes from the start; a minus sign is before it (a pre-workout at -30).", style = FTType.Caption, color = FT.TextSecondary)
            if (valid) {
                val preview = FuelBlock(initial.id, label, form.key, startMin!!, intervalMin ?: 0, n!!, num(carbs) ?: 0.0, num(fluid) ?: 0.0, num(sodium) ?: 0.0, num(caffeine) ?: 0.0)
                Text("${n} intake${if (n == 1) "" else "s"}, ${amount(preview.carbsG * n)} g carbs, ${amount(preview.fluidMl * n)} ml, last at ${clock(preview.endMin)}", style = FTType.LabelCaps, color = FT.TextPrimary)
            }
            FTButton("SAVE BLOCK", enabled = valid, emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth()) {
                onSave(
                    FuelBlock(
                        id = initial.id, label = label.trim(), form = form.key, startMin = startMin!!, intervalMin = if (n!! > 1) intervalMin!! else 0, count = n,
                        carbsG = num(carbs) ?: 0.0, fluidMl = num(fluid) ?: 0.0, sodiumMg = num(sodium) ?: 0.0, caffeineMg = num(caffeine) ?: 0.0,
                        fuelingProductId = fuelingId, supplementProductId = supplementId,
                    ),
                )
            }
            if (!isNew) FTButton("DELETE BLOCK", modifier = Modifier.fillMaxWidth()) { onDelete() }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, color = FT.TextMuted, style = FTType.LabelCaps, modifier = Modifier.padding(bottom = 6.dp))
}
