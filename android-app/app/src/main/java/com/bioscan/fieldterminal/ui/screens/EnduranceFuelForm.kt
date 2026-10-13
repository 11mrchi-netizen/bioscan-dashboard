package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.FuelingIntakeRow
import com.bioscan.fieldterminal.data.FuelingProduct
import com.bioscan.fieldterminal.data.FuelingRepository
import com.bioscan.fieldterminal.data.FuelingSessionRow
import com.bioscan.fieldterminal.data.NewFuelingIntake
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.functionErrorMessage
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.screens.status.FuelingExperimentSheet
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val KINDS = listOf("gel", "drink_mix", "chew", "bar", "homemade", "other")
private val STATUSES = listOf("consumed", "partial", "planned", "skipped")
private val WHEN_FMT = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)

private fun sessionLabel(s: FuelingSessionRow): String {
    val whenText = runCatching { OffsetDateTime.parse(s.startTime).toLocalDateTime().format(WHEN_FMT) }.getOrDefault(s.startTime.take(16))
    val dur = s.durationMin?.let { " · ${it.toInt()} MIN" } ?: ""
    return "${s.type.uppercase()} · $whenText$dur"
}

private fun num(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()

/**
 * Endurance fueling: one intake event (a gel, a bottle, a chew) attached to a run, hike or ride. Per-serving values come from
 * the product catalog; the totals saved are those values times the servings. The server counts consumed events once in the
 * daily nutrient totals (a trigger on fueling_intake_log), planned and skipped events never reach them.
 */
@Composable
internal fun EnduranceFuelForm(onSaved: () -> Unit) {
    val repo = remember { FuelingRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<FuelingSessionRow>>(emptyList()) }
    var products by remember { mutableStateOf<List<FuelingProduct>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    var sessionId by remember { mutableStateOf<Long?>(null) }
    var productId by remember { mutableStateOf<Long?>(null) }
    var servings by remember { mutableStateOf("1") }
    var minutes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("consumed") }
    var logged by remember { mutableStateOf<List<FuelingIntakeRow>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var experimenting by remember { mutableStateOf(false) }
    // One key per form: pressing SAVE again after a failed attempt updates the same row instead of adding another.
    val clientKey = remember { UUID.randomUUID().toString() }

    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf<String?>("gel") }
    var newCarbs by remember { mutableStateOf("") }
    var newSodium by remember { mutableStateOf("") }
    var newFluid by remember { mutableStateOf("") }
    var newCaffeine by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        loading = true
        loadError = null
        try {
            sessions = repo.recentSessions()
            products = repo.loadProducts()
            if (sessionId == null) sessionId = sessions.firstOrNull()?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.functionErrorMessage()
        } finally {
            loading = false
        }
    }
    LaunchedEffect(sessionId) {
        logged = emptyList()
        val id = sessionId ?: return@LaunchedEffect
        logged = try { repo.intakeFor(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    val product = products.firstOrNull { it.product.id == productId }
    val version = product?.latest
    val n = num(servings)
    val mins = minutes.trim().toIntOrNull()
    fun scaled(v: Double?) = if (v != null && n != null) v * n else null
    val valid = sessionId != null && product != null && n != null && n > 0 && mins != null && mins in 0..1440

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            loading -> Text("Loading...", style = FTType.BodySmall, color = FT.TextSecondary)
            loadError != null -> {
                Text("Could not load ($loadError).", style = FTType.BodySmall, color = FT.Critical)
                AmberButton(label = "RETRY") { reload++ }
            }
            sessions.isEmpty() -> Text("No run, hike or ride in the last three weeks to attach fueling to. Sync your watch or log the activity first.", style = FTType.BodySmall, color = FT.TextSecondary)
            else -> {
                Column {
                    Label("ACTIVITY")
                    val labels = sessions.associateBy({ sessionLabel(it) }, { it.id })
                    TextChipRow(labels.keys.toList(), labels.entries.firstOrNull { it.value == sessionId }?.key, perRow = 1) { picked ->
                        if (picked != null) sessionId = labels[picked]
                    }
                }
                if (logged.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Label("ALREADY LOGGED FOR THIS ACTIVITY")
                        logged.forEach { e ->
                            val parts = listOfNotNull(
                                e.carbsG?.let { "${it.toInt()} g carbs" },
                                e.sodiumMg?.let { "${it.toInt()} mg Na" },
                                e.fluidMl?.let { "${it.toInt()} ml" },
                                e.caffeineMg?.let { "${it.toInt()} mg caf" },
                            ).joinToString(" · ")
                            Text("${e.elapsedSeconds / 60} min · ${e.status} · ${e.description ?: "?"}${if (parts.isNotEmpty()) " · $parts" else ""}", style = FTType.Caption, color = FT.TextSecondary)
                        }
                    }
                }

                Column {
                    Label("PRODUCT")
                    val names = products.map { it.product.name }
                    TextChipRow(names + "+ NEW PRODUCT", if (adding) "+ NEW PRODUCT" else product?.product?.name, perRow = 2) { picked ->
                        when {
                            picked == "+ NEW PRODUCT" -> { adding = true; productId = null }
                            picked != null -> { adding = false; productId = products.firstOrNull { it.product.name == picked }?.product?.id }
                        }
                    }
                }

                if (adding) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("NEW PRODUCT, VALUES PER SERVING (ONE GEL, ONE BOTTLE, ONE BAR)")
                        FieldTextField(newName, { newName = it }, "Name, e.g. Maurten 100")
                        TextChipRow(KINDS, newKind, perRow = 3) { newKind = it ?: newKind }
                        FieldTextField(newCarbs, { newCarbs = it }, "Carbs g", keyboardType = KeyboardType.Decimal)
                        FieldTextField(newSodium, { newSodium = it }, "Sodium mg (optional)", keyboardType = KeyboardType.Decimal)
                        FieldTextField(newFluid, { newFluid = it }, "Fluid ml (optional)", keyboardType = KeyboardType.Decimal)
                        FieldTextField(newCaffeine, { newCaffeine = it }, "Caffeine mg (optional)", keyboardType = KeyboardType.Decimal)
                        AmberButton(label = if (saving) "SAVING..." else "SAVE PRODUCT") {
                            if (saving || newName.isBlank() || newKind == null || num(newCarbs) == null) {
                                error = "A product needs a name and its carbs per serving."
                                return@AmberButton
                            }
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    repo.createProduct(newKind!!, newName, num(newCarbs), num(newSodium), num(newFluid), num(newCaffeine))
                                    val created = newName.trim()
                                    products = repo.loadProducts()
                                    productId = products.firstOrNull { it.product.name == created }?.product?.id
                                    adding = false
                                    newName = ""; newCarbs = ""; newSodium = ""; newFluid = ""; newCaffeine = ""
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.functionErrorMessage()
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                } else if (product != null) {
                    val per = listOfNotNull(
                        version?.carbsG?.let { "${it.toInt()} g carbs" },
                        version?.sodiumMg?.let { "${it.toInt()} mg Na" },
                        version?.fluidMl?.let { "${it.toInt()} ml" },
                        version?.caffeineMg?.let { "${it.toInt()} mg caf" },
                    ).joinToString(" · ")
                    Text("Per serving: ${per.ifEmpty { "no values stored" }}", style = FTType.Caption, color = FT.TextSecondary)

                    Column { Label("SERVINGS"); FieldTextField(servings, { servings = it }, "e.g. 1 or 0.5", keyboardType = KeyboardType.Decimal) }
                    Column { Label("MINUTES INTO THE ACTIVITY"); FieldTextField(minutes, { minutes = it }, "e.g. 45", keyboardType = KeyboardType.Number) }
                    Column { Label("STATUS"); TextChipRow(STATUSES, status, perRow = 4) { status = it ?: status } }

                    AmberButton(label = if (saving) "SAVING..." else "SAVE") {
                        if (!valid || saving) {
                            error = "Pick an activity, a product, servings above 0 and minutes between 0 and 1440."
                            return@AmberButton
                        }
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                repo.logIntake(
                                    NewFuelingIntake(
                                        exerciseSessionId = sessionId!!,
                                        recipeVersionId = version?.id,
                                        description = product.product.name,
                                        status = status,
                                        elapsedSeconds = mins!! * 60,
                                        quantity = n,
                                        quantityUnit = version?.servingUnit ?: "serving",
                                        carbsG = scaled(version?.carbsG),
                                        fluidMl = scaled(version?.fluidMl),
                                        sodiumMg = scaled(version?.sodiumMg),
                                        caffeineMg = scaled(version?.caffeineMg),
                                        source = "app",
                                        clientKey = clientKey,
                                    ),
                                )
                                onSaved()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.functionErrorMessage()
                            } finally {
                                saving = false
                            }
                        }
                    }
                    AmberButton(label = "TEST THIS PRODUCT (N-OF-1)") { experimenting = true }
                }
                error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            }
        }
    }

    if (experimenting && product != null) {
        FuelingExperimentSheet(
            productId = product.product.id,
            productName = product.product.name,
            onDismiss = { experimenting = false },
            onRegistered = { experimenting = false; onSaved() },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}
