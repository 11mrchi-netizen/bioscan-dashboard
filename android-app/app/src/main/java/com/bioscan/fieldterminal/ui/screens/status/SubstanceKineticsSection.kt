package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.KineticsRow
import com.bioscan.fieldterminal.data.SubstanceKineticsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.SubstanceCurves
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.launch
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The person's own half-life, peak time and amount per serving for a medication, peptide or hormone (substance_kinetics). The FDA
// label gives a half-life for approved drugs; anything else (a research peptide, a compounded hormone) has none, and the label never
// gives the peak. What is entered here wins over the label and feeds TODAY's IN THE BODY curve. Nothing here suggests a dose.

private val AMOUNT_UNITS = listOf("mg", "mcg", "g", "IU", "units")

@Composable
fun SubstanceKineticsSection(supplementId: Long?) {
    if (supplementId == null) return
    val repo = remember { SubstanceKineticsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var loaded by remember(supplementId) { mutableStateOf(false) }
    var kept by remember(supplementId) { mutableStateOf<KineticsRow?>(null) }
    var halfLife by remember(supplementId) { mutableStateOf("") }
    var unitDays by remember(supplementId) { mutableStateOf(false) }
    var tmax by remember(supplementId) { mutableStateOf("") }
    var perServing by remember(supplementId) { mutableStateOf("") }
    var amountUnit by remember(supplementId) { mutableStateOf("mg") }
    var note by remember(supplementId) { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(supplementId) {
        catching { repo.load(supplementId) }.getOrNull()?.let { k ->
            kept = k
            // Long half-lives (a depot) read better in days.
            unitDays = k.halfLifeHours >= 72
            halfLife = trim(if (unitDays) k.halfLifeHours / 24 else k.halfLifeHours)
            tmax = k.tmaxHours?.let { trim(if (unitDays) it / 24 else it) }.orEmpty()
            perServing = k.amountPerServing?.let { trim(it) }.orEmpty()
            amountUnit = k.amountUnit ?: "mg"
            note = k.note.orEmpty()
        }
        loaded = true
    }
    if (!loaded) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text("YOUR OWN HALF-LIFE AND PEAK", style = FTType.LabelCaps, color = FT.TextSecondary)
        Text(
            "For an item the FDA label does not cover (a peptide, a hormone), or to override the label. It draws this item's curve on TODAY. " +
                "Use a source you trust: a paper, the vendor's sheet or your own estimate, and say which in the note.",
            style = FTType.Caption, color = FT.TextMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (unitDays) "HALF-LIFE (DAYS)" else "HALF-LIFE (HOURS)", style = FTType.Micro, color = FT.TextMuted)
                FieldTextField(halfLife, { halfLife = it }, "e.g. 17.5", keyboardType = KeyboardType.Decimal)
            }
            Column(Modifier.weight(1f)) {
                Text(if (unitDays) "PEAK (DAYS) · OPTIONAL" else "PEAK (HOURS) · OPTIONAL", style = FTType.Micro, color = FT.TextMuted)
                FieldTextField(tmax, { tmax = it }, "assumed ${SubstanceCurves.DEFAULT_TMAX_H.toInt()} h", keyboardType = KeyboardType.Decimal)
            }
        }
        Text(
            if (unitDays) "Entered in days. Switch to hours" else "Entered in hours. Switch to days",
            style = FTType.Caption, color = FT.Ok,
            modifier = Modifier.ftClickable {
                val h = parse(halfLife); val t = parse(tmax)
                // Re-read the same duration in the other unit.
                halfLife = h?.let { trim(if (unitDays) it * 24 else it / 24) }.orEmpty()
                tmax = t?.let { trim(if (unitDays) it * 24 else it / 24) }.orEmpty()
                unitDays = !unitDays
            }.padding(vertical = 4.dp),
        )
        Text("AMOUNT IN ONE SERVING · ONLY IF THE PRODUCT HAS NO INGREDIENT ROW", style = FTType.Micro, color = FT.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { FieldTextField(perServing, { perServing = it }, "e.g. 5", keyboardType = KeyboardType.Decimal) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AMOUNT_UNITS.forEach { u ->
                    Text(u, style = FTType.LabelCaps, color = if (u == amountUnit) FT.TextPrimary else FT.TextMuted,
                        modifier = Modifier.ftClickable { amountUnit = u }.padding(vertical = 6.dp))
                }
            }
        }
        FieldTextField(note, { note = it }, "Where these numbers come from")
        val hours = parse(halfLife)?.let { if (unitDays) it * 24 else it }
        val peak = parse(tmax)?.let { if (unitDays) it * 24 else it }
        val amount = parse(perServing)
        val problem = when {
            halfLife.isBlank() -> null
            hours == null || hours <= 0 || hours > 24 * 365 -> "A half-life is a positive number of hours or days, at most a year."
            tmax.isNotBlank() && (peak == null || peak <= 0 || peak > 24 * 60) -> "The peak time is a positive number of hours or days."
            perServing.isNotBlank() && (amount == null || amount <= 0) -> "The amount in a serving is a positive number."
            else -> null
        }
        (problem ?: message)?.let { Text(it, style = FTType.Caption, color = if (problem != null) FT.Bad else FT.TextMuted) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            FTButton(if (kept == null) "SAVE" else "UPDATE", modifier = Modifier.weight(1f)) {
                if (hours == null || problem != null) { message = "Enter a half-life first."; return@FTButton }
                val row = KineticsRow(supplementId, hours, peak, amount, if (amount != null) amountUnit else null, note.trim().ifBlank { null })
                scope.launch {
                    message = catching { repo.save(row); kept = row; "Saved. TODAY draws it from the next refresh." }.getOrElse { "Couldn't save (${it.message})." }
                }
            }
            if (kept != null) FTButton("REMOVE", emphasis = FTButtonEmphasis.Secondary, modifier = Modifier.weight(1f)) {
                scope.launch {
                    message = catching {
                        repo.remove(supplementId); kept = null; halfLife = ""; tmax = ""; perServing = ""; note = ""
                        "Removed. The FDA label's half-life, if any, is used again."
                    }.getOrElse { "Couldn't remove (${it.message})." }
                }
            }
        }
    }
}

private fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
private fun trim(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
