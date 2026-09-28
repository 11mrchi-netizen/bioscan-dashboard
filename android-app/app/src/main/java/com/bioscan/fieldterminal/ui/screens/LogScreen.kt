package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.bioscan.fieldterminal.data.LOG_PAGE_SIZE
import com.bioscan.fieldterminal.data.LogRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.LogEntry
import com.bioscan.fieldterminal.domain.LogEntryKind
import com.bioscan.fieldterminal.domain.LogSource
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Log tab: Step 11's unified read feed plus Step 12's "+" add-entry flow and
// its edit/delete counterpart. Real data merged from meals/runs/sleep_daily/
// arousal_daily/stool_log/encounters/notes/hydration_daily -- see
// domain/Log.kt for what's deliberately NOT included (supplement-taken
// confirmations have no backing table) and data/LogRepository.kt for the
// pagination approach Step 11's roadmap text explicitly asked to be decided.
// Tapping "+" opens AddEntrySheet; tapping an existing entry opens
// EntryActionSheet (EDIT/DELETE), and EDIT opens EditEntrySheet (all in
// ui/screens/AddEntrySheet.kt). Any successful save or delete bumps
// `reloadKey` so the feed re-fetches immediately.
@Composable
fun LogScreen(onOpenSessionDetail: (Long) -> Unit) {
    var allEntries by remember { mutableStateOf<List<LogEntry>?>(null) }
    var visibleCount by remember { mutableStateOf(LOG_PAGE_SIZE) }
    var reloadKey by remember { mutableStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<LogEntry?>(null) }
    var editingEntry by remember { mutableStateOf<LogEntry?>(null) }
    var sleepDetailEntryId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        error = null
        try {
            allEntries = LogRepository(SupabaseClientProvider.client).loadAllEntries()
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
    }

    fun refresh() {
        visibleCount = LOG_PAGE_SIZE
        reloadKey += 1
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
            val entries = allEntries
            ScreenHeader(
                title = "LOG",
                context = when {
                    error != null -> "ERROR"
                    entries != null -> "${entries.size} ENTRIES"
                    else -> "LOADING"
                },
            )

            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Failed to load: $error", style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.Critical)
                }
                entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.DomainLog)
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing logged yet.", style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.TextSecondary)
                }
                else -> {
                    val visible = entries.take(visibleCount)
                    val grouped = visible.groupBy { it.timestamp.toLocalDate() }

                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        grouped.forEach { (date, dayEntries) ->
                            DayHeader(date)
                            dayEntries.forEach { entry -> EntryRow(entry, onClick = { actionEntry = entry }) }
                        }
                        if (visibleCount < entries.size) {
                            LoadOlderButton(onClick = { visibleCount += LOG_PAGE_SIZE })
                        }
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp))
                    }
                }
            }
        }

        AddEntryFab(onClick = { showAddSheet = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(22.dp))
    }

    if (showAddSheet) {
        AddEntrySheet(
            onDismiss = { showAddSheet = false },
            onSaved = {
                showAddSheet = false
                refresh()
            },
        )
    }

    actionEntry?.let { entry ->
        EntryActionSheet(
            entry = entry,
            onDismiss = { actionEntry = null },
            onEdit = {
                editingEntry = entry
                actionEntry = null
            },
            onDeleted = {
                actionEntry = null
                refresh()
            },
            onViewDetail = when (entry.source) {
                LogSource.Exercise -> {
                    {
                        actionEntry = null
                        onOpenSessionDetail(entry.id)
                    }
                }
                LogSource.Sleep -> {
                    {
                        actionEntry = null
                        sleepDetailEntryId = entry.id
                    }
                }
                else -> null
            },
        )
    }

    editingEntry?.let { entry ->
        EditEntrySheet(
            entry = entry,
            onDismiss = { editingEntry = null },
            onSaved = {
                editingEntry = null
                refresh()
            },
        )
    }

    sleepDetailEntryId?.let { id ->
        SleepDetailSheet(entryId = id, onDismiss = { sleepDetailEntryId = null })
    }
}

@Composable
private fun AddEntryFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(FT.DomainLog)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+",
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            color = FT.Base,
        )
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> date.format(DateTimeFormatter.ofPattern("dd MMM")).uppercase()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FT.GlassFill)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14.em), color = FT.TextSecondary)
        Text(
            date.format(DateTimeFormatter.ofPattern("dd MMM")).uppercase(),
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14.em),
            color = FT.TextSecondary,
        )
    }
}

@Composable
private fun EntryRow(entry: LogEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            entry.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
            style = TextStyle(fontFamily = RobotoMono, fontSize = 13.sp),
            color = FT.TextSecondary,
            modifier = Modifier.width(42.dp).padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeChip(entry.kind)
                Text(entry.headline, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp), color = FT.TextPrimary)
            }
            entry.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// Category colors per direct user request (2026-09-15): deep blue for
// sleep, orange for activity (runs, strength, ...), green for food/drink/
// supplements, sand for stool, azure for wellness, red for encounter and
// arousal. Note has no assigned category -- stays neutral. OSTRC (added
// later, Category 8) shares stool's sand -- both are the Evaluation Method
// Spec's own "digestive & injury tracking" category. DAV-107 explicitly
// preserves these exact hues ("keep... category colors") even though the
// FT contract's own domain-accent table doesn't define per-entry-type
// colors (only per-section) -- only the font/text-color-for-"filled"
// chips migrated to FT tokens.
@Composable
private fun TypeChip(kind: LogEntryKind) {
    val color = when (kind) {
        LogEntryKind.Exercise -> FieldColors.Orange
        LogEntryKind.Food, LogEntryKind.Drink, LogEntryKind.Supplement -> FieldColors.Green
        LogEntryKind.Sleep -> FieldColors.DeepBlue
        LogEntryKind.Arousal, LogEntryKind.Encounter, LogEntryKind.Masturbation -> FieldColors.Red
        LogEntryKind.Note -> FT.TextSecondary
        LogEntryKind.Stool, LogEntryKind.Ostrc -> FieldColors.Sand
        LogEntryKind.Wellness -> FieldColors.Azure
    }
    val filled = kind in setOf(LogEntryKind.Exercise, LogEntryKind.Food, LogEntryKind.Drink, LogEntryKind.Supplement, LogEntryKind.Stool)
    Box(
        modifier = Modifier
            .background(if (filled) color else Color.Transparent)
            .then(if (!filled) Modifier.border(1.dp, color) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            kind.label,
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 0.14f.em),
            color = if (filled) FT.Base else color,
        )
    }
}

@Composable
private fun LoadOlderButton(onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .border(1.dp, FT.GlassBorder)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("LOAD OLDER", style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em), color = FT.TextSecondary)
        }
    }
}
