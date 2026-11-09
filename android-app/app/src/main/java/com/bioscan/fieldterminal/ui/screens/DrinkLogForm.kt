package com.bioscan.fieldterminal.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bioscan.fieldterminal.data.DrinksRepository
import com.bioscan.fieldterminal.data.NutritionBarcodeLookupRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.DrinkMatch
import com.bioscan.fieldterminal.domain.STANDARD_DRINK_G
import com.bioscan.fieldterminal.domain.UsualDrink
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.drinkAmounts
import com.bioscan.fieldterminal.domain.drinkSizes
import com.bioscan.fieldterminal.domain.normalizeBarcode
import com.bioscan.fieldterminal.domain.usualDrinks
import com.bioscan.fieldterminal.ui.components.DateTimeField
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTRatingSlider
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.ftGlass
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.createCameraCaptureUri
import com.bioscan.fieldterminal.util.scanBarcode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

// The drink log: a real drink, found by usual drinks, its kind, a search of the catalog (USDA, Taiwan FDA, scanned
// products) or its barcode, then the amount on a slider in the drink's own sizes. Plain water keeps the day's running
// total (hydration_daily); every other drink is saved as a meal item with its calories, sugar, caffeine and alcohol.

/** The kinds of drink, in the order offered, with their names. */
internal val DRINK_KINDS = listOf(
    "water" to "Water", "coffee" to "Coffee", "tea" to "Tea", "milk" to "Milk", "plant_milk" to "Plant milk",
    "juice" to "Juice", "soda" to "Soda", "electrolyte" to "Electrolyte", "alcohol" to "Alcohol",
)

private val ALCOHOL_STRENGTHS = listOf("beer 5" to "5", "wine 12" to "12", "spirits 40" to "40")

/** Where a catalog drink's numbers come from, as said to the person. */
private fun sourceName(source: String?) = when (source) {
    "usda_fdc_sr_legacy", "usda_fdc_foundation_foods" -> "USDA"
    "taiwan_fda_nutrient_db" -> "Taiwan FDA"
    "openfoodfacts" -> "Open Food Facts"
    "manual", null -> "your label"
    else -> source
}

private const val STEP_ML = 10

@Composable
internal fun DrinkLogForm(
    saving: Boolean,
    onWater: (date: String, ml: Int) -> Unit,
    onSaved: () -> Unit,
    externalActivityActive: MutableState<Boolean>? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { DrinksRepository(SupabaseClientProvider.client) }
    val zone = remember { ZoneId.systemDefault() }

    var dateTime by remember { mutableStateOf(LocalDateTime.now()) }
    var defaults by remember { mutableStateOf<List<DrinkMatch>>(emptyList()) }
    var usual by remember { mutableStateOf<List<Pair<UsualDrink, DrinkMatch?>>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DrinkMatch>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    var kind by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<DrinkMatch?>(null) }
    var pickedFromScan by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<Int?>(null) }
    var abv by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // A barcode no database has numbers for: its label is scanned, then the saved product is picked.
    var labelBarcode by remember { mutableStateOf<String?>(null) }
    var labelPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun pick(match: DrinkMatch?, k: String?, ml: Double? = null, scanned: Boolean = false) {
        picked = match
        pickedFromScan = scanned
        kind = k ?: match?.beverageClass
        error = null
        abv = match?.alcoholG?.takeIf { it > 0 }?.let { "%.1f".format(it / 0.789).trimEnd('0').trimEnd('.') } ?: ""
        val start = ml ?: drinkSizes(match, kind).firstOrNull { it.ml >= 200 }?.ml ?: 250.0
        steps = (start / STEP_ML).roundToInt().coerceIn(1, 100)
    }

    LaunchedEffect(Unit) {
        try {
            defaults = repo.search("")
            val logged = repo.logged(days = 60, zone = zone)
            val u = usualDrinks(logged, java.time.Instant.now())
            val byId = repo.byIds(u.mapNotNull { it.foodId }.distinct()).associateBy { it.id }
            usual = u.map { it to it.foodId?.let(byId::get) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("DrinkLog", "drink lists failed", e)
        }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(250)
        searching = true
        results = try { repo.search(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Search failed: ${e.message}"; emptyList() }
        searching = false
    }

    fun lookupBarcode(code: String) {
        val barcode = normalizeBarcode(code) ?: code.trim()
        if (barcode.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                val found = NutritionBarcodeLookupRepository(SupabaseClientProvider.client).lookup(barcode)
                if (found.food == null || found.needsLabel) {
                    labelBarcode = barcode
                } else {
                    val match = repo.search(found.food.barcode ?: barcode, 1).firstOrNull()
                    if (match == null) error = "That product is in the catalog but has no drink numbers."
                    else pick(match, match.beverageClass, scanned = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Barcode lookup failed"
            } finally {
                busy = false
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        externalActivityActive?.value = false
        com.bioscan.fieldterminal.util.PendingCameraCapture.clear(context)
        if (ok) pendingCameraUri?.let { labelPhotos = labelPhotos + it }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createCameraCaptureUri(context)
            pendingCameraUri = uri
            takePicture.launch(uri)
        } else {
            externalActivityActive?.value = false
            error = "Camera permission denied"
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        externalActivityActive?.value = false
        if (uris.isNotEmpty()) labelPhotos = labelPhotos + uris
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DateTimeField("WHEN", dateTime, { dateTime = it })

        if (usual.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DrinkLabel("USUAL")
                usual.forEach { (u, match) ->
                    DrinkRow(
                        title = match?.title ?: u.description,
                        detail = "${u.ml.roundToInt()} ml · ${u.times}× in 60 days",
                        selected = picked != null && picked?.id == match?.id && match != null,
                    ) {
                        val k = match?.beverageClass ?: u.beverageClass
                        pick(match ?: defaults.firstOrNull { it.beverageClass == k }, k, u.ml)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DrinkLabel("KIND")
            TextChipRow(DRINK_KINDS.map { it.second }, DRINK_KINDS.firstOrNull { it.first == kind && (picked == null || picked?.drinkDefault == true) }?.second, perRow = 3) { label ->
                val k = DRINK_KINDS.firstOrNull { it.second == label }?.first
                if (k == null) { kind = null; picked = null; steps = null }
                else pick(if (k == "water") null else defaults.firstOrNull { it.beverageClass == k }, k)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DrinkLabel("FIND A DRINK")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FieldTextField(query, { query = it }, "Name, brand or 中文 (e.g. oat latte, 珍珠奶茶)", modifier = Modifier.weight(1f))
                FTButton(if (busy) "..." else "SCAN", enabled = !busy) {
                    scope.launch { scanBarcode(context) { error = it }?.let(::lookupBarcode) }
                }
            }
            if (searching) Text("Searching…", style = FTType.BodySmall, color = FT.TextMuted)
            results.forEach { m ->
                DrinkRow(
                    title = m.title,
                    detail = listOfNotNull(
                        m.nameZh?.takeIf { it != m.name },
                        m.kcal?.let { "${it.roundToInt()} kcal/100 ml" },
                        m.caffeineMg?.takeIf { it > 0 }?.let { "${it.roundToInt()} mg caffeine" + if (m.caffeineEstimated) " (est.)" else "" },
                        sourceName(m.source),
                    ).joinToString(" · "),
                    selected = picked?.id == m.id,
                ) { pick(m, m.beverageClass); query = ""; results = emptyList() }
            }
            if (query.trim().length >= 2 && !searching && results.isEmpty()) {
                Text("Nothing found. Pick the kind above, or scan the barcode or label.", style = FTType.BodySmall, color = FT.TextMuted)
            }
        }

        val k = kind
        val st = steps
        if (k != null && st != null) {
            val ml = st * STEP_ML.toDouble()
            val abvValue = abv.toDoubleOrNull()?.takeIf { k == "alcohol" }
            val amounts = drinkAmounts(picked, ml, abvValue)
            Column(Modifier.fillMaxWidth().ftGlass(RoundedCornerShape(FT.RadiusModule)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = picked
                Text(if (k == "water") "Water" else p?.title ?: DRINK_KINDS.first { it.first == k }.second, style = FTType.RowTitle, color = FT.TextPrimary)
                if (p != null) Text(
                    listOfNotNull(p.nameZh?.takeIf { it != p.name }, if (p.drinkDefault) "a typical ${DRINK_KINDS.firstOrNull { it.first == k }?.second?.lowercase()}" else null, "numbers from ${sourceName(p.source)}").joinToString(" · "),
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
                if (p != null && p.beverageClass == null) {
                    DrinkLabel("WHAT KIND OF DRINK IS IT?")
                    TextChipRow(DRINK_KINDS.drop(1).map { it.second }, DRINK_KINDS.firstOrNull { it.first == k }?.second, perRow = 3) { label ->
                        DRINK_KINDS.firstOrNull { it.second == label }?.first?.let { kind = it }
                    }
                }
                val sizes = drinkSizes(p, k)
                TextChipRow(sizes.map { "${it.name} ${it.ml.roundToInt()}" }, sizes.firstOrNull { (it.ml / STEP_ML).roundToInt() == st }?.let { "${it.name} ${it.ml.roundToInt()}" }, perRow = 3) { label ->
                    sizes.firstOrNull { "${it.name} ${it.ml.roundToInt()}" == label }?.let { steps = (it.ml / STEP_ML).roundToInt().coerceIn(1, 100) }
                }
                FTRatingSlider(
                    label = "AMOUNT",
                    value = st,
                    range = 1..100,
                    color = FT.Metric.Hydration,
                    onChange = { v -> v?.let { steps = it } },
                    optional = false,
                    valueText = { "${it * STEP_ML} ml" },
                    ticks = false,
                    endLabels = "10 ml" to "1 litre",
                )
                if (k == "alcohol") {
                    DrinkLabel("STRENGTH (ABV %)")
                    FieldTextField(abv, { abv = it }, "e.g. 5", keyboardType = KeyboardType.Decimal)
                    TextChipRow(ALCOHOL_STRENGTHS.map { it.first }, ALCOHOL_STRENGTHS.firstOrNull { it.second == abv }?.first, perRow = 3) { picked2 ->
                        abv = ALCOHOL_STRENGTHS.firstOrNull { it.first == picked2 }?.second ?: ""
                    }
                }
                if (k != "water") DrinkReadout(amounts.kcal, amounts.sugarG, amounts.caffeineMg, p?.caffeineEstimated == true || p?.drinkDefault == true, amounts.alcoholG)
            }

            error?.let { Text(it, style = FTType.BodySmall, color = FT.Bad) }
            FTButton(
                label = if (saving || busy) "SAVING..." else "LOG ${(if (k == "water") "water" else picked?.name?.substringBefore(',') ?: DRINK_KINDS.first { it.first == k }.second).uppercase().take(28)}",
                emphasis = FTButtonEmphasis.Primary,
                enabled = !saving && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (k == "water") {
                    onWater(dateTime.toLocalDate().toString(), ml.roundToInt())
                    return@FTButton
                }
                if (k == "alcohol" && amounts.alcoholG == null) {
                    error = "Enter the drink's strength (ABV %, above 0 and at most 100)."
                    return@FTButton
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        val p = picked
                        val name = p?.title ?: DRINK_KINDS.first { it.first == k }.second
                        val description = if (k == "alcohol" && abvValue != null) "$name ${abvValue.toBigDecimal().stripTrailingZeros().toPlainString()}% ABV" else name
                        repo.save(
                            loggedAt = WallClock.write(dateTime),
                            drink = p,
                            beverageClass = k,
                            amounts = amounts,
                            description = description,
                            estimated = p == null || p.drinkDefault || p.caffeineEstimated,
                            source = if (pickedFromScan) "barcode" else "manual",
                        )
                        onSaved()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save the drink"
                        busy = false
                    }
                }
            }
        } else {
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Bad) }
        }
    }

    labelBarcode?.let { code ->
        LabelEntrySheet(
            barcode = code,
            initialName = "",
            initialBrand = null,
            photos = labelPhotos,
            onTakePhoto = {
                externalActivityActive?.value = true
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    val uri = createCameraCaptureUri(context)
                    pendingCameraUri = uri
                    takePicture.launch(uri)
                } else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            onChoosePhoto = {
                externalActivityActive?.value = true
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onSaved = { food ->
                labelBarcode = null
                labelPhotos = emptyList()
                scope.launch {
                    val match = runCatching { repo.byIds(listOf(food.id)).firstOrNull() }.getOrNull()
                    if (match != null) pick(match, match.beverageClass, scanned = true) else error = "Saved the label, but could not read it back."
                }
            },
            onDismiss = {
                labelBarcode = null
                labelPhotos = emptyList()
            },
        )
    }
}

@Composable
private fun DrinkLabel(text: String) = Text(text, style = FTType.LabelCaps, color = FT.TextSecondary)

@Composable
private fun DrinkRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(FT.SelectedGlass, RoundedCornerShape(FT.RadiusSmall))
                    .border(FT.BorderWidth, FT.SelectedBorder, RoundedCornerShape(FT.RadiusSmall))
                else Modifier.ftGlass(RoundedCornerShape(FT.RadiusSmall)),
            )
            .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(title, style = FTType.Body, color = FT.TextPrimary, maxLines = 2)
        if (detail.isNotBlank()) Text(detail, style = FTType.BodySmall, color = FT.TextMuted, maxLines = 2)
    }
}

/** What the amount holds, each number in its metric's hue. */
@Composable
private fun DrinkReadout(kcal: Double?, sugarG: Double?, caffeineMg: Double?, caffeineEstimated: Boolean, alcoholG: Double?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ReadoutCell("KCAL", kcal?.roundToInt()?.toString(), FT.Metric.Calories)
        ReadoutCell("SUGAR", sugarG?.let { "${"%.0f".format(it)} g" }, FT.Metric.Sugar)
        ReadoutCell(if (caffeineEstimated) "CAFFEINE ~" else "CAFFEINE", caffeineMg?.let { "${it.roundToInt()} mg" }, FT.Metric.Caffeine)
        if (alcoholG != null && alcoholG > 0) ReadoutCell("ALCOHOL", "${"%.0f".format(alcoholG)} g · ${"%.1f".format(alcoholG / STANDARD_DRINK_G)}", FT.Metric.Sugar)
    }
    if (caffeineEstimated && (caffeineMg ?: 0.0) > 0) {
        Text("Caffeine is an estimate for this kind of drink; a product's own label is better.", style = FTType.Micro, color = FT.TextMuted)
    }
}

@Composable
private fun ReadoutCell(label: String, value: String?, ink: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, style = FTType.Micro, color = FT.TextMuted)
        Text(value ?: "—", style = FTType.StatValue, color = if (value == null) FT.TextMuted else ink)
    }
}
