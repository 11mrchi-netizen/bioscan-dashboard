package com.bioscan.fieldterminal.ui.screens.status

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bioscan.fieldterminal.data.ScaleReadingRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.bluetoothReady
import com.bioscan.fieldterminal.data.scaleReadings
import com.bioscan.fieldterminal.domain.ScaleReading
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.ExternalActivityGuard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Weigh in on the body scale and log it here, without the OKOK app: the scale advertises weight and impedance over BLE
// (domain/ScaleBroadcast.kt). Step on barefoot and wait for the scale to finish; the first finished reading is shown for
// the person to confirm before it is saved.

private const val SCAN_TIMEOUT_MS = 60_000L

private fun scanPermission() = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

private fun hasScanPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, scanPermission()) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleWeighInSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { ScaleReadingRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf(hasScanPermission(context)) }
    var askedAndDenied by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<ScaleReading?>(null) }
    var scanTry by remember { mutableIntStateOf(0) }
    // The reading just discarded: the scale may keep advertising it, so a new scan skips that exact frame.
    var skipHex by remember { mutableStateOf<String?>(null) }
    var timedOut by remember { mutableStateOf(false) }
    var btOff by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asking = false
        permitted = granted
        askedAndDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!permitted) { asking = true; permissionLauncher.launch(scanPermission()) }
    }

    // One scan per attempt: ends at the first finished reading, the timeout, or when the sheet leaves.
    LaunchedEffect(permitted, scanTry) {
        if (!permitted) return@LaunchedEffect
        reading = null
        timedOut = false
        error = null
        btOff = !bluetoothReady(context)
        if (btOff) return@LaunchedEffect
        try {
            val found = withTimeoutOrNull(SCAN_TIMEOUT_MS) { scaleReadings(context).first { it.rawHex != skipHex } }
            if (found == null) timedOut = true else reading = found
        } catch (e: IllegalStateException) {
            error = e.message
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!asking && !ExternalActivityGuard.active) onDismiss() },
        sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("WEIGH-IN", style = FTType.SectionTitle, color = FT.Category.Activity.c500)
            val r = reading
            when {
                !permitted -> {
                    Text(
                        if (askedAndDenied) "Bluetooth scanning is needed to read the scale. Allow it in the app's settings, then try again."
                        else "Allow Bluetooth scanning to read the scale.",
                        style = FTType.BodySmall, color = FT.TextSecondary,
                    )
                    FTButton("ALLOW", emphasis = FTButtonEmphasis.Primary) { asking = true; permissionLauncher.launch(scanPermission()) }
                }
                btOff -> {
                    Text("Bluetooth is off. Switch it on, then try again.", style = FTType.BodySmall, color = FT.TextSecondary)
                    FTButton("TRY AGAIN", emphasis = FTButtonEmphasis.Primary) { scanTry++ }
                }
                r != null -> {
                    Text("%.1f KG".format(r.weightKg), style = FTType.SectionTitle, color = FT.TextPrimary)
                    Text("Impedance %.0f Ω".format(r.impedanceOhm), style = FTType.Caption, color = FT.TextSecondary)
                    Text(
                        "If this is the previous weigh-in left on the scale, discard it and step on again.",
                        style = FTType.Caption, color = FT.TextSecondary,
                    )
                    error?.let { Text(it, style = FTType.Caption, color = FT.Critical) }
                    FTButton(if (saving) "SAVING…" else "SAVE", enabled = !saving, emphasis = FTButtonEmphasis.Primary) {
                        saving = true
                        scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                            repo.save(r)
                            onSaved()
                        }
                    }
                    FTButton("WEIGH AGAIN", enabled = !saving) { skipHex = r.rawHex; scanTry++ }
                }
                timedOut || error != null -> {
                    Text(error ?: "No finished reading in a minute. Step on barefoot and stay still until the scale finishes.", style = FTType.BodySmall, color = FT.TextSecondary)
                    FTButton("TRY AGAIN", emphasis = FTButtonEmphasis.Primary) { scanTry++ }
                }
                else -> Text(
                    "Listening for the scale. Step on barefoot and stay still until it finishes measuring.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
