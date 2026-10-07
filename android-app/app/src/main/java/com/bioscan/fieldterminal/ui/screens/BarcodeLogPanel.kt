package com.bioscan.fieldterminal.ui.screens

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
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementLookupRepository
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.normalizeBarcode
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.scanBarcode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val note = FTType.Caption

private class UnknownBarcode(val code: String, val providerLines: List<String>)

// DAV-362. Log by barcode. A scan (Google code scanner) or a typed barcode is
// resolved to roster items already linked to that barcode; an unknown one shows what
// the providers say it is and lets the user link it to a roster item. Linking
// writes the barcode only -- provider data is applied through the reviewed verify
// flow in the supplement editor, never from here.
@Composable
fun BarcodeLogPanel(roster: List<SupplementRow>, onMatched: (List<SupplementRow>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var unknown by remember { mutableStateOf<UnknownBarcode?>(null) }

    fun resolve(raw: String) {
        val code = normalizeBarcode(raw)
        if (code == null) {
            message = "That is not a valid barcode (expected 8, 12, 13 or 14 digits)."
            unknown = null
            return
        }
        busy = true
        message = null
        unknown = null
        scope.launch {
            try {
                val hits = SupplementsRepository(SupabaseClientProvider.client).activeSupplementsForBarcode(code)
                if (hits.isNotEmpty()) {
                    onMatched(hits)
                    message = "Matched ${hits.joinToString { it.name }}. Check it below, then save."
                } else {
                    val lookup = runCatching { SupplementLookupRepository(SupabaseClientProvider.client).lookupBarcode(code) }.getOrNull()
                    val lines = buildList {
                        lookup?.suppco?.value?.products?.firstOrNull()?.let { add("SuppCo: ${it.name ?: "unnamed"}${it.brand?.let { b -> " · $b" } ?: ""}") }
                        lookup?.dsld?.value?.labels?.firstOrNull()?.let { add("NIH DSLD: ${it.fullName ?: "unnamed"}${it.brandName?.let { b -> " · $b" } ?: ""}") }
                    }
                    unknown = UnknownBarcode(code, lines)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Couldn't look that up (${e.message ?: "error"})."
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "LOG BY BARCODE",
            style = FTType.LabelCaps,
            color = FT.TextSecondary,
        )
        FieldTextField(typed, { typed = it }, "Barcode (UPC)", keyboardType = KeyboardType.Number)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmberButton(label = "SCAN", enabled = !busy) {
                scope.launch {
                    message = null
                    val raw = scanBarcode(context) { message = "Scanner unavailable ($it). Type the barcode instead." }
                    if (raw != null) {
                        typed = raw
                        resolve(raw)
                    }
                }
            }
            AmberButton(label = if (busy) "LOOKING UP..." else "FIND", enabled = !busy && typed.isNotBlank()) { resolve(typed) }
        }
        message?.let { Text(it, style = note, color = FT.TextSecondary) }
        unknown?.let { u ->
            Text("Barcode ${u.code} isn't linked to any of your supplements yet.", style = note.copy(fontWeight = FontWeight.SemiBold), color = FT.TextPrimary)
            u.providerLines.forEach { Text(it, style = note, color = FT.TextSecondary) }
            if (u.providerLines.isEmpty()) Text("Neither NIH DSLD nor SuppCo has a listing for it.", style = note, color = FT.TextSecondary)
            Text("Link it to one of your supplements (only the barcode is saved):", style = note, color = FT.TextSecondary)
            roster.forEach { s ->
                Text(
                    s.name,
                    style = FTType.RowTitle,
                    color = FT.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
                        .clickable(enabled = !busy) {
                            busy = true
                            scope.launch {
                                try {
                                    SupplementsRepository(SupabaseClientProvider.client).linkBarcode(s, u.code)
                                    onMatched(listOf(s))
                                    message = "Linked ${s.name} to ${u.code}. Check it below, then save."
                                    unknown = null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = "Couldn't link it (${e.message ?: "error"})."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}
