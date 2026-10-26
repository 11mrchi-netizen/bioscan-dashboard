package com.bioscan.fieldterminal.ui.screens

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.BarcodeLookupFood
import com.bioscan.fieldterminal.data.LabelCheck
import com.bioscan.fieldterminal.data.LabelValues
import com.bioscan.fieldterminal.data.NutritionBarcodeLookupRepository
import com.bioscan.fieldterminal.data.ScannedLabel
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.formatAmount
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.readAndCompressImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A packaged food no database knows (most Taiwanese products): photograph its nutrition label (營養標示), check the
// numbers read from it -- or type them -- and save it under the barcode, so the next scan of it is instant.
// Values are entered per serving (每份) when the label gives a serving size, else per 100 g/ml.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelEntrySheet(
    barcode: String,
    initialName: String,
    initialBrand: String?,
    photos: List<Uri>,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    onSaved: (BarcodeLookupFood) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { NutritionBarcodeLookupRepository(SupabaseClientProvider.client) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(initialName) }
    var brand by remember { mutableStateOf(initialBrand ?: "") }
    var perServing by remember { mutableStateOf(true) }
    var servingSize by remember { mutableStateOf("") }
    var servingUnit by remember { mutableStateOf("g") }
    var servingsPerPackage by remember { mutableStateOf("") }
    val values = remember { LABEL_FIELDS.associate { it.key to mutableStateOf("") } }
    var checks by remember { mutableStateOf<List<LabelCheck>>(emptyList()) }
    var reading by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Each new set of photos is read once; the numbers prefill the form for the user to check.
    LaunchedEffect(photos) {
        if (photos.isEmpty()) return@LaunchedEffect
        reading = true
        readError = null
        try {
            val images = withContext(Dispatchers.IO) { photos.takeLast(3).map { readAndCompressImage(context, it) } }
            val read = repo.readLabel(images)
            val label = read.label
            if (name.isBlank()) label.productName?.takeIf { it.isNotBlank() }?.let { name = it }
            label.servingUnit?.let { servingUnit = if (it == "ml") "ml" else "g" }
            label.servingSize?.let { servingSize = formatAmount(it) }
            label.servingsPerPackage?.let { servingsPerPackage = formatAmount(it) }
            val source = if (label.perServing?.calories != null && label.servingSize != null) label.perServing else label.per100 ?: label.perServing
            perServing = source === label.perServing && label.servingSize != null
            val kj = label.energyUnit == "kJ"
            LABEL_FIELDS.forEach { field ->
                val v = source?.let(field.get)
                values.getValue(field.key).value = v?.let { formatAmount(if (kj && field.key == "calories") it / 4.184 else it) } ?: ""
            }
            checks = read.normalized.checks
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            readError = e.message ?: "Couldn't read the label"
        } finally {
            reading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("NEW PRODUCT", style = FTType.SectionTitle, color = FT.Emerald)
            Text(
                "Barcode $barcode isn't in any food database yet. Take a photo of its nutrition label (營養標示) and check the " +
                    "numbers -- or type them in. It's saved under this barcode, so next time the scan is instant.",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton("PHOTO OF LABEL", emphasis = FTButtonEmphasis.Secondary, onClick = onTakePhoto)
                FTButton("CHOOSE PHOTO", emphasis = FTButtonEmphasis.Tertiary, onClick = onChoosePhoto)
            }
            when {
                reading -> Text("Reading the label...", style = FTType.Caption, color = FT.TextSecondary)
                readError != null -> Text("$readError -- you can type the numbers in.", style = FTType.Caption, color = FT.Warning)
            }
            if (checks.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CHECK THESE", style = FTType.LabelCaps, color = FT.Warning)
                    checks.forEach { Text("• ${it.message}", style = FTType.Caption, color = FT.Warning) }
                }
            }

            Column { LabelFormLabel("PRODUCT NAME"); FieldTextField(name, { name = it }, "e.g. 超能蛋白 咖啡口味") }
            Column { LabelFormLabel("BRAND (OPTIONAL)"); FieldTextField(brand, { brand = it }, "e.g. 福樂") }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    LabelFormLabel("SERVING SIZE (每一份量)")
                    FieldTextField(servingSize, { servingSize = it }, "e.g. 30", keyboardType = KeyboardType.Number)
                }
                Column(Modifier.weight(1f)) {
                    LabelFormLabel("SERVINGS / PACK")
                    FieldTextField(servingsPerPackage, { servingsPerPackage = it }, "e.g. 3", keyboardType = KeyboardType.Number)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("g", servingUnit == "g") { servingUnit = "g" }
                ChoiceChip("ml", servingUnit == "ml") { servingUnit = "ml" }
                Spacer(Modifier.weight(1f))
                ChoiceChip("PER SERVING", perServing) { perServing = true }
                ChoiceChip("PER 100", !perServing) { perServing = false }
            }

            LABEL_FIELDS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { field ->
                        Column(Modifier.weight(1f)) {
                            LabelFormLabel(field.label)
                            FieldTextField(values.getValue(field.key).value, { values.getValue(field.key).value = it }, "", keyboardType = KeyboardType.Number)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            saveError?.let { Text("Couldn't save ($it).", style = FTType.Caption, color = FT.Critical) }

            val size = servingSize.toAmount()
            val calories = values.getValue("calories").value.toAmount()
            val valid = name.isNotBlank() && calories != null && (!perServing || (size != null && size > 0))
            FTButton(
                label = if (saving) "SAVING..." else "SAVE PRODUCT",
                enabled = valid && !saving && !reading,
                emphasis = FTButtonEmphasis.Primary,
            ) {
                saving = true
                saveError = null
                val entered = LabelValues(
                    calories = calories,
                    proteinG = values.getValue("protein").value.toAmount(),
                    fatG = values.getValue("fat").value.toAmount(),
                    saturatedFatG = values.getValue("saturated_fat").value.toAmount(),
                    transFatG = values.getValue("trans_fat").value.toAmount(),
                    carbsG = values.getValue("carbs").value.toAmount(),
                    sugarG = values.getValue("sugar").value.toAmount(),
                    fiberG = values.getValue("fiber").value.toAmount(),
                    sodiumMg = values.getValue("sodium").value.toAmount(),
                )
                val label = ScannedLabel(
                    productName = name.trim(),
                    servingSize = size,
                    servingUnit = servingUnit,
                    servingsPerPackage = servingsPerPackage.toAmount(),
                    perServing = entered.takeIf { perServing },
                    per100 = entered.takeIf { !perServing },
                    energyUnit = "kcal",
                )
                scope.launch {
                    try {
                        val saved = repo.saveLabel(barcode, name.trim(), brand.trim().ifBlank { null }, label)
                        saved.food?.let(onSaved) ?: run { saveError = "no product returned" }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        saveError = e.message ?: "Unknown error"
                    } finally {
                        saving = false
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private class LabelField(val key: String, val label: String, val get: (LabelValues) -> Double?)

private val LABEL_FIELDS = listOf(
    LabelField("calories", "KCAL (熱量)") { it.calories },
    LabelField("protein", "PROTEIN G") { it.proteinG },
    LabelField("fat", "FAT G") { it.fatG },
    LabelField("saturated_fat", "SAT FAT G") { it.saturatedFatG },
    LabelField("trans_fat", "TRANS FAT G") { it.transFatG },
    LabelField("carbs", "CARBS G") { it.carbsG },
    LabelField("sugar", "SUGAR G") { it.sugarG },
    LabelField("fiber", "FIBER G") { it.fiberG },
    LabelField("sodium", "SODIUM MG") { it.sodiumMg },
)

private fun String.toAmount(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = FTType.LabelCaps,
        color = if (selected) FT.Emerald else FT.TextSecondary,
        modifier = Modifier
            .border(FT.BorderWidth, if (selected) FT.Emerald else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
            .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun LabelFormLabel(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}
