package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.HealthEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.launch
import java.time.LocalDate

// DAV-88. New injuries always start "active" -- ending one is RESOLVE on the
// open card itself (HealthEventsScreen.kt), not a status field in this form.
private val sheetHeaderTitleStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private val sheetActionLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InjuryFormSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { HealthEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()

    var date by remember { mutableStateOf(LocalDate.now()) }
    var part by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("LOG INJURY", style = sheetHeaderTitleStyle, color = FT.Emerald)

            DateField("START DATE", date, { date = it })
            Column { FormFieldLabel("BODY PART"); FieldTextField(part, { part = it }, "e.g. Knee (Right)") }
            Column { FormFieldLabel("TYPE"); FieldTextField(type, { type = it }, "e.g. Soreness, Strain") }
            Column { FormFieldLabel("SEVERITY 1-10"); FieldTextField(severity, { severity = it }, "e.g. 3", keyboardType = KeyboardType.Number) }
            Column { FormFieldLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Anything else worth noting", singleLine = false) }

            error?.let {
                Text("Couldn't save ($it).", style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.Critical)
            }

            val severityValue = severity.toIntOrNull()
            val valid = part.isNotBlank() && type.isNotBlank() && severityValue != null && severityValue in 1..10
            AmberButton(label = if (saving) "SAVING..." else "SAVE") {
                if (valid && !saving) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            repo.addInjury(part.trim(), type.trim(), severityValue!!, date, notes.trim().ifBlank { null })
                            onSaved()
                        } catch (e: Exception) {
                            error = e.message ?: "Unknown error"
                        } finally {
                            saving = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FormFieldLabel(text: String) {
    Text(text, style = sheetActionLabelStyle, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}
