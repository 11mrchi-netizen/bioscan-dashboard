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
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsOverview
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.supplementOutcome
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira

// Step 8 (Phase C). Real data from `supplements`. Ports index.html's
// isSupplementActive()/supplementOutcome() 1:1 (domain/Supplements.kt) --
// same active-or-ended-within-7-days display filter, same outcome-by-name
// keyword map. Shows every supplement in one condensed list (not distributed
// across body-region panels like the web dashboard does -- that's a
// web-specific decision from an earlier chapter, outside Step 8's own
// "active/ended list, condensed" scope). See ROADMAP.md P8 Step 8.
private val TIME_OF_DAY_ORDER = listOf("morning", "afternoon", "night", "as-needed")

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

    LaunchedEffect(reloadKey) {
        isLoading = true
        overview = SupplementsRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
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
                style = FieldTextStyles.headerContext,
                color = FieldColors.InkMuted,
            )
            AmberButton(label = "+ ADD") { onAddClick() }
        }

        if (overview.active.isEmpty() && overview.ended.isEmpty()) {
            Text("No supplements logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
            return@Column
        }

        if (overview.active.isNotEmpty()) {
            SectionLabel("ACTIVE", FieldColors.Amber)
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
                        Text(timeOfDay.uppercase(), style = FieldTextStyles.subTabLabel, color = FieldColors.InkMuted)
                        Column(modifier = Modifier.fillMaxWidth().background(FieldColors.RaisedSurface)) {
                            items.forEachIndexed { i, s ->
                                ActiveRow(s, showDivider = i < items.lastIndex, onClick = { onSupplementClick(s) })
                            }
                        }
                    }
                }
            }
        }

        if (overview.ended.isNotEmpty()) {
            SectionLabel("ENDED", FieldColors.InkMuted)
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
        Text(text, style = FieldTextStyles.subTabLabel, color = color)
        Box(Modifier.weight(1f).height(1.dp).background(FieldColors.Hairline))
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
                Text(s.name, style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 16.5.sp), color = FieldColors.Ink)
                val outcome = supplementOutcome(s.name)
                if (outcome.isNotEmpty()) {
                    Text(
                        outcome,
                        style = TextStyle(fontFamily = Saira, fontSize = 14.sp),
                        color = FieldColors.InkMuted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(s.dose, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp), color = FieldColors.Ink)
                Text(
                    s.timeOfDay.uppercase(),
                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                    color = FieldColors.InkMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(FieldColors.Hairline))
        }
    }
}

@Composable
private fun EndedRow(s: SupplementRow, showDivider: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(s.name, style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp), color = FieldColors.InkMuted)
        Text(
            s.endDate ?: "",
            style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
            color = FieldColors.InkMuted,
        )
    }
}
