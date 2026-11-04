package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.FuelSettingsStore
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.fuel.MealSlotKind
import com.bioscan.fieldterminal.domain.fuel.suggestMealSlot
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDateTime

// Which planned meal a logged meal was (meals.meal_slot). Pre-selected from the person's meal times and the labels already
// used that day (domain/fuel/MealSuggestions.kt suggestMealSlot: a late breakfast stays breakfast); a tap changes it, and a
// pick is kept when the time is changed afterwards.

private val CHOICES = listOf(
    MealSlotKind.BREAKFAST, MealSlotKind.LUNCH, MealSlotKind.SNACK,
    MealSlotKind.DINNER, MealSlotKind.PRE_SESSION, MealSlotKind.RECOVERY,
)

@Composable
fun MealSlotChips(dateTime: LocalDateTime, selected: MealSlotKind?, onSelect: (MealSlotKind) -> Unit, exceptMealId: Long? = null) {
    val context = LocalContext.current
    val times = remember { FuelSettingsStore.get(context).meals }
    var logged by remember { mutableStateOf<Set<MealSlotKind>>(emptySet()) }
    // An existing label (editing a meal) counts as the person's choice.
    var picked by remember { mutableStateOf(selected != null) }
    val date = dateTime.toLocalDate()
    LaunchedEffect(date, exceptMealId) {
        logged = runCatching { AddEntryRepository(SupabaseClientProvider.client).mealSlotsOn(date, exceptMealId) }.getOrDefault(emptySet())
    }
    val suggestion = suggestMealSlot(dateTime.toLocalTime(), times, logged)
    LaunchedEffect(suggestion, picked) { if (!picked) onSelect(suggestion) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("WHICH MEAL", style = FTType.LabelCaps, color = FT.TextSecondary)
        TextChipRow(CHOICES.map { it.label }, (selected ?: suggestion).label, perRow = 3) { label ->
            CHOICES.firstOrNull { it.label == label }?.let { picked = true; onSelect(it) }
        }
    }
}
