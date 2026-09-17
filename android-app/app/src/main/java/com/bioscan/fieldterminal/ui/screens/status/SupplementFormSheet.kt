package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.Saira
import kotlinx.coroutines.launch
import java.time.LocalDate

// DAV-81. The roster (`supplements`) itself had no UI to create or edit --
// only ever read, by SupplementsScreen.kt's display and AddEntrySheet.kt's
// logging flow, both of which assumed rows already existed. Add and edit
// share this one sheet since they're the same 3 fields either way; ending a
// supplement is a separate, single-tap action here rather than a 4th field,
// since it's a status transition, not part of "what/how much/when."
private val TIME_OF_DAY_OPTIONS = listOf("morning", "afternoon", "night", "as-needed")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplementFormSheet(existing: SupplementRow?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { SupplementsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var dose by remember { mutableStateOf(existing?.dose ?: "") }
    var timeOfDay by remember { mutableStateOf(existing?.timeOfDay ?: "morning") }
    var saving by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FieldColors.Panel,
        contentColor = FieldColors.Ink,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (existing == null) "ADD SUPPLEMENT" else "EDIT SUPPLEMENT",
                style = FieldTextStyles.headerTitle,
                color = FieldColors.Amber,
            )

            Column { FormFieldLabel("NAME"); FieldTextField(name, { name = it }, "e.g. Vitamin D3 (NOW Foods)") }
            Column { FormFieldLabel("DOSE"); FieldTextField(dose, { dose = it }, "e.g. 5000 IU") }

            Column {
                FormFieldLabel("TIME OF DAY")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TIME_OF_DAY_OPTIONS.forEach { option ->
                        val selected = option == timeOfDay
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, if (selected) FieldColors.Amber else FieldColors.Hairline)
                                .background(if (selected) FieldColors.Amber.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { timeOfDay = option }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option.uppercase(),
                                style = TextStyle(fontFamily = Saira, fontSize = 11.sp),
                                color = if (selected) FieldColors.Amber else FieldColors.InkMuted,
                            )
                        }
                    }
                }
            }

            error?.let {
                Text("Couldn't save ($it).", style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp), color = FieldColors.Alert)
            }

            val valid = name.isNotBlank() && dose.isNotBlank()
            AmberButton(label = if (saving) "SAVING..." else "SAVE") {
                if (valid && !saving) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            if (existing == null) {
                                repo.addSupplement(name.trim(), dose.trim(), timeOfDay, LocalDate.now())
                            } else {
                                repo.updateSupplement(existing.id, name.trim(), dose.trim(), timeOfDay)
                            }
                            onSaved()
                        } catch (e: Exception) {
                            error = e.message ?: "Unknown error"
                        } finally {
                            saving = false
                        }
                    }
                }
            }

            if (existing != null && existing.status == "active") {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ending) "ENDING..." else "END THIS SUPPLEMENT",
                    style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                    color = FieldColors.Alert,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!ending) {
                                ending = true
                                scope.launch {
                                    try {
                                        repo.endSupplement(existing.id)
                                        onSaved()
                                    } catch (e: Exception) {
                                        error = e.message ?: "Unknown error"
                                        ending = false
                                    }
                                }
                            }
                        },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FormFieldLabel(text: String) {
    Text(text, style = FieldTextStyles.subTabLabel, color = FieldColors.InkMuted, modifier = Modifier.padding(bottom = 6.dp))
}
