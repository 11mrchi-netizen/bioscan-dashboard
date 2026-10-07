package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.health.connect.client.PermissionController
import com.bioscan.fieldterminal.auth.GoogleAuthManager
import com.bioscan.fieldterminal.data.GeminiApiKeyStore
import com.bioscan.fieldterminal.data.HealthConnectSyncResult
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.NotificationRulesRepository
import com.bioscan.fieldterminal.data.NutritionGoals
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.model.NotificationPrefsRow
import com.bioscan.fieldterminal.domain.notifications.parseHm
import com.bioscan.fieldterminal.ui.components.TimeField
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.UserProfileRepository
import com.bioscan.fieldterminal.data.ZeppRepository
import com.bioscan.fieldterminal.data.ZeppSyncStateRow
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import com.bioscan.fieldterminal.healthconnect.HealthConnectSyncStatus
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Step 15's real scope is deliberately unfilled beyond what's here (per
// mobile-app-scoping.md: "genuinely unscoped ... expand only when a concrete
// need arises"). Sign-out has been real since Step 2. The Gemini API key
// field was added for Step 13 (AI food-photo estimation) -- see
// data/GeminiApiKeyStore.kt for why it's stored locally (Android Keystore)
// rather than synced to Supabase.
@Composable
fun SettingsScreen(scope: CoroutineScope, onOpenTraining: () -> Unit = {}) {
    val context = LocalContext.current
    var apiKeyInput by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(GeminiApiKeyStore.get(context) != null) }

    var cartoKeyInput by remember { mutableStateOf("") }
    var cartoKeySaved by remember { mutableStateOf(MapSettingsStore.getCartoKey(context) != null) }
    val savedHome = remember { mutableStateOf(MapSettingsStore.getHome(context)) }
    var homeLatInput by remember { mutableStateOf(savedHome.value?.first?.toString() ?: "") }
    var homeLonInput by remember { mutableStateOf(savedHome.value?.second?.toString() ?: "") }

    var homeInputError by remember { mutableStateOf(false) }

    val savedGoals = remember { mutableStateOf(NutritionGoalsStore.getGoals(context)) }
    var caloriesInput by remember { mutableStateOf(savedGoals.value.caloriesKcal?.toString() ?: "") }
    var proteinInput by remember { mutableStateOf(savedGoals.value.proteinG?.toString() ?: "") }
    var carbsInput by remember { mutableStateOf(savedGoals.value.carbsG?.toString() ?: "") }
    var fatInput by remember { mutableStateOf(savedGoals.value.fatG?.toString() ?: "") }
    var goalsInputError by remember { mutableStateOf(false) }

    val userProfileRepository = remember { UserProfileRepository(SupabaseClientProvider.client) }
    var dobInput by remember { mutableStateOf("") }
    var selectedSex by remember { mutableStateOf<String?>(null) }
    var profileLoaded by remember { mutableStateOf(false) }
    var profileSaving by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf(false) }
    var profileSavedAt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val profile = userProfileRepository.loadProfile()
        dobInput = profile?.dateOfBirth ?: ""
        selectedSex = profile?.sex
        profileLoaded = true
    }

    val notificationsRepository = remember { NotificationRulesRepository(SupabaseClientProvider.client) }
    var notifPrefs by remember { mutableStateOf<NotificationPrefsRow?>(null) }
    var notifSaving by remember { mutableStateOf(false) }
    var notifError by remember { mutableStateOf(false) }
    var notifSavedAt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val zone = ZoneId.systemDefault().id
        notifPrefs = runCatching {
            notificationsRepository.loadPrefs() ?: run {
                notificationsRepository.ensureDefaults(zone)
                notificationsRepository.loadPrefs()
            }
        }.getOrNull() ?: NotificationPrefsRow(timezone = zone)
    }

    val hcAvailable = remember { HealthConnectManager.isAvailable(context) }
    var hcChecked by remember { mutableStateOf(false) }
    var hcGranted by remember { mutableStateOf(false) }
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted -> hcGranted = granted.containsAll(HealthConnectManager.PERMISSIONS) }

    LaunchedEffect(Unit) {
        if (hcAvailable) hcGranted = HealthConnectManager.hasAllPermissions(context)
        hcChecked = true
    }

    val zeppRepository = remember { ZeppRepository(SupabaseClientProvider.client) }
    var zeppStatus by remember { mutableStateOf<ZeppSyncStateRow?>(null) }
    var zeppStatusLoaded by remember { mutableStateOf(false) }
    var zeppSyncing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        zeppStatus = zeppRepository.getSyncStatus()
        zeppStatusLoaded = true
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        ScreenHeader(title = "SETUP", context = "APP CONFIG")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FTCard(title = "PROFILE") {
                Text(
                    "Date of birth and sex, used by the Aging Profile (User tab) and by population " +
                        "comparisons elsewhere. Stored with your account, not on this device only.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                )
                FieldTextField(
                    value = dobInput,
                    onValueChange = { dobInput = it },
                    placeholder = "Date of birth (YYYY-MM-DD)",
                )
                Column {
                    Text(
                        "SEX",
                        style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
                        color = FT.TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SexChip("Male", selectedSex == "male") { selectedSex = if (selectedSex == "male") null else "male" }
                        SexChip("Female", selectedSex == "female") { selectedSex = if (selectedSex == "female") null else "female" }
                    }
                }
                AmberButton(label = if (profileSaving) "SAVING…" else "SAVE PROFILE") {
                    val dob = dobInput.trim().ifBlank { null }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                    if (dobInput.isNotBlank() && dob == null) {
                        profileError = true
                    } else {
                        profileError = false
                        scope.launch {
                            profileSaving = true
                            userProfileRepository.saveProfile(dob, selectedSex)
                            profileSaving = false
                            profileSavedAt = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                        }
                    }
                }
                if (profileError) {
                    Text(
                        "Enter the date as YYYY-MM-DD (e.g. 1990-05-14).",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                        color = FT.Critical,
                    )
                }
                profileSavedAt?.let {
                    Text("Saved $it", style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.TextSecondary)
                }
            }

            FTCard(title = "TRAINING PROGRAMS") {
                Text(
                    "Your program library, the plates you own and default session times. Plan a block from any program.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                )
                AmberButton(label = "OPEN", onClick = onOpenTraining)
            }

            notifPrefs?.let { prefs ->
                FTCard(title = "NOTIFICATIONS") {
                    Text(
                        "Quiet hours hold reminders until the window ends instead of interrupting you. " +
                            "Stored with your account.",
                        style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                        color = FT.TextSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "QUIET HOURS",
                            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
                            color = FT.TextSecondary,
                        )
                        Switch(
                            checked = prefs.quietEnabled,
                            onCheckedChange = {
                                notifPrefs = notifPrefs?.copy(quietEnabled = it)
                                notifSavedAt = null
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = FT.Emerald,
                                checkedTrackColor = FT.Emerald.copy(alpha = 0.3f),
                                uncheckedThumbColor = FT.TextSecondary,
                                uncheckedTrackColor = FT.GlassFill,
                            ),
                        )
                    }
                    if (prefs.quietEnabled) {
                        TimeField("FROM", parseHm(prefs.quietStart)) {
                            notifPrefs = notifPrefs?.copy(quietStart = it.format(DateTimeFormatter.ofPattern("HH:mm")))
                            notifSavedAt = null
                        }
                        TimeField("UNTIL", parseHm(prefs.quietEnd)) {
                            notifPrefs = notifPrefs?.copy(quietEnd = it.format(DateTimeFormatter.ofPattern("HH:mm")))
                            notifSavedAt = null
                        }
                        Text(
                            if (prefs.quietStart == prefs.quietEnd) "FROM and UNTIL are the same, so there is no quiet window."
                            else "Reminders due ${prefs.quietStart}–${prefs.quietEnd} are shown at ${prefs.quietEnd}.",
                            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                            color = FT.TextSecondary,
                        )
                    }
                    AmberButton(label = if (notifSaving) "SAVING…" else "SAVE QUIET HOURS") {
                        val toSave = notifPrefs ?: return@AmberButton
                        scope.launch {
                            notifSaving = true
                            notifError = runCatching { notificationsRepository.savePrefs(toSave) }.isFailure
                            if (!notifError) notifSavedAt = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                            notifSaving = false
                        }
                    }
                    if (notifError) {
                        Text(
                            "Couldn't save. Check your connection and try again.",
                            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                            color = FT.Critical,
                        )
                    }
                    notifSavedAt?.let {
                        Text("Saved $it", style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.TextSecondary)
                    }
                }
            }

            FTCard(title = "AI NUTRITION ESTIMATION") {
                Text(
                    "Gemini API key for photo-based calorie/macro estimation on the Food entry form. " +
                        "Stored on this device only (Android Keystore-encrypted) — never synced to Supabase.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                )
                FieldTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    placeholder = if (keySaved) "•••••••• (key saved — paste to replace)" else "Paste your Gemini API key",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmberButton(label = "SAVE KEY") {
                        if (apiKeyInput.isNotBlank()) {
                            GeminiApiKeyStore.save(context, apiKeyInput.trim())
                            keySaved = true
                            apiKeyInput = ""
                        }
                    }
                    if (keySaved) {
                        ClearChip {
                            GeminiApiKeyStore.clear(context)
                            keySaved = false
                        }
                    }
                }
                Text(
                    if (keySaved) "AI estimation is available on the Food entry form." else "No key set — AI estimation is hidden on the Food entry form until one is saved.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                    color = FT.TextSecondary,
                )
            }

            FTCard(title = "NUTRITION GOALS") {
                Text(
                    "Daily calorie and macro targets, used by Nutrition Analysis's goal-adherence view. " +
                        "Leave a field blank to clear just that target — stored on this device only.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldTextField(caloriesInput, { caloriesInput = it }, "Calories (kcal)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                    FieldTextField(proteinInput, { proteinInput = it }, "Protein (g)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldTextField(carbsInput, { carbsInput = it }, "Carbs (g)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                    FieldTextField(fatInput, { fatInput = it }, "Fat (g)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmberButton(label = "SAVE GOALS") {
                        // Blank clears that one field; anything entered must be a positive number.
                        fun parse(input: String): Double? = input.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                        val calories = parse(caloriesInput)
                        val protein = parse(proteinInput)
                        val carbs = parse(carbsInput)
                        val fat = parse(fatInput)
                        val enteredButInvalid = listOf(caloriesInput to calories, proteinInput to protein, carbsInput to carbs, fatInput to fat)
                            .any { (input, parsed) -> input.isNotBlank() && (parsed == null || parsed <= 0) }
                        if (enteredButInvalid) {
                            goalsInputError = true
                        } else {
                            val goals = NutritionGoals(calories, protein, carbs, fat)
                            NutritionGoalsStore.saveGoals(context, goals)
                            savedGoals.value = goals
                            goalsInputError = false
                        }
                    }
                    if (savedGoals.value.isSet) {
                        ClearChip {
                            NutritionGoalsStore.clearGoals(context)
                            savedGoals.value = NutritionGoals()
                            caloriesInput = ""; proteinInput = ""; carbsInput = ""; fatInput = ""
                            goalsInputError = false
                        }
                    }
                }
                if (goalsInputError) {
                    Text(
                        "Enter a positive number for each target you set (or leave it blank).",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                        color = FT.Critical,
                    )
                }
            }

            FTCard(title = "MAP") {
                Text(
                    "Free CARTO API key for the Map tab's dark basemap tiles (no billing — " +
                        "get one at carto.com/basemaps/apikey). Stored on this device only.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                )
                FieldTextField(
                    value = cartoKeyInput,
                    onValueChange = { cartoKeyInput = it },
                    placeholder = if (cartoKeySaved) "•••••••• (key saved — paste to replace)" else "Paste your CARTO API key",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmberButton(label = "SAVE KEY") {
                        if (cartoKeyInput.isNotBlank()) {
                            MapSettingsStore.saveCartoKey(context, cartoKeyInput.trim())
                            cartoKeySaved = true
                            cartoKeyInput = ""
                        }
                    }
                    if (cartoKeySaved) {
                        ClearChip {
                            MapSettingsStore.clearCartoKey(context)
                            cartoKeySaved = false
                        }
                    }
                }

                Text(
                    "Home location — the starting point for the Map tab's \"DIRECTIONS\" link. " +
                        "Never synced anywhere; used only to build a Google Maps link on this device.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldTextField(
                        value = homeLatInput,
                        onValueChange = { homeLatInput = it },
                        placeholder = "Latitude",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    FieldTextField(
                        value = homeLonInput,
                        onValueChange = { homeLonInput = it },
                        placeholder = "Longitude",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmberButton(label = "SAVE HOME") {
                        val lat = homeLatInput.toDoubleOrNull()
                        val lon = homeLonInput.toDoubleOrNull()
                        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                            MapSettingsStore.saveHome(context, lat, lon)
                            savedHome.value = lat to lon
                            homeInputError = false
                        } else {
                            homeInputError = true
                        }
                    }
                    if (savedHome.value != null) {
                        ClearChip {
                            MapSettingsStore.clearHome(context)
                            savedHome.value = null
                            homeLatInput = ""
                            homeLonInput = ""
                        }
                    }
                }
                if (homeInputError) {
                    Text(
                        "Enter valid decimal coordinates (lat −90 to 90, lon −180 to 180).",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                        color = FT.Critical,
                    )
                }
                Text(
                    savedHome.value?.let { "Home set: %.4f, %.4f".format(it.first, it.second) } ?: "No home location set — the DIRECTIONS link is hidden until one is saved.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                    color = FT.TextSecondary,
                )
            }

            FTCard(title = "HEALTH CONNECT") {
                Text(
                    "Reads activity, body, sleep, and vitals data on every app open. " +
                        "Grants are managed by the OS, not re-requested every screen load.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                )
                Text(
                    text = when {
                        !hcAvailable -> "Health Connect isn't available on this device."
                        !hcChecked -> "Checking access…"
                        hcGranted -> "Connected — all requested permissions granted."
                        else -> "Not connected yet."
                    },
                    style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                    color = if (hcGranted) FT.Emerald else FT.TextSecondary,
                )
                if (hcAvailable && hcChecked && !hcGranted) {
                    AmberButton(label = "CONNECT HEALTH CONNECT") {
                        hcPermissionLauncher.launch(HealthConnectManager.PERMISSIONS)
                    }
                }
                if (hcGranted) {
                    Text(
                        text = when (val result = HealthConnectSyncStatus.lastResult) {
                            null -> "Syncing…"
                            is HealthConnectSyncResult.Success -> {
                                val at = HealthConnectSyncStatus.lastSyncedAt
                                    ?.atZone(ZoneId.systemDefault())
                                    ?.format(DateTimeFormatter.ofPattern("HH:mm"))
                                "Last synced $at — " + result.counts.entries.joinToString(", ") { (k, v) -> "$k: $v" }
                            }
                            is HealthConnectSyncResult.Failed -> "Sync failed: ${result.message}"
                            HealthConnectSyncResult.NotGranted -> "Sync skipped — permissions not granted."
                            HealthConnectSyncResult.Unavailable -> "Sync skipped — Health Connect unavailable."
                        },
                        style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                        color = FT.TextSecondary,
                    )
                    HealthConnectSyncStatus.lastWriteBackResult?.let { wbResult ->
                        val wbAt = HealthConnectSyncStatus.lastWriteBackAt
                            ?.atZone(ZoneId.systemDefault())
                            ?.format(DateTimeFormatter.ofPattern("HH:mm"))
                        val wbText = when (wbResult) {
                            is com.bioscan.fieldterminal.data.HealthConnectWriteBackResult.Success ->
                                "Write-back $wbAt — ${wbResult.mealsWritten} meals, ${wbResult.hydrationDaysWritten} hydration days."
                            is com.bioscan.fieldterminal.data.HealthConnectWriteBackResult.Failed ->
                                "Write-back failed: ${wbResult.message}"
                            com.bioscan.fieldterminal.data.HealthConnectWriteBackResult.NotGranted ->
                                "Write-back skipped — permissions not granted."
                            com.bioscan.fieldterminal.data.HealthConnectWriteBackResult.Unavailable ->
                                "Write-back skipped — Health Connect unavailable."
                        }
                        Text(
                            text = wbText,
                            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                            color = if (wbResult is com.bioscan.fieldterminal.data.HealthConnectWriteBackResult.Success) FT.TextSecondary else FT.Warning,
                        )
                    }
                }
            }

            FTCard(title = "ZEPP") {
                Text(
                    "Pulls workout detail (real per-point pace/power/route) and Zepp-native " +
                        "metrics directly from Zepp's cloud, reconciled against Health Connect " +
                        "sessions rather than duplicating them. Runs automatically on app open.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                )
                Text(
                    text = when {
                        !zeppStatusLoaded -> "Checking status…"
                        zeppStatus?.lastError != null -> "Needs re-authentication: ${zeppStatus?.lastError}"
                        zeppStatus?.lastSyncedAt != null -> "Last synced ${zeppStatus?.lastSyncedAt}"
                        else -> "Not synced yet."
                    },
                    style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                    color = if (zeppStatus?.lastError != null) FT.Warning else FT.TextSecondary,
                )
                if (zeppStatus?.lastError != null) {
                    Text(
                        "Re-capture: log into watchface.zepp.com in a browser, read apptoken/userid " +
                            "from cookies, and update the ZEPP_APP_TOKEN/ZEPP_USER_ID Edge Function " +
                            "secrets (docs/zepp-integration/02-token-capture-and-data-extraction.md).",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                        color = FT.TextSecondary,
                    )
                }
                AmberButton(label = if (zeppSyncing) "SYNCING…" else "SYNC NOW") {
                    if (!zeppSyncing) {
                        scope.launch {
                            zeppSyncing = true
                            zeppRepository.sync(daysBack = 30)
                            zeppStatus = zeppRepository.getSyncStatus()
                            zeppSyncing = false
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AmberButton(label = "SIGN OUT") { scope.launch { GoogleAuthManager.signOut() } }
            }
        }
    }
}

// Bordered "CLEAR" chip -- shared by every saved-value field on this screen
// (Gemini key, CARTO key, home location) rather than repeating the same
// Box/clickable/Text block per field.
@Composable
private fun ClearChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("CLEAR", style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.14f.em), color = FT.TextSecondary)
    }
}

// Tap-to-select chip, same shape as ClearChip -- tapping the already-selected
// chip clears it back to null (matches AddEntrySheet.kt's TextChipRow, an
// optional field, not a forced either/or).
@Composable
private fun SexChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(FT.BorderWidth, if (selected) FT.Emerald else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
            .background(if (selected) FT.Emerald.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(FT.RadiusSmall))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
            color = if (selected) FT.Emerald else FT.TextSecondary,
        )
    }
}
