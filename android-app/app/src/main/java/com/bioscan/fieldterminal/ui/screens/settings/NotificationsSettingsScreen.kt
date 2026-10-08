package com.bioscan.fieldterminal.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bioscan.fieldterminal.data.NotificationRulesRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.data.model.NotificationPrefsRow
import com.bioscan.fieldterminal.data.model.NotificationRuleRow
import com.bioscan.fieldterminal.domain.notifications.parseHm
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.TimeField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val RULE_LABELS = mapOf(
    NotificationKind.SUPPLEMENT_DUE to "SUPPLEMENT REMINDERS",
    NotificationKind.CHECKIN_MORNING to "MORNING CHECK-IN",
    NotificationKind.CHECKIN_EVENING to "EVENING CHECK-IN",
)

// Setup > Notifications: single destination, no sub-tabs.
@Composable
fun NotificationsSettingsScreen(scope: CoroutineScope, onBack: () -> Unit) {
    val context = LocalContext.current
    val notificationsRepository = remember { NotificationRulesRepository(SupabaseClientProvider.client) }
    var notifPrefs by remember { mutableStateOf<NotificationPrefsRow?>(null) }
    var notifSaving by remember { mutableStateOf(false) }
    var notifError by remember { mutableStateOf(false) }
    var notifSavedAt by remember { mutableStateOf<String?>(null) }
    var rules by remember { mutableStateOf<List<NotificationRuleRow>>(emptyList()) }
    var notifPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifPermissionGranted = granted
    }

    LaunchedEffect(Unit) {
        val zone = ZoneId.systemDefault().id
        notifPrefs = runCatching {
            notificationsRepository.loadPrefs() ?: run {
                notificationsRepository.ensureDefaults(zone)
                notificationsRepository.loadPrefs()
            }
        }.getOrNull() ?: NotificationPrefsRow(timezone = zone)
        rules = runCatching { notificationsRepository.loadRules() }.getOrElse { emptyList() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack)
        ScreenHeader(title = "NOTIFICATIONS", context = "QUIET HOURS")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val prefs = notifPrefs
            if (prefs == null) {
                Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
            } else {
                FTCard(title = "NOTIFICATIONS") {
                    Text(
                        "Quiet hours hold reminders until the window ends instead of interrupting you. " +
                            "Stored with your account.",
                        style = FTType.BodySmall,
                        color = FT.TextSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "QUIET HOURS",
                            style = FTType.LabelCaps,
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
                            style = FTType.Caption,
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
                            style = FTType.Caption,
                            color = FT.Critical,
                        )
                    }
                    notifSavedAt?.let {
                        Text("Saved $it", style = FTType.Caption, color = FT.TextSecondary)
                    }
                }

                if (rules.isNotEmpty()) {
                    FTCard(title = "WHAT TO RECEIVE") {
                        NotificationKind.ALL.forEach { kind ->
                            val rule = rules.find { it.kind == kind }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    RULE_LABELS[kind] ?: kind.uppercase(),
                                    style = FTType.LabelCaps,
                                    color = FT.TextSecondary,
                                )
                                Switch(
                                    checked = rule?.enabled ?: true,
                                    onCheckedChange = { enabled ->
                                        rules = rules.map { if (it.kind == kind) it.copy(enabled = enabled) else it }
                                        scope.launch { runCatching { notificationsRepository.setRuleEnabled(kind, enabled) } }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = FT.Emerald,
                                        checkedTrackColor = FT.Emerald.copy(alpha = 0.3f),
                                        uncheckedThumbColor = FT.TextSecondary,
                                        uncheckedTrackColor = FT.GlassFill,
                                    ),
                                )
                            }
                        }
                        if (!notifPermissionGranted) {
                            Text(
                                "Allow notifications in system settings to receive reminders.",
                                style = FTType.Caption,
                                color = FT.Critical,
                            )
                        }
                    }
                }
            }
        }
    }
}
