package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.health.connect.client.PermissionController
import com.bioscan.fieldterminal.data.GeminiApiKeyStore
import com.bioscan.fieldterminal.data.HealthConnectSyncResult
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.ZeppRepository
import com.bioscan.fieldterminal.data.ZeppSyncStateRow
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import com.bioscan.fieldterminal.healthconnect.HealthConnectSyncStatus
import com.bioscan.fieldterminal.ui.components.ClearChip
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.nav.ConnectedServicesTab
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Setup > Connected Services: AI Nutrition Estimation, Map, Health Connect, Zepp.
@Composable
fun ConnectedServicesScreen(scope: CoroutineScope, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(ConnectedServicesTab.AiNutrition) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack, title = "CONNECTED SERVICES", subtitle = "AI NUTRITION · MAPS · HEALTH CONNECT · ZEPP")
        SubTabRow(items = ConnectedServicesTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (tab) {
                ConnectedServicesTab.AiNutrition -> AiNutritionCard(context)
                ConnectedServicesTab.Map -> MapCard(context)
                ConnectedServicesTab.HealthConnect -> HealthConnectCard(context)
                ConnectedServicesTab.Zepp -> ZeppCard(scope)
            }
        }
    }
}

@Composable
private fun AiNutritionCard(context: android.content.Context) {
    var apiKeyInput by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(GeminiApiKeyStore.get(context) != null) }

    FTCard(title = "AI NUTRITION ESTIMATION") {
        Text(
            "Gemini API key for photo-based calorie/macro estimation on the Food entry form. " +
                "Stored on this device only (Android Keystore-encrypted) — never synced to Supabase.",
            style = FTType.BodySmall,
            color = FT.TextSecondary,
        )
        FieldTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            placeholder = if (keySaved) "•••••••• (key saved — paste to replace)" else "Paste your Gemini API key",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FTButton(label = "SAVE KEY") {
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
            style = FTType.Caption,
            color = FT.TextSecondary,
        )
    }
}

@Composable
private fun MapCard(context: android.content.Context) {
    var cartoKeyInput by remember { mutableStateOf("") }
    var cartoKeySaved by remember { mutableStateOf(MapSettingsStore.getCartoKey(context) != null) }
    val savedHome = remember { mutableStateOf(MapSettingsStore.getHome(context)) }
    var homeLatInput by remember { mutableStateOf(savedHome.value?.first?.toString() ?: "") }
    var homeLonInput by remember { mutableStateOf(savedHome.value?.second?.toString() ?: "") }
    var homeInputError by remember { mutableStateOf(false) }

    FTCard(title = "MAP") {
        Text(
            "Free CARTO API key for the Map tab's dark basemap tiles (no billing — " +
                "get one at carto.com/basemaps/apikey). Stored on this device only.",
            style = FTType.BodySmall,
            color = FT.TextSecondary,
        )
        FieldTextField(
            value = cartoKeyInput,
            onValueChange = { cartoKeyInput = it },
            placeholder = if (cartoKeySaved) "•••••••• (key saved — paste to replace)" else "Paste your CARTO API key",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FTButton(label = "SAVE KEY") {
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
            style = FTType.BodySmall,
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
            FTButton(label = "SAVE HOME") {
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
                style = FTType.Caption,
                color = FT.Critical,
            )
        }
        Text(
            savedHome.value?.let { "Home set: %.4f, %.4f".format(it.first, it.second) } ?: "No home location set — the DIRECTIONS link is hidden until one is saved.",
            style = FTType.Caption,
            color = FT.TextSecondary,
        )
    }
}

@Composable
private fun HealthConnectCard(context: android.content.Context) {
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

    FTCard(title = "HEALTH CONNECT") {
        Text(
            "Reads activity, body, sleep, and vitals data on every app open. " +
                "Grants are managed by the OS, not re-requested every screen load.",
            style = FTType.BodySmall,
            color = FT.TextSecondary,
        )
        Text(
            text = when {
                !hcAvailable -> "Health Connect isn't available on this device."
                !hcChecked -> "Checking access…"
                hcGranted -> "Connected — all requested permissions granted."
                else -> "Not connected yet."
            },
            style = FTType.BodySmall,
            color = if (hcGranted) FT.Emerald else FT.TextSecondary,
        )
        if (hcAvailable && hcChecked && !hcGranted) {
            FTButton(label = "CONNECT HEALTH CONNECT") {
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
                style = FTType.Caption,
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
                    style = FTType.Caption,
                    color = if (wbResult is com.bioscan.fieldterminal.data.HealthConnectWriteBackResult.Success) FT.TextSecondary else FT.Warning,
                )
            }
        }
    }
}

@Composable
private fun ZeppCard(scope: CoroutineScope) {
    val zeppRepository = remember { ZeppRepository(SupabaseClientProvider.client) }
    var zeppStatus by remember { mutableStateOf<ZeppSyncStateRow?>(null) }
    var zeppStatusLoaded by remember { mutableStateOf(false) }
    var zeppSyncing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Offline must not crash the settings page: an unreadable status just stays "not loaded".
        try {
            zeppStatus = zeppRepository.getSyncStatus()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ConnectedServices", "zepp status load failed", e)
        }
        zeppStatusLoaded = true
    }

    FTCard(title = "ZEPP") {
        Text(
            "Pulls workout detail (real per-point pace/power/route) and Zepp-native " +
                "metrics directly from Zepp's cloud, reconciled against Health Connect " +
                "sessions rather than duplicating them. Runs automatically on app open.",
            style = FTType.BodySmall,
            color = FT.TextSecondary,
        )
        Text(
            text = when {
                !zeppStatusLoaded -> "Checking status…"
                zeppStatus?.lastError != null -> "Needs re-authentication: ${zeppStatus?.lastError}"
                zeppStatus?.lastSyncedAt != null -> "Last synced ${zeppStatus?.lastSyncedAt}"
                else -> "Not synced yet."
            },
            style = FTType.BodySmall,
            color = if (zeppStatus?.lastError != null) FT.Warning else FT.TextSecondary,
        )
        if (zeppStatus?.lastError != null) {
            Text(
                "Re-capture: log into watchface.zepp.com in a browser, read apptoken/userid " +
                    "from cookies, and update the ZEPP_APP_TOKEN/ZEPP_USER_ID Edge Function " +
                    "secrets (docs/zepp-integration/02-token-capture-and-data-extraction.md).",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
        }
        FTButton(label = if (zeppSyncing) "SYNCING…" else "SYNC NOW") {
            if (!zeppSyncing) {
                scope.launchSafe(onDone = { zeppSyncing = false }) {
                    zeppSyncing = true
                    zeppRepository.sync(daysBack = 30)
                    zeppStatus = zeppRepository.getSyncStatus()
                    zeppSyncing = false
                }
            }
        }
        // Writes the next 7 days of planned sessions to Zepp's calendar: a real feature, so it stays next to SYNC NOW.
        var probing by remember { mutableStateOf(false) }
        var probeResult by remember { mutableStateOf<String?>(null) }
        FTButton(label = if (probing) "SENDING…" else "SEND NEXT 7 DAYS TO ZEPP") {
            if (!probing) {
                scope.launchSafe(onDone = { probing = false }) {
                    probing = true
                    probeResult = zeppRepository.pushUpcoming()
                    probing = false
                }
            }
        }
        // UX review F13: developer checks of Zepp's workout template/schedule API (zepp-training; answers land in
        // zepp_raw_extracts) sit behind a collapsed row, and the ones that leave entries in the Zepp account or delete
        // them ask first.
        var showDiagnostics by remember { mutableStateOf(false) }
        var confirming by remember { mutableStateOf<ZeppDiagnostic?>(null) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ftClickable { showDiagnostics = !showDiagnostics; confirming = null }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DIAGNOSTICS", style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.weight(1f))
            Text(if (showDiagnostics) "▾" else "▸", style = FTType.LabelCaps, color = FT.TextMuted)
        }
        if (showDiagnostics) {
            Text(
                "Developer checks of Zepp's template and calendar API. Results are saved to zepp_raw_extracts.",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
            ZeppDiagnostic.entries.forEach { diagnostic ->
                if (confirming == diagnostic) {
                    val run = {
                        confirming = null
                        if (!probing) {
                            scope.launchSafe(onDone = { probing = false }) {
                                probing = true
                                probeResult = zeppRepository.probeTrainingApi(diagnostic.mode)
                                probing = false
                            }
                        }
                    }
                    if (diagnostic == ZeppDiagnostic.BulkCleanup) {
                        // Contract A2: deleting from the Zepp account is destructive, so it gets the destructive confirm.
                        FTConfirmDestructive(diagnostic.writes.orEmpty(), confirmLabel = "DELETE TEST ENTRIES", onCancel = { confirming = null }, onConfirm = run)
                    } else {
                        Text(diagnostic.writes.orEmpty(), style = FTType.BodySmall, color = FT.TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FTButton(label = "CANCEL") { confirming = null }
                            FTButton(label = "RUN ${diagnostic.label}", emphasis = FTButtonEmphasis.Primary, onClick = run)
                        }
                    }
                } else {
                    FTButton(label = if (probing) "CHECKING…" else diagnostic.label) {
                        if (diagnostic.writes != null) {
                            confirming = diagnostic
                        } else if (!probing) {
                            scope.launchSafe(onDone = { probing = false }) {
                                probing = true
                                probeResult = zeppRepository.probeTrainingApi(diagnostic.mode)
                                probing = false
                            }
                        }
                    }
                }
            }
        }
        probeResult?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
    }
}

// The zepp-training probe modes behind DIAGNOSTICS. `writes` says what a run leaves in (or removes from) the Zepp account;
// those ask before running. The rest only read, or create a throwaway and delete it again.
private enum class ZeppDiagnostic(val label: String, val mode: String?, val writes: String? = null) {
    // No action: the function's read-only overview (the original CHECK TEMPLATE API request body, "{}").
    CheckApi("CHECK TEMPLATE API", null),
    WriteTest("TEMPLATE WRITE TEST", "write_test"),
    ScheduleTest("TEMPLATE SCHEDULE TEST", "schedule_test"),
    BulkTest("BULK SCHEDULE TEST", "bulk_test", "Adds 3 templates and 13 calendar entries over 4 weeks and January to your Zepp account and leaves them there."),
    VariantTest("STRUCTURE VARIANTS TEST", "variant_test", "Adds the same session written 3 ways to tomorrow in your Zepp calendar. BULK CLEANUP removes them."),
    IcalTest("ICAL FORMAT TEST", "ical_test", "Adds two calendar entries for tomorrow to your Zepp calendar."),
    RefTest("REFERENCE ICAL TEST", "ref_test", "Adds three entries for tomorrow afternoon to your Zepp calendar."),
    CalendarDump("CALENDAR DUMP", "calendar_dump"),
    BulkCleanup("BULK CLEANUP", "bulk_cleanup", "Deletes the test templates and calendar entries from your Zepp account."),
}
