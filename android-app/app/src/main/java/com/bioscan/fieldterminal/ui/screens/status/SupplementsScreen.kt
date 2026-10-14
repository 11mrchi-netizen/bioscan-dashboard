package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ExperimentResultRow
import com.bioscan.fieldterminal.data.ExperimentRow
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsOverview
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.data.EvidenceRepository
import com.bioscan.fieldterminal.data.InteractionRepository
import com.bioscan.fieldterminal.domain.DetectedInteraction
import com.bioscan.fieldterminal.domain.InteractionRecord
import com.bioscan.fieldterminal.domain.RosterItem
import com.bioscan.fieldterminal.domain.detectInteractions
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter

// Step 8 (Phase C). Real data from `supplements`. Ports index.html's
// isSupplementActive()/supplementOutcome() 1:1 (domain/Supplements.kt) --
// same active-or-ended-within-7-days display filter, same outcome-by-name
// keyword map. Shows every supplement in one condensed list (not distributed
// across body-region panels like the web dashboard does -- that's a
// web-specific decision from an earlier chapter, outside Step 8's own
// "active/ended list, condensed" scope). See ROADMAP.md P8 Step 8.
private val TIME_OF_DAY_ORDER = listOf("morning", "afternoon", "night", "as-needed")
private val sectionLabelStyle = FTType.LabelCaps

@Composable
fun SupplementsScreen(onOpenPantry: () -> Unit = {}) {
    var overview by remember { mutableStateOf<SupplementsOverview?>(null) }
    var outcomes by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    // DAV-81: bumping this re-runs the LaunchedEffect below to reload the
    // roster after add/edit/end -- simpler than threading a repository
    // callback through the sheet just to mutate `overview` in place.
    var reloadKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<SupplementRow?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    // Analysis Layer 3 / Phase 5: supplement N-of-1 experiments.
    var experimenting by remember { mutableStateOf<SupplementRow?>(null) }
    var experiments by remember { mutableStateOf<List<ExperimentRow>>(emptyList()) }
    var experimentResults by remember { mutableStateOf<Map<String, ExperimentResultRow>>(emptyMap()) }

    // Phase 6: curated interaction registry checked against the active roster.
    var interactionRecords by remember { mutableStateOf<List<InteractionRecord>>(emptyList()) }
    var rosterItems by remember { mutableStateOf<List<RosterItem>>(emptyList()) }
    val detections = remember(interactionRecords, rosterItems) { detectInteractions(rosterItems, interactionRecords) }

    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        isLoading = true
        error = null
        try {
            val loaded = SupplementsRepository(SupabaseClientProvider.client).loadOverview()
            overview = loaded
            // Evidence is enrichment: a failed lookup must not hide the roster.
            outcomes = runCatching { EvidenceRepository(SupabaseClientProvider.client).outcomeTextBySupplement(loaded.active) }
                .getOrDefault(emptyMap())
            // Enrichment too: no registry or a failed lookup just hides the card.
            runCatching {
                val interactionRepo = InteractionRepository(SupabaseClientProvider.client)
                interactionRecords = interactionRepo.loadRecords()
                rosterItems = interactionRepo.rosterItems(loaded.active)
            }.onFailure { interactionRecords = emptyList(); rosterItems = emptyList() }
            val expRepo = ExperimentsRepository(SupabaseClientProvider.client)
            experiments = expRepo.loadExperiments()
            experimentResults = expRepo.loadResults()
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }

    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.Emerald)
        }
        error != null -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("Failed to load: $error", style = FTType.BodySmall, color = FT.Critical)
        }
        else -> SupplementsContent(
            overview = overview!!,
            outcomes = outcomes,
            onAddClick = { showAddSheet = true },
            onOpenPantry = onOpenPantry,
            onSupplementClick = { editing = it },
            onExperiment = { experimenting = it },
            experiments = experiments,
            experimentResults = experimentResults,
            detections = detections,
        )
    }

    if (showAddSheet) {
        SupplementFormSheet(
            existing = null,
            roster = rosterItems,
            interactions = interactionRecords,
            onDismiss = { showAddSheet = false },
            onSaved = { showAddSheet = false; reloadKey++ },
        )
    }
    experimenting?.let { row ->
        ExperimentSheet(
            subjectName = row.name,
            register = { name, primary, protocol, preview ->
                ExperimentsRepository(SupabaseClientProvider.client).register(supplementId = row.id, name = name, primary = primary, secondary = emptyList(), protocol = protocol, preview = preview)
            },
            onDismiss = { experimenting = null },
            onRegistered = { experimenting = null; reloadKey++ },
        )
    }
    editing?.let { row ->
        SupplementFormSheet(
            existing = row,
            roster = rosterItems,
            interactions = interactionRecords,
            onDismiss = { editing = null },
            onSaved = { editing = null; reloadKey++ },
        )
    }
}

@Composable
private fun SupplementsContent(
    overview: SupplementsOverview,
    outcomes: Map<Long, String>,
    onAddClick: () -> Unit,
    onOpenPantry: () -> Unit,
    onSupplementClick: (SupplementRow) -> Unit,
    onExperiment: (SupplementRow) -> Unit,
    experiments: List<ExperimentRow>,
    experimentResults: Map<String, ExperimentResultRow>,
    detections: List<DetectedInteraction>,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${overview.active.size} ACTIVE · ${overview.ended.size} ENDED",
                style = FTType.LabelCaps,
                color = FT.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton(label = "PANTRY") { onOpenPantry() }
                FTButton(label = "+ ADD") { onAddClick() }
            }
        }

        if (overview.active.isEmpty() && overview.ended.isEmpty()) {
            Text("No supplements logged yet.", style = FTType.Body, color = FT.TextSecondary)
            return@Column
        }

        if (overview.active.isNotEmpty()) {
            SectionLabel("ACTIVE", FT.Emerald)
            // DAV-82: same morning/afternoon/night/as-needed grouping
            // AddEntrySheet.kt's SupplementsForm already uses for the "log as
            // taken" bundles -- this roster view and that logging view now
            // read as the same mental model instead of a flat list here vs.
            // grouped there.
            val grouped = overview.active.groupBy { it.timeOfDay }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TIME_OF_DAY_ORDER.forEach { timeOfDay ->
                    val items = grouped[timeOfDay] ?: return@forEach
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(timeOfDay.uppercase(), style = sectionLabelStyle, color = FT.TextSecondary)
                        Column(modifier = Modifier.fillMaxWidth().background(FT.GlassFill)) {
                            items.forEachIndexed { i, s ->
                                ActiveRow(s, outcomes[s.id].orEmpty(), showDivider = i < items.lastIndex, onClick = { onSupplementClick(s) }, onExperiment = { onExperiment(s) })
                            }
                        }
                    }
                }
            }
        }

        InteractionsSection(detections)

        ExperimentsSection(experiments, experimentResults)

        if (overview.ended.isNotEmpty()) {
            SectionLabel("ENDED", FT.TextSecondary)
            Column(modifier = Modifier.fillMaxWidth()) {
                overview.ended.forEachIndexed { i, s ->
                    EndedRow(s, showDivider = i < overview.ended.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = sectionLabelStyle, color = color)
        Box(Modifier.weight(1f).height(1.dp).background(FT.GlassBorder))
    }
}

@Composable
private fun ActiveRow(s: SupplementRow, registryOutcome: String, showDivider: Boolean, onClick: () -> Unit, onExperiment: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ftClickable(onClick = onClick)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(s.name, style = FTType.RowTitle, color = FT.TextPrimary)
                // Evidence-registry claims win (each labelled with its review
                // status); s.aiNote (a one-off Gemini guess made when this
                // supplement was first added) only fills in where the registry
                // has nothing, never overwrites it. DAV-83 / DAV-357.
                val outcome = registryOutcome.ifEmpty { s.aiNote ?: "" }
                if (outcome.isNotEmpty()) {
                    Text(
                        outcome,
                        style = FTType.BodySmall,
                        color = FT.TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    "TEST IT (EXPERIMENT)",
                    style = FTType.MonoCaption,
                    color = FT.DomainLog,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .ftClickable(onClick = onExperiment),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(s.dose, style = FTType.Telemetry, color = FT.TextPrimary)
                Text(
                    s.timeOfDay.uppercase(),
                    style = FTType.MonoCaption,
                    color = FT.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(FT.GlassBorder))
        }
    }
}

@Composable
private fun EndedRow(s: SupplementRow, showDivider: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(s.name, style = FTType.RowTitle, color = FT.TextSecondary)
        Text(
            s.endDate ?: "",
            style = FTType.Value,
            color = FT.TextSecondary,
        )
    }
}
