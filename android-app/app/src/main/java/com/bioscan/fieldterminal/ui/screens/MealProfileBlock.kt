package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.fuel.MealIndex
import com.bioscan.fieldterminal.domain.fuel.MealIndexLine
import com.bioscan.fieldterminal.domain.fuel.MealProfile
import com.bioscan.fieldterminal.domain.fuel.mealGroupLabels
import com.bioscan.fieldterminal.domain.fuel.mealIndexLines
import com.bioscan.fieldterminal.domain.fuel.mealTagLabels
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// MEAL PROFILE on the Log's entry sheet for a meal: each index on its own line (value in the index's hue, the band and
// how it was got in words), then the food groups and the gut-relevant tags. No combined score, by the owner's choice.
// The rows are read-only (nothing here looks tappable), so none needs a history.

/** The hue of one index (FT.Metric, design/tokens). */
internal fun mealIndexInk(index: MealIndex): Color = when (index) {
    MealIndex.ENERGY_DENSITY -> FT.Metric.EnergyDensity
    MealIndex.PROTEIN -> FT.Metric.Protein
    MealIndex.CARBS -> FT.Metric.Carbs
    MealIndex.FIBER -> FT.Metric.Fiber
    MealIndex.GLYCEMIC_LOAD -> FT.Metric.GlycemicLoad
    MealIndex.FULLNESS -> FT.Metric.Fullness
    MealIndex.PROCESSED -> FT.Metric.UltraProcessed
}

@Composable
fun MealProfileBlock(mealId: Long) {
    val repo = remember { AddEntryRepository(SupabaseClientProvider.client) }
    var profile by remember(mealId) { mutableStateOf<MealProfile?>(null) }
    var weightKg by remember { mutableStateOf<Double?>(null) }
    var failed by remember(mealId) { mutableStateOf(false) }
    LaunchedEffect(mealId) {
        try {
            profile = repo.fetchMealProfile(mealId)?.toDomain()
            weightKg = runCatching { repo.latestWeightKg() }.getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val p = profile
    if (p == null) {
        if (failed) Text("Meal profile couldn't load.", style = FTType.Caption, color = FT.TextMuted)
        return
    }
    MealProfileContent(p, weightKg)
}

@Composable
internal fun MealProfileContent(p: MealProfile, weightKg: Double?) {
    val lines = mealIndexLines(p, weightKg)
    val groups = mealGroupLabels(p)
    val tags = mealTagLabels(p)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("MEAL PROFILE", style = FTType.LabelCaps, color = FT.TextMuted)
        if (lines.isEmpty()) Text("Not enough detail logged to profile this meal.", style = FTType.BodySmall, color = FT.TextSecondary)
        lines.forEach { IndexLine(it) }
        if (groups.isNotEmpty()) Text("Foods: " + groups.joinToString(" · "), style = FTType.BodySmall, color = FT.TextSecondary)
        if (tags.isNotEmpty()) Text("For the gut: " + tags.joinToString(" · "), style = FTType.BodySmall, color = FT.TextSecondary)
        if (p.macroMismatch) {
            Text("The kcal don't match the protein, carbs and fat. Worth a check with EDIT.", style = FTType.BodySmall, color = FT.Warn)
        }
    }
}

@Composable
private fun IndexLine(line: MealIndexLine) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(line.label, style = FTType.Body, color = FT.TextSecondary)
            Text(
                line.value,
                style = FTType.Value,
                color = mealIndexInk(line.index),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        Text("${line.band} · ${line.why}", style = FTType.Caption, color = FT.TextMuted)
    }
}
