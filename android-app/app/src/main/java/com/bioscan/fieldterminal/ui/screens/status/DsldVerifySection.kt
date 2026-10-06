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
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementSourceRepository
import com.bioscan.fieldterminal.domain.DsldConflictKind
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.launch

// DAV-359. Verify a saved product against NIH DSLD by barcode. Read-only for the
// user's data: the result is shown and stored as a provenance snapshot, never
// applied to the product's ingredients.
@Composable
fun DsldVerifySection(productId: Long) {
    val scope = rememberCoroutineScope()
    val repo = remember { SupplementSourceRepository(SupabaseClientProvider.client) }
    var barcode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DsldVerification?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "VERIFY WITH NIH DSLD",
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
            color = FT.TextSecondary,
        )
        Text(
            "Enter the barcode on the label to compare this product with the NIH label database. " +
                "Your product is not changed; the comparison is saved with its source and date.",
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
                    result = repo.verifyAgainstDsld(productId, barcode.trim())
                } catch (e: Exception) {
                    error = e.message ?: "Lookup failed"
                } finally {
                    busy = false
                }
            }
        }
        error?.let { Text("Couldn't verify ($it).", style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.Critical) }
        result?.let { VerificationResult(it) }
    }
}

@Composable
private fun VerificationResult(v: DsldVerification) {
    val body = TextStyle(fontFamily = Inter, fontSize = 13.sp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = v.label
        if (label == null) {
            Text("No DSLD label found for this barcode. Nothing was compared.", style = body, color = FT.TextSecondary)
            return@Column
        }
        Text(
            "Found: ${label.fullName ?: "Unnamed label"}${label.brandName?.let { " · $it" } ?: ""} (DSLD ${label.dsldId})",
            style = body.copy(fontWeight = FontWeight.SemiBold),
            color = FT.TextPrimary,
        )
        val c = v.comparison
        if (c == null || c.matchedIngredients == 0) {
            Text("No ingredient on this label matched your product's ingredient names, so no amounts were compared.", style = body, color = FT.TextSecondary)
        } else if (c.conflicts.isEmpty()) {
            Text("${c.matchedIngredients} matching ingredient(s) compared; amounts are consistent.", style = body, color = FT.TextSecondary)
        } else {
            Text("${c.conflicts.size} difference(s) found (not applied):", style = body, color = FT.Warning)
            c.conflicts.forEach {
                val what = if (it.kind == DsldConflictKind.UNIT_NOT_COMPARABLE) "units can't be compared" else "amounts differ"
                Text("${it.ingredient}: yours ${it.productText} vs label ${it.dsldText} ($what)", style = body, color = FT.TextSecondary)
            }
        }
        if (c != null && c.dsldOnlyCount > 0) {
            Text("${c.dsldOnlyCount} other label ingredient(s) not in your product.", style = body, color = FT.TextSecondary)
        }
    }
}
