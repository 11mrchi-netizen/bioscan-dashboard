package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodayFuel
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.fuel.MealAdherence
import com.bioscan.fieldterminal.domain.fuel.MealSlotKind
import com.bioscan.fieldterminal.domain.fuel.Plate
import com.bioscan.fieldterminal.domain.fuel.logTimeFor
import com.bioscan.fieldterminal.domain.fuel.mealTargetLine
import com.bioscan.fieldterminal.domain.fuel.plannedMealDescription
import com.bioscan.fieldterminal.domain.fuel.plateChoices
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.Toasts
import com.bioscan.fieldterminal.ui.screens.status.glycaemicWord
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Scoring system (docs/scoring-system/02-page-mockups.md §1): a planned meal from TODAY'S PLAN. What the plan aims for,
// what the plan puts on it (the prepped breakfast or container, else the example plates), LOG THIS for any of them, and
// LOG SOMETHING ELSE for the food form. The time rule is domain/fuel/MealLogging.kt's.

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun MealOverlay(fuel: TodayFuel, kind: MealSlotKind, onClose: () -> Unit, onLogged: () -> Unit, onLogOther: () -> Unit) {
    val meals = fuel.todayCoded.meals
    val meal = meals.firstOrNull { it.kind == kind }
    FullScreenOverlay(title = kind.label, trailing = meal?.time?.format(HM), onClose = onClose) {
        if (meal == null) {
            Text("This meal is no longer in today's plan.", style = FTType.Body, color = FT.TextMuted)
            return@FullScreenOverlay
        }
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        fun log(plate: Plate) {
            val at = logTimeFor(meal, meals, ZonedDateTime.now())
            // Optimistic: the overlay closes at once; TODAY refreshes when the meal lands, or RETRY says it did not.
            Toasts.optimistic(apply = onClose, revert = {}, what = "Couldn't log ${kind.label.lowercase()}", save = {
                AddEntryRepository(SupabaseClientProvider.client).addFood(
                    loggedAt = WallClock.write(at),
                    description = plannedMealDescription(meal, plate),
                    calories = plate.kcal, proteinG = plate.proteinG, carbsG = plate.carbG, fatG = plate.fatG,
                    fiberG = plate.fibreG, sugarG = null, sodiumMg = null,
                    // Logged from the plan's own meal: that is its label.
                    mealSlot = meal.kind.name.lowercase(),
                )
                onLogged()
            })
        }

        Text(mealTargetLine(meal), style = FTType.RowTitle, color = FT.TextPrimary)
        Text(meal.reason, style = FTType.BodySmall, color = FT.TextSecondary)
        fuel.adherence[meal.kind.name]?.let { (g, a) ->
            if (a != MealAdherence.NOT_LOGGED) Text("Logged ${g.roundToInt()} g carbs · ${a.label}", style = FTType.LabelCaps, color = FT.Ok)
        }
        // The glucose-spiking band of what was logged for this meal (FUEL › Nutrition has the detail).
        fuel.glycaemic[meal.kind.name]?.let { band ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GLUCOSE SPIKING", style = FTType.Micro, color = FT.TextMuted)
                FTStatePill(glycaemicWord(band))
                Text("estimate", style = FTType.Micro, color = FT.TextMuted)
            }
        }

        val index = meals.indexOf(meal)
        val today = fuel.horizon.firstOrNull()
        val choices = plateChoices(meal, today?.suggestions?.getOrNull(index), today?.prep)
        if (choices.isEmpty()) {
            OverlaySection("ON THE PLATE") { Text("No example plate for this meal; log what you eat.", style = FTType.BodySmall, color = FT.TextMuted) }
        }
        for (choice in choices) {
            OverlaySection(choice.heading) {
                Text(choice.plate.label, style = FTType.BodySmall, color = FT.TextPrimary)
                Text(
                    "C ${choice.plate.carbG.roundToInt()} · P ${choice.plate.proteinG.roundToInt()} · F ${choice.plate.fatG.roundToInt()} g · ${choice.plate.kcal.roundToInt()} kcal",
                    style = FTType.MonoCaption, color = FT.TextSecondary,
                )
                FTButton(label = if (saving) "SAVING..." else "LOG THIS", emphasis = FTButtonEmphasis.Primary, enabled = !saving, modifier = Modifier.fillMaxWidth()) { log(choice.plate) }
            }
        }
        error?.let { Text("Couldn't log: $it", style = FTType.BodySmall, color = FT.Bad) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FTButton(label = "LOG SOMETHING ELSE", emphasis = FTButtonEmphasis.Secondary, enabled = !saving, modifier = Modifier.fillMaxWidth()) { onLogOther() }
            Text("Logged now, or at ${meal.time.format(HM)} if now is outside this meal's slot.", style = FTType.Caption, color = FT.TextMuted)
        }
        Spacer(Modifier.height(24.dp))
    }
}
