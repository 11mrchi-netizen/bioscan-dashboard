package com.bioscan.fieldterminal.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.data.BarcodeLookupFood
import com.bioscan.fieldterminal.data.CronometerEnrichment
import com.bioscan.fieldterminal.data.CronometerItemInput
import com.bioscan.fieldterminal.data.MealItemSource
import com.bioscan.fieldterminal.data.NutritionBarcodeLookupRepository
import com.bioscan.fieldterminal.data.NutritionCronometerLookupRepository
import com.bioscan.fieldterminal.data.NutritionImageEstimateRepository
import com.bioscan.fieldterminal.data.NutritionMealEstimate
import com.bioscan.fieldterminal.data.PhotoAnswer
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.functionErrorMessage
import com.bioscan.fieldterminal.data.model.FoodRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.withMicronutrients
import com.bioscan.fieldterminal.domain.normalizeBarcode
import com.bioscan.fieldterminal.ui.components.UriListSaver
import com.bioscan.fieldterminal.util.PendingCameraCapture
import com.bioscan.fieldterminal.util.readAndCompressImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime

// Logging a food: what the form holds and what it can do. FoodForm (EntryFoodForms.kt) draws it.
//
// A food reaches the confirm sheet four ways: photos (Gemini reads them, a database lookup refines the numbers), a typed
// description, a barcode (a known product, or a scan of its label when no database has it), or the manual fields. The
// first three never write a meal themselves; they fill [mealEstimate] or [reviewSeedItems], whose sheets save.

// The barcode a label scan is for, with what the database knew about the product (an Open Food Facts entry without a
// nutrition table still has a name).
internal data class LabelTarget(val barcode: String, val name: String, val brand: String?)

@Stable
internal class FoodLogState(
    private val context: Context,
    private val scope: CoroutineScope,
    dateTimeState: MutableState<LocalDateTime>,
    mealSlotState: MutableState<String?>,
    descriptionState: MutableState<String>,
    caloriesState: MutableState<String>,
    proteinState: MutableState<String>,
    carbsState: MutableState<String>,
    fatState: MutableState<String>,
    fiberState: MutableState<String>,
    sugarState: MutableState<String>,
    sodiumState: MutableState<String>,
    pendingCameraUriState: MutableState<Uri?>,
    pendingPhotosState: MutableState<List<Uri>>,
    photoNoteState: MutableState<String>,
    photoWeightState: MutableState<String>,
    estimatedPhotosState: MutableState<List<Uri>>,
) {
    // Kept across the camera: TAKE PHOTO hands the screen to the camera app, which can get this app killed and restored.
    var dateTime by dateTimeState
    var mealSlot by mealSlotState
    var description by descriptionState
    var calories by caloriesState
    var protein by proteinState
    var carbs by carbsState
    var fat by fatState
    var fiber by fiberState
    var sugar by sugarState
    var sodium by sodiumState
    var pendingCameraUri by pendingCameraUriState
    // One or more photos of the same meal queue up here before a single ESTIMATE tap, so the estimate sees the whole plate.
    var pendingPhotos by pendingPhotosState
    // What the person says the meal is (it wins over the photo), the weighed food, and the photos and answers of the last
    // estimate, so a tapped answer or a correction reruns it on the same photos.
    var photoNote by photoNoteState
    var photoWeight by photoWeightState
    var estimatedPhotos by estimatedPhotosState

    var estimating by mutableStateOf(false)
    var estimationError by mutableStateOf<String?>(null)
    // A photo taken just before the app was killed, whose result never arrived (util/ImageUtils.kt PendingCameraCapture).
    var strandedPhoto by mutableStateOf<Uri?>(null)
    var photoAnswers by mutableStateOf<List<PhotoAnswer>>(emptyList())
    var mealEstimate by mutableStateOf<NutritionMealEstimate?>(null)
    var mealEstimateId by mutableStateOf<Long?>(null)
    var cronometerEnrichment by mutableStateOf<CronometerEnrichment?>(null)
    var enriching by mutableStateOf(false)
    var mealEstimateSource by mutableStateOf(MealItemSource.AiImage)

    // Candidates from any source funnel into the same review sheet before ever touching meal_items.
    var reviewSeedItems by mutableStateOf<List<ReviewSeedItem>?>(null)
    // A barcode no database has calories for: the label-scan sheet for it, and the photos taken of that label.
    var labelTarget by mutableStateOf<LabelTarget?>(null)
    var labelPhotos by mutableStateOf<List<Uri>>(emptyList())
    /** Meal photos and label photos share the camera and the picker; this says where the next result goes. */
    var photoForLabel by mutableStateOf(false)
    var barcodeInput by mutableStateOf("")
    var barcodeLooking by mutableStateOf(false)
    var barcodeError by mutableStateOf<String?>(null)
    var showRecentMeals by mutableStateOf(false)
    var recentMeals by mutableStateOf<List<MealRow>?>(null)
    var cloning by mutableStateOf(false)

    // [answers] and a corrected [note] rerun the estimate on the same photos (from the confirm sheet); the photo's own
    // foods then go to the database lookup as they are, at the grams the photo showed.
    fun runImageEstimate(uris: List<Uri>, answers: List<PhotoAnswer> = emptyList()) {
        if (uris.isEmpty()) return
        estimating = true
        estimationError = null
        mealEstimateSource = MealItemSource.AiImage
        scope.launch {
            try {
                val images = uris.map { readAndCompressImage(context, it) }
                val result = NutritionImageEstimateRepository(SupabaseClientProvider.client).estimate(
                    images,
                    note = photoNote,
                    totalWeightG = photoWeight.replace(',', '.').toDoubleOrNull(),
                    answers = answers,
                )
                estimatedPhotos = uris
                photoAnswers = answers
                cronometerEnrichment = null
                mealEstimateId = result.estimateId
                mealEstimate = result.estimate
                pendingPhotos = emptyList()
                // The Gemini estimate is usable now; the Cronometer lookup below must not keep the spinner running.
                estimating = false

                enriching = true
                try {
                    val lookup = NutritionCronometerLookupRepository(SupabaseClientProvider.client)
                    cronometerEnrichment = if (result.estimate.items.isNotEmpty()) {
                        lookup.enrichPhoto(result.estimate.items, result.estimate.description)
                    } else {
                        lookup.enrich(result.estimate.description)
                    }
                } catch (_: Exception) {
                    // Enrichment is best-effort; Gemini estimate stands if Cronometer fails
                } finally {
                    enriching = false
                }
            } catch (e: Exception) {
                estimationError = e.functionErrorMessage()
            } finally {
                estimating = false
            }
        }
    }

    fun runTextEstimate() {
        if (description.isBlank()) return
        estimating = true
        estimationError = null
        cronometerEnrichment = null
        mealEstimateSource = MealItemSource.AiText
        scope.launch {
            try {
                enriching = true
                val enrichResult = NutritionCronometerLookupRepository(SupabaseClientProvider.client)
                    .enrich(description)
                cronometerEnrichment = enrichResult
                mealEstimate = NutritionMealEstimate(
                    description = description.trim(),
                    calories = enrichResult.calories ?: 0.0,
                    proteinG = enrichResult.proteinG ?: 0.0,
                    carbsG = enrichResult.carbsG ?: 0.0,
                    fatG = enrichResult.fatG ?: 0.0,
                    fiberG = enrichResult.fiberG,
                    sugarG = enrichResult.sugarG,
                    sodiumMg = enrichResult.sodiumMg,
                    confidence = enrichResult.confidence,
                )
                mealEstimateId = null
            } catch (e: Exception) {
                estimationError = e.functionErrorMessage()
            } finally {
                enriching = false
                estimating = false
            }
        }
    }

    // A barcode product as one catalog item (its label numbers, servings and package size), topped up with a good
    // Cronometer match's micronutrients; the confirm sheet then offers serving / package / count portions.
    suspend fun showBarcodeProduct(food: BarcodeLookupFood) {
        val foodDesc = if (food.brand != null) "${food.name} (${food.brand})" else food.name
        val lookup = NutritionCronometerLookupRepository(SupabaseClientProvider.client)
        enriching = true
        try {
            val product = lookup.enrichItems(listOf(CronometerItemInput(foodDesc, catalogFoodId = food.id)))
            val micros = try {
                lookup.enrichItems(listOf(CronometerItemInput(food.name, quantityG = product.items.firstOrNull()?.quantityG)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val enrichResult = withMicronutrients(product, micros).copy(confidence = 0.95, aiEstimateTotal = null)
            cronometerEnrichment = enrichResult
            mealEstimate = NutritionMealEstimate(
                description = foodDesc,
                calories = enrichResult.calories ?: 0.0,
                proteinG = enrichResult.proteinG ?: 0.0,
                carbsG = enrichResult.carbsG ?: 0.0,
                fatG = enrichResult.fatG ?: 0.0,
                fiberG = enrichResult.fiberG,
                sugarG = enrichResult.sugarG,
                sodiumMg = enrichResult.sodiumMg,
                confidence = enrichResult.confidence,
            )
            mealEstimateId = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reviewSeedItems = listOf(
                ReviewSeedItem(
                    description = food.name,
                    isBeverage = food.beverageClass != null,
                    foodConfidence = 1.0,
                    source = MealItemSource.Barcode,
                    preMatchedFood = FoodRow(
                        id = food.id,
                        foodSourceId = 0,
                        name = food.name,
                        brand = food.brand,
                        barcode = food.barcode,
                        category = food.category,
                        beverageClass = food.beverageClass,
                        beverageSubtype = food.beverageSubtype,
                    ),
                ),
            )
        } finally {
            enriching = false
        }
    }

    // Known product -> confirm sheet. Unknown barcode, or a product without calories -> scan its nutrition label.
    fun runBarcodeLookup(raw: String = barcodeInput) {
        val barcode = normalizeBarcode(raw) ?: raw.trim()
        if (barcode.isBlank()) return
        barcodeLooking = true
        barcodeError = null
        cronometerEnrichment = null
        mealEstimateSource = MealItemSource.Barcode
        scope.launch {
            try {
                val result = NutritionBarcodeLookupRepository(SupabaseClientProvider.client).lookup(barcode)
                val food = result.food
                if (food == null || result.needsLabel) {
                    labelPhotos = emptyList()
                    labelTarget = LabelTarget(barcode, food?.nameZh ?: food?.name ?: "", food?.brand)
                } else {
                    showBarcodeProduct(food)
                }
            } catch (e: Exception) {
                barcodeError = e.functionErrorMessage()
            } finally {
                barcodeLooking = false
            }
        }
    }
}

@Composable
internal fun rememberFoodLogState(
    initialDateTime: LocalDateTime,
    initialMealSlot: String?,
    initialDescription: String,
    initialCalories: Double?,
    initialProtein: Double?,
    initialCarbs: Double?,
    initialFat: Double?,
    initialFiber: Double?,
    initialSugar: Double?,
    initialSodium: Double?,
): FoodLogState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateTime = rememberSaveable { mutableStateOf(initialDateTime) }
    val mealSlot = rememberSaveable { mutableStateOf(initialMealSlot) }
    val description = rememberSaveable { mutableStateOf(initialDescription) }
    val calories = rememberSaveable { mutableStateOf(initialCalories?.toString() ?: "") }
    val protein = rememberSaveable { mutableStateOf(initialProtein?.toString() ?: "") }
    val carbs = rememberSaveable { mutableStateOf(initialCarbs?.toString() ?: "") }
    val fat = rememberSaveable { mutableStateOf(initialFat?.toString() ?: "") }
    val fiber = rememberSaveable { mutableStateOf(initialFiber?.toString() ?: "") }
    val sugar = rememberSaveable { mutableStateOf(initialSugar?.toString() ?: "") }
    val sodium = rememberSaveable { mutableStateOf(initialSodium?.toString() ?: "") }
    val pendingCameraUri = rememberSaveable { mutableStateOf<Uri?>(null) }
    val pendingPhotos = rememberSaveable(stateSaver = UriListSaver) { mutableStateOf<List<Uri>>(emptyList()) }
    val photoNote = rememberSaveable { mutableStateOf("") }
    val photoWeight = rememberSaveable { mutableStateOf("") }
    val estimatedPhotos = rememberSaveable(stateSaver = UriListSaver) { mutableStateOf<List<Uri>>(emptyList()) }
    val state = remember {
        FoodLogState(
            context, scope, dateTime, mealSlot, description, calories, protein, carbs, fat, fiber, sugar, sodium,
            pendingCameraUri, pendingPhotos, photoNote, photoWeight, estimatedPhotos,
        )
    }
    LaunchedEffect(Unit) {
        val stranded = PendingCameraCapture.stranded(context)
        if (stranded != null && stranded !in state.pendingPhotos) state.strandedPhoto = stranded
    }
    return state
}
