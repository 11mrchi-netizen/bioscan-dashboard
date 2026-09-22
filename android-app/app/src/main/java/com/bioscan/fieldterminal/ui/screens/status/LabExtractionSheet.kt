package com.bioscan.fieldterminal.ui.screens.status

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.ExtractedLabMarker
import com.bioscan.fieldterminal.data.GeminiApiKeyStore
import com.bioscan.fieldterminal.data.LabExtractionRepository
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

// DAV-85. One uploaded report -> one review list -> the same addLabResult()
// save path DAV-84 already built (one call per surviving row, all landing on
// the same find-or-created draw). Never auto-saves what Gemini extracts --
// every row stays editable and removable until SAVE, since a vision
// extraction from a real scanned report is exactly the kind of "plausible
// but unverified" output this app's own established stance elsewhere
// (NutritionEstimationRepository's own review-before-save framing) already
// refuses to trust unreviewed.
private enum class ExtractionPhase { PickFile, Extracting, Review, Saving }
private val sheetHeaderTitleStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private val sheetContextStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.08f.em)

private class EditableMarkerRow(marker: ExtractedLabMarker) {
    var name by mutableStateOf(marker.markerName)
    var value by mutableStateOf(marker.value?.toString() ?: "")
    var unit by mutableStateOf(marker.unit ?: "")
    var refLow by mutableStateOf(marker.refLow?.toString() ?: "")
    var refHigh by mutableStateOf(marker.refHigh?.toString() ?: "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabExtractionSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val labsRepo = remember { LabsRepository(SupabaseClientProvider.client) }

    var phase by remember { mutableStateOf(ExtractionPhase.PickFile) }
    var error by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val rows = remember { mutableStateListOf<EditableMarkerRow>() }

    fun runExtraction(uri: android.net.Uri) {
        val apiKey = GeminiApiKeyStore.get(context)
        if (apiKey == null) {
            error = "Set a Gemini API key in Setup to enable report extraction."
            return
        }
        phase = ExtractionPhase.Extracting
        error = null
        scope.launch {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "application/pdf"
                val bytes = com.bioscan.fieldterminal.util.readFileBytes(context, uri)
                val known = labsRepo.knownMarkerNames()
                val extracted = LabExtractionRepository(apiKey).extract(bytes, mimeType, known)
                rows.clear()
                rows.addAll(extracted.map { EditableMarkerRow(it) })
                phase = ExtractionPhase.Review
            } catch (e: Exception) {
                error = e.message ?: "Extraction failed"
                phase = ExtractionPhase.PickFile
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runExtraction(uri)
    }

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
            Text("UPLOAD LAB REPORT", style = sheetHeaderTitleStyle, color = FT.Emerald)

            error?.let {
                Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.Critical)
            }

            when (phase) {
                ExtractionPhase.PickFile -> {
                    val apiKey = remember { GeminiApiKeyStore.get(context) }
                    if (apiKey == null) {
                        Text(
                            "Set a Gemini API key in Setup to enable report extraction.",
                            style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                            color = FT.TextSecondary,
                        )
                    } else {
                        Text(
                            "Choose a photo or PDF of a lab report. Gemini extracts the markers -- review and edit before anything saves.",
                            style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                            color = FT.TextSecondary,
                        )
                        AmberButton(label = "CHOOSE FILE") {
                            filePickerLauncher.launch(arrayOf("application/pdf", "image/*"))
                        }
                    }
                }
                ExtractionPhase.Extracting -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = FT.Emerald)
                            Text(
                                "Extracting markers...",
                                style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                                color = FT.TextSecondary,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
                ExtractionPhase.Review, ExtractionPhase.Saving -> {
                    DateField("DRAW DATE", date, { date = it })
                    Text(
                        "${rows.size} marker${if (rows.size == 1) "" else "s"} found — review before saving",
                        style = sheetContextStyle,
                        color = FT.TextSecondary,
                    )
                    rows.forEachIndexed { index, row ->
                        EditableMarkerCard(row, onRemove = { rows.removeAt(index) })
                    }
                    val valid = rows.isNotEmpty() && rows.all { it.name.isNotBlank() && it.value.toDoubleOrNull() != null }
                    AmberButton(label = if (phase == ExtractionPhase.Saving) "SAVING..." else "SAVE ${rows.size} RESULT${if (rows.size == 1) "" else "S"}") {
                        if (valid && phase != ExtractionPhase.Saving) {
                            phase = ExtractionPhase.Saving
                            error = null
                            scope.launch {
                                try {
                                    rows.forEach { row ->
                                        labsRepo.addLabResult(
                                            date = date,
                                            markerName = row.name.trim(),
                                            value = row.value.toDoubleOrNull(),
                                            unit = row.unit.trim().ifBlank { null },
                                            refLow = row.refLow.toDoubleOrNull(),
                                            refHigh = row.refHigh.toDoubleOrNull(),
                                            source = "Uploaded report",
                                        )
                                    }
                                    onSaved()
                                } catch (e: Exception) {
                                    error = e.message ?: "Unknown error"
                                    phase = ExtractionPhase.Review
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EditableMarkerCard(row: EditableMarkerRow, onRemove: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FieldTextField(row.name, { row.name = it }, "Marker name", modifier = Modifier.weight(1f))
            Text(
                "REMOVE",
                style = TextStyle(fontFamily = Inter, fontSize = 11.5.sp),
                color = FT.Critical,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRemove),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { MiniLabel("VALUE"); FieldTextField(row.value, { row.value = it }, "0", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { MiniLabel("UNIT"); FieldTextField(row.unit, { row.unit = it }, "") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { MiniLabel("REF LOW"); FieldTextField(row.refLow, { row.refLow = it }, "", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { MiniLabel("REF HIGH"); FieldTextField(row.refHigh, { row.refHigh = it }, "", keyboardType = KeyboardType.Number) }
        }
    }
}

@Composable
private fun MiniLabel(text: String) {
    Text(text, style = TextStyle(fontFamily = Inter, fontSize = 10.5.sp), color = FT.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
}
