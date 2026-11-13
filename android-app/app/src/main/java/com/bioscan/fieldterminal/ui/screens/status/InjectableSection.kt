package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.InjectablesRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.VialRow
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.RegimenSpec
import com.bioscan.fieldterminal.domain.Vial
import com.bioscan.fieldterminal.domain.daysToDiscard
import com.bioscan.fieldterminal.domain.formatAmount
import com.bioscan.fieldterminal.domain.nextSite
import com.bioscan.fieldterminal.domain.siteLabel
import com.bioscan.fieldterminal.domain.sitesFor
import com.bioscan.fieldterminal.domain.vialState
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// An injectable's sites and vial (domain/Injectables.kt): the last sites used with the one rested longest, and the open
// vial's concentration, doses left and discard date, with the prescribed syringe units cross-checked against the mix.

@Composable
fun InjectableSection(supplementId: Long, spec: RegimenSpec, prescribedUnits: Double?) {
    val repo = remember { InjectablesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<Pair<String, Instant>>>(emptyList()) }
    var vial by remember { mutableStateOf<VialRow?>(null) }
    var used by remember { mutableStateOf(0.0) }
    var editing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(supplementId, reload) {
        history = catching { repo.siteHistory(listOf(supplementId))[supplementId].orEmpty() }.getOrDefault(emptyList())
        vial = catching { repo.openVial(supplementId) }.getOrNull()
        used = vial?.let { v -> catching { repo.usedSince(supplementId, LocalDate.parse(v.openedOn)) }.getOrDefault(0.0) } ?: 0.0
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        error = null
        catching { block() }.onSuccess { editing = false; reload++ }.onFailure { error = it.message ?: "Couldn't save" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("INJECTION SITES", style = FTType.LabelCaps, color = FT.TextSecondary)
        val next = nextSite(sitesFor(spec), history)
        Text("Next: ${next?.label ?: "—"} (rested longest)", style = FTType.BodySmall, color = FT.Emerald)
        if (history.isEmpty()) Text("No sites logged yet: the logging sheet asks for one with each dose.", style = FTType.Caption, color = FT.TextMuted)
        history.take(5).forEach { (site, at) ->
            val d = at.atZone(ZoneId.systemDefault()).toLocalDate()
            Text("${d.dayOfMonth} ${d.month.name.take(3).lowercase()} · ${siteLabel(site)}", style = FTType.Caption, color = FT.TextSecondary)
        }

        Text("VIAL", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        val v = vial
        if (v == null || editing) {
            VialForm(v, spec) { newVial -> act { repo.saveVial(supplementId, v?.id, newVial) } }
            if (editing) Text("CANCEL", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.ftClickable { editing = false })
        } else {
            val st = vialState(v.toVial(), spec.amount, spec.unit, used, prescribedUnits)
            val u = v.amountUnit
            Text("${formatAmount(v.amount)} $u" + (v.diluentMl?.let { " mixed with ${formatAmount(it)} ml" } ?: v.volumeMl?.let { " in ${formatAmount(it)} ml" } ?: "") +
                " · opened ${v.openedOn}", style = FTType.BodySmall, color = FT.TextPrimary)
            Text("${formatAmount(round3(st.perMl))} $u per ml · about ${formatAmount(round3(st.remaining))} $u left" +
                (st.dosesLeft?.let { " · $it of your doses" } ?: ""), style = FTType.Caption, color = FT.TextSecondary)
            daysToDiscard(st, LocalDate.now())?.let { d ->
                Text(if (d < 0) "Past its discard date (${st.discardOn})" else "Discard by ${st.discardOn} ($d days)",
                    style = FTType.Caption, color = if (d <= 3) FT.Warning else FT.TextSecondary)
            }
            if (v.spares > 0) Text("${v.spares} unopened spare${if (v.spares == 1) "" else "s"}", style = FTType.Caption, color = FT.TextSecondary)
            st.doseUnits?.let { units ->
                when {
                    prescribedUnits == null -> Text("Your dose from this mix comes to ${formatAmount(round3(st.doseMl!!))} ml, ${formatAmount(round1(units))} units on a U-100 syringe. " +
                        "Check it against your prescriber's instructions.", style = FTType.Caption, color = FT.TextMuted)
                    st.unitsMismatch -> Text("Check before the next dose: your prescribed ${formatAmount(prescribedUnits)} units and this mix give " +
                        "${formatAmount(round1(units))} units for your ${formatAmount(spec.amount ?: 0.0)} ${spec.unit} dose. Ask your prescriber or pharmacist.",
                        style = FTType.BodySmall, color = FT.Critical)
                    else -> Text("Your prescribed ${formatAmount(prescribedUnits)} units match this mix.", style = FTType.Caption, color = FT.TextMuted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("START THE NEXT VIAL", style = FTType.LabelCaps, color = FT.Emerald, modifier = Modifier.ftClickable { act { repo.startNextVial(v) } })
                Text("EDIT", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.ftClickable { editing = true })
            }
        }
        error?.let { Text(it, style = FTType.Caption, color = FT.Critical) }
    }
}

@Composable
private fun VialForm(existing: VialRow?, spec: RegimenSpec, onSave: (Vial) -> Unit) {
    var amount by remember { mutableStateOf(existing?.amount?.let(::formatAmount) ?: "") }
    var unit by remember { mutableStateOf(existing?.amountUnit ?: (spec.unit?.takeIf { it in listOf("mg", "mcg", "IU", "unit") } ?: "mg")) }
    var water by remember { mutableStateOf(existing?.diluentMl?.let(::formatAmount) ?: "") }
    var volume by remember { mutableStateOf(existing?.volumeMl?.let(::formatAmount) ?: "") }
    var opened by remember { mutableStateOf(existing?.openedOn ?: LocalDate.now().toString()) }
    var keep by remember { mutableStateOf(existing?.discardAfterDays?.toString() ?: "") }
    var spares by remember { mutableStateOf(existing?.spares?.toString() ?: "0") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldTextField(amount, { amount = it }, "holds, e.g. 5", keyboardType = KeyboardType.Decimal, modifier = Modifier.width(110.dp))
        TextChipRow(listOf("mg", "mcg", "IU", "unit"), unit, perRow = 4) { it?.let { u -> unit = u } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldTextField(water, { water = it }, "water added, ml", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        FieldTextField(volume, { volume = it }, "or ready-made volume, ml", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldTextField(opened, { opened = it }, "opened (YYYY-MM-DD)", modifier = Modifier.weight(1f))
        FieldTextField(keep, { keep = it }, "keeps, days", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
        FieldTextField(spares, { spares = it }, "spares", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
    }
    Text("How long a mixed vial keeps is on its label or from your pharmacist.", style = FTType.Caption, color = FT.TextMuted)
    val a = amount.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val w = water.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val vol = volume.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val day = runCatching { LocalDate.parse(opened.trim()) }.getOrNull()
    val ok = a != null && (w != null || vol != null) && day != null
    Text(if (ok) "SAVE THE VIAL" else "Needs what it holds, the water added (or its volume) and the day it was opened",
        style = if (ok) FTType.LabelCaps else FTType.Caption, color = if (ok) FT.Emerald else FT.TextMuted,
        modifier = Modifier.ftClickable(enabled = ok) {
            onSave(Vial(a!!, unit, w, if (w == null) vol else null, day!!, keep.toIntOrNull()?.takeIf { it in 1..365 }, spares.toIntOrNull()?.coerceIn(0, 999) ?: 0))
        })
}

private fun round1(v: Double) = Math.round(v * 10) / 10.0
private fun round3(v: Double) = Math.round(v * 1000) / 1000.0
