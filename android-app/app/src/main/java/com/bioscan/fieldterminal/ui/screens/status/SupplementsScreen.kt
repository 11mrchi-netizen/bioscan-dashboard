package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsOverview
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.supplementOutcome
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono

// Step 8 (Phase C). Real data from `supplements`. Ports index.html's
// isSupplementActive()/supplementOutcome() 1:1 (domain/Supplements.kt) --
// same active-or-ended-within-7-days display filter, same outcome-by-name
// keyword map. Shows every supplement in one condensed list (not distributed
// across body-region panels like the web dashboard does -- that's a
// web-specific decision from an earlier chapter, outside Step 8's own
// "active/ended list, condensed" scope). See ROADMAP.md P8 Step 8.
private val TIME_OF_DAY_ORDER = listOf("morning", "afternoon", "night", "as-needed")
private val sectionLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)

@Composable
fun SupplementsScreen() {
    var overview by remember { mutableStateOf<SupplementsOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    // DAV-81: bumping this re-runs the LaunchedEffect below to reload the
    // roster after add/edit/end -- simpler than threading a repository
    // callback through the sheet just to mutate `overview` in place.
    var reloadKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<SupplementRow?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        isLoading = true
        error = null
        try {
            overview = SupplementsRepository(SupabaseClientProvider.client).loadOverview()
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
            Text("Failed to load: $error", style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.Critical)
        }
        else -> SupplementsContent(
            overview = overview!!,
            onAddClick = { showAddSheet = true },
            onSupplementClick = { editing = it },
        )
    }

    if (showAddSheet) {
        SupplementFormSheet(
            existing = null,
            onDismiss = { showAddSheet = false },
            onSaved = { showAddSheet = false; reloadKey++ },
        )
    }
    editing?.let { row ->
        SupplementFormSheet(
            existing = row,
            onDismiss = { editing = null },
            onSaved = { editing = null; reloadKey++ },
        )
    }
}

@Composable
private fun SupplementsContent(overview: SupplementsOverview, onAddClick: () -> Unit, onSupplementClick: (SupplementRow) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${overview.active.size} ACTIVE · ${overview.ended.size} ENDED",
                style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.08f.em),
                color = FT.TextSecondary,
            )
            AmberButton(label = "+ ADD") { onAddClick() }
        }

        if (overview.active.isEmpty() && overview.ended.isEmpty()) {
            Text("No supplements logged yet.", style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.TextSecondary)
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
                                ActiveRow(s, showDivider = i < items.lastIndex, onClick = { onSupplementClick(s) })
                            }
                        }
                    }
                }
            }
        }

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
private fun ActiveRow(s: SupplementRow, showDivider: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(s.name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.5.sp), color = FT.TextPrimary)
                // DAV-83: the curated outcome map wins where it has a real,
                // research-backed entry -- s.aiNote (a one-off Gemini guess
                // made when this supplement was first added) only fills in
                // for names that map has nothing for, never overwrites it.
                val outcome = supplementOutcome(s.name).ifEmpty { s.aiNote ?: "" }
                if (outcome.isNotEmpty()) {
                    Text(
                        outcome,
                        style = TextStyle(fontFamily = Inter, fontSize = 14.sp),
                        color = FT.TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(s.dose, style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp), color = FT.TextPrimary)
                Text(
                    s.timeOfDay.uppercase(),
                    style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
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
        Text(s.name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp), color = FT.TextSecondary)
        Text(
            s.endDate ?: "",
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
            color = FT.TextSecondary,
        )
    }
}
