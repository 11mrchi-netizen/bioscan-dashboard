package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.MaintenanceInputs
import com.bioscan.fieldterminal.data.NutritionGoals
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.NutritionTargetsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.ActivityLevel
import com.bioscan.fieldterminal.domain.EnergyBalance
import com.bioscan.fieldterminal.domain.EnergyBalanceKind
import com.bioscan.fieldterminal.domain.FatBasis
import com.bioscan.fieldterminal.domain.MacroPlan
import com.bioscan.fieldterminal.domain.MacroPlanResult
import com.bioscan.fieldterminal.domain.MaintenanceEstimate
import com.bioscan.fieldterminal.domain.ProteinBasis
import com.bioscan.fieldterminal.domain.energyBalance
import com.bioscan.fieldterminal.domain.maintenanceEstimate
import com.bioscan.fieldterminal.domain.planMacros
import com.bioscan.fieldterminal.domain.restingEnergyKcal
import com.bioscan.fieldterminal.ui.components.ClearChip
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.MACRO_COLORS
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.softLoad
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.abs
import kotlin.math.roundToInt

// Setup › Profile & goals › Nutrition goals (domain/NutritionTargets.kt). Calories, protein and fat are entered, carbs
// are what the calories leave, so the goal always adds up; the card shows each macro's share of the calories and reads
// the goal against estimated maintenance as a deficit, maintenance or surplus. Saved as grams, so everything that reads
// the goals (Fuel's goal-adherence views) is unchanged; day-to-day intake is whatever was logged.

private const val INFO =
    "Set calories, protein and fat; carbohydrate is what the calories leave, so the macros always add up " +
        "(4 kcal per gram of protein and carbohydrate, 9 per gram of fat). The goal is read against your estimated " +
        "maintenance: within 5 % is maintenance, below is a deficit, above a surplus."

private const val INFO_DETAILS =
    "Maintenance is resting energy by the Mifflin-St Jeor equation (sex, age, latest height and weight) times an " +
        "activity factor: 1.2 sedentary, 1.375 light (under 2.5 h of training a week), 1.55 moderate (2.5–5 h), 1.725 " +
        "very active (5–7.5 h), 1.9 extra active (over 7.5 h), taken from the last four weeks of logged training unless " +
        "you choose one. Equations like this are within about 10 % for most people, so treat the reading as a guide " +
        "and adjust from your weight trend. The weekly weight change uses about 7,700 kcal per kg, an upper bound: " +
        "the change slows as the body adapts (Hall 2011). Reference ranges in the notes: protein 0.8 g/kg adult RDA and " +
        "1.4–2.0 g/kg for people who train (ISSN 2017), benefit flattening beyond 1.6–2.2 g/kg (Morton 2018); fat 20–35 % " +
        "of calories (Institute of Medicine); carbohydrate from 3 g/kg for light training (ACSM 2016)."

@Composable
internal fun NutritionGoalsCard() {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(NutritionGoalsStore.getGoals(context)) }
    var inputs by remember { mutableStateOf<MaintenanceInputs?>(null) }
    LaunchedEffect(Unit) {
        inputs = softLoad { NutritionTargetsRepository(SupabaseClientProvider.client).loadMaintenanceInputs() } ?: MaintenanceInputs()
    }

    var kcalIn by remember { mutableStateOf(saved.caloriesKcal.input()) }
    var proteinBasis by remember { mutableStateOf(if (saved.proteinPerKg != null) ProteinBasis.PER_KG else ProteinBasis.GRAMS) }
    var proteinIn by remember { mutableStateOf((saved.proteinPerKg ?: saved.proteinG).input()) }
    var fatBasis by remember { mutableStateOf(if (saved.fatPct != null) FatBasis.PERCENT else FatBasis.GRAMS) }
    var fatIn by remember { mutableStateOf((saved.fatPct ?: saved.fatG).input()) }
    var activity by remember { mutableStateOf(ActivityLevel.of(saved.activityLevel)) }
    var waterMinIn by remember { mutableStateOf(saved.hydrationMinMl.input()) }
    var waterMaxIn by remember { mutableStateOf(saved.hydrationMaxMl.input()) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    val i = inputs
    val resting = i?.let { restingEnergyKcal(it.sex, it.ageYears, it.heightCm, it.weightKg) }
    val maintenance = maintenanceEstimate(resting, activity, i?.weeklyTrainingMin)
    val enteredBad = listOf(kcalIn, proteinIn, fatIn, waterMinIn, waterMaxIn).any { it.isNotBlank() && it.parse() == null }
    val result = planMacros(kcalIn.parse(), proteinIn.parse(), proteinBasis, fatIn.parse(), fatBasis, i?.weightKg, resting)
    val waterMin = waterMinIn.parse()
    val waterMax = waterMaxIn.parse()
    val waterBad = (waterMin == null) != (waterMax == null) || (waterMin != null && waterMax != null && (waterMin <= 0 || waterMin >= waterMax))

    FTCard(title = "NUTRITION GOALS", info = INFO, infoDetails = INFO_DETAILS) {
        Text(
            "Daily targets for the goal-adherence views. Stored on this device only.",
            style = FTType.BodySmall,
            color = FT.TextSecondary,
        )

        Label("MAINTENANCE")
        MaintenanceSection(i, maintenance, activity) { activity = it; savedNote = null }

        Label("DAILY GOAL")
        FieldTextField(kcalIn, { kcalIn = it; savedNote = null }, "Calories (kcal)", keyboardType = KeyboardType.Decimal)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldTextField(proteinIn, { proteinIn = it; savedNote = null }, if (proteinBasis == ProteinBasis.PER_KG) "Protein (g/kg)" else "Protein (g)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            SegmentedToggle(ProteinBasis.entries, proteinBasis, { it.label }, { proteinBasis = convertProtein(it, proteinBasis, proteinIn, i?.weightKg) { v -> proteinIn = v }; savedNote = null }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldTextField(fatIn, { fatIn = it; savedNote = null }, if (fatBasis == FatBasis.PERCENT) "Fat (% of kcal)" else "Fat (g)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            SegmentedToggle(FatBasis.entries, fatBasis, { it.label }, { fatBasis = convertFat(it, fatBasis, fatIn, kcalIn.parse()) { v -> fatIn = v }; savedNote = null }, modifier = Modifier.weight(1f))
        }

        when {
            enteredBad -> Text("Enter numbers only, or leave a field blank.", style = FTType.Caption, color = FT.Critical)
            result is MacroPlanResult.Invalid -> Text(result.message, style = FTType.Caption, color = FT.Critical)
            result is MacroPlanResult.Ok -> GoalSummary(result, maintenance, i?.weightKg)
            else -> Text("No calorie goal: the views show your intake without a target.", style = FTType.Caption, color = FT.TextMuted)
        }

        Label("WATER")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldTextField(waterMinIn, { waterMinIn = it; savedNote = null }, "Water min (ml)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            FieldTextField(waterMaxIn, { waterMaxIn = it; savedNote = null }, "Water max (ml)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        }
        Text(
            if (waterBad) "Water needs both a min and a max, min below max." else "A range; leave both blank for the 2,500–3,500 ml reference.",
            style = FTType.Caption,
            color = if (waterBad) FT.Critical else FT.TextMuted,
        )

        val canSave = !enteredBad && !waterBad && result !is MacroPlanResult.Invalid
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FTButton(label = "SAVE GOALS", emphasis = FTButtonEmphasis.Primary, enabled = canSave) {
                val ok = result as? MacroPlanResult.Ok
                val plan = ok?.plan
                val goals = NutritionGoals(
                    caloriesKcal = ok?.kcal,
                    proteinG = plan?.proteinG?.oneDecimal(),
                    carbsG = plan?.carbsG?.oneDecimal(),
                    fatG = plan?.fatG?.oneDecimal(),
                    hydrationMinMl = waterMin,
                    hydrationMaxMl = waterMax,
                    proteinPerKg = if (plan != null && proteinBasis == ProteinBasis.PER_KG) proteinIn.parse() else null,
                    fatPct = if (plan != null && fatBasis == FatBasis.PERCENT) fatIn.parse() else null,
                    activityLevel = activity?.key,
                )
                NutritionGoalsStore.saveGoals(context, goals)
                saved = goals
                savedNote = "Saved."
            }
            if (saved.anySet || saved.activityLevel != null) {
                ClearChip {
                    NutritionGoalsStore.clearGoals(context)
                    saved = NutritionGoals()
                    kcalIn = ""; proteinIn = ""; fatIn = ""; waterMinIn = ""; waterMaxIn = ""
                    activity = null
                    savedNote = "Cleared."
                }
            }
        }
        savedNote?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun MaintenanceSection(inputs: MaintenanceInputs?, estimate: MaintenanceEstimate?, chosen: ActivityLevel?, onChoose: (ActivityLevel?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            inputs == null -> Text("Loading your profile, weight and training…", style = FTType.Caption, color = FT.TextMuted)
            estimate == null -> Text(missingInputs(inputs), style = FTType.Caption, color = FT.TextMuted)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Estimated maintenance", style = FTType.Body, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                    Text("${kcal(estimate.kcal)} kcal/day", style = FTType.Value, color = FT.TextPrimary)
                }
                Text(
                    "Resting ${kcal(estimate.restingKcal)} kcal (Mifflin-St Jeor: ${inputs.sex}, ${inputs.ageYears} y, " +
                        "${inputs.heightCm?.roundToInt()} cm, ${"%.1f".format(inputs.weightKg)} kg) × ${estimate.level.factor} " +
                        estimate.level.label.lowercase() +
                        (estimate.weeklyTrainingMin?.takeIf { estimate.fromTraining }?.let { " (${hoursMinutes(it)} of training a week, last 4 weeks)" } ?: " (chosen)"),
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            }
        }
        // The activity level: AUTO takes it from logged training; a choice overrides it.
        // Two rows of three; the selected one is lit in whichever row holds it.
        SegmentedToggle(listOf(null, ActivityLevel.SEDENTARY, ActivityLevel.LIGHT), chosen, { activityShort(it) }, onChoose)
        SegmentedToggle(listOf(ActivityLevel.MODERATE, ActivityLevel.VERY, ActivityLevel.EXTRA), chosen, { activityShort(it) }, { onChoose(it) })
    }
}

private fun activityShort(level: ActivityLevel?): String = when (level) {
    null -> "AUTO"
    ActivityLevel.SEDENTARY -> "SEDENTARY"
    ActivityLevel.LIGHT -> "LIGHT"
    ActivityLevel.MODERATE -> "MODERATE"
    ActivityLevel.VERY -> "VERY"
    ActivityLevel.EXTRA -> "EXTRA"
}

private fun missingInputs(i: MaintenanceInputs): String {
    val missing = listOfNotNull(
        "sex".takeIf { i.sex != "male" && i.sex != "female" },
        "date of birth".takeIf { i.ageYears == null },
        "height".takeIf { i.heightCm == null },
        "a weight".takeIf { i.weightKg == null },
    )
    return "Maintenance needs " + missing.joinToString(", ") +
        ": set sex and date of birth in Profile; height and weight come from Health Connect or the body check-in."
}

@Composable
private fun GoalSummary(ok: MacroPlanResult.Ok, maintenance: MaintenanceEstimate?, weightKg: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        ok.plan?.let { plan ->
            MacroSplitBar(plan)
            val (p, c, f) = plan.sharesPercent()
            MacroRow("Protein", plan.proteinG, plan.proteinKcal, p, MACRO_COLORS[1], weightKg)
            MacroRow("Carbs (remainder)", plan.carbsG, plan.carbsKcal, c, MACRO_COLORS[0], weightKg)
            MacroRow("Fat", plan.fatG, plan.fatKcal, f, MACRO_COLORS[2], weightKg)
        }
        maintenance?.let { BalanceLine(energyBalance(ok.kcal, it.kcal)) }
            ?: Text("Set the profile inputs above to read this goal against maintenance.", style = FTType.Caption, color = FT.TextMuted)
        ok.notes.forEach { Text("• $it", style = FTType.Caption, color = FT.TextSecondary) }
    }
}

/** One bar split by each macro's calories, protein · carbs · fat, with a 2 dp gap between segments. */
@Composable
private fun MacroSplitBar(plan: MacroPlan) {
    val parts = listOf(plan.proteinKcal to MACRO_COLORS[1], plan.carbsKcal to MACRO_COLORS[0], plan.fatKcal to MACRO_COLORS[2])
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val gap = 2.dp.toPx()
        val usable = size.width - gap * (parts.count { it.first > 0 } - 1).coerceAtLeast(0)
        var x = 0f
        parts.filter { it.first > 0 }.forEach { (k, color) ->
            val w = (k / plan.kcal).toFloat() * usable
            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
            x += w + gap
        }
    }
}

@Composable
private fun MacroRow(name: String, grams: Double, kcalValue: Double, pct: Int, color: Color, weightKg: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(name, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f))
        val perKg = weightKg?.takeIf { it > 0 }?.let { " · %.1f g/kg".format(grams / it) } ?: ""
        Text("${grams.roundToInt()} g · ${kcal(kcalValue)} kcal · $pct %$perKg", style = FTType.BodySmall, color = FT.TextPrimary)
    }
}

@Composable
private fun BalanceLine(b: EnergyBalance) {
    val sign = if (b.diffKcal >= 0) "+" else "−"
    val detail = when (b.kind) {
        EnergyBalanceKind.MAINTENANCE -> "within 5 % of maintenance (${sign}${kcal(abs(b.diffKcal))} kcal/day): weight should hold"
        else -> "${sign}${kcal(abs(b.diffKcal))} kcal/day (${sign}${(abs(b.diffShare) * 100).roundToInt()} %) against maintenance · " +
            "about ${sign}${"%.2f".format(abs(b.kgPerWeek))} kg a week at first"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(b.kind.label, style = FTType.LabelCaps, color = FT.TextPrimary)
        Text(detail, style = FTType.Caption, color = FT.TextSecondary)
    }
}

// ---- small helpers ----

private fun Double?.input(): String = this?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.1f".format(it) } ?: ""
private fun String.parse(): Double? = trim().takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()
private fun Double.oneDecimal(): Double = (this * 10).roundToInt() / 10.0
private fun kcal(v: Double): String = "%,d".format(v.roundToInt())
private fun hoursMinutes(min: Double): String = (min.roundToInt()).let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" }

/** Switching the protein basis converts the number already typed, when it can. */
private fun convertProtein(to: ProteinBasis, from: ProteinBasis, input: String, weightKg: Double?, set: (String) -> Unit): ProteinBasis {
    val v = input.parse()
    if (v != null && to != from && weightKg != null && weightKg > 0) {
        set((if (to == ProteinBasis.PER_KG) v / weightKg else v * weightKg).let { if (to == ProteinBasis.PER_KG) "%.1f".format(it) else it.roundToInt().toString() })
    }
    return to
}

/** Switching the fat basis converts the number already typed, when the calories are set. */
private fun convertFat(to: FatBasis, from: FatBasis, input: String, kcal: Double?, set: (String) -> Unit): FatBasis {
    val v = input.parse()
    if (v != null && to != from && kcal != null && kcal > 0) {
        set((if (to == FatBasis.PERCENT) v * 9 / kcal * 100 else kcal * v / 100 / 9).roundToInt().toString())
    }
    return to
}
