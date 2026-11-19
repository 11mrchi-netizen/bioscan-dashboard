package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.CalendarFilter
import com.bioscan.fieldterminal.domain.LogDay
import com.bioscan.fieldterminal.domain.matches
import com.bioscan.fieldterminal.domain.monthCells
import com.bioscan.fieldterminal.ui.components.SubTabChip
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The Log as a month: every logged thing counted per day, narrowed by a filter (SEX, TRAINING, FOOD, ...). A day's
// square is darker the more it holds; tapping it lists that day's entries below, through the same card the list uses.
// The feed is loaded a window at a time, so stepping back to a month before the window asks for it ([onNeedFrom]).

private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val WEEKDAYS = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
internal fun LogCalendar(
    days: List<LogDay>,
    loadedFrom: LocalDate,
    onNeedFrom: (LocalDate) -> Unit,
    dayContent: @Composable (LogDay) -> Unit,
) {
    val today = LocalDate.now()
    // Kept as text so the month, the filter and the picked day survive a tab switch.
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var filterName by rememberSaveable { mutableStateOf(CalendarFilter.All.name) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthText)
    val filter = CalendarFilter.valueOf(filterName)
    val selected = LocalDate.parse(selectedText)

    LaunchedEffect(month, loadedFrom) { if (month.atDay(1).isBefore(loadedFrom)) onNeedFrom(month.atDay(1)) }

    val byDate = remember(days) { days.associateBy { it.date } }
    val counts = remember(days, filter) { days.associate { d -> d.date to d.entries.count { filter.matches(it) } } }
    val max = counts.filterKeys { YearMonth.from(it) == month }.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val accent = if (filter == CalendarFilter.Sex) FT.Category.Arousal.c500 else FT.DomainLog

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ PREV", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.ftClickable(role = Role.Button) { monthText = month.minusMonths(1).toString() })
            Text(month.format(MONTH_TITLE).uppercase(), style = FTType.SectionTitle, color = FT.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            val canGoOn = month.isBefore(YearMonth.from(today))
            Text(
                "NEXT ›", style = FTType.LabelCaps, color = if (canGoOn) FT.TextSecondary else FT.TextMuted,
                modifier = Modifier.ftClickable(role = Role.Button) { if (canGoOn) monthText = month.plusMonths(1).toString() },
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalendarFilter.entries.forEach { f -> SubTabChip(f.label, filter == f) { filterName = f.name } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WEEKDAYS.forEach { Text(it, style = FTType.Micro, color = FT.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        monthCells(month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    val count = date?.let { counts[it] } ?: 0
                    val known = date != null && !date.isBefore(loadedFrom) && !date.isAfter(today)
                    Box(
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                            .background(if (count > 0) accent.copy(alpha = 0.18f + 0.62f * count / max) else if (known) FT.GlassFill else Color.Transparent, RoundedCornerShape(FT.RadiusSmall))
                            .border(FT.BorderWidth, if (date == selected) FT.TextPrimary else Color.Transparent, RoundedCornerShape(FT.RadiusSmall))
                            .then(if (date != null && !date.isAfter(today)) Modifier.ftClickable(shape = RoundedCornerShape(FT.RadiusSmall), role = Role.Button, onClickLabel = date.toString()) { selectedText = date.toString() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${date.dayOfMonth}", style = FTType.Value, color = if (date.isAfter(today)) FT.TextMuted else FT.TextPrimary)
                                Text(if (count > 0) "$count" else "", style = FTType.Micro, color = FT.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
        if (month.atDay(1).isBefore(loadedFrom)) Text("Loading this month…", style = FTType.Micro, color = FT.TextMuted)

        val day = byDate[selected]
        val shown = day?.let { d ->
            if (filter == CalendarFilter.All) d
            else d.copy(
                entries = d.entries.filter { filter.matches(it) },
                byCategory = d.byCategory.mapValues { (_, list) -> list.filter { filter.matches(it) } }.filterValues { it.isNotEmpty() },
            )
        }
        when {
            shown == null -> Text("Nothing loaded for ${selected}.", style = FTType.BodySmall, color = FT.TextMuted)
            shown.entries.isEmpty() -> Text(
                if (filter == CalendarFilter.All) "Nothing logged that day." else "Nothing under ${filter.label} that day.",
                style = FTType.BodySmall, color = FT.TextMuted,
            )
            // The day card is full-bleed in the list; here it sits inside the calendar's side padding.
            else -> dayContent(shown)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp))
    }
}
