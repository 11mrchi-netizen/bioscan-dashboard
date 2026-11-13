package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.data.catching
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import com.bioscan.fieldterminal.domain.RegimenKind
import com.bioscan.fieldterminal.domain.doseTextOn
import com.bioscan.fieldterminal.domain.amountOn
import com.bioscan.fieldterminal.domain.scheduleWords
import com.bioscan.fieldterminal.domain.spec
import com.bioscan.fieldterminal.domain.stateWords
import com.bioscan.fieldterminal.domain.stepsWords
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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
    var reviewing by remember { mutableStateOf(false) }
    // Analysis Layer 3 / Phase 5: supplement N-of-1 experiments.
    var experimenting by remember { mutableStateOf<SupplementRow?>(null) }
    var experiments by remember { mutableStateOf<List<ExperimentRow>>(emptyList()) }
    var experimentResults by remember { mutableStateOf<Map<String, ExperimentResultRow>>(emptyMap()) }

    // Phase 6: curated interaction registry checked against the active roster.
    var interactionRecords by remember { mutableStateOf<List<InteractionRecord>>(emptyList()) }
    var rosterItems by remember { mutableStateOf<List<RosterItem>>(emptyList()) }
    val detections = remember(interactionRecords, rosterItems) { detectInteractions(rosterItems, interactionRecords) }

    // Reference cards: the other active items each FDA label names (owner's name, mention).
    var labelNotes by remember { mutableStateOf<List<Pair<String, com.bioscan.fieldterminal.domain.LabelMention>>>(emptyList()) }

    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val cacheVersion = com.bioscan.fieldterminal.ui.components.rememberCacheVersion()
    LaunchedEffect(reloadKey, cacheVersion) {
        // The kept roster stays on screen while a fresher one loads.
        isLoading = overview == null
        error = null
        try {
            val loaded = SupplementsRepository(SupabaseClientProvider.client).loadOverview()
            overview = loaded
            // Evidence is enrichment: a failed lookup must not hide the roster.
            outcomes = catching { EvidenceRepository(SupabaseClientProvider.client).outcomeTextBySupplement(loaded.active) }
                .getOrDefault(emptyMap())
            // Enrichment too: no registry or a failed lookup just hides the card.
            catching {
                val interactionRepo = InteractionRepository(SupabaseClientProvider.client)
                interactionRecords = interactionRepo.loadRecords()
                rosterItems = interactionRepo.rosterItems(loaded.active)
            }.onFailure { interactionRecords = emptyList(); rosterItems = emptyList() }
            labelNotes = catching {
                val refs = com.bioscan.fieldterminal.data.RegimenReferenceRepository(SupabaseClientProvider.client).loadAll()
                val active = loaded.active.associateBy { it.id }
                refs.flatMap { ref ->
                    val owner = active[ref.supplementId] ?: return@flatMap emptyList()
                    val label = ref.label ?: return@flatMap emptyList()
                    com.bioscan.fieldterminal.domain.labelMentions(label.sections, loaded.active.filter { it.id != owner.id }.map { it.id to it.name })
                        .map { owner.name to it }
                }
            }.getOrDefault(emptyList())
            val expRepo = ExperimentsRepository(SupabaseClientProvider.client)
            // Daily tests of a behaviour live on the Today page; supplements and fueling products here.
            experiments = expRepo.loadExperiments().filter { it.protocol?.behaviour == null }
            experimentResults = expRepo.loadResults()
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }

    when {
        isLoading -> com.bioscan.fieldterminal.ui.components.SkeletonSections()
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
            onDeleteExperiment = { e ->
                scope.launch {
                    catching { ExperimentsRepository(SupabaseClientProvider.client).delete(e.id) }
                        .onSuccess { reloadKey++ }
                        .onFailure { error = it.message ?: "Could not delete the experiment" }
                }
            },
            detections = detections,
            labelNotes = labelNotes,
            onOpenReview = { reviewing = true },
        )
    }
    if (reviewing) RegistryReviewScreen(onClose = { reviewing = false; reloadKey++ })

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
    onDeleteExperiment: (ExperimentRow) -> Unit,
    detections: List<DetectedInteraction>,
    labelNotes: List<Pair<String, com.bioscan.fieldterminal.domain.LabelMention>> = emptyList(),
    onOpenReview: () -> Unit = {},
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
            // Regimen model: an item taken twice a day is listed under both its times; as-needed items have their own group.
            val grouped = TIME_OF_DAY_ORDER.associateWith { slot ->
                overview.active.filter { s -> val sp = s.spec(); if (slot == "as-needed") sp.asNeeded else slot in sp.slots }
            }.filterValues { it.isNotEmpty() }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TIME_OF_DAY_ORDER.forEach { timeOfDay ->
                    val items = grouped[timeOfDay] ?: return@forEach
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(timeOfDay.uppercase(), style = sectionLabelStyle, color = FT.TextSecondary)
                        // App restructure: the time-of-day groups had square corners; they use the module radius like every other block.
                        val groupShape = androidx.compose.foundation.shape.RoundedCornerShape(FT.RadiusModule)
                        Column(modifier = Modifier.fillMaxWidth().clip(groupShape).background(FT.Glass, groupShape).border(FT.BorderWidth, FT.GlassBorder, groupShape)) {
                            items.forEachIndexed { i, s ->
                                ActiveRow(s, outcomes[s.id].orEmpty(), showDivider = i < items.lastIndex, onClick = { onSupplementClick(s) }, onExperiment = { onExperiment(s) })
                            }
                        }
                    }
                }
            }
        }

        InteractionsSection(detections)
        Text(
            "REVIEW THE SOURCES BEHIND THESE ›",
            style = FTType.MonoCaption,
            color = FT.Emerald,
            modifier = Modifier.ftClickable(onClick = onOpenReview).padding(vertical = 4.dp),
        )

        if (labelNotes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("FROM THE LABELS", FT.Warning,
                    info = "What an FDA label says about something else you take, in its own words. Read it; it is not a verdict.")
                labelNotes.forEach { (owner, m) ->
                    Column {
                        Text("$owner's label names ${m.itemName} · ${m.section}", style = FTType.BodySmall, color = FT.TextPrimary)
                        Text("\u201C${m.excerpt}\u201D", style = FTType.Caption, color = FT.TextSecondary)
                    }
                }
            }
        }

        ExperimentsSection(experiments, experimentResults, onDelete = onDeleteExperiment)

        if (overview.ended.isNotEmpty()) {
            SectionLabel("ENDED", FT.TextSecondary)
            Column(modifier = Modifier.fillMaxWidth()) {
                overview.ended.forEachIndexed { i, s ->
                    EndedRow(s, showDivider = i < overview.ended.lastIndex, onClick = { onSupplementClick(s) })
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color, info: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = sectionLabelStyle, color = color)
        Box(Modifier.weight(1f).height(1.dp).background(FT.GlassBorder))
        if (info != null) com.bioscan.fieldterminal.ui.components.InfoHelpButton(title = text, body = info)
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
            val spec = s.spec()
            val today = java.time.LocalDate.now()
            Column(horizontalAlignment = Alignment.End) {
                Text(com.bioscan.fieldterminal.domain.unitsWords(1.0, spec.amountOn(today), spec.unit) ?: spec.doseTextOn(today), style = FTType.Telemetry, color = FT.TextPrimary)
                s.syringeUnits?.let { Text("${com.bioscan.fieldterminal.domain.formatAmount(it)} UNITS", style = FTType.MonoCaption, color = FT.TextSecondary) }
                if (spec.kind != RegimenKind.SUPPLEMENT) {
                    Text(spec.kind.label.uppercase(), style = FTType.MonoCaption, color = FT.DomainLog, modifier = Modifier.padding(top = 3.dp))
                }
                Text(
                    spec.scheduleWords().uppercase(),
                    style = FTType.MonoCaption,
                    color = FT.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
                (spec.stateWords(today) ?: spec.stepsWords()?.takeIf { spec.steps != null })?.let {
                    Text(it, style = FTType.Caption, color = FT.Warning, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(FT.GlassBorder))
        }
    }
}

@Composable
private fun EndedRow(s: SupplementRow, showDivider: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().ftClickable(onClick = onClick).padding(vertical = 12.dp),
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
