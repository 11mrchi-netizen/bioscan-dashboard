package com.bioscan.fieldterminal.ui.screens.fuel

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.FuelSettingsStore
import com.bioscan.fieldterminal.domain.fuel.MealTimes
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.TimeField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalTime

// Fuel planning settings (data/FuelSettingsStore.kt): meal times, training low, the carbohydrate rate the gut tolerates,
// caffeine and sodium. Body weight comes from Fuel › Body and the home location (for weather) from Setup › Connected services.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelSettingsSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { FuelSettingsStore.get(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var breakfast by remember { mutableStateOf(initial.meals.breakfast) }
    var lunch by remember { mutableStateOf(initial.meals.lunch) }
    var snack by remember { mutableStateOf(initial.meals.snack) }
    var dinner by remember { mutableStateOf(initial.meals.dinner) }
    var trainLow by remember { mutableStateOf(initial.trainLow) }
    var tolerated by remember { mutableStateOf(initial.toleratedGPerHour) }
    var caffeine by remember { mutableStateOf(initial.caffeineOk) }
    var caffeineDose by remember { mutableStateOf(initial.caffeineMgPerKg) }
    var salty by remember { mutableStateOf(initial.saltySweater) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("FUEL SETTINGS", style = FTType.SectionTitle, color = FT.DomainFuel)
            Text("USUAL MEAL TIMES", style = FTType.LabelCaps, color = FT.TextSecondary)
            TimeField("BREAKFAST", breakfast) { breakfast = it }
            TimeField("LUNCH", lunch) { lunch = it }
            SegmentedToggle(listOf(false, true), snack != null, { if (it) "AFTERNOON SNACK" else "NO SNACK" }, { on -> snack = if (on) snack ?: LocalTime.of(16, 0) else null }, accentColor = FT.DomainFuel)
            snack?.let { s -> TimeField("SNACK", s) { snack = it } }
            TimeField("DINNER", dinner) { dinner = it }

            Text("TRAIN LOW BEFORE EASY SESSIONS", style = FTType.LabelCaps, color = FT.TextSecondary)
            SegmentedToggle(listOf(false, true), trainLow, { if (it) "YES" else "NO" }, { trainLow = it }, accentColor = FT.DomainFuel)
            Text(
                "Some easy sessions started with little carbohydrate can add to endurance adaptations (Impey 2018). Never before hard " +
                    "sessions, never on a deficit day.",
                style = FTType.Caption, color = FT.TextSecondary,
            )

            Text("CARBOHYDRATE YOUR GUT TOLERATES IN A SESSION", style = FTType.LabelCaps, color = FT.TextSecondary)
            SegmentedToggle(listOf(45.0, 60.0, 75.0, 90.0), tolerated, { "${it.toInt()} G/H" }, { tolerated = it }, accentColor = FT.DomainFuel)
            Text("Above 60 g an hour needs practice in long sessions and glucose-plus-fructose products.", style = FTType.Caption, color = FT.TextSecondary)

            Text("CAFFEINE IN RACES", style = FTType.LabelCaps, color = FT.TextSecondary)
            SegmentedToggle(listOf(false, true), caffeine, { if (it) "YES" else "NO" }, { caffeine = it }, accentColor = FT.DomainFuel)
            if (caffeine) SegmentedToggle(listOf(2.0, 3.0, 4.0), caffeineDose, { "${it.toInt()} MG/KG" }, { caffeineDose = it }, accentColor = FT.DomainFuel)

            Text("SALTY SWEATER", style = FTType.LabelCaps, color = FT.TextSecondary)
            SegmentedToggle(listOf(false, true), salty, { if (it) "YES" else "NO" }, { salty = it }, accentColor = FT.DomainFuel)
            Text("White salt marks on kit or skin after long sessions: plans use 1,000 mg of sodium per litre instead of 500-700.", style = FTType.Caption, color = FT.TextSecondary)

            Text(
                "Weather is read at an event's course or place, else at your home location (Setup › Connected services). " +
                    "Add sweat tests under + › BODY to make hydration yours.",
                style = FTType.Caption, color = FT.TextSecondary,
            )
            FTButton("SAVE", emphasis = FTButtonEmphasis.Primary) {
                FuelSettingsStore.save(
                    context,
                    initial.copy(
                        meals = MealTimes(breakfast, lunch, snack, dinner), trainLow = trainLow, toleratedGPerHour = tolerated,
                        caffeineOk = caffeine, caffeineMgPerKg = caffeineDose, saltySweater = salty,
                    ),
                )
                onSaved()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
