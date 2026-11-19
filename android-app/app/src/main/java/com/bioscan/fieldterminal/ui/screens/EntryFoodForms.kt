package com.bioscan.fieldterminal.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.MEAL_PORTIONS
import com.bioscan.fieldterminal.data.MealItemSource
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.portionLabel
import com.bioscan.fieldterminal.data.scaled
import com.bioscan.fieldterminal.data.NutritionMealSaveRepository
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.FuelSubType
import com.bioscan.fieldterminal.domain.fuel.mealSlotOf
import com.bioscan.fieldterminal.domain.target
import com.bioscan.fieldterminal.domain.toAppIso
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.DateTimeField
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.FlowEssentials
import com.bioscan.fieldterminal.ui.components.FlowMore
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.FlowTile
import com.bioscan.fieldterminal.ui.components.FormBackHandler
import com.bioscan.fieldterminal.ui.components.LocalFlowStartPage
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.util.PendingCameraCapture
import com.bioscan.fieldterminal.util.createCameraCaptureUri
import com.bioscan.fieldterminal.util.scanBarcode
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// + LOG: food, fuel taken during a session, and drinks.

// FUEL: one entry point, three sub-tabs. Reuses FoodForm/DrinkForm/
// SupplementsForm exactly as they'd be used standalone -- only the picker
// wrapping them is new.
@Composable
internal fun FuelForm(
    saving: Boolean,
    onSubmit: ((suspend (AddEntryRepository) -> Unit)) -> Unit,
    onCanonicalSaved: () -> Unit,
    externalActivityActive: MutableState<Boolean>? = null,
    foodPrefill: Pair<String, LocalDateTime>? = null,
    initialSubType: FuelSubType = FuelSubType.Food,
    supplementBundle: String? = null,
) {
    // A "say it" food hand-off always lands on FOOD, whatever the sheet was opened on.
    var subType by rememberSaveable { mutableStateOf(if (foodPrefill != null) FuelSubType.Food else initialSubType) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelSubType.entries.forEach { sub ->
                val isSelected = sub == subType
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .border(FT.BorderWidth, if (isSelected) Color.Transparent else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                        .background(if (isSelected) FT.PlatinumSolid else Color.Transparent, RoundedCornerShape(FT.RadiusSmall))
                        .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall)) { subType = sub }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(sub.label, style = sheetActionLabelStyle, color = if (isSelected) FT.PlatinumInk else FT.TextSecondary)
                }
            }
        }
        when (subType) {
            FuelSubType.Food -> FoodForm(
                saving,
                initialDateTime = foodPrefill?.second ?: LocalDateTime.now(),
                initialDescription = foodPrefill?.first ?: "",
                onSave = { dt, desc, cal, p, c, f, fi, su, so, slot -> onSubmit { it.addFood(dt, desc, cal, p, c, f, fi, su, so, slot) } },
                onCanonicalSaved = onCanonicalSaved,
                externalActivityActive = externalActivityActive,
            )
            FuelSubType.Drink -> DrinkLogForm(
                saving,
                onWater = { date, ml -> onSubmit { it.addDrink(date, ml) } },
                onSaved = onCanonicalSaved,
                externalActivityActive = externalActivityActive,
            )
            FuelSubType.Supplements -> SupplementsForm(saving, preselectBundle = supplementBundle, onSave = { takenAt, items, source, quantities, sites -> onSubmit { it.addSupplementsTaken(takenAt, items, source, quantities, sites) } })
            FuelSubType.Endurance -> com.bioscan.fieldterminal.ui.screens.fuel.RunFuelFlow(onDismiss = { subType = FuelSubType.Food }, onSaved = onCanonicalSaved)
        }
    }
}

@Composable
internal fun FoodForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialDescription: String = "",
    initialCalories: Double? = null,
    initialProtein: Double? = null,
    initialCarbs: Double? = null,
    initialFat: Double? = null,
    initialFiber: Double? = null,
    initialSugar: Double? = null,
    initialSodium: Double? = null,
    // Which planned meal it was; null pre-selects one (MealSlotChips). [editingMealId] leaves its own label out of the day's.
    initialMealSlot: String? = null,
    editingMealId: Long? = null,
    onSave: (
        loggedAt: String,
        description: String,
        calories: Double?,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
        fiberG: Double?,
        sugarG: Double?,
        sodiumMg: Double?,
        mealSlot: String?,
    ) -> Unit,
    // DAV-168: the canonical (foods/meal_items) save path doesn't go through
    // AddEntryRepository at all -- NutritionMealSaveRepository writes
    // directly -- so it can't reuse onSave's addFood-shaped contract above.
    // This is the same "the whole sheet is done" signal AddEntrySheet's own
    // onSaved already is, just reachable from inside this nested form.
    onCanonicalSaved: () -> Unit = {},
    externalActivityActive: MutableState<Boolean>? = null,
) {
    val state = rememberFoodLogState(
        initialDateTime, initialMealSlot, initialDescription, initialCalories, initialProtein, initialCarbs, initialFat,
        initialFiber, initialSugar, initialSodium,
    )
    val photos = state.rememberPhotoSources(externalActivityActive)

    // HOW first (Logging Flows Redesign Plan, Nov 2026): the way the meal is logged decides the pages after it. A recent
    // meal goes to its CONFIRM; a photo, a barcode or a described meal go through the estimate or the lookup to their
    // confirm sheet; BY HAND is one page that saves itself. Time and meal slot sit where the meal is confirmed, filled in
    // for now. An entry being edited, or one handed over with its numbers, opens BY HAND directly.
    val direct = editingMealId != null || initialCalories != null
    // The fit test opens each path in turn (LocalFlowStartPage); the app starts at HOW.
    val testStart = LocalFlowStartPage.current
    var mode by rememberSaveable {
        mutableStateOf(
            when {
                testStart in 1..4 -> listOf(FoodMode.ByHand, FoodMode.Describe, FoodMode.Photo, FoodMode.Barcode)[testStart - 1]
                direct -> FoodMode.ByHand
                initialDescription.isNotBlank() -> FoodMode.Describe
                else -> FoodMode.How
            },
        )
    }
    var recentPick by remember { mutableStateOf<MealRow?>(null) }
    if (mode == FoodMode.Recent && recentPick == null) mode = FoodMode.How
    FormBackHandler(enabled = !direct && mode != FoodMode.How) { mode = FoodMode.How; recentPick = null }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (mode) {
            FoodMode.How -> state.HowPage(onMode = { mode = it }, onRecent = { recentPick = it; mode = FoodMode.Recent })
            FoodMode.Recent -> recentPick?.let { state.RecentConfirm(it, editingMealId, onCanonicalSaved) }
            FoodMode.Photo -> {
                FlowQuestion("Photograph the meal", "One or more photos of the same plate. What it is or its weight makes the estimate better.")
                state.PhotoEstimateSection(photos)
            }
            FoodMode.Barcode -> {
                FlowQuestion("Scan the barcode", "Or type its number. A product no database knows opens the label to copy.")
                state.BarcodeSection()
            }
            FoodMode.Describe -> state.DescribePage()
            FoodMode.ByHand -> {
                FlowEssentials {
                    state.WhenFields(editingMealId)
                    Column { FormLabel("DESCRIPTION"); FieldTextField(state.description, { state.description = it }, "e.g. Chicken rice bowl") }
                    state.ManualFields()
                }
                FlowMore("food_by_hand", summary = "Fiber, sugar, sodium") { state.ExtraFields() }
                SaveButton(saving, state.description.isNotBlank()) { state.saveManual(onSave) }
            }
        }
    }

    state.Sheets(photos, onCanonicalSaved)
}

private enum class FoodMode { How, Recent, Photo, Barcode, Describe, ByHand }

/** Time and meal slot, filled in for now and the slot of this hour. */
@Composable
private fun FoodLogState.WhenFields(editingMealId: Long?) {
    DateTimeField("WHEN", dateTime, { dateTime = it })
    MealSlotChips(dateTime, mealSlotOf(mealSlot), { mealSlot = it.name.lowercase() }, editingMealId)
}

// HOW: the recent meals first (each a tap to its CONFIRM), then the four ways in.
@Composable
private fun FoodLogState.HowPage(onMode: (FoodMode) -> Unit, onRecent: (MealRow) -> Unit) {
    FlowQuestion("How are you logging it?")
    LaunchedEffect(Unit) {
        if (recentMeals == null) {
            recentMeals = try { NutritionRepository(SupabaseClientProvider.client).loadRecentMeals(limit = 20) } catch (e: Exception) { emptyList() }
        }
    }
    FlowEssentials {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowTile("PHOTO", false, Modifier.weight(1f)) { onMode(FoodMode.Photo) }
            FlowTile("BARCODE", false, Modifier.weight(1f)) { onMode(FoodMode.Barcode) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowTile("DESCRIBE IT", false, Modifier.weight(1f)) { onMode(FoodMode.Describe) }
            FlowTile("BY HAND", false, Modifier.weight(1f)) { onMode(FoodMode.ByHand) }
        }
    }
    val meals = recentMeals
    FormLabel("AGAIN")
    when {
        meals == null -> CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.size(14.dp))
        meals.isEmpty() -> Text("No meals logged yet.", style = FTType.BodySmall, color = FT.TextSecondary)
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            meals.filter { !it.description.isNullOrBlank() }.distinctBy { it.description!!.trim().lowercase() }.take(8).forEach { meal ->
                val shape = RoundedCornerShape(FT.RadiusSmall)
                Row(
                    Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, shape)
                        .ftClickable(shape = shape, onClickLabel = "Log ${meal.description} again") { onRecent(meal) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(meal.description ?: "Meal", style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f), maxLines = 2)
                    meal.calories?.let { Text("${it.roundToInt()} kcal", style = FTType.MonoCaption, color = FT.TextSecondary) }
                }
            }
        }
    }
}

// CONFIRM for a meal logged again: its portion, the numbers it comes to, time and slot, and SAVE.
@Composable
private fun FoodLogState.RecentConfirm(meal: MealRow, editingMealId: Long?, onCanonicalSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var portion by rememberSaveable(meal.id) { mutableStateOf(1.0) }
    val m = meal.scaled(portion)
    FlowEssentials {
        FlowQuestion(meal.description ?: "Meal", "Logged again: pick the portion against last time.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MEAL_PORTIONS.forEach { p -> FlowTile(portionLabel(p), p == portion, Modifier.weight(1f)) { portion = p } }
        }
        Text(
            listOfNotNull(
                m.calories?.let { "${it.roundToInt()} kcal" }, m.carbsG?.let { "carbs ${it.roundToInt()} g" },
                m.proteinG?.let { "protein ${it.roundToInt()} g" }, m.fatG?.let { "fat ${it.roundToInt()} g" },
            ).joinToString(" · ").ifEmpty { "No numbers on this meal." },
            style = FTType.BodySmall, color = FT.TextSecondary,
        )
        WhenFields(editingMealId)
    }
    barcodeError?.let { Text(it, style = FTType.BodySmall, color = FT.Bad) }
    SaveButton(cloning, meal.id != null) {
        val id = meal.id ?: return@SaveButton
        cloning = true
        barcodeError = null
        scope.launch {
            try {
                NutritionMealSaveRepository(SupabaseClientProvider.client).cloneMeal(id, dateTime.toAppIso(), mealSlot, portion)
                onCanonicalSaved()
            } catch (e: Exception) {
                barcodeError = e.message ?: "Could not log this meal again"
            } finally {
                cloning = false
            }
        }
    }
}

// DESCRIBE IT: one line, and the estimate opens its confirm sheet.
@Composable
private fun FoodLogState.DescribePage() {
    FlowEssentials {
        FlowQuestion("Describe the meal", "What and roughly how much, e.g. \"beef noodle soup, large bowl\".")
        FieldTextField(description, { description = it }, "What you ate", singleLine = false)
        PhotoActionButton(
            label = if (estimating) "ESTIMATING..." else "ESTIMATE",
            modifier = Modifier.fillMaxWidth(),
            enabled = description.isNotBlank() && !estimating,
        ) { runTextEstimate() }
    }
    if (estimating) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.size(14.dp))
            Text("Estimating...", style = FTType.BodySmall, color = FT.TextSecondary)
        }
    }
    estimationError?.let { Text(it, style = FTType.BodySmall, color = FT.Bad) }
}

/** The camera and the photo picker, already wired to put what they return where the form expects it. */
internal class PhotoSources(val camera: () -> Unit, val picker: () -> Unit)

@Composable
private fun FoodLogState.rememberPhotoSources(externalActivityActive: MutableState<Boolean>?): PhotoSources {
    val context = LocalContext.current
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        externalActivityActive?.value = false
        PendingCameraCapture.clear(context)
        if (success) pendingCameraUri?.let {
            if (photoForLabel) labelPhotos = labelPhotos + it
            else if (it !in pendingPhotos) pendingPhotos = pendingPhotos + it
        }
        photoForLabel = false
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createCameraCaptureUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
            // externalActivityActive stays true: camera is about to take foreground
        } else {
            externalActivityActive?.value = false
            photoForLabel = false
            estimationError = "Camera permission denied"
        }
    }
    val pickPhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        externalActivityActive?.value = false
        if (uris.isNotEmpty()) {
            if (photoForLabel) labelPhotos = labelPhotos + uris else pendingPhotos = pendingPhotos + uris
        }
        photoForLabel = false
    }

    // Meal photos and label photos share the camera and the picker; photoForLabel says where the result goes.
    fun launchCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        externalActivityActive?.value = true
        if (granted) {
            val uri = createCameraCaptureUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun launchPicker() {
        externalActivityActive?.value = true
        pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    return PhotoSources(::launchCamera, ::launchPicker)
}

// Photos of the meal, the optional note and weight, ESTIMATE MEAL, and the estimate from the typed description.
@Composable
private fun FoodLogState.PhotoEstimateSection(photos: PhotoSources) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PhotoActionButton(label = "TAKE PHOTO", modifier = Modifier.weight(1f)) {
            photoForLabel = false
            photos.camera()
        }
        PhotoActionButton(label = "CHOOSE PHOTOS", modifier = Modifier.weight(1f)) {
            photoForLabel = false
            photos.picker()
        }
    }
    strandedPhoto?.let { photo ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            Text("PHOTO JUST TAKEN", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            FTButton("USE IT", emphasis = FTButtonEmphasis.Secondary) {
                pendingPhotos = pendingPhotos + photo
                strandedPhoto = null
                PendingCameraCapture.clear(context)
            }
            FTButton("DISCARD", emphasis = FTButtonEmphasis.Tertiary) {
                strandedPhoto = null
                PendingCameraCapture.clear(context)
            }
        }
    }
    if (pendingPhotos.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Text(
                "${pendingPhotos.size} photo${if (pendingPhotos.size == 1) "" else "s"} ready",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
            Text(
                "CLEAR",
                style = sheetActionLabelStyle,
                color = FT.Bad,
                modifier = Modifier.ftClickable {
                    pendingPhotos = emptyList()
                },
            )
        }
        // Photo logging v3: optional, and both make the estimate better than the photo alone.
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldTextField(photoNote, { photoNote = it }, "What is it? (optional, e.g. beef noodle soup, no oil)", singleLine = false)
            FieldTextField(photoWeight, { photoWeight = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, "Weighed? total grams (optional)", keyboardType = KeyboardType.Number)
        }
        PhotoActionButton(
            label = if (estimating) "ESTIMATING..." else "ESTIMATE MEAL",
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            enabled = !estimating,
        ) { runImageEstimate(pendingPhotos) }
    }
    if (estimating) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.size(14.dp))
            Text("Estimating...", style = FTType.BodySmall, color = FT.TextSecondary)
        }
    }
    estimationError?.let {
        Text(it, style = FTType.BodySmall, color = FT.Bad, modifier = Modifier.padding(top = 10.dp))
    }
}

// A barcode typed or scanned: a known product goes to the confirm sheet, an unknown one to the label scan.
@Composable
private fun FoodLogState.BarcodeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
        FieldTextField(
            barcodeInput,
            { barcodeInput = it },
            "Barcode number",
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Number,
        )
        PhotoActionButton(label = if (barcodeLooking) "..." else "LOOKUP", enabled = barcodeInput.isNotBlank() && !barcodeLooking) {
            runBarcodeLookup()
        }
        PhotoActionButton(label = "SCAN", enabled = !barcodeLooking) {
            scope.launch {
                val code = scanBarcode(context) { barcodeError = it }
                if (code != null) {
                    barcodeInput = code
                    runBarcodeLookup(code, scanned = true)
                }
            }
        }
    }
    barcodeError?.let {
        Text(it, style = FTType.BodySmall, color = FT.Bad, modifier = Modifier.padding(top = 8.dp))
    }
}

// The numbers typed by hand: kcal and the three macros.
@Composable
private fun FoodLogState.ManualFields() {
    Column { FormLabel("CALORIES (OPTIONAL)"); FieldTextField(calories, { calories = it }, "e.g. 650", keyboardType = KeyboardType.Number) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { FormLabel("PROTEIN G"); FieldTextField(protein, { protein = it }, "0", keyboardType = KeyboardType.Number) }
        Column(Modifier.weight(1f)) { FormLabel("CARBS G"); FieldTextField(carbs, { carbs = it }, "0", keyboardType = KeyboardType.Number) }
        Column(Modifier.weight(1f)) { FormLabel("FAT G"); FieldTextField(fat, { fat = it }, "0", keyboardType = KeyboardType.Number) }
    }
}

// DAV-77: kept to fiber/sugar/sodium -- the smallest real "beyond macros" set, not the dozens of vitamins/minerals a
// photo/description estimate can't plausibly guess anyway. Folded under MORE DETAIL.
@Composable
private fun FoodLogState.ExtraFields() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { FormLabel("FIBER G"); FieldTextField(fiber, { fiber = it }, "0", keyboardType = KeyboardType.Number) }
        Column(Modifier.weight(1f)) { FormLabel("SUGAR G"); FieldTextField(sugar, { sugar = it }, "0", keyboardType = KeyboardType.Number) }
        Column(Modifier.weight(1f)) { FormLabel("SODIUM MG"); FieldTextField(sodium, { sodium = it }, "0", keyboardType = KeyboardType.Number) }
    }
}

/** SAVE for a meal logged from the typed fields alone. */
private fun FoodLogState.saveManual(onSave: (String, String, Double?, Double?, Double?, Double?, Double?, Double?, Double?, String?) -> Unit) {
    onSave(
        dateTime.toAppIso(),
        description.trim(),
        calories.toDoubleOrNull(),
        protein.toDoubleOrNull(),
        carbs.toDoubleOrNull(),
        fat.toDoubleOrNull(),
        fiber.toDoubleOrNull(),
        sugar.toDoubleOrNull(),
        sodium.toDoubleOrNull(),
        mealSlot,
    )
}

// The sheets the form opens: the review of candidates, the label scan of an unknown barcode, and the estimate to confirm.
@Composable
private fun FoodLogState.Sheets(photos: PhotoSources, onCanonicalSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    reviewSeedItems?.let { seeds ->
        NutritionCandidateReviewSheet(
            seedItems = seeds,
            mealDateTime = dateTime,
            mealSlot = mealSlot,
            onDismiss = { reviewSeedItems = null },
            onSaved = {
                reviewSeedItems = null
                onCanonicalSaved()
            },
        )
    }

    labelTarget?.let { target ->
        LabelEntrySheet(
            barcode = target.barcode,
            initialName = target.name,
            initialBrand = target.brand,
            photos = labelPhotos,
            onTakePhoto = {
                photoForLabel = true
                photos.camera()
            },
            onChoosePhoto = {
                photoForLabel = true
                photos.picker()
            },
            onSaved = { food ->
                labelTarget = null
                labelPhotos = emptyList()
                mealEstimateSource = MealItemSource.Barcode
                scope.launch { showBarcodeProduct(food) }
            },
            onDismiss = {
                labelTarget = null
                labelPhotos = emptyList()
            },
        )
    }

    mealEstimate?.let { estimate ->
        NutritionEstimateConfirmSheet(
            estimate = estimate,
            mealDateTime = dateTime,
            mealSlot = mealSlot,
            aiEstimateId = mealEstimateId,
            enrichment = cronometerEnrichment,
            enriching = enriching,
            source = mealEstimateSource,
            // Photo logging v3: a tapped answer, or a correction of what the meal is, reruns the estimate on the same photos.
            rerunning = estimating,
            onAnswer = if (mealEstimateSource == MealItemSource.AiImage && estimatedPhotos.isNotEmpty()) ({ answers ->
                runImageEstimate(estimatedPhotos, photoAnswers.filter { a -> answers.none { it.question == a.question } } + answers)
            }) else null,
            onCorrect = if (mealEstimateSource == MealItemSource.AiImage && estimatedPhotos.isNotEmpty()) ({ note, answers ->
                photoNote = note
                runImageEstimate(estimatedPhotos, photoAnswers.filter { a -> answers.none { it.question == a.question } } + answers)
            }) else null,
            onDismiss = { mealEstimate = null; mealEstimateId = null; cronometerEnrichment = null },
            onSaved = {
                mealEstimate = null
                mealEstimateId = null
                cronometerEnrichment = null
                photoNote = ""
                photoWeight = ""
                photoAnswers = emptyList()
                onCanonicalSaved()
            },
        )
    }
}


// Beverage classes from DAV-181's hydration_factor_models vocabulary that
// go through the meal_items path (non-water). Water stays on the simple
// hydration_daily path for backward compatibility.

// Editing a day's water total (hydration_daily); new drinks are logged in DrinkLogForm.
@Composable
internal fun DrinkForm(
    saving: Boolean,
    initialDate: LocalDate = LocalDate.now(),
    initialMl: Int? = null,
    onSave: (date: String, ml: Int) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    var ml by remember { mutableStateOf(initialMl?.toString() ?: "") }
    val mlValue = ml.toIntOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateField("DATE", date, { date = it })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormLabel("WATER THAT DAY (ML)")
            TextChipRow(DRINK_PRESETS_ML, ml.takeIf { it in DRINK_PRESETS_ML }, perRow = 4) { ml = it ?: "" }
            FieldTextField(ml, { ml = it }, "e.g. 500", keyboardType = KeyboardType.Number)
        }
        SaveButton(saving, mlValue != null && mlValue > 0) {
            onSave(date.toString(), mlValue!!)
        }
    }
}

private val DRINK_PRESETS_ML = listOf("250", "330", "500", "750")
internal val OSTRC_Q1Q4_OPTIONS = listOf("0", "8", "17", "25")
internal val OSTRC_Q2Q3_OPTIONS = listOf("0", "6", "13", "19", "25")
