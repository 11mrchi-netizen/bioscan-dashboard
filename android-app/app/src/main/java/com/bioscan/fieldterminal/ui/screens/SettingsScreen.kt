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
fun SettingsScreen(scope: CoroutineScope) {
    val context = LocalContext.current
    var apiKeyInput by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(GeminiApiKeyStore.get(context) != null) }

    var cartoKeyInput by remember { mutableStateOf("") }
    var cartoKeySaved by remember { mutableStateOf(MapSettingsStore.getCartoKey(context) != null) }
    val savedHome = remember { mutableStateOf(MapSettingsStore.getHome(context)) }
    var homeLatInput by remember { mutableStateOf(savedHome.value?.first?.toString() ?: "") }
    var homeLonInput by remember { mutableStateOf(savedHome.value?.second?.toString() ?: "") }

    var homeInputError by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        ScreenHeader(title = "SETUP", context = "APP CONFIG")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
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
