package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.DsldVerification
import com.bioscan.fieldterminal.data.ProductVerification
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SuppcoVerification
import com.bioscan.fieldterminal.data.SupplementSourceRepository
import com.bioscan.fieldterminal.domain.DsldComparison
import com.bioscan.fieldterminal.domain.DsldConflictKind
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.launch

private val body = TextStyle(fontFamily = Inter, fontSize = 13.sp)
private val sourceLabel = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.12f.em)

// DAV-359 / DAV-360. Verify a saved product against NIH DSLD and SuppCo by
// barcode. Read-only for the user's data: results are shown and stored as
// provenance snapshots, never applied to the product's ingredients.
@Composable
fun ProductVerifySection(productId: Long) {
    val scope = rememberCoroutineScope()
    val repo = remember { SupplementSourceRepository(SupabaseClientProvider.client) }
    var barcode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProductVerification?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "VERIFY WITH NIH DSLD + SUPPCO",
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
            color = FT.TextSecondary,
        )
        Text(
            "Enter the barcode on the label to compare this product with the NIH label database and SuppCo. " +
                "Your product is not changed; each comparison is saved with its source and date.",
            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
            color = FT.TextSecondary,
        )
        FieldTextField(barcode, { barcode = it; result = null; error = null }, "Barcode (UPC)", keyboardType = KeyboardType.Number)
        AmberButton(label = if (busy) "VERIFYING..." else "VERIFY", enabled = !busy && barcode.filter { it.isDigit() }.length >= 6) {
            busy = true
            error = null
            result = null
            scope.launch {
                try {
                    result = repo.verifyProduct(productId, barcode.trim())
                } catch (e: Exception) {
                    error = e.message ?: "Lookup failed"
                } finally {
                    busy = false
                }
            }
        }
        error?.let { Text("Couldn't verify ($it).", style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.Critical) }
        result?.let { r ->
            DsldBlock(r.dsld, r.dsldError)
            SuppcoBlock(r.suppco, r.suppcoError)
        }
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
                    (p.trust.percentileInCategory?.let { " · ${"%.0f".format(it)}th percentile in its category" } ?: "") +
                    (p.trust.status?.takeIf { it != "complete" }?.let { " · scoring $it" } ?: ""),
                style = body,
                color = FT.TextSecondary,
            )
        }
        if (p.trust.details.isNotEmpty()) {
            Text(p.trust.details.joinToString(" · ") { "${it.category}: ${it.rank}" }, style = body.copy(fontSize = 12.sp), color = FT.TextSecondary)
        }

        // Safety facts are stated in words, never by color alone.
        val flags = buildList {
            if (p.safety.activeFdaRecall) add("ACTIVE FDA RECALL${p.safety.recallBody?.let { ": $it" } ?: ""}")
            if (p.safety.brandActiveFdaRecall) add("ACTIVE FDA RECALL ON ANOTHER PRODUCT FROM THIS BRAND")
            if (p.safety.fdaWarningLetter) add("FDA WARNING LETTER RECEIVED")
            if (p.safety.failedAnyTests) add("FAILED INDEPENDENT TESTS")
            if (p.offMarket == true) add("OFF MARKET")
        }
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
