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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.LabsOverview
import com.bioscan.fieldterminal.data.LabsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.LabMarkerTheme
import com.bioscan.fieldterminal.domain.MarkerComparison
import com.bioscan.fieldterminal.domain.MarkerDirection
import com.bioscan.fieldterminal.domain.labMarkerTheme
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira

// Step 9 (Phase C). Real data from `lab_draws`/`lab_results` -- NOT a port of
// index.html's Labs panel, which is entirely hardcoded prose with an empty
// draw() (same pattern as Step 7's Training panel). Merges markers across
// the earliest and latest draw by name (domain/Labs.kt); a marker tested in
// only one draw shows "—" for the other, which this account's real data
// genuinely has. See ROADMAP.md P8 Step 9.
@Composable
fun LabsScreen() {
    var overview by remember { mutableStateOf<LabsOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showUploadSheet by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        isLoading = true
        overview = LabsRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
        }
        overview?.latestDraw == null -> Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
            AddResultButtons(onAddClick = { showAddSheet = true }, onUploadClick = { showUploadSheet = true })
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text("No lab draws logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
            }
        }
        else -> LabsContent(overview!!, onAddClick = { showAddSheet = true }, onUploadClick = { showUploadSheet = true })
    }

    if (showAddSheet) {
        LabResultFormSheet(
            onDismiss = { showAddSheet = false },
            onSaved = { showAddSheet = false; reloadKey++ },
        )
    }
    if (showUploadSheet) {
        LabExtractionSheet(
            onDismiss = { showUploadSheet = false },
            onSaved = { showUploadSheet = false; reloadKey++ },
        )
    }
}

@Composable
private fun AddResultButtons(onAddClick: () -> Unit, onUploadClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmberButton(label = "+ UPLOAD REPORT") { onUploadClick() }
            AmberButton(label = "+ ADD RESULT") { onAddClick() }
        }
    }
}

@Composable
private fun LabsContent(overview: LabsOverview, onAddClick: () -> Unit, onUploadClick: () -> Unit) {
    val drawCount = if (overview.earlierDraw != null) 2 else 1
    // DAV-86: collapsed-by-default is the wrong first impression right after
    // this grouped view replaces a flat list -- everything open, matching
    // what was already visible before, lets the user collapse only the
    // themes they don't care about rather than hunting for what disappeared.
    var collapsedThemes by remember { mutableStateOf(setOf<LabMarkerTheme>()) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
        AddResultButtons(onAddClick = onAddClick, onUploadClick = onUploadClick)
        Text(
            text = "BLOODWORK · $drawCount DRAW${if (drawCount == 1) "" else "S"} · ${overview.markers.size} MARKERS",
            style = FieldTextStyles.headerContext,
            color = FieldColors.InkMuted,
            modifier = Modifier.padding(top = 14.dp, bottom = 14.dp),
        )

        // Column header row
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("MARKER", style = FieldTextStyles.subTabLabel, color = FieldColors.InkMuted, modifier = Modifier.weight(1f))
            if (overview.earlierDraw != null) {
                Text(
                    shortDate(overview.earlierDraw.drawDate),
                    style = FieldTextStyles.tabBarLabel,
                    color = FieldColors.InkMuted,
                    modifier = Modifier.width(64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Text(
                shortDate(overview.latestDraw!!.drawDate),
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.1f.em),
                color = FieldColors.Amber,
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Box(modifier = Modifier.width(24.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FieldColors.Hairline))

        val grouped = overview.markers.groupBy { labMarkerTheme(it.name) }
        LabMarkerTheme.entries.forEach { theme ->
            val markers = grouped[theme] ?: return@forEach
            val collapsed = theme in collapsedThemes
            ThemeGroupHeader(
                theme = theme,
                count = markers.size,
                collapsed = collapsed,
                onClick = { collapsedThemes = if (collapsed) collapsedThemes - theme else collapsedThemes + theme },
            )
            if (!collapsed) {
                markers.forEach { marker -> MarkerRow(marker, hasEarlierColumn = overview.earlierDraw != null) }
            }
        }
    }
}

@Composable
private fun ThemeGroupHeader(theme: LabMarkerTheme, count: Int, collapsed: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${theme.label} ($count)",
            style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            color = FieldColors.Amber,
        )
        Text(
            if (collapsed) "▸" else "▾",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp),
            color = FieldColors.Amber,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(FieldColors.Hairline))
}

@Composable
private fun MarkerRow(marker: MarkerComparison, hasEarlierColumn: Boolean) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(marker.name, style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp), color = FieldColors.Ink)
                val refText = formatRef(marker.unit, marker.refLow, marker.refHigh)
                if (refText.isNotEmpty()) {
                    Text(refText, style = TextStyle(fontFamily = Saira, fontSize = 13.sp), color = FieldColors.InkMuted, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (hasEarlierColumn) {
                Text(
                    marker.earlierDisplay ?: "—",
                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 14.5.sp),
                    color = FieldColors.InkMuted,
                    modifier = Modifier.width(64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Text(
                marker.latestDisplay ?: "—",
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 15.5.sp),
                color = flagColor(marker.latestFlag),
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    directionSymbol(marker.direction),
                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp),
                    color = flagColor(marker.latestFlag),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FieldColors.HairlineFaint))
    }
}

private fun flagColor(flag: String?): Color = when (flag) {
    "high", "low" -> FieldColors.Alert
    "watch" -> FieldColors.Amber
    else -> FieldColors.Ink
}

private fun directionSymbol(direction: MarkerDirection): String = when (direction) {
    MarkerDirection.UP -> "▲"
    MarkerDirection.DOWN -> "▼"
    MarkerDirection.FLAT -> "—"
    MarkerDirection.UNKNOWN -> ""
}

private fun formatRef(unit: String?, low: Double?, high: Double?): String {
    val range = when {
        low != null && high != null -> "${trimZero(low)}–${trimZero(high)}"
        high != null -> "<${trimZero(high)}"
        low != null -> ">${trimZero(low)}"
        else -> null
    }
    return listOfNotNull(unit, range).joinToString(" · ")
}

private fun trimZero(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

private fun shortDate(iso: String): String {
    // "2026-04-12" -> "12 APR" -- avoids pulling in a date-formatting
    // dependency for one label.
    val months = listOf("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC")
    val parts = iso.take(10).split("-")
    if (parts.size != 3) return iso
    val month = parts[1].toIntOrNull()?.let { months.getOrNull(it - 1) } ?: parts[1]
    return "${parts[2]} $month"
}
