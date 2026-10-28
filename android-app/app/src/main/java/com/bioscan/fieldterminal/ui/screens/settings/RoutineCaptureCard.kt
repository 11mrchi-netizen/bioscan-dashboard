package com.bioscan.fieldterminal.ui.screens.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.LocationCollector
import com.bioscan.fieldterminal.data.routine.LocationRepository
import com.bioscan.fieldterminal.data.routine.PhoneAppUsageRow
import com.bioscan.fieldterminal.data.routine.PhoneUsageCollector
import com.bioscan.fieldterminal.data.routine.PhoneUsageDailyRow
import com.bioscan.fieldterminal.data.routine.PhoneUsageRepository
import com.bioscan.fieldterminal.data.routine.RoutineCaptureWorker
import com.bioscan.fieldterminal.data.routine.RoutineOutbox
import com.bioscan.fieldterminal.data.routine.RoutineSettingsStore
import com.bioscan.fieldterminal.data.routine.RoutineStream
import com.bioscan.fieldterminal.data.routine.TripRecordingService
import com.bioscan.fieldterminal.data.routine.VisitRow
import com.bioscan.fieldterminal.data.routine.parseTimestamp
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Routine intelligence R1 + R2 (docs/routine-intelligence/01-research-and-plan.md, section 10): the switches, grants and
// collector health for PHONE USE and LOCATIONS, with the latest results so the gates can be checked by hand (screen time
// against Digital Wellbeing, visits against a diary). Grants are re-read every two seconds while the card is on screen,
// because they are changed in Settings, outside the app.
@Composable
fun RoutineCaptureCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    PhoneUsageSection(context, tick, reload, onChanged = { reload++ }, scope = scope)
    LocationSection(context, tick, reload, onChanged = { reload++ }, scope = scope)
}

private val timeFmt = DateTimeFormatter.ofPattern("EEE HH:mm")

private fun localTime(iso: String?): String =
    iso?.let(::parseTimestamp)?.atZone(ZoneId.systemDefault())?.format(timeFmt) ?: "-"

private fun minutes(m: Double?): String {
    if (m == null) return "-"
    val total = m.toInt()
    return if (total >= 60) "${total / 60} h ${total % 60} min" else "$total min"
}

private fun ago(ms: Long): String {
    if (ms == 0L) return "never"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 120 -> "$min min ago"
        else -> "${min / 60} h ago"
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Body, color = FT.TextPrimary)
        Text(
            value,
            style = FTType.Telemetry,
            color = when (ok) {
                true -> FT.Emerald
                false -> FT.Warning
                null -> FT.TextSecondary
            },
        )
    }
}

@Composable
private fun StreamControls(
    context: Context,
    stream: RoutineStream,
    tick: Int,
    canTurnOn: Boolean,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onDeleteAll: suspend () -> Unit,
    onChanged: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val enabled = remember(tick) { RoutineSettingsStore.isEnabled(context, stream) }
    val pausedUntil = remember(tick) { RoutineSettingsStore.pausedUntil(context, stream) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (enabled && pausedUntil > System.currentTimeMillis()) {
        Text("Paused until midnight.", style = FTType.BodySmall, color = FT.Warning)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (enabled) {
            FTButton("TURN OFF", modifier = Modifier.weight(1f)) { onTurnOff(); onChanged() }
            if (pausedUntil > System.currentTimeMillis()) {
                FTButton("RESUME", modifier = Modifier.weight(1f)) { RoutineSettingsStore.resume(context, stream); onChanged() }
            } else {
                FTButton("PAUSE TODAY", modifier = Modifier.weight(1f)) { RoutineSettingsStore.pauseForToday(context, stream); onChanged() }
            }
        } else {
            FTButton("TURN ON", modifier = Modifier.weight(1f), enabled = canTurnOn, emphasis = FTButtonEmphasis.Primary) {
                onTurnOn(); onChanged()
            }
        }
    }
    if (confirmDelete) {
        FTConfirmDestructive(
            message = "Deletes every ${if (stream == RoutineStream.LOCATION) "location" else "phone-use"} row of this account, " +
                "in Supabase and on the phone. This cannot be undone.",
            confirmLabel = "DELETE ALL",
            busy = deleting,
            onCancel = { confirmDelete = false },
            onConfirm = {
                deleting = true
                scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { deleting = false; confirmDelete = false; onChanged() }) {
                    onDeleteAll()
                }
            },
        )
    } else {
        FTButton("DELETE ALL", emphasis = FTButtonEmphasis.Destructive, modifier = Modifier.fillMaxWidth()) { confirmDelete = true }
    }
    error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
}

@Composable
private fun PhoneUsageSection(context: Context, tick: Int, reload: Int, onChanged: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val collector = remember { PhoneUsageCollector(context) }
    val repo = remember { PhoneUsageRepository(SupabaseClientProvider.client) }
    val hasAccess = remember(tick) { collector.hasAccess() }
    var latest by remember { mutableStateOf<PhoneUsageDailyRow?>(null) }
    var apps by remember { mutableStateOf<List<PhoneAppUsageRow>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        scope.launchSafe(onError = { loadError = describeLoadFailure(it) }) {
            latest = repo.latestDay()
            apps = latest?.let { repo.topApps(it.date) }.orEmpty()
            loadError = null
        }
    }

    FTCard(
        title = "PHONE USE",
        info = "Screen time, unlocks and time per app from Android's own usage events, read once every 6 hours for the last " +
            "three completed days (04:00 to 04:00). The bed window is the hour before the night's sleep onset from Health " +
            "Connect. Only daily totals are stored, per app with its name; the raw events are not.",
    ) {
        StatusRow("Usage access", if (hasAccess) "GRANTED" else "NOT GRANTED", ok = hasAccess)
        if (!hasAccess) {
            FTButton("OPEN USAGE ACCESS", modifier = Modifier.fillMaxWidth()) { context.startActivity(collector.accessSettingsIntent()) }
        }
        StatusRow("Last read", ago(remember(tick) { RoutineSettingsStore.getLong(context, RoutineSettingsStore.KEY_LAST_PHONE_RUN) }))
        StreamControls(
            context, RoutineStream.PHONE_USAGE, tick,
            canTurnOn = hasAccess,
            onTurnOn = {
                RoutineSettingsStore.setEnabled(context, RoutineStream.PHONE_USAGE, true)
                RoutineCaptureWorker.forcePhoneRun(context)
            },
            onTurnOff = { RoutineSettingsStore.setEnabled(context, RoutineStream.PHONE_USAGE, false) },
            onDeleteAll = { repo.deleteAll() },
            onChanged = onChanged,
            scope = scope,
        )
        loadError?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
        latest?.let { day ->
            Text("LATEST DAY · ${day.date}", style = FTType.Label, color = FT.TextSecondary)
            StatusRow("Screen time", minutes(day.screenMin))
            StatusRow("Unlocks", day.unlocks.toString())
            StatusRow("First unlock", localTime(day.firstUnlockAt))
            StatusRow("Hour before sleep", minutes(day.bedWindowMin))
            StatusRow("During the night", "${minutes(day.nightScreenMin)} · ${day.nightUnlocks ?: "-"} unlocks")
            StatusRow("Longest phone-free, 08-22", minutes(day.longestFreeMin))
            apps.forEach { app -> StatusRow(app.appLabel ?: app.pkg, "${minutes(app.foregroundMin)} · ${app.launches}×") }
        }
    }
}

@Composable
private fun LocationSection(context: Context, tick: Int, reload: Int, onChanged: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val repo = remember { LocationRepository(SupabaseClientProvider.client) }
    val fine = remember(tick) { LocationCollector.hasFineLocation(context) }
    val background = remember(tick) { LocationCollector.hasBackgroundLocation(context) }
    val activity = remember(tick) { LocationCollector.hasActivityRecognition(context) }
    val battery = remember(tick) { LocationCollector.isIgnoringBatteryOptimizations(context) }
    val pending = remember(tick) { RoutineOutbox.pendingCount(context) }
    var visits by remember { mutableStateOf<List<VisitRow>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }

    LaunchedEffect(reload) {
        scope.launchSafe(onError = { loadError = describeLoadFailure(it) }) {
            visits = repo.recentVisits(Instant.now().minusSeconds(24 * 3600))
            loadError = null
        }
    }

    FTCard(
        title = "LOCATIONS",
        info = "Places and trips, recorded passively. Movement detected by the phone starts a trip, recorded with a fix every " +
            "30-60 s; a quiet notification shows while it runs. Between trips the phone takes one low-power fix an hour. " +
            "Stays of 10 minutes or more within 150 m become visits. Fixes and visits are stored in Supabase with their " +
            "coordinates, readable only by this account.",
    ) {
        StatusRow("Precise location", if (fine) "GRANTED" else "NOT GRANTED", ok = fine)
        if (!fine) {
            FTButton("ALLOW LOCATION", modifier = Modifier.fillMaxWidth()) {
                fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        StatusRow("Allow all the time", if (background) "GRANTED" else "NOT GRANTED", ok = background)
        if (fine && !background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 11+ shows this choice only on the app's own location page; the request opens it.
            FTButton("ALLOW ALL THE TIME", modifier = Modifier.fillMaxWidth()) {
                singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        StatusRow("Physical activity", if (activity) "GRANTED" else "NOT GRANTED", ok = activity)
        if (!activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            FTButton("ALLOW PHYSICAL ACTIVITY", modifier = Modifier.fillMaxWidth()) {
                singleLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        StatusRow("Battery unrestricted", if (battery) "YES" else "NO", ok = battery)
        if (!battery) {
            FTButton("REMOVE BATTERY LIMIT", modifier = Modifier.fillMaxWidth()) {
                context.startActivity(LocationCollector.batteryExemptionIntent(context))
            }
        }
        StatusRow("Collector heartbeat", ago(remember(tick) { RoutineSettingsStore.getLong(context, RoutineSettingsStore.KEY_LAST_HEARTBEAT) }))
        StatusRow("Last upload", ago(remember(tick) { RoutineSettingsStore.getLong(context, RoutineSettingsStore.KEY_LAST_UPLOAD) }))
        StatusRow("Waiting to upload", pending.toString())
        StatusRow("Trip", if (remember(tick) { TripRecordingService.running }) "RECORDING" else "-")
        StreamControls(
            context, RoutineStream.LOCATION, tick,
            canTurnOn = fine && background && activity,
            onTurnOn = {
                RoutineSettingsStore.setEnabled(context, RoutineStream.LOCATION, true)
                LocationCollector.registerAll(context)
                RoutineCaptureWorker.runSoon(context)
            },
            onTurnOff = {
                RoutineSettingsStore.setEnabled(context, RoutineStream.LOCATION, false)
                LocationCollector.unregisterAll(context)
            },
            onDeleteAll = { repo.deleteAll(context) },
            onChanged = onChanged,
            scope = scope,
        )
        loadError?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
        if (visits.isNotEmpty()) {
            Text("VISITS, LAST 24 H", style = FTType.Label, color = FT.TextSecondary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                visits.forEach { v ->
                    StatusRow(
                        "${localTime(v.startAt)} – ${if (v.open) "now" else localTime(v.endAt)}",
                        "%.4f, %.4f · %d%%".format(v.lat, v.lon, (v.confidence * 100).toInt()),
                        ok = v.confidence >= 0.8,
                    )
                }
            }
        }
    }
}
