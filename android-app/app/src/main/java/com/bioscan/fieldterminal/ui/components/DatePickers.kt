package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// Themed date / time picker. Every add-entry form defaults its date to "now"
// but lets it be changed (added 2026-09-15 per direct user request). It used
// to be the platform DatePickerDialog/TimePickerDialog, which was unthemed
// and showed a 24-hour clock with a second, inner ring of hours. This is a
// Futuristic Material bottom sheet instead: a custom month calendar, and a
// 12-hour time panel with an AM/PM toggle (no dial, no 24h, no inner ring).
// The public API (DateField / DateTimeField / TimeField) is unchanged.
//
// Nothing is written back to the caller until SET: dismissing or CANCEL
// leaves the field as it was.

private val FIELD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val FIELD_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy · h:mm a", Locale.ENGLISH)
private val SUMMARY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH)
private val SUMMARY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

@Composable
fun DateField(label: String, date: LocalDate, onDateChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerBox(label = label, valueText = date.format(FIELD_DATE)) { open = true }
    if (open) {
        FTPickerSheet(
            label = label,
            initial = date.atStartOfDay(),
            withTime = false,
            onConfirm = { onDateChange(it.toLocalDate()); open = false },
            onDismiss = { open = false },
        )
    }
}

// Time-only entry (quiet hours, default session times): the same sheet in
// time mode -- 12-hour with AM/PM, no calendar. Seconds are dropped.
@Composable
fun TimeField(label: String, time: LocalTime, onTimeChange: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerBox(label = label, valueText = time.format(SUMMARY_TIME)) { open = true }
    if (open) {
        FTPickerSheet(
            label = label,
            initial = LocalDateTime.of(LocalDate.now(), time),
            withDate = false,
            withTime = true,
            onConfirm = { onTimeChange(it.toLocalTime()); open = false },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun DateTimeField(label: String, dateTime: LocalDateTime, onDateTimeChange: (LocalDateTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerBox(label = label, valueText = dateTime.format(FIELD_DATE_TIME)) { open = true }
    if (open) {
        FTPickerSheet(
            label = label,
            initial = dateTime,
            withTime = true,
            onConfirm = { onDateTimeChange(it); open = false },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun PickerBox(label: String, valueText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusSmall))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = FTType.MonoCaption, color = FT.TextSecondary)
        Text(valueText, style = FTType.Value, color = FT.TextPrimary)
    }
}

private enum class PickerTab(val label: String) { Date("DATE"), Time("TIME") }

private enum class TimeUnit { Hour, Minute }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FTPickerSheet(
    label: String,
    initial: LocalDateTime,
    withDate: Boolean = true,
    withTime: Boolean,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var tab by remember { mutableStateOf(PickerTab.Date) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(label, style = FTType.LabelCaps, color = FT.TextMuted)
                Text(
                    text = listOfNotNull(
                        if (withDate) selected.format(SUMMARY_DATE) else null,
                        if (withTime) selected.format(SUMMARY_TIME) else null,
                    ).joinToString(" · "),
                    style = FTType.SectionTitle,
                    color = FT.TextPrimary,
                )
            }

            if (withDate && withTime) {
                SegmentedToggle(options = PickerTab.entries, selected = tab, labelOf = { it.label }, onSelect = { tab = it })
            }

            if (withDate && (!withTime || tab == PickerTab.Date)) {
                CalendarPanel(date = selected.toLocalDate()) { picked ->
                    selected = LocalDateTime.of(picked, selected.toLocalTime())
                }
            } else {
                TimePanel(time = selected.toLocalTime()) { picked ->
                    selected = LocalDateTime.of(selected.toLocalDate(), picked)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("CANCEL", style = FTType.LabelCaps, color = FT.TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
                AmberButton(label = "SET") { onConfirm(selected) }
            }
        }
    }
}

// ---- Calendar ----

@Composable
private fun CalendarPanel(date: LocalDate, onPick: (LocalDate) -> Unit) {
    var shown by remember { mutableStateOf(YearMonth.from(date)) }
    val today = LocalDate.now()
    val leadingBlanks = shown.atDay(1).dayOfWeek.value - 1 // Monday-first
    val daysInMonth = shown.lengthOfMonth()
    val rows = (leadingBlanks + daysInMonth + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NavGlyph("«") { shown = shown.minusYears(1) }
            NavGlyph("‹") { shown = shown.minusMonths(1) }
            Text(
                shown.format(MONTH_TITLE).uppercase(),
                style = FTType.Telemetry,
                color = FT.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            NavGlyph("›") { shown = shown.plusMonths(1) }
            NavGlyph("»") { shown = shown.plusYears(1) }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (name in listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")) {
                Text(name, style = FTType.Micro, color = FT.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val day = r * 7 + c - leadingBlanks + 1
                    if (day in 1..daysInMonth) {
                        val d = shown.atDay(day)
                        DayCell(
                            day = day,
                            selected = d == date,
                            isToday = d == today,
                            modifier = Modifier.weight(1f),
                        ) { onPick(d) }
                    } else {
                        Spacer(Modifier.weight(1f).height(44.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NavGlyph(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = FTType.MetricMedium, color = FT.TextSecondary)
    }
}

@Composable
private fun DayCell(day: Int, selected: Boolean, isToday: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier = modifier
            .height(44.dp)
            .padding(2.dp)
            .clip(shape)
            .then(
                when {
                    selected -> Modifier
                        .background(FT.Emerald.copy(alpha = 0.14f), shape)
                        .border(FT.BorderWidth, FT.Emerald, shape)
                    isToday -> Modifier.border(FT.BorderWidth, FT.TextSecondary.copy(alpha = 0.6f), shape)
                    else -> Modifier
                },
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$day",
            style = if (selected) FTType.Telemetry else FTType.Value,
            color = if (selected) FT.Emerald else FT.TextPrimary,
        )
    }
}

// ---- Time (12-hour, AM/PM) ----

@Composable
private fun WheelPicker(
    count: Int,
    selected: Int,
    onSelected: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val visibleItems = 5
    val itemHeight = 44.dp
    val padding = visibleItems / 2
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (selected + padding).coerceAtLeast(0)
    )
    val fling = rememberSnapFlingBehavior(state)
    val centred by remember { derivedStateOf {
        (state.firstVisibleItemIndex - padding).coerceIn(0, count - 1)
    } }
    LaunchedEffect(centred) { onSelected(centred) }

    Box(modifier = modifier.height(itemHeight * visibleItems)) {
        // centre-slot highlight
        Box(
            Modifier.fillMaxWidth().height(itemHeight).align(Alignment.Center)
                .background(FT.GlassFill, RoundedCornerShape(FT.RadiusSmall))
        )
        LazyColumn(state = state, flingBehavior = fling, modifier = Modifier.fillMaxSize()) {
            items(padding) { Box(Modifier.height(itemHeight).fillMaxWidth()) }
            items(count) { i ->
                val active = i == centred
                Box(Modifier.height(itemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        label(i),
                        style = if (active) FTType.MetricMedium else FTType.BodySmall,
                        color = if (active) FT.TextPrimary else FT.TextMuted,
                    )
                }
            }
            items(padding) { Box(Modifier.height(itemHeight).fillMaxWidth()) }
        }
    }
}

@Composable
private fun TimePanel(time: LocalTime, onChange: (LocalTime) -> Unit) {
    val pm = time.hour >= 12
    val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12

    fun build(h12: Int, minute: Int, isPm: Boolean): LocalTime =
        LocalTime.of((h12 % 12) + if (isPm) 12 else 0, minute)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        WheelPicker(
            count = 12,
            selected = hour12 - 1,
            onSelected = { onChange(build(it + 1, time.minute, pm)) },
            label = { "%02d".format(it + 1) },
            modifier = Modifier.weight(1f),
        )
        Text(":", style = FTType.DisplayMetric, color = FT.TextMuted)
        WheelPicker(
            count = 60,
            selected = time.minute,
            onSelected = { onChange(build(hour12, it, pm)) },
            label = { "%02d".format(it) },
            modifier = Modifier.weight(1f),
        )
        SegmentedToggle(
            options = listOf(false, true),
            selected = pm,
            labelOf = { if (it) "PM" else "AM" },
            onSelect = { onChange(build(hour12, time.minute, it)) },
            modifier = Modifier.width(96.dp),
        )
    }
}

@Composable
private fun TimeSegment(text: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (active) FT.Emerald.copy(alpha = 0.14f) else FT.GlassFill, shape)
            .border(FT.BorderWidth, if (active) FT.Emerald else FT.GlassBorder, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = FTType.DisplayMetric, color = if (active) FT.Emerald else FT.TextPrimary)
    }
}

@Composable
private fun ButtonGrid(items: List<String>, selectedIndex: Int?, columns: Int = 4, onPick: (Int) -> Unit) {
    val rows = (items.size + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until columns) {
                    val i = r * columns + c
                    if (i < items.size) {
                        GridButton(items[i], selected = i == selectedIndex, modifier = Modifier.weight(1f)) { onPick(i) }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GridButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(if (selected) FT.Emerald.copy(alpha = 0.14f) else FT.GlassFill, shape)
            .border(FT.BorderWidth, if (selected) FT.Emerald else FT.GlassBorder, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (selected) FTType.Telemetry else FTType.Value, color = if (selected) FT.Emerald else FT.TextPrimary)
    }
}
