package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.DSLD_SOURCE_NAME
import com.bioscan.fieldterminal.data.DsldVerification
import com.bioscan.fieldterminal.data.OwnProduct
import com.bioscan.fieldterminal.data.ProductLookup
import com.bioscan.fieldterminal.data.ProductVerification
import com.bioscan.fieldterminal.data.SUPPCO_SOURCE_NAME
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SuppcoVerification
import com.bioscan.fieldterminal.data.model.flagTexts
import com.bioscan.fieldterminal.data.SupplementLookupRepository
import com.bioscan.fieldterminal.data.SupplementSourceRepository
import com.bioscan.fieldterminal.domain.DsldComparison
import com.bioscan.fieldterminal.domain.DsldConflictKind
import com.bioscan.fieldterminal.domain.FactField
import com.bioscan.fieldterminal.domain.ProductFacts
import com.bioscan.fieldterminal.domain.normalizeBarcode
import com.bioscan.fieldterminal.domain.proposeFacts
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.scanBarcode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val body = FTType.BodySmall
private val sourceLabel = FTType.LabelCaps

// DAV-359 / DAV-360 / DAV-362. Verify a saved product against NIH DSLD and SuppCo
// by barcode (scanned, typed, or found by name). Results are shown and stored as
// provenance snapshots; the user's product only changes when they pick fields in
// the review step, and each applied field is recorded on the snapshot.
@Composable
fun ProductVerifySection(productId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SupplementSourceRepository(SupabaseClientProvider.client) }
    var barcode by remember { mutableStateOf("") }
    var own by remember { mutableStateOf<OwnProduct?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProductVerification?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<ProductLookup?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(productId) {
        own = runCatching { repo.ownProduct(productId) }.getOrNull()
        own?.barcode?.let { if (barcode.isEmpty()) barcode = it }
    }

    fun verify(code: String) {
        busy = true
        error = null
        info = null
        result = null
        candidates = null
        scope.launch {
            try {
                result = repo.verifyProduct(productId, code.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Lookup failed"
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "VERIFY WITH NIH DSLD + SUPPCO",
            style = FTType.LabelCaps,
            color = FT.TextSecondary,
        )
        Text(
            "Scan or type the barcode on the label, or search by name, to compare this product with the NIH label database and SuppCo. " +
                "Nothing changes until you choose fields to apply; each comparison is saved with its source and date.",
            style = FTType.Caption,
            color = FT.TextSecondary,
        )
        FieldTextField(barcode, { barcode = it; result = null; error = null; info = null }, "Barcode (UPC)", keyboardType = KeyboardType.Number)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmberButton(label = "SCAN", enabled = !busy) {
                scope.launch {
                    info = null
                    val raw = scanBarcode(context) { info = "Scanner unavailable ($it). Type the barcode instead." }
                    if (raw != null) {
                        barcode = raw
                        verify(raw)
                    }
                }
            }
            AmberButton(label = if (busy) "WORKING..." else "VERIFY", enabled = !busy && barcode.filter { it.isDigit() }.length >= 6) { verify(barcode) }
        }
        AmberButton(label = "FIND BY NAME", enabled = !busy && own != null) {
            busy = true
            error = null
            info = null
            result = null
            scope.launch {
                try {
                    candidates = SupplementLookupRepository(SupabaseClientProvider.client).searchByName(own!!.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "Search failed"
                } finally {
                    busy = false
                }
            }
        }
        info?.let { Text(it, style = body, color = FT.TextSecondary) }
        error?.let { Text("Couldn't verify ($it).", style = FTType.Caption, color = FT.Critical) }
        candidates?.let { c ->
            CandidateList(c, own?.name ?: "") { code ->
                barcode = code
                verify(code)
            }
        }
        result?.let { r ->
            DsldBlock(r.dsld, r.dsldError)
            r.dsld?.let { v -> ApplyPanel(productId, DSLD_SOURCE_NAME, "NIH DSLD", r.yours, v.facts, repo) }
            SuppcoBlock(r.suppco, r.suppcoError)
            r.suppco?.let { v -> ApplyPanel(productId, SUPPCO_SOURCE_NAME, "SuppCo", r.yours, v.facts, repo) }
            val code = normalizeBarcode(barcode)
            if (code != null && code != r.currentBarcode) {
                BarcodePanel(productId, code, r.currentBarcode, repo)
            }
        }
    }
}

// Name search yields candidates, not answers (SuppCo's name search is patchy and DSLD
// hits carry no UPC): picking one resolves its barcode, then the normal verify runs.
@Composable
private fun CandidateList(c: ProductLookup, query: String, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val suppco = c.suppco.value?.products.orEmpty().filter { !it.upc.isNullOrBlank() }.take(5)
    val dsld = c.dsld.value?.candidates.orEmpty().take(5)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Matches for \"$query\" (tap one to verify with its barcode):", style = body, color = FT.TextSecondary)
        if (suppco.isEmpty() && dsld.isEmpty()) Text("No matches from either source. Try the barcode instead.", style = body, color = FT.TextSecondary)
        c.suppco.error?.let { Text("SuppCo unavailable ($it).", style = body, color = FT.Warning) }
        c.dsld.error?.let { Text("DSLD unavailable ($it).", style = body, color = FT.Warning) }
        suppco.forEach { p ->
            CandidateRow("SuppCo · ${p.name ?: "unnamed"}${p.brand?.let { " · $it" } ?: ""}") { onPick(p.upc!!) }
        }
        dsld.forEach { d ->
            CandidateRow("DSLD · ${d.fullName ?: "unnamed"}${d.brandName?.let { " · $it" } ?: ""}${if (d.offMarket == true) " (off market)" else ""}") {
                if (resolving) return@CandidateRow
                resolving = true
                note = null
                scope.launch {
                    try {
                        val upc = SupplementLookupRepository(SupabaseClientProvider.client).dsldLabelById(d.dsldId).value?.labels?.firstOrNull()?.upcSku
                        if (upc.isNullOrBlank()) note = "That DSLD label has no barcode on file." else onPick(upc)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        note = "Couldn't read that label (${e.message ?: "error"})."
                    } finally {
                        resolving = false
                    }
                }
            }
        }
        note?.let { Text(it, style = body, color = FT.Warning) }
    }
}

@Composable
private fun CandidateRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = body,
        color = FT.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

// Review-and-apply for one provider: only facts that the provider states and that
// differ from the user's product are offered, each unchecked by default.
@Composable
private fun ApplyPanel(productId: Long, sourceName: String, label: String, yours: ProductFacts, theirs: ProductFacts, repo: SupplementSourceRepository) {
    val scope = rememberCoroutineScope()
    val proposals = remember(yours, theirs) { proposeFacts(yours, theirs) }
    if (proposals.isEmpty()) return
    var picked by remember(proposals) { mutableStateOf(setOf<FactField>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label SUGGESTS (choose what to apply)", style = sourceLabel, color = FT.TextSecondary)
        proposals.forEach { p ->
            val on = p.field in picked
            Text(
                "${if (on) "[x]" else "[ ]"} ${p.field.label}: yours ${p.yours ?: "not set"} → ${p.theirs}",
                style = body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                color = FT.TextPrimary,
                modifier = Modifier.fillMaxWidth().clickable { picked = if (on) picked - p.field else picked + p.field }.padding(vertical = 6.dp),
            )
        }
        AmberButton(label = if (busy) "APPLYING..." else "APPLY ${picked.size} SELECTED", enabled = !busy && picked.isNotEmpty()) {
            busy = true
            message = null
            scope.launch {
                try {
                    repo.applyFacts(productId, sourceName, theirs, picked)
                    message = "Applied ${picked.joinToString { it.label.lowercase() }} from $label. Reopen the editor to see them."
                    picked = emptySet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Couldn't apply (${e.message ?: "error"})."
                } finally {
                    busy = false
                }
            }
        }
        message?.let { Text(it, style = body, color = FT.TextSecondary) }
    }
}

@Composable
private fun BarcodePanel(productId: Long, code: String, current: String?, repo: SupplementSourceRepository) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("BARCODE", style = sourceLabel, color = FT.TextSecondary)
        Text(
            if (current == null) "This product has no barcode saved. Save $code so scanning it logs this supplement?"
            else "This product's saved barcode is $current. Replace it with $code?",
            style = body,
            color = FT.TextSecondary,
        )
        AmberButton(label = if (busy) "SAVING..." else "SAVE BARCODE", enabled = !busy && message == null) {
            busy = true
            scope.launch {
                try {
                    repo.setBarcode(productId, code)
                    message = "Saved barcode $code."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Couldn't save it (${e.message ?: "error"}). Another product may already use this barcode."
                } finally {
                    busy = false
                }
            }
        }
        message?.let { Text(it, style = body, color = FT.TextSecondary) }
    }
}

@Composable
private fun DsldBlock(v: DsldVerification?, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("NIH DSLD${v?.retrievedAt?.let { " · retrieved ${it.take(10)}" } ?: ""}", style = sourceLabel, color = FT.TextSecondary)
        if (v == null) {
            Text("DSLD unavailable${error?.let { " ($it)" } ?: ""}. The other source's result is still shown.", style = body, color = FT.Warning)
            return@Column
        }
        val label = v.label
        if (label == null) {
            Text("No DSLD label found for this barcode.", style = body, color = FT.TextSecondary)
            return@Column
        }
        Text(
            "${label.fullName ?: "Unnamed label"}${label.brandName?.let { " · $it" } ?: ""} (DSLD ${label.dsldId}" +
                "${label.entryDate?.let { ", entered $it" } ?: ""}${if (label.offMarket == true) ", OFF MARKET" else ""})",
            style = body.copy(fontWeight = FontWeight.SemiBold),
            color = FT.TextPrimary,
        )
        listOfNotNull(
            label.netContents?.let { "Package: $it" },
            label.servingsPerContainer?.let { "Servings per container: ${trim(it)}" },
            label.form?.let { "Form: $it" },
        ).forEach { Text(it, style = body, color = FT.TextSecondary) }
        ComparisonSummary(v.comparison)
    }
}

@Composable
private fun SuppcoBlock(v: SuppcoVerification?, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("SUPPCO${v?.retrievedAt?.let { " · retrieved ${it.take(10)}" } ?: ""} · data: supp.co", style = sourceLabel, color = FT.TextSecondary)
        if (v == null) {
            Text("SuppCo unavailable${error?.let { " ($it)" } ?: ""}. The other source's result is still shown.", style = body, color = FT.Warning)
            return@Column
        }
        val p = v.product
        if (p == null) {
            Text("No SuppCo listing for this barcode.", style = body, color = FT.TextSecondary)
            return@Column
        }
        Text(
            "${p.name ?: "Unnamed listing"}${p.brand?.let { " · $it" } ?: ""}" +
                if (v.listings > 1) " (best of ${v.listings} listings with this barcode)" else "",
            style = body.copy(fontWeight = FontWeight.SemiBold),
            color = FT.TextPrimary,
        )
        listOfNotNull(
            p.format?.let { "Format: $it" },
            p.servingSize.raw?.let { "Serving size: $it" },
            p.servingsPerContainer?.let { "Servings per container: ${trim(it)}" },
            p.suggestedUse?.let { "Suggested use: $it" },
            p.validated?.let { "Listing ${if (it) "verified by SuppCo" else "not verified by SuppCo"}" },
        ).forEach { Text(it, style = body, color = FT.TextSecondary) }

        p.trust.product?.let { score ->
            Text(
                "Trust score ${"%.1f".format(score)}/10" +
                    (p.trust.brand?.let { " (brand ${"%.1f".format(it)})" } ?: "") +
                    (p.trust.percentileInCategory?.let { " · percentile ${"%.0f".format(it)} in its category" } ?: "") +
                    (p.trust.status?.takeIf { it != "complete" }?.let { " · scoring $it" } ?: ""),
                style = body,
                color = FT.TextSecondary,
            )
        }
        if (p.trust.details.isNotEmpty()) {
            Text(p.trust.details.joinToString(" · ") { "${it.category}: ${it.rank}" }, style = body.copy(fontSize = 12.sp), color = FT.TextSecondary)
        }

        // Safety facts are stated in words, never by color alone.
        val flags = p.flagTexts()
        if (flags.isEmpty()) {
            Text("No recall, warning letter, failed test or off-market flag reported.", style = body, color = FT.TextSecondary)
        } else {
            flags.forEach { Text("! $it", style = body.copy(fontWeight = FontWeight.SemiBold), color = FT.Warning) }
        }
        if (p.testing.certifications.isNotEmpty()) {
            Text("Certifications: ${p.testing.certifications.joinToString()}", style = body, color = FT.TextSecondary)
        }
        ComparisonSummary(v.comparison)
    }
}

@Composable
private fun ComparisonSummary(c: DsldComparison?) {
    when {
        c == null || c.matchedIngredients == 0 ->
            Text("No ingredient matched your product's ingredient names, so no amounts were compared.", style = body, color = FT.TextSecondary)
        c.conflicts.isEmpty() ->
            Text("${c.matchedIngredients} matching ingredient(s) compared; amounts are consistent.", style = body, color = FT.TextSecondary)
        else -> {
            Text("${c.conflicts.size} difference(s) found (not applied):", style = body, color = FT.Warning)
            c.conflicts.forEach {
                val what = if (it.kind == DsldConflictKind.UNIT_NOT_COMPARABLE) "units can't be compared" else "amounts differ"
                Text("${it.ingredient}: yours ${it.productText} vs source ${it.dsldText} ($what)", style = body, color = FT.TextSecondary)
            }
        }
    }
    if (c != null && c.dsldOnlyCount > 0) {
        Text("${c.dsldOnlyCount} other ingredient(s) listed by this source are not in your product.", style = body, color = FT.TextSecondary)
    }
}

private fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
