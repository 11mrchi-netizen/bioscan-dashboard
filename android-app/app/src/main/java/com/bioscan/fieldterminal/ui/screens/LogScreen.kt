package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.LOG_PAGE_DAYS
import com.bioscan.fieldterminal.data.LogFeed
import com.bioscan.fieldterminal.data.LogRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.LogCategory
import com.bioscan.fieldterminal.domain.LogEntry
import com.bioscan.fieldterminal.domain.LogDay
import com.bioscan.fieldterminal.domain.LogEntryKind
import com.bioscan.fieldterminal.domain.LogMark
import com.bioscan.fieldterminal.domain.LogSource
import com.bioscan.fieldterminal.domain.DayMark
import com.bioscan.fieldterminal.domain.MarkState
import com.bioscan.fieldterminal.domain.buildLogDays
import com.bioscan.fieldterminal.domain.category
import com.bioscan.fieldterminal.domain.supplementExpectedOn
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.components.SubTabChip
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
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
    var feed by remember { mutableStateOf<LogFeed?>(null) }
    var visibleDays by remember { mutableStateOf(LOG_PAGE_DAYS) }
    var reloadKey by remember { mutableStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<LogEntry?>(null) }
    var editingEntry by remember { mutableStateOf<LogEntry?>(null) }
    var sleepDetailEntryId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Days are collapsed unless opened; the open set and the filter survive switching tabs (kept as a string so it is saveable).
    var expandedKeys by rememberSaveable { mutableStateOf("") }
    var gapsOnly by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        error = null
        try {
            feed = LogRepository(SupabaseClientProvider.client).loadFeed()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
    }

    fun refresh() {
        visibleDays = LOG_PAGE_DAYS
        reloadKey += 1
    }

    // Everything about days (grouping, marks, gaps, summaries) is computed in the domain; the screen only draws it.
    val days = remember(feed) {
        feed?.let { f ->
            buildLogDays(f.entries, LocalDate.now(), f.coverage) { date -> supplementExpectedOn(f.supplementSchedule, date) }
        }.orEmpty()
    }
    val expanded = expandedKeys.split(',').filter { it.isNotEmpty() }.toSet()
    fun toggle(day: LogDay) {
        val key = day.date.toString()
        expandedKeys = (if (key in expanded) expanded - key else expanded + key).joinToString(",")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
            val current = feed
            val gapCount = days.count { it.hasGap }
            ScreenHeader(
                title = "LOG",
                context = when {
                    error != null -> "ERROR"
                    current != null -> "${current.entries.size} ENTRIES · ${if (gapCount == 0) "NO GAPS" else "$gapCount DAYS WITH GAPS"}"
                    else -> "LOADING"
                },
            )

            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Failed to load: $error", style = FTType.BodySmall, color = FT.Critical)
                }
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.DomainLog)
                }
                current.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing logged yet.", style = FTType.Body, color = FT.TextSecondary)
                }
                else -> {
                    val shown = if (gapsOnly) days.filter { it.hasGap } else days
                    val visible = shown.take(visibleDays)
                    LogToolbar(
                        gapsOnly = gapsOnly,
                        onToggleGaps = { gapsOnly = !gapsOnly; visibleDays = LOG_PAGE_DAYS },
                        allOpen = visible.isNotEmpty() && visible.all { it.date.toString() in expanded },
                        onToggleAll = {
                            expandedKeys = if (visible.all { it.date.toString() in expanded }) "" else visible.joinToString(",") { it.date.toString() }
                        },
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (visible.isEmpty()) {
                            item(key = "no-gaps") {
                                Text(
                                    "No gaps in the loaded days.",
                                    style = FTType.Body, color = FT.TextSecondary,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                )
                            }
                        }
                        items(visible, key = { it.date.toString() }) { day ->
                            LogDayCard(
                                day = day,
                                open = day.date.toString() in expanded,
                                onToggle = { toggle(day) },
                                onEntryClick = { actionEntry = it },
                            )
                        }
                        if (visibleDays < shown.size) {
                            item(key = "older") { LoadOlderButton(onClick = { visibleDays += LOG_PAGE_DAYS }) }
                        }
                        item(key = "end-pad") { Box(Modifier.fillMaxWidth().padding(vertical = 40.dp)) }
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
            .background(FT.DomainLog, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+",
            style = FTType.MetricMedium,
            color = FT.Base,
        )
    }
}

@Composable
private fun LogToolbar(gapsOnly: Boolean, onToggleGaps: () -> Unit, allOpen: Boolean, onToggleAll: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SubTabChip(label = "GAPS ONLY", selected = gapsOnly, onClick = onToggleGaps)
            Box(Modifier.weight(1f))
            Text(
                if (allOpen) "COLLAPSE ALL" else "EXPAND ALL",
                style = FTType.Label, color = FT.TextSecondary,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggleAll),
            )
        }
        Text(
            "SOLID = LOGGED · HALF = PARTIAL (MEALS UNDER 3) · DASHED = MISSING · FAINT = NOT EXPECTED OR NOT KNOWN",
            style = FTType.Micro, color = FT.TextMuted,
        )
    }
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM")).uppercase()
    }
}

// One day: collapsed it shows the date, the one-line summary and the row of marks; opened it lists the entries as before
// (category by category), each still tappable for detail / edit / delete.
@Composable
private fun LogDayCard(day: LogDay, open: Boolean, onToggle: () -> Unit, onEntryClick: (LogEntry) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (open) FT.GlassStrongFill else FT.GlassFill)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayLabel(day.date), style = FTType.LabelCaps, color = if (day.hasGap) FT.Warning else FT.TextPrimary, modifier = Modifier.weight(1f))
                Text("${day.entries.size}", style = FTType.Value, color = FT.TextSecondary)
                Text(if (open) "  ▾" else "  ▸", style = FTType.Value, color = FT.TextMuted)
            }
            Text(day.summary, style = FTType.BodySmall, color = if (day.entries.isEmpty()) FT.TextMuted else FT.TextSecondary, maxLines = 2)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                day.marks.forEach { DayMarkCell(it, Modifier.weight(1f)) }
            }
        }
        if (open) {
            day.byCategory.let { byCategory ->
                LogCategory.entries.forEach { category ->
                    val categoryEntries = byCategory[category] ?: return@forEach
                    CategoryLabel(category)
                    categoryEntries.forEach { entry -> EntryRow(entry, onClick = { onEntryClick(entry) }) }
                }
            }
            if (day.entries.isEmpty()) {
                Text("Nothing was logged this day.", style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
            }
        }
    }
}

private fun markColor(mark: LogMark): Color = when (mark) {
    LogMark.FOOD -> FT.Category.Intake.c500
    LogMark.DRINK -> FT.Category.Intake.c300
    LogMark.SUPP -> FT.Category.Intake.c700
    LogMark.SLEEP -> FT.Category.Sleep.c500
    LogMark.WELL -> FT.Category.Wellbeing.c500
    LogMark.EX -> FT.Category.Activity.c500
    LogMark.OTHER -> FT.TextSecondary
}

// A glyph that differs by shape as well as colour: solid bar = logged, half-filled = partial, dashed outline = missing,
// faint outline = nothing to say. The count (or "2/3", or a dash) is written under it.
@Composable
private fun DayMarkCell(m: DayMark, modifier: Modifier = Modifier) {
    val color = markColor(m.mark)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            val corner = CornerRadius(size.height / 2f)
            when (m.state) {
                MarkState.PRESENT -> drawRoundRect(color, Offset.Zero, size, corner)
                MarkState.PARTIAL -> {
                    drawRoundRect(color, Offset.Zero, Size(size.width / 2f, size.height), corner)
                    drawRoundRect(color, Offset.Zero, size, corner, style = Stroke(1.5.dp.toPx()))
                }
                MarkState.MISSING -> drawRoundRect(
                    FT.Warning, Offset.Zero, size, corner,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))),
                )
                MarkState.NONE -> drawRoundRect(FT.GlassTrack, Offset.Zero, size, corner, style = Stroke(1.dp.toPx()))
            }
        }
        Text(
            when (m.state) {
                MarkState.PARTIAL -> "${m.count}/3"
                MarkState.MISSING -> "—"
                MarkState.NONE -> ""
                MarkState.PRESENT -> "${m.count}"
            },
            style = FTType.Micro,
            color = if (m.state == MarkState.MISSING) FT.Warning else FT.TextSecondary,
            maxLines = 1,
        )
        Text(
            m.mark.label,
            style = FTType.Micro,
            color = if (m.state == MarkState.NONE) FT.TextMuted.copy(alpha = 0.6f) else FT.TextMuted,
            maxLines = 1,
        )
    }
}

// DAV-219 (24/9 fixes): lighter weight than the day row (no background fill) --
// subordinate to the day it sits under, not a peer section of its own.
@Composable
private fun CategoryLabel(category: LogCategory) {
    Text(
        category.label,
        style = FTType.LabelCaps,
        color = FT.TextMuted,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 2.dp),
    )
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
            style = FTType.Value,
            color = FT.TextSecondary,
            modifier = Modifier.width(42.dp).padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeChip(entry.kind)
                Text(entry.headline, style = FTType.RowTitle, color = FT.TextPrimary)
            }
            entry.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// Category colors per direct user request (2026-09-15): deep blue for
// sleep, orange for activity (runs, strength, ...), green for food/drink/
// supplements, sand for stool, azure for wellness, red for encounter and
// masturbation/intercourse. Note has no assigned category -- stays neutral. OSTRC (added
// later, Category 8) shares stool's sand -- both are the Evaluation Method
// Spec's own "digestive & injury tracking" category. DAV-107 explicitly
// preserves these exact hues ("keep... category colors") even though the
// FT contract's own domain-accent table doesn't define per-entry-type
// colors (only per-section) -- only the font/text-color-for-"filled"
// chips migrated to FT tokens. Now sourced from FT.Category (the contract's
// third color tier) rather than the legacy FieldColors; arousal moved from
// red to magenta-crimson so it no longer reads as a Critical state.
@Composable
private fun TypeChip(kind: LogEntryKind) {
    val color = when (kind) {
        LogEntryKind.Exercise, LogEntryKind.Grip -> FT.Category.Activity.c500
        LogEntryKind.Food, LogEntryKind.Drink, LogEntryKind.Supplement -> FT.Category.Intake.c500
        LogEntryKind.Sleep -> FT.Category.Sleep.c500
        LogEntryKind.Encounter, LogEntryKind.Masturbation, LogEntryKind.Intercourse -> FT.Category.Arousal.c500
        LogEntryKind.Note -> FT.TextSecondary
        LogEntryKind.Stool, LogEntryKind.Ostrc -> FT.Category.Digestion.c500
        LogEntryKind.Wellness -> FT.Category.Wellbeing.c500
    }
    val filled = kind in setOf(LogEntryKind.Exercise, LogEntryKind.Food, LogEntryKind.Drink, LogEntryKind.Supplement, LogEntryKind.Stool)
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier = Modifier
            .background(if (filled) color else Color.Transparent, shape)
            .then(if (!filled) Modifier.border(1.dp, color, shape) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            kind.label,
            style = FTType.LabelCaps,
            color = if (filled) FT.Base else color,
        )
    }
}

@Composable
private fun LoadOlderButton(onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("LOAD OLDER", style = FTType.LabelCaps, color = FT.TextSecondary)
        }
    }
}
