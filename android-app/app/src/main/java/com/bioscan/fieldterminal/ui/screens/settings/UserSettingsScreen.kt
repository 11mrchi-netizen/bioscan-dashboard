package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.NutritionGoals
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.UserProfileRepository
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.ClearChip
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.nav.UserTab
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Setup > User: opens on Profile, then Nutrition Goals.
@Composable
fun UserSettingsScreen(scope: CoroutineScope, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(UserTab.Profile) }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack)
        SubTabRow(items = UserTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (tab) {
                UserTab.Profile -> ProfileCard(scope)
                UserTab.NutritionGoals -> NutritionGoalsCard()
            }
        }
    }
}

@Composable
private fun ProfileCard(scope: CoroutineScope) {
    val userProfileRepository = remember { UserProfileRepository(SupabaseClientProvider.client) }
    var dobInput by remember { mutableStateOf("") }
    var selectedSex by remember { mutableStateOf<String?>(null) }
    var profileSaving by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf(false) }
    var profileSavedAt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val profile = userProfileRepository.loadProfile()
        dobInput = profile?.dateOfBirth ?: ""
        selectedSex = profile?.sex
    }

    FTCard(title = "PROFILE") {
        Text(
            "Date of birth and sex, used by the Aging Profile (User tab) and by population " +
                "comparisons elsewhere. Stored with your account, not on this device only.",
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
                style = FTType.Caption,
                color = FT.Critical,
            )
        }
        profileSavedAt?.let {
            Text("Saved $it", style = FTType.Caption, color = FT.TextSecondary)
        }
    }
}

@Composable
private fun NutritionGoalsCard() {
    val context = LocalContext.current
    val savedGoals = remember { mutableStateOf(NutritionGoalsStore.getGoals(context)) }
    var caloriesInput by remember { mutableStateOf(savedGoals.value.caloriesKcal?.toString() ?: "") }
    var proteinInput by remember { mutableStateOf(savedGoals.value.proteinG?.toString() ?: "") }
    var carbsInput by remember { mutableStateOf(savedGoals.value.carbsG?.toString() ?: "") }
    var fatInput by remember { mutableStateOf(savedGoals.value.fatG?.toString() ?: "") }
    var hydrationMinInput by remember { mutableStateOf(savedGoals.value.hydrationMinMl?.toString() ?: "") }
    var hydrationMaxInput by remember { mutableStateOf(savedGoals.value.hydrationMaxMl?.toString() ?: "") }
    var goalsInputError by remember { mutableStateOf(false) }

    FTCard(title = "NUTRITION GOALS") {
        Text(
            "Daily calorie and macro targets, used by Nutrition Analysis's goal-adherence view. " +
                "Leave a field blank to clear just that target — stored on this device only.",
            style = FTType.BodySmall,
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
            FieldTextField(hydrationMinInput, { hydrationMinInput = it }, "Water min (ml)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            FieldTextField(hydrationMaxInput, { hydrationMaxInput = it }, "Water max (ml)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        }
        Text(
            "Water is a range; set both ends, or leave both blank for the 2,500–3,500 ml reference range.",
            style = FTType.Caption,
            color = FT.TextMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmberButton(label = "SAVE GOALS") {
                // Blank clears that one field; anything entered must be a positive number.
                fun parse(input: String): Double? = input.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                val calories = parse(caloriesInput)
                val protein = parse(proteinInput)
                val carbs = parse(carbsInput)
                val fat = parse(fatInput)
                val hydrationMin = parse(hydrationMinInput)
                val hydrationMax = parse(hydrationMaxInput)
                val enteredButInvalid = listOf(
                    caloriesInput to calories, proteinInput to protein, carbsInput to carbs, fatInput to fat,
                    hydrationMinInput to hydrationMin, hydrationMaxInput to hydrationMax,
                ).any { (input, parsed) -> input.isNotBlank() && (parsed == null || parsed <= 0) }
                // A range needs both ends, in order.
                val hydrationRangeInvalid = (hydrationMin == null) != (hydrationMax == null) ||
                    (hydrationMin != null && hydrationMax != null && hydrationMin >= hydrationMax)
                if (enteredButInvalid || hydrationRangeInvalid) {
                    goalsInputError = true
                } else {
                    val goals = NutritionGoals(calories, protein, carbs, fat, hydrationMin, hydrationMax)
                    NutritionGoalsStore.saveGoals(context, goals)
                    savedGoals.value = goals
                    goalsInputError = false
                }
            }
            if (savedGoals.value.anySet) {
                ClearChip {
                    NutritionGoalsStore.clearGoals(context)
                    savedGoals.value = NutritionGoals()
                    caloriesInput = ""; proteinInput = ""; carbsInput = ""; fatInput = ""
                    hydrationMinInput = ""; hydrationMaxInput = ""
                    goalsInputError = false
                }
            }
        }
        if (goalsInputError) {
            Text(
                "Enter a positive number for each target you set (or leave it blank). Water needs both a min and a max, min below max.",
                style = FTType.Caption,
                color = FT.Critical,
            )
        }
    }
}

// Tap-to-select chip -- tapping the already-selected chip clears it back to
// null (matches AddEntrySheet.kt's TextChipRow, an optional field, not a
// forced either/or).
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
            style = FTType.LabelCaps,
            color = if (selected) FT.Emerald else FT.TextSecondary,
        )
    }
}
