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
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Step 11 (Phase D): the unified, read-only log feed. Real data merged from
// meals/runs/sleep_daily/arousal_daily/stool_log/encounters -- see
// domain/Log.kt for what's deliberately NOT included (supplement-taken
// confirmations and freeform notes have no backing table yet) and
// data/LogRepository.kt for the pagination approach Step 11's roadmap text
// explicitly asked to be decided. Step 12 adds the "+" add-entry flow on
// top of this; this step is read-only by design.
@Composable
fun LogScreen() {
    var allEntries by remember { mutableStateOf<List<LogEntry>?>(null) }
    var visibleCount by remember { mutableStateOf(LOG_PAGE_SIZE) }

    LaunchedEffect(Unit) {
        allEntries = LogRepository(SupabaseClientProvider.client).loadAllEntries()
    }

    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground)) {
        val entries = allEntries
        ScreenHeader(
            title = "LOG",
            context = if (entries != null) "${entries.size} ENTRIES" else "LOADING",
        )

        when {
            entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FieldColors.Amber)
            }
            entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
            }
            else -> {
                val visible = entries.take(visibleCount)
                val grouped = visible.groupBy { it.timestamp.toLocalDate() }

                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    grouped.forEach { (date, dayEntries) ->
                        DayHeader(date)
                        dayEntries.forEach { EntryRow(it) }
                    }
                    if (visibleCount < entries.size) {
                        LoadOlderButton(onClick = { visibleCount += LOG_PAGE_SIZE })
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp))
                }
            }
        }
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
            .background(FieldColors.Hairline.copy(alpha = 0.4f))
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = FieldTextStyles.subTabLabel, color = FieldColors.InkMuted)
        Text(
            date.format(DateTimeFormatter.ofPattern("dd MMM")).uppercase(),
            style = FieldTextStyles.subTabLabel,
            color = FieldColors.InkMuted,
        )
    }
}

@Composable
private fun EntryRow(entry: LogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            entry.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.5.sp),
            color = FieldColors.InkMuted,
            modifier = Modifier.width(42.dp).padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeChip(entry.kind)
                Text(entry.headline, style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = FieldColors.Ink)
            }
            entry.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp), color = FieldColors.InkMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun TypeChip(kind: LogEntryKind) {
    val color = when (kind) {
        LogEntryKind.Run -> FieldColors.Cyan
        LogEntryKind.Food -> FieldColors.Green
        LogEntryKind.Sleep, LogEntryKind.Arousal, LogEntryKind.Encounter -> FieldColors.InkMuted
        LogEntryKind.Stool -> FieldColors.Amber
    }
    val filled = kind == LogEntryKind.Run || kind == LogEntryKind.Food || kind == LogEntryKind.Stool
    Box(
        modifier = Modifier
            .background(if (filled) color else Color.Transparent)
            .then(if (!filled) Modifier.border(1.dp, color) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            kind.label,
            style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.14f.em),
            color = if (filled) FieldColors.Ground else color,
        )
    }
}

@Composable
private fun LoadOlderButton(onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .border(1.dp, FieldColors.Hairline)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("LOAD OLDER", style = FieldTextStyles.subTabLabel, color = FieldColors.InkMuted)
        }
    }
}
