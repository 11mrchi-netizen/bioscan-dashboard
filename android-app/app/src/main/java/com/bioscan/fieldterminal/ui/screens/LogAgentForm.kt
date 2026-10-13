package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.LogAgentRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.LogProposal
import com.bioscan.fieldterminal.domain.UnparsedEntry
import com.bioscan.fieldterminal.domain.ethanolGrams
import com.bioscan.fieldterminal.domain.isReady
import com.bioscan.fieldterminal.domain.missing
import com.bioscan.fieldterminal.domain.whenLocal
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID

// Analysis Layer 3 / R2 (docs/analysis-layer-3). "Say it": one message, reviewed before anything is written.
//   1. The log-agent Edge Function reads the message into PROPOSED entries; every number in a proposal was stated in the
//      message (the function drops any it cannot find there) and what it could not ground is shown as a field to fill in.
//   2. Nothing is saved until the person presses SAVE: ready, un-skipped proposals are written one by one through the same
//      repositories the forms use, and each card shows SAVED or the error. A failed one stays editable and can be retried.
//   3. Food is not written here: a food proposal opens the normal food form with the text and time filled in.
private val ALCOHOL_PRESETS = listOf("beer 5" to "5", "wine 12" to "12", "spirits 40" to "40")

@Composable
fun LogAgentForm(onSaved: () -> Unit, onFoodHandoff: (text: String, at: LocalDateTime) -> Unit) {
    val scope = rememberCoroutineScope()
    val repo = remember { LogAgentRepository(SupabaseClientProvider.client) }
    var text by remember { mutableStateOf("") }
    // A retry of the same unedited message reuses the turn id, so the server returns the stored proposals (no second model call).
    var turnId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var runId by remember { mutableStateOf<String?>(null) }
    val proposals = remember { mutableStateListOf<LogProposal>() }
    var unparsed by remember { mutableStateOf<List<UnparsedEntry>>(emptyList()) }
    val skipped = remember { mutableStateListOf<String>() }
    val saved = remember { mutableStateListOf<String>() }
    val failures = remember { mutableStateMapOf<String, String>() }
    var committing by remember { mutableStateOf(false) }

    fun update(updated: LogProposal) {
        val i = proposals.indexOfFirst { it.id == updated.id }
        if (i >= 0) proposals[i] = updated
    }

    fun propose() {
        if (text.isBlank() || loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val r = repo.propose(text, turnId)
                runId = r.runId
                proposals.clear(); proposals.addAll(r.proposals)
                unparsed = r.unparsed
                skipped.clear(); saved.clear(); failures.clear()
                if (r.proposals.isEmpty()) error = "Nothing loggable found in that message."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not read that message"
            } finally {
                loading = false
            }
        }
    }

    fun isPending(p: LogProposal) = p.kind != "meal" && p.id !in skipped && p.id !in saved
    val readyToSave = proposals.filter { isPending(it) && it.isReady() }
    val pendingOthers = proposals.any { isPending(it) }

    fun commitAll() {
        if (readyToSave.isEmpty() || committing) return
        committing = true
        val batch = readyToSave.toList()
        scope.launch {
            try {
                for (p in batch) {
                    try {
                        repo.commit(p)
                        saved.add(p.id)
                        failures.remove(p.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures[p.id] = e.message ?: "Save failed"
                    }
                }
                runId?.let { repo.reportCommitted(it, saved.size) }
            } finally {
                committing = false
            }
            val allDone = proposals.none { isPending(it) } && proposals.none { it.kind == "meal" && it.id !in skipped }
            if (allDone) onSaved()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AgentLabel("TELL IT WHAT YOU DID")
        FieldTextField(
            text,
            { text = it; turnId = UUID.randomUUID().toString() },
            "e.g. 500 ml beer 5%, magnesium, energy 7, 20 min sauna",
            singleLine = false,
        )
        AmberButton(label = if (loading) "READING..." else "READ MESSAGE", enabled = text.isNotBlank() && !loading) { propose() }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.size(14.dp))
                Text("Reading...", style = FTType.BodySmall, color = FT.TextSecondary)
            }
        }
        error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }

        proposals.forEach { p ->
            ProposalCard(
                p = p,
                skipped = p.id in skipped,
                saved = p.id in saved,
                error = failures[p.id],
                canHandoff = !pendingOthers,
                onChange = ::update,
                onToggleSkip = { if (p.id in skipped) skipped.remove(p.id) else skipped.add(p.id) },
                onFood = { onFoodHandoff(p.text.orEmpty(), p.whenLocal() ?: LocalDateTime.now()) },
            )
        }

        if (unparsed.isNotEmpty()) {
            Text("Not used:", style = FTType.LabelCaps, color = FT.TextMuted)
            unparsed.forEach { Text("“${it.quote}” (${it.reason})", style = FTType.BodySmall, color = FT.TextMuted) }
        }

        if (proposals.any { it.kind != "meal" }) {
            AmberButton(
                label = if (committing) "SAVING..." else "SAVE ${readyToSave.size}",
                enabled = readyToSave.isNotEmpty() && !committing,
            ) { commitAll() }
            val incomplete = proposals.count { isPending(it) && !it.isReady() }
            if (incomplete > 0) {
                Text("$incomplete entr${if (incomplete == 1) "y needs" else "ies need"} more detail, or skip ${if (incomplete == 1) "it" else "them"}.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
        }
    }
}

@Composable
private fun ProposalCard(
    p: LogProposal,
    skipped: Boolean,
    saved: Boolean,
    error: String?,
    canHandoff: Boolean,
    onChange: (LogProposal) -> Unit,
    onToggleSkip: () -> Unit,
    onFood: () -> Unit,
) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    // Typed text lives here (not derived from the proposal) so "5." survives while the person is still typing a decimal.
    var volText by remember(p.id) { mutableStateOf(p.volumeMl?.let(::trimNumber) ?: "") }
    var abvText by remember(p.id) { mutableStateOf(p.abvPercent?.let(::trimNumber) ?: "") }
    var durText by remember(p.id) { mutableStateOf(p.durationMin?.toString() ?: "") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, if (saved) FT.DomainLog else FT.GlassBorder, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(KIND_LABELS[p.kind] ?: p.kind.uppercase(), style = FTType.LabelCaps, color = FT.DomainLog)
            if (saved) {
                Text("SAVED", style = FTType.LabelCaps, color = FT.DomainLog)
            } else if (p.kind != "meal") {
                Text(
                    if (skipped) "SKIPPED (TAP TO UNDO)" else "SKIP",
                    style = FTType.LabelCaps,
                    color = FT.TextSecondary,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggleSkip() },
                )
            }
        }
        Text(summaryOf(p), style = FTType.Body, color = if (skipped) FT.TextMuted else FT.TextPrimary)
        if (p.quote.isNotBlank()) Text("“${p.quote}”", style = FTType.BodySmall, color = FT.TextMuted)
        p.warnings.forEach { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
        error?.let { Text("Not saved: $it", style = FTType.BodySmall, color = FT.Critical) }
        if (saved || skipped) return@Column

        when (p.kind) {
            "drink" -> {
                if ("volume_ml" in p.needs) {
                    AgentLabel("VOLUME (ML)")
                    FieldTextField(volText, { volText = it; onChange(p.copy(volumeMl = it.toDoubleOrNull())) }, "e.g. 330", keyboardType = KeyboardType.Decimal)
                }
                if (p.beverageClass == "alcohol" && "abv_percent" in p.needs) {
                    AgentLabel("STRENGTH (ABV %)")
                    FieldTextField(abvText, { abvText = it; onChange(p.copy(abvPercent = it.toDoubleOrNull())) }, "e.g. 5", keyboardType = KeyboardType.Decimal)
                    TextChipRow(ALCOHOL_PRESETS.map { it.first }, ALCOHOL_PRESETS.firstOrNull { it.second == abvText }?.first, perRow = 3) { picked ->
                        val preset = ALCOHOL_PRESETS.firstOrNull { it.first == picked }?.second
                        abvText = preset ?: ""
                        onChange(p.copy(abvPercent = preset?.toDouble()))
                    }
                }
            }
            "wellbeing" -> p.needs.filter { dim -> wellbeingOf(p, dim) == null }.forEach { dim ->
                AgentLabel("${dim.uppercase()} (0-10)")
                TextChipRow((0..10).map { it.toString() }, null, perRow = 6) { picked -> picked?.toIntOrNull()?.let { onChange(withDim(p, dim, it)) } }
            }
            "supplement_taken" -> if (p.supplementId == null) {
                if (p.candidates.isEmpty()) {
                    Text("Not in your roster. Add it under Supplements first, or skip.", style = FTType.BodySmall, color = FT.TextSecondary)
                } else {
                    AgentLabel("WHICH ONE?")
                    TextChipRow(p.candidates.map { it.name }, null, perRow = 2) { picked ->
                        p.candidates.firstOrNull { it.name == picked }?.let { onChange(p.copy(supplementId = it.id, supplementName = it.name)) }
                    }
                }
            }
            "meal" -> {
                AmberButton(label = "OPEN FOOD FORM", enabled = canHandoff) { onFood() }
                if (!canHandoff) Text("Save or skip the other entries first.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
            "recovery" -> if (p.durationMin == null) {
                AgentLabel("DURATION (MIN, OPTIONAL)")
                FieldTextField(durText, { durText = it; onChange(p.copy(durationMin = it.toIntOrNull()?.takeIf { m -> m in 1..600 })) }, "e.g. 20", keyboardType = KeyboardType.Number)
            }
        }
        val still = p.missing()
        if (still.isNotEmpty() && p.kind != "meal") Text("Needs: ${still.joinToString(", ")}", style = FTType.BodySmall, color = FT.TextSecondary)
    }
}

@Composable
private fun AgentLabel(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

private val KIND_LABELS = mapOf(
    "drink" to "DRINK", "meal" to "FOOD", "wellbeing" to "WELLNESS", "supplement_taken" to "SUPPLEMENT", "recovery" to "RECOVERY",
)

private fun trimNumber(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

private fun wellbeingOf(p: LogProposal, dim: String): Int? = when (dim) {
    "energy" -> p.energy
    "mood" -> p.mood
    "stress" -> p.stress
    "soreness" -> p.soreness
    else -> null
}

private fun withDim(p: LogProposal, dim: String, v: Int): LogProposal = when (dim) {
    "energy" -> p.copy(energy = v)
    "mood" -> p.copy(mood = v)
    "stress" -> p.copy(stress = v)
    "soreness" -> p.copy(soreness = v)
    else -> p
}

// The server summary is built from what it could ground; recompute it from the (possibly edited) fields so the card always
// shows exactly what will be written, including the ethanol grams.
private fun summaryOf(p: LogProposal): String = when (p.kind) {
    "drink" -> buildString {
        p.volumeMl?.let { append("${trimNumber(it)} ml ") }
        append((p.beverageClass ?: "drink").replace('_', ' '))
        if (p.beverageClass == "alcohol") p.abvPercent?.let { append(" ${trimNumber(it)}%") }
        val g = if (p.beverageClass == "alcohol") ethanolGrams(p.volumeMl ?: 0.0, p.abvPercent ?: 0.0) else null
        if (g != null) append(" = $g g ethanol")
    }
    "wellbeing" -> listOf("energy", "mood", "stress", "soreness")
        .filter { wellbeingOf(p, it) != null || it in p.needs }
        .joinToString(", ") { "$it ${wellbeingOf(p, it) ?: "?"}" }
    "supplement_taken" -> "${if (p.quantity > 1) "${p.quantity} x " else ""}${p.supplementName.orEmpty()}${if (p.supplementId == null) " (which one?)" else ""}"
    "recovery" -> "${p.name.orEmpty()}${p.durationMin?.let { " $it min" } ?: ""}"
    else -> p.summary
}
