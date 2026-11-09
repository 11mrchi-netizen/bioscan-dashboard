package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ProtocolsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.InterventionNameRow
import com.bioscan.fieldterminal.data.model.ProtocolMemberRow
import com.bioscan.fieldterminal.data.model.ProtocolRow
import com.bioscan.fieldterminal.data.model.ProtocolTemplateRow
import com.bioscan.fieldterminal.domain.ProtocolLifecycleState
import com.bioscan.fieldterminal.domain.isValidProtocolTransition
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Lab › N=1: protocols, the bundles of interventions around one objective ("sleep optimisation: strips + magnesium +
// caffeine cut-off"). Starting from one of the seeded templates is optional; the template is descriptive, never a rule.

private const val PROTOCOLS_INFO =
    "A protocol groups interventions that aim at the same thing. It moves proposed → active → evaluating → completed (or stopped " +
        "at any point), and every change is kept. Evidence is still read per intervention."

@Composable
fun ProtocolsSection() {
    val repo = remember { ProtocolsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var key by remember { mutableIntStateOf(0) }
    var protocols by remember { mutableStateOf<List<ProtocolRow>>(emptyList()) }
    var members by remember { mutableStateOf<List<ProtocolMemberRow>>(emptyList()) }
    var interventions by remember { mutableStateOf<List<InterventionNameRow>>(emptyList()) }
    var templates by remember { mutableStateOf<List<ProtocolTemplateRow>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(key) {
        protocols = repo.loadProtocols()
        members = repo.loadMembers()
        interventions = repo.loadInterventionNames()
        templates = repo.loadTemplates()
    }
    val names = interventions.associate { it.id to it.name }

    FTCard(title = "PROTOCOLS", info = PROTOCOLS_INFO) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (protocols.isEmpty()) {
                Text("No protocols yet. Start from a template or from scratch, then add the interventions that belong to it.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
            protocols.forEach { p ->
                val state = ProtocolLifecycleState.fromKey(p.lifecycleState)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = FTType.Label, color = FT.TextPrimary)
                    Text("aiming at ${p.objectiveOutcomeKey} · ${p.lifecycleState}", style = FTType.Caption, color = FT.TextMuted)
                    members.filter { it.protocolId == p.id }.sortedBy { it.sequenceOrder }.forEach { m ->
                        Text("${if (m.role == "primary") "●" else "○"} ${names[m.interventionId] ?: "an intervention"}", style = FTType.BodySmall, color = FT.TextSecondary)
                    }
                    if (state != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProtocolLifecycleState.entries.filter { isValidProtocolTransition(state, it) }.forEach { next ->
                                FTButton(label = next.key.uppercase()) {
                                    error = null
                                    scope.launchSafe(onError = { e -> error = e.message ?: "Could not change the state" }) { repo.transition(p, next); key++ }
                                }
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            FTButton(label = "NEW PROTOCOL") { creating = true }
        }
    }

    if (creating) ProtocolSheet(templates, interventions, onDismiss = { creating = false }, onCreated = { creating = false; key++ })
}

private val DIRECTIONS = listOf("Increase" to "increase", "Decrease" to "decrease", "Stabilise" to "stabilize")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolSheet(templates: List<ProtocolTemplateRow>, interventions: List<InterventionNameRow>, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { ProtocolsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val series = rememberOutcomeSeries()
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf<String?>(null) }
    var objective by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // A template fills the name and objective in; both can still be changed.
    val picked = templates.firstOrNull { it.templateKey == template }
    val objectiveOptions = (listOfNotNull(picked?.objectiveOutcomeKey) + series.map { it.id }).distinct().map { id -> id to (series.firstOrNull { it.id == id }?.label ?: id) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("NEW PROTOCOL", style = FTType.SectionTitle, color = FT.DomainLabs)
            if (templates.isNotEmpty()) {
                Text("START FROM", style = FTType.LabelCaps, color = FT.TextSecondary)
                TextChipRow(templates.map { it.name }, picked?.name, perRow = 2) { label ->
                    val t = templates.firstOrNull { it.name == label }
                    template = t?.templateKey
                    if (t != null) {
                        if (name.isBlank()) name = t.name
                        objective = t.objectiveOutcomeKey
                    }
                }
                picked?.let { Text(it.description, style = FTType.Caption, color = FT.TextMuted) }
            }
            Text("NAME", style = FTType.LabelCaps, color = FT.TextSecondary)
            FieldTextField(name, { name = it }, "e.g. Sleep optimisation")
            Text("AIMING AT", style = FTType.LabelCaps, color = FT.TextSecondary)
            FTDropdown(objectiveOptions, objective, "Outcome", Modifier.fillMaxWidth()) { objective = it }
            Text("EXPECTED TO", style = FTType.LabelCaps, color = FT.TextSecondary)
            TextChipRow(DIRECTIONS.map { it.first }, DIRECTIONS.firstOrNull { it.second == direction }?.first, perRow = 3) { label -> direction = DIRECTIONS.firstOrNull { it.first == label }?.second }
            Text("INTERVENTIONS (THE FIRST IS THE MAIN ONE)", style = FTType.LabelCaps, color = FT.TextSecondary)
            if (interventions.isEmpty()) Text("Add an intervention first; it can join a protocol later.", style = FTType.Caption, color = FT.TextMuted)
            interventions.forEach { iv ->
                val on = iv.id in chosen
                FTButton(label = (if (on) "✓ " else "") + iv.name.uppercase(), modifier = Modifier.fillMaxWidth()) { if (on) chosen.remove(iv.id) else chosen.add(iv.id) }
            }
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            FTButton(label = if (saving) "SAVING..." else "SAVE PROTOCOL", enabled = name.trim().length >= 2 && objective != null && !saving, emphasis = FTButtonEmphasis.Primary) {
                saving = true
                scope.launch {
                    try {
                        repo.createProtocol(name, objective!!, template, direction, chosen.toList())
                        onCreated()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save"
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}
