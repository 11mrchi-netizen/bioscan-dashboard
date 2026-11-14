package com.bioscan.fieldterminal.ui.screens.settings

import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.UserProfileRepository
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTChoiceChip
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.nav.UserTab
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Setup > User: opens on Profile, then Nutrition Goals.
@Composable
fun UserSettingsScreen(scope: CoroutineScope, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(UserTab.Profile) }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack, title = "PROFILE & GOALS", subtitle = "PROFILE · NUTRITION GOALS · DATA CAPTURE")
        SubTabRow(items = UserTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (tab) {
                UserTab.Profile -> ProfileCard(scope)
                UserTab.NutritionGoals -> NutritionGoalsCard()
                UserTab.DataCapture -> {
                    AssistantCard()
                    RoutineCaptureCard()
                    PlacesCard()
                    CaptureCoverageCard()
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(scope: CoroutineScope) {
    val userProfileRepository = remember { UserProfileRepository(SupabaseClientProvider.client) }
    var dobInput by remember { mutableStateOf("") }
    var selectedSex by remember { mutableStateOf<String?>(null) }
    var heightInput by remember { mutableStateOf("") }
    var athleteMode by remember { mutableStateOf(false) }
    var profileSaving by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf(false) }
    var profileSavedAt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val profile = userProfileRepository.loadProfile()
            dobInput = profile?.dateOfBirth ?: ""
            selectedSex = profile?.sex
            heightInput = profile?.heightCm?.let { "%.0f".format(it) } ?: ""
            athleteMode = profile?.athleteMode ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline: leave the form empty; saving later surfaces its own error.
            Log.w("UserSettings", "profile load failed", e)
        }
    }

    FTCard(title = "PROFILE") {
        Text(
            "Date of birth and sex, used by the Aging Profile (User tab) and by population " +
                "comparisons elsewhere. Height and athlete mode feed the body-composition estimates from " +
                "the scale's impedance. Stored with your account, not on this device only.",
            style = FTType.BodySmall,
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
                style = FTType.LabelCaps,
                color = FT.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SexChip("Male", selectedSex == "male") { selectedSex = if (selectedSex == "male") null else "male" }
                SexChip("Female", selectedSex == "female") { selectedSex = if (selectedSex == "female") null else "female" }
            }
        }
        FieldTextField(
            value = heightInput,
            onValueChange = { heightInput = it },
            placeholder = "Height (cm)",
        )
        Column {
            Text(
                "ATHLETE MODE",
                style = FTType.LabelCaps,
                color = FT.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SexChip("Off", !athleteMode) { athleteMode = false }
                SexChip("On", athleteMode) { athleteMode = true }
            }
        }
        FTButton(label = if (profileSaving) "SAVING…" else "SAVE PROFILE", emphasis = FTButtonEmphasis.Primary) {
            val dob = dobInput.trim().ifBlank { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val height = heightInput.trim().replace(',', '.').ifBlank { null }?.toDoubleOrNull()
            val heightInvalid = heightInput.isNotBlank() && (height == null || height !in 100.0..230.0)
            if ((dobInput.isNotBlank() && dob == null) || heightInvalid) {
                profileError = true
            } else {
                profileError = false
                scope.launchSafe(onError = { profileError = true }, onDone = { profileSaving = false }) {
                    profileSaving = true
                    userProfileRepository.saveProfile(dob, selectedSex, height, athleteMode)
                    profileSaving = false
                    profileSavedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                }
            }
        }
        if (profileError) {
            Text(
                "Enter the date as YYYY-MM-DD (e.g. 1990-05-14) and height in cm (100–230).",
                style = FTType.Caption,
                color = FT.Bad,
            )
        }
        profileSavedAt?.let {
            Text("Saved $it", style = FTType.Caption, color = FT.TextSecondary)
        }
    }
}

// Tap-to-select chip -- tapping the already-selected chip clears it back to
// null (matches AddEntrySheet.kt's TextChipRow, an optional field, not a
// forced either/or).
@Composable
private fun SexChip(label: String, selected: Boolean, onClick: () -> Unit) = FTChoiceChip(label.uppercase(), selected, onClick)
