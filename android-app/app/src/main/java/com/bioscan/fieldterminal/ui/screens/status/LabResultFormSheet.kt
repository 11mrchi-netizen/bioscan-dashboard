package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.LabsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.launch
import java.time.LocalDate

// DAV-84. Single marker+value+date at a time, matching AddEntrySheet.kt's
// established one-item-per-form pattern -- bulk panel import from a file is
// DAV-85's separate scope, not this one. Ref low/high are optional, but
// entering just one of value/ref-low/ref-high without the others still
// saves (LabsRepository.addLabResult tolerates partial data the same way
// this table's own imported rows already do -- not every real result comes
// with both bounds).
private val sheetHeaderTitleStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private val sheetActionLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabResultFormSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { LabsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()

    var date by remember { mutableStateOf(LocalDate.now()) }
    var markerName by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var refLow by remember { mutableStateOf("") }
    var refHigh by remember { mutableStateOf("") }
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
            Text("ADD LAB RESULT", style = sheetHeaderTitleStyle, color = FT.Emerald)

            DateField("DRAW DATE", date, { date = it })
            Column { FormFieldLabel("MARKER"); FieldTextField(markerName, { markerName = it }, "e.g. Ferritin") }
            Column { FormFieldLabel("VALUE"); FieldTextField(value, { value = it }, "e.g. 85", keyboardType = KeyboardType.Number) }
            Column { FormFieldLabel("UNIT (OPTIONAL)"); FieldTextField(unit, { unit = it }, "e.g. ng/mL") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { FormFieldLabel("REF LOW (OPTIONAL)"); FieldTextField(refLow, { refLow = it }, "e.g. 30", keyboardType = KeyboardType.Number) }
                Column(Modifier.weight(1f)) { FormFieldLabel("REF HIGH (OPTIONAL)"); FieldTextField(refHigh, { refHigh = it }, "e.g. 400", keyboardType = KeyboardType.Number) }
            }

            error?.let {
                Text("Couldn't save ($it).", style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.Critical)
            }

            val valid = markerName.isNotBlank() && value.toDoubleOrNull() != null
            AmberButton(label = if (saving) "SAVING..." else "SAVE") {
                if (valid && !saving) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            repo.addLabResult(
                                date = date,
                                markerName = markerName.trim(),
                                value = value.toDoubleOrNull(),
                                unit = unit.trim().ifBlank { null },
                                refLow = refLow.toDoubleOrNull(),
                                refHigh = refHigh.toDoubleOrNull(),
                            )
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
