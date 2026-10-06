package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.PantryItem
import com.bioscan.fieldterminal.data.PantryRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.flagTexts
import com.bioscan.fieldterminal.domain.PantryFindingKind
import com.bioscan.fieldterminal.domain.RateBasis
import com.bioscan.fieldterminal.domain.StockEventKind
import com.bioscan.fieldterminal.domain.OBSERVED_WINDOW_DAYS
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

private val label = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)
private val body = TextStyle(fontFamily = Inter, fontSize = 13.sp)
private val strong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)

// DAV-363. What you have, when it runs out, and when to reorder. Everything here is
// derived from recorded purchases and the supplement log (domain/SupplementPantry.kt);
// "no purchase recorded" is a normal state, not an error, and nothing is scored.
@Composable
fun PantryScreen(onBack: () -> Unit) {
    val repo = remember { PantryRepository(SupabaseClientProvider.client) }
    var items by remember { mutableStateOf<List<PantryItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(reload) {
        try {
            items = repo.load()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("PANTRY", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp), color = FT.TextPrimary)
            Text(
                "Remaining stock is your recorded purchases minus the servings you logged since the first one. " +
                    "Run-out dates are estimates from your schedule or recent logging.",
                style = body, color = FT.TextSecondary,
            )
            val list = items
            when {
                error != null && list == null -> Text("Failed to load: $error", style = body, color = FT.Critical)
                list == null -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FT.Emerald) }
                list.isEmpty() -> Text("No supplements with a linked product yet. Add ingredients to a supplement (or verify it by barcode) to track its stock here.", style = body, color = FT.TextSecondary)
                else -> {
                    val attention = list.filter { it.assessment.findings.any { f -> f.kind != PantryFindingKind.NoStock } }
                    val rest = list - attention.toSet()
                    val inStock = rest.filter { it.assessment.remainingServings != null }
                    val noStock = rest.filter { it.assessment.remainingServings == null }
                    val plan = list.filter { it.assessment.reorderBy != null }.sortedBy { it.assessment.reorderBy }

                    if (attention.isNotEmpty()) Section("NEEDS ATTENTION", attention) { openId = it }
                    if (inStock.isNotEmpty()) Section("IN STOCK", inStock) { openId = it }
                    if (noStock.isNotEmpty()) Section("NO STOCK RECORDED", noStock) { openId = it }
                    if (plan.isNotEmpty()) PlanAhead(plan)
                }
            }
        }
    }

    items?.firstOrNull { it.productId == openId }?.let { item ->
        PantryDetailSheet(item, repo, onDismiss = { openId = null }, onChanged = { reload++ })
    }
}

@Composable
private fun Section(title: String, items: List<PantryItem>, onOpen: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = label, color = FT.TextSecondary)
        items.forEach { PantryRow(it) { onOpen(it.productId) } }
    }
}

@Composable
private fun PantryRow(item: PantryItem, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(FT.GlassFill).border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.name, style = strong, color = FT.TextPrimary)
        StockLines(item)
    }
}

@Composable
private fun StockLines(item: PantryItem) {
    val a = item.assessment
    if (a.remainingServings == null) {
        Text("No purchase recorded. Tap to record one.", style = body, color = FT.TextSecondary)
    } else {
        Text(
            "${fmt(a.remainingServings)} servings left" + (a.daysLeft?.let { " · about $it days" } ?: "") + (a.runoutDate?.let { " · runs out $it" } ?: ""),
            style = body, color = FT.TextPrimary,
        )
        Text(
            when {
                a.rateBasis == RateBasis.Scheduled -> "Estimated from your schedule (${fmt(a.dailyRate!!)} per day)."
                a.rateBasis == RateBasis.Observed -> "Estimated from your last $OBSERVED_WINDOW_DAYS days of logging (${"%.2f".format(a.dailyRate)} per day)."
                else -> "No daily rate yet: as-needed supplements need about a week of logs."
            },
            style = body, color = FT.TextSecondary,
        )
        if (a.rateDiffers && a.observedRate != null) {
            Text("You've actually averaged ${"%.2f".format(a.observedRate)} per day recently, different from your schedule.", style = body, color = FT.TextSecondary)
        }
    }
    a.findings.filter { it.kind != PantryFindingKind.NoStock }.forEach {
        val name = when (it.kind) {
            PantryFindingKind.Low -> "LOW"
            PantryFindingKind.ReorderSoon -> "REORDER SOON"
            PantryFindingKind.ExpiresFirst -> "EXPIRES FIRST"
            PantryFindingKind.ProviderFlag -> "SOURCE FLAG"
            PantryFindingKind.NoStock -> ""
        }
        Text("! $name: ${it.reason}", style = body.copy(fontWeight = FontWeight.SemiBold), color = FT.Warning)
    }
}

@Composable
private fun PlanAhead(plan: List<PantryItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PLAN AHEAD", style = label, color = FT.TextSecondary)
        plan.forEach { item ->
            val a = item.assessment
            // Spend is an estimate: SuppCo's listed price per serving x the daily rate x 90 days.
            val spend = item.facts?.product?.pricePerServing?.let { p -> a.dailyRate?.let { r -> p * r * 90 } }
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("Reorder by ${a.reorderBy} · ${item.name}", style = body.copy(fontWeight = FontWeight.SemiBold), color = FT.TextPrimary)
                Text(
                    "Runs out ${a.runoutDate}." + (spend?.let { " Estimated ${"%.0f".format(it)} over the next 90 days at SuppCo's listed price (estimate)." } ?: ""),
                    style = body, color = FT.TextSecondary,
                )
            }
        }
    }
}

private enum class StockMode(val title: String) { Received("RECEIVED"), Count("COUNT"), Discard("DISCARD") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PantryDetailSheet(item: PantryItem, repo: PantryRepository, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<StockMode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var containers by remember { mutableStateOf("1") }
    var perContainer by remember { mutableStateOf(item.servingsPerContainer?.let(::fmt) ?: "") }
    var expires by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf("") }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
                mode = null
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Save failed"
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape, containerColor = FT.Surface, contentColor = FT.TextPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp), color = FT.TextPrimary)
            item.brand?.let { Text(it, style = body, color = FT.TextSecondary) }
            StockLines(item)

            Text("RECORD STOCK", style = label, color = FT.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StockMode.entries.forEach { m -> AmberButton(label = m.title) { mode = if (mode == m) null else m; error = null } }
            }
            when (mode) {
                StockMode.Received -> {
                    Text("Containers received", style = body, color = FT.TextSecondary)
                    FieldTextField(containers, { containers = it }, "1", keyboardType = KeyboardType.Number)
                    Text("Servings per container", style = body, color = FT.TextSecondary)
                    FieldTextField(perContainer, { perContainer = it }, "e.g. 240", keyboardType = KeyboardType.Number)
                    Text("Expires (YYYY-MM-DD, optional)", style = body, color = FT.TextSecondary)
                    FieldTextField(expires, { expires = it }, "2027-06-30")
                    Text("Price paid (optional)", style = body, color = FT.TextSecondary)
                    FieldTextField(price, { price = it }, "", keyboardType = KeyboardType.Number)
                    val n = containers.toDoubleOrNull()
                    val per = perContainer.toDoubleOrNull()
                    val exp = expires.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val expOk = expires.isBlank() || exp != null
                    AmberButton(label = if (busy) "SAVING..." else "SAVE PURCHASE", enabled = !busy && n != null && n > 0 && per != null && per > 0 && expOk) {
                        run {
                            if (item.servingsPerContainer == null) repo.setServingsPerContainer(item.productId, per!!)
                            repo.addEvent(item.productId, StockEventKind.Purchase, n!! * per!!, exp, price.toDoubleOrNull())
                        }
                    }
                    if (!expOk) Text("Use the format YYYY-MM-DD for the expiry date.", style = body, color = FT.Warning)
                }
                StockMode.Count -> {
                    val remaining = item.assessment.remainingServings
                    if (remaining == null) {
                        Text("Record a purchase first; a count adjusts recorded stock.", style = body, color = FT.TextSecondary)
                    } else {
                        Text("Servings you actually have now (recorded: ${fmt(remaining)})", style = body, color = FT.TextSecondary)
                        FieldTextField(count, { count = it }, "", keyboardType = KeyboardType.Number)
                        val target = count.toDoubleOrNull()
                        AmberButton(label = if (busy) "SAVING..." else "SAVE COUNT", enabled = !busy && target != null && target >= 0 && target != remaining) {
                            run { repo.addEvent(item.productId, StockEventKind.Adjustment, target!! - remaining) }
                        }
                    }
                }
                StockMode.Discard -> {
                    Text("Servings to discard (spilled, expired, given away)", style = body, color = FT.TextSecondary)
                    FieldTextField(discard, { discard = it }, "", keyboardType = KeyboardType.Number)
                    val n = discard.toDoubleOrNull()
                    AmberButton(label = if (busy) "SAVING..." else "SAVE DISCARD", enabled = !busy && n != null && n > 0) {
                        run { repo.addEvent(item.productId, StockEventKind.Discard, -n!!) }
                    }
                }
                null -> {}
            }
            error?.let { Text("Couldn't save ($it).", style = body, color = FT.Critical) }

            if (item.events.isNotEmpty()) {
                Text("STOCK HISTORY", style = label, color = FT.TextSecondary)
                item.events.sortedByDescending { it.occurredAt }.forEach { e ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${e.occurredAt.take(10)} · ${e.kind} ${if (e.servings > 0) "+" else ""}${fmt(e.servings)}" + (e.expiresOn?.let { " · expires $it" } ?: ""),
                            style = body, color = FT.TextPrimary, modifier = Modifier.weight(1f),
                        )
                        Text(
                            "REMOVE", style = label, color = FT.TextSecondary,
                            modifier = Modifier.clickable(enabled = !busy) { run { repo.deleteEvent(e.id) } }.padding(8.dp),
                        )
                    }
                }
            }

            ProviderFactsCard(item)
            Box(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProviderFactsCard(item: PantryItem) {
    Text("PRODUCT FACTS", style = label, color = FT.TextSecondary)
    val f = item.facts
    if (f == null) {
        Text("No SuppCo data saved for this product. Open it in the supplement editor and verify it by barcode to add format, serving size, trust score and recall information.", style = body, color = FT.TextSecondary)
        return
    }
    val p = f.product
    Text("SuppCo · retrieved ${f.retrievedAt.take(10)} · data: supp.co", style = body.copy(fontWeight = FontWeight.SemiBold), color = FT.TextPrimary)
    listOfNotNull(
        p.format?.let { "Format: $it" },
        p.servingSize.raw?.let { "Serving size: $it" },
        p.servingsPerContainer?.let { "Servings per container: ${fmt(it)}" },
        p.suggestedUse?.let { "Suggested use: $it" },
        p.validated?.let { "Listing ${if (it) "verified by SuppCo" else "not verified by SuppCo"}" },
        p.trust.product?.let { "Trust score ${"%.1f".format(it)}/10" + (p.trust.percentileInCategory?.let { pc -> " · percentile ${"%.0f".format(pc)} in its category" } ?: "") },
        p.trust.details.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.category}: ${it.rank}" },
        p.testing.certifications.takeIf { it.isNotEmpty() }?.let { "Certifications: ${it.joinToString()}" },
    ).forEach { Text(it, style = body, color = FT.TextSecondary) }
    val flags = p.flagTexts()
    if (flags.isEmpty()) Text("No recall, warning letter, failed test or off-market flag reported at that date.", style = body, color = FT.TextSecondary)
    else flags.forEach { Text("! $it", style = body.copy(fontWeight = FontWeight.SemiBold), color = FT.Warning) }
    Text("To refresh, open this supplement in the editor and verify it by barcode.", style = body, color = FT.TextSecondary)
}
