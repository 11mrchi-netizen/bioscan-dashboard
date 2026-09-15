package com.bioscan.fieldterminal.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Every add-entry form defaults its date to "now" but lets it be changed --
// added 2026-09-15 per direct user request. Backed by the platform's own
// DatePickerDialog/TimePickerDialog rather than a custom-built calendar
// widget: a native picker is a well-understood pattern even inside an
// otherwise custom-styled app, and building a themed calendar grid from
// scratch buys little here.
@Composable
fun DateField(label: String, date: LocalDate, onDateChange: (LocalDate) -> Unit) {
    val context = LocalContext.current
    PickerBox(label = label, valueText = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))) {
        DatePickerDialog(
            context,
            { _, year, month, day -> onDateChange(LocalDate.of(year, month + 1, day)) },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth,
        ).show()
    }
}

@Composable
fun DateTimeField(label: String, dateTime: LocalDateTime, onDateTimeChange: (LocalDateTime) -> Unit) {
    val context = LocalContext.current
    PickerBox(label = label, valueText = dateTime.format(DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm"))) {
        DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        onDateTimeChange(LocalDateTime.of(LocalDate.of(year, month + 1, day), LocalTime.of(hour, minute)))
                    },
                    dateTime.hour,
                    dateTime.minute,
                    true,
                ).show()
            },
            dateTime.year,
            dateTime.monthValue - 1,
            dateTime.dayOfMonth,
        ).show()
    }
}

@Composable
private fun PickerBox(label: String, valueText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, FieldColors.Hairline)
            .background(FieldColors.RaisedSurface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp), color = FieldColors.InkMuted)
        Text(valueText, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp), color = FieldColors.Ink)
    }
}
