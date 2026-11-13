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
import com.bioscan.fieldterminal.data.MealItemSource
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
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.fuel.RunFuelEntry
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.util.PendingCameraCapture
import com.bioscan.fieldterminal.util.createCameraCaptureUri
import com.bioscan.fieldterminal.util.scanBarcode
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
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
            FuelSubType.Endurance -> RunFuelEntry(onSaved = onCanonicalSaved)
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", state.dateTime, { state.dateTime = it })
        MealSlotChips(state.dateTime, mealSlotOf(state.mealSlot), { state.mealSlot = it.name.lowercase() }, editingMealId)
        Column { FormLabel("DESCRIPTION"); FieldTextField(state.description, { state.description = it }, "e.g. Chicken rice bowl") }

        // Photo, description and barcode go through the server-side estimate and lookup functions; a candidate from any of
        // them opens a confirm or review sheet, and nothing in this block writes to `meals` directly.
        Column {
            FormLabel("LOG WITH AI OR BARCODE")
            state.PhotoEstimateSection(photos)
            state.BarcodeSection()
            state.RecentMealsSection(onCanonicalSaved)
        }

        state.ManualFields(saving, onSave)
    }

    state.Sheets(photos, onCanonicalSaved)
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
    PhotoActionButton(
        label = "FROM DESCRIPTION",
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        enabled = description.isNotBlank(),
    ) { runTextEstimate() }
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
                    runBarcodeLookup(code)
                }
            }
        }
    }
    barcodeError?.let {
        Text(it, style = FTType.BodySmall, color = FT.Bad, modifier = Modifier.padding(top = 8.dp))
    }
}

// REPEAT A RECENT MEAL: the last meals, each a tap that logs it again at this form's time and meal.
@Composable
private fun FoodLogState.RecentMealsSection(onCanonicalSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    Text(
        if (showRecentMeals) "HIDE RECENT MEALS" else "REPEAT A RECENT MEAL",
        style = sheetActionLabelStyle,
        color = FT.TextSecondary,
        modifier = Modifier
            .padding(top = 14.dp)
            .ftClickable {
                showRecentMeals = !showRecentMeals
            },
    )
    if (showRecentMeals) {
        LaunchedEffect(Unit) {
            if (recentMeals == null) {
                recentMeals = try { NutritionRepository(SupabaseClientProvider.client).loadRecentMeals() } catch (e: Exception) { emptyList() }
            }
        }
        val meals = recentMeals
        if (meals == null) {
            CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.padding(top = 8.dp).size(14.dp))
        } else if (meals.isEmpty()) {
            Text("No recent meals yet.", style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        } else {
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                meals.forEach { meal ->
                    Text(
                        meal.description ?: "Meal",
                        style = FTType.BodySmall,
                        color = if (cloning) FT.TextMuted else FT.TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .ftClickable(enabled = !cloning) {
                                val id = meal.id ?: return@ftClickable
                                cloning = true
                                scope.launch {
                                    try {
                                        NutritionMealSaveRepository(SupabaseClientProvider.client).cloneMeal(id, dateTime.toAppIso(), mealSlot)
                                        onCanonicalSaved()
                                    } catch (e: Exception) {
                                        barcodeError = e.message ?: "Could not repeat this meal"
                                    } finally {
                                        cloning = false
                                    }
                                }
                            }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// The fields typed by hand, and SAVE for a meal logged from them alone.
@Composable
private fun FoodLogState.ManualFields(
    saving: Boolean,
    onSave: (String, String, Double?, Double?, Double?, Double?, Double?, Double?, Double?, String?) -> Unit,
) {
    Column { FormLabel("CALORIES (OPTIONAL)"); FieldTextField(calories, { calories = it }, "e.g. 650", keyboardType = KeyboardType.Number) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { FormLabel("PROTEIN G"); FieldTextField(protein, { protein = it }, "0", keyboardType = KeyboardType.Number) }
        Column(Modifier.weight(1f)) { FormLabel("CARBS G"); FieldTextField(carbs, { carbs = it }, "0", keyboardType = KeyboardType.Number) }
        Column(Modifier.weight(1f)) { FormLabel("FAT G"); FieldTextField(fat, { fat = it }, "0", keyboardType = KeyboardType.Number) }
    }
    // DAV-77: kept to fiber/sugar/sodium -- the smallest real
    // "beyond macros" set, not the dozens of vitamins/minerals a
    // photo/description estimate can't plausibly guess anyway.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { FormLabel("FIBER G"); FieldTextField(fiber, { fiber = it }, "0", keyboardType = KeyboardType.Number) }
        Column(Modifier.weight(1f)) { FormLabel("SUGAR G"); FieldTextField(sugar, { sugar = it }, "0", keyboardType = KeyboardType.Number) }
        Column(Modifier.weight(1f)) { FormLabel("SODIUM MG"); FieldTextField(sodium, { sodium = it }, "0", keyboardType = KeyboardType.Number) }
    }
    SaveButton(saving, description.isNotBlank()) {
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
