package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.GeminiApiKeyStore
import com.bioscan.fieldterminal.data.HydrationAccountingRepository
import com.bioscan.fieldterminal.data.HydrationExplanationRepository
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.NutrientBreakdownRepository
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodaySweat
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.data.todaySweatRate
import com.bioscan.fieldterminal.domain.CaffeineEvent
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.MacroMove
import com.bioscan.fieldterminal.domain.NUTRIENT_REFERENCE
import com.bioscan.fieldterminal.domain.NutrientCategory
import com.bioscan.fieldterminal.domain.TdeeEvaluation
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.computeCaffeineWindow
import com.bioscan.fieldterminal.domain.computeHydrationIntelligence
import com.bioscan.fieldterminal.domain.evaluateNutrition
import com.bioscan.fieldterminal.domain.evaluateTdee
import com.bioscan.fieldterminal.domain.evaluateWeightTrend
import com.bioscan.fieldterminal.domain.fuel.DayHydration
import com.bioscan.fieldterminal.domain.fuel.HydrationStatus
import com.bioscan.fieldterminal.domain.macroMove
import com.bioscan.fieldterminal.domain.perKg
import com.bioscan.fieldterminal.domain.proteinByMeal
import com.bioscan.fieldterminal.domain.score.StoolLane
import com.bioscan.fieldterminal.domain.score.bowelTiming
import com.bioscan.fieldterminal.domain.score.bowelTimingHeadline
import com.bioscan.fieldterminal.domain.score.stoolPattern
import com.bioscan.fieldterminal.domain.score.stoolPatternHeadline
import com.bioscan.fieldterminal.ui.components.NutrientColor
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.components.softLoad
import com.bioscan.fieldterminal.ui.screens.today.BowelTimingChart
import com.bioscan.fieldterminal.ui.screens.today.StoolHypnogram
import com.bioscan.fieldterminal.ui.screens.today.laneColor
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Day columns up to 60 days, weekly means beyond, oldest first; each column the values [pick] returns. */
internal fun <T> dayColumns(rows: List<Pair<LocalDate, T>>, today: LocalDate, days: Int, pick: (T) -> List<Double>): List<Pair<String, List<Double>>> {
    val from = today.minusDays((days - 1).toLong())
    val inRange = rows.filter { !it.first.isBefore(from) && !it.first.isAfter(today) }.sortedBy { it.first }
    if (days <= 60) return inRange.map { (d, t) -> shortDate(d) to pick(t) }
    return inRange.groupBy { ChronoUnit.WEEKS.between(from, it.first) }.toSortedMap().map { (_, week) ->
        val parts = week.map { pick(it.second) }
        val n = parts.maxOf { it.size }
        shortDate(week.first().first) to (0 until n).map { i -> parts.map { it.getOrElse(i) { 0.0 } }.average() }
    }
}

private fun moveWords(m: MacroMove?) = when (m) {
    MacroMove.Growing -> "▲ growing"
    MacroMove.Shrinking -> "▼ shrinking"
    MacroMove.Steady -> "● steady"
    null -> ""
}

private const val BALANCE_INFO =
    "Each logged day's intake minus your estimated expenditure (TDEE, worked out after the fact from your intake and weight " +
        "trend). Not a predictive formula and not a target to hit. The headline is the last 7 logged days' average."

private const val CAL_INFO =
    "Calories logged each day. The shaded band is your calorie target ± 10 % (Setup); the dashed lines are your estimated " +
        "expenditure (TDEE), once your logging and weight trend are enough to work it out. Days you did not log are left empty."

@Composable
fun NutritionScreen(onBack: () -> Unit, onOpenNutrientBreakdown: () -> Unit) {
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    var weights by remember { mutableStateOf<List<Pair<LocalDate, Double>>>(emptyList()) }
    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        guard.run { overview = NutritionRepository(SupabaseClientProvider.client).loadOverview() }
        weights = softLoad { AnalysisRepository(SupabaseClientProvider.client).loadBodyMetrics() }
            ?.mapNotNull { r -> r.weightKg?.let { LocalDate.parse(r.date) to it } }.orEmpty()
    }
    val goals = NutritionGoalsStore.getGoals(LocalContext.current)
    DetailScreen("NUTRITION", onBack, eyebrow = "FUEL", domain = FT.Domain.Fuel) {
        val o = overview
        if (o == null) {
            Loading(guard)
            return@DetailScreen
        }
        val today = LocalDate.now()
        val days = LocalRangeDays.current
        val daily = remember(o) { o.allDays.map { LocalDate.parse(it.date) to it } }
        val tdee: TdeeEvaluation? = remember(o, weights) {
            if (weights.isEmpty()) null else evaluateTdee(evaluateNutrition(o.allDays), evaluateWeightTrend(weights))
        }
        val calories = remember(daily) { daily.associate { it.first to it.second.calories } }
        Section("CALORIES", info = CAL_INFO) {
            val latest = daily.maxByOrNull { it.first }
            Text("${latest?.second?.calories?.roundToInt() ?: "—"} kcal" + (latest?.first?.takeIf { it != today }?.let { " · ${shortDate(it)}" } ?: " · today so far"),
                style = FTType.MetricMedium, color = FT.TextPrimary)
            goals.caloriesKcal?.let { Text("target ${it.roundToInt()} kcal", style = FTType.Caption, color = FT.TextMuted) }
            val tdeeBand = tdee?.takeIf { it.state == EvalState.Stable && it.rangeLowKcal != null && it.rangeHighKcal != null }?.let { it.rangeLowKcal!! to it.rangeHighKcal!! }
            TrendChart(calories, today, days, goals.caloriesKcal?.let { it * 0.9 to it * 1.1 }, Fmt.int, NutrientColor.Calories,
                target = tdeeBand, targetLabel = "your TDEE", bandLabel = "target ± 10 %")
        }

        // Moved here from ME › BODY: each logged day's intake against the post-fact TDEE. Building until both gates are met
        // (10+ complete-logged days in 14 and an established weight trend), never a made-up number.
        Section("ENERGY BALANCE", info = BALANCE_INFO) {
            val expenditure = tdee?.takeIf { it.state == EvalState.Stable }?.tdeeKcal
            if (expenditure == null) {
                EmptyLine("Building: needs 10+ complete-logged days in the last 14 and an established weight trend (10+ weigh-ins across 14+ days).")
            } else {
                val balance = remember(daily, expenditure) { daily.filter { it.second.calories > 0 }.associate { it.first to it.second.calories - expenditure } }
                val week = balance.filterKeys { it > today.minusDays(7) }.values
                // charts-bars-and-scales.md §3: a bipolar value on a zero-centred ±500 kcal axis, deficit left, surplus right.
                val avg = week.takeIf { it.isNotEmpty() }?.average()
                com.bioscan.fieldterminal.ui.components.FTDivergingScale(
                    value = avg, d = 500.0, poles = com.bioscan.fieldterminal.ui.components.DivergingPoles.EnergyBalance,
                    label = "IN − OUT · 7-DAY", valueText = avg?.let { "${Fmt.signed(it)} kcal" },
                    deadZone = 50.0, negativeWord = "deficit", positiveWord = "surplus",
                    description = avg?.let { "Energy balance ${Fmt.signed(it)} kcal a day, ${if (it < 0) "deficit" else "surplus"}" } ?: "Energy balance, no data",
                )
                Text("Expenditure about ${expenditure.roundToInt()} kcal / day", style = FTType.Caption, color = FT.TextMuted)
                TrendChart(balance, today, days, null, Fmt.signed, FT.Metric.EnergyBalance)
            }
        }

        Section("MACROS", info = "Grams of protein, carbs and fat each day (weekly averages beyond two months). Tap a column to read it.") {
            StackedBars(
                dayColumns(daily, today, days) { listOf(it.proteinG, it.carbsG, it.fatG) },
                listOf(NutrientColor.Protein, NutrientColor.Carbs, NutrientColor.Fat), listOf("Protein", "Carbs", "Fat"), Fmt.int, "g", lastPartial = days <= 60 && daily.any { it.first == today },
            )
        }

        Section("PER KG OF BODY WEIGHT", info = "The last 7 logged days' average grams per kilogram of your latest weight, and whether each macro grew or shrank across the range (second half against first half, beyond 5 %).") {
            if (weights.isEmpty()) EmptyLine("Needs a weigh-in.") else {
                val from = today.minusDays((days - 1).toLong())
                fun perKgSeries(pick: (DailyNutrition) -> Double) = perKg(o.allDays, weights, pick).filter { !it.first.isBefore(from) }
                val p = perKgSeries { it.proteinG }
                val c = perKgSeries { it.carbsG }
                val f = perKgSeries { it.fatG }
                fun last7(s: List<Pair<LocalDate, Double>>) = s.takeLast(7).map { it.second }.takeIf { it.isNotEmpty() }?.average()
                // Each tile opens that macro's grams per kilogram over time (all logged days, not just this range).
                fun allPerKg(pick: (DailyNutrition) -> Double) = perKg(o.allDays, weights, pick).toMap()
                val one: (Double) -> String = { "%.1f".format(it) }
                StatTileGrid(
                    listOf(
                        Stat("PROTEIN", last7(p)?.let { "%.1f g".format(it) } ?: "—", moveWords(macroMove(p.map { it.second })),
                            HistorySpec("Protein per kg", allPerKg { it.proteinG }, "g/kg", one, NutrientColor.Protein)),
                        Stat("CARBS", last7(c)?.let { "%.1f g".format(it) } ?: "—", moveWords(macroMove(c.map { it.second })),
                            HistorySpec("Carbs per kg", allPerKg { it.carbsG }, "g/kg", one, NutrientColor.Carbs)),
                        Stat("FAT", last7(f)?.let { "%.1f g".format(it) } ?: "—", moveWords(macroMove(f.map { it.second })),
                            HistorySpec("Fat per kg", allPerKg { it.fatG }, "g/kg", one, NutrientColor.Fat)),
                    ),
                    today,
                )
            }
        }

        Section("PROTEIN PER MEAL", info = "Each column a day, split by meals in the order eaten. Even parts mean protein is spread through the day; a few large ones mean it is bunched into one or two meals.") {
            val byDay = remember(o) { proteinByMeal(o.meals).entries.map { it.key to it.value } }
            val slots = byDay.maxOfOrNull { it.second.size }?.coerceAtMost(6) ?: 0
            StackedBars(
                dayColumns(byDay, today, days) { l -> (0 until slots).map { l.getOrElse(it) { 0.0 } } },
                (0 until slots).map { NutrientColor.Protein.copy(alpha = 1f - it * 0.13f) }, (1..slots).map { "Meal $it" }, Fmt.int, "g", lastPartial = days <= 60 && byDay.any { it.first == today },
            )
        }

        Section("GLUCOSE SPIKING · ESTIMATE", info = com.bioscan.fieldterminal.ui.screens.status.GLUCOSE_INFO, trailing = "TODAY") {
            com.bioscan.fieldterminal.ui.screens.status.GlucoseSpikingRows(o.todaysMeals)
        }

        val lowest by produceState<List<Pair<String, Double>>?>(null, days) {
            value = softLoad {
                val data = NutrientBreakdownRepository(SupabaseClientProvider.client).loadNutrients(days.coerceAtMost(365))
                NUTRIENT_REFERENCE.values
                    .filter { (it.category == NutrientCategory.Vitamin || it.category == NutrientCategory.Mineral) && it.rda != null }
                    .mapNotNull { info -> data.nutrients[info.key]?.let { info.displayName to (it / data.daysCovered / info.rda!!) * 100.0 } }
                    .sortedBy { it.second }.take(8)
            }
        }
        Section("MICRONUTRIENTS · LOWEST", info = "The eight vitamins and minerals furthest below their reference intake, as a daily average over the range (100 % = the adult reference intake, a general guide, not a personal target).") {
            val l = lowest
            when {
                l == null -> EmptyLine("Working it out…")
                l.isEmpty() -> EmptyLine("No micronutrient data logged in this range.")
                else -> ShareBars(l.map { (n, pct) -> Triple(n, pct.coerceAtMost(100.0), "${pct.roundToInt()} %") }, 100.0, NutrientColor.Carbs)
            }
        }
        LinkRow("Every nutrient", "Vitamins, minerals and the rest, against reference intakes", onOpenNutrientBreakdown)
    }
}

private const val NEED_INFO =
    "Each day's fluid (the water log plus the effective hydration of logged drinks) against the range your body likely " +
        "needed that day: your daily baseline (Setup; 2.5-3.5 L if none is set) plus the sweat that day's sessions probably " +
        "cost, from your weight, the session's effort, the temperature at home and your sweat tests. An estimate from logged data, not a dehydration test."

@Composable
fun HydrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    var exerciseMinutes by remember { mutableDoubleStateOf(0.0) }
    var sweat by remember { mutableStateOf<TodaySweat?>(null) }
    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        guard.run { overview = NutritionRepository(SupabaseClientProvider.client).loadOverview() }
        val today = LocalDate.now()
        exerciseMinutes = softLoad { AnalysisRepository(SupabaseClientProvider.client).loadExerciseSessionsForTrainingLoad() }
            ?.filter { WallClock.date(it.startTime) == today }?.sumOf { it.durationMin ?: 0.0 } ?: 0.0
        if (exerciseMinutes > 0) sweat = softLoad { todaySweatRate(SupabaseClientProvider.client, MapSettingsStore.getHome(context)) }
    }
    DetailScreen("HYDRATION", onBack, eyebrow = "FUEL", domain = FT.Domain.Fuel) {
        val o = overview
        if (o == null) {
            Loading(guard)
            return@DetailScreen
        }
        val beverages = o.todayBeverageItems.mapNotNull { it.effectiveHydrationMl }.sum()
        val total = (o.todayHydrationMl?.toDouble() ?: 0.0) + beverages
        val intel = computeHydrationIntelligence(
            measuredIntakeMl = o.todayHydrationMl?.toDouble(),
            effectiveHydrationMl = beverages.takeIf { o.todayBeverageItems.isNotEmpty() },
            exerciseDurationMinutesToday = exerciseMinutes,
            sweatRateMlPerHour = sweat?.mlPerHour,
            environmentKnown = sweat?.environmentKnown ?: false,
        )
        Section("TODAY", info = "Water logged plus the effective hydration of logged drinks, and the extra your sessions today probably cost." +
            if (!intel.environmentalContextAvailable) " Today's temperature is not known (set a home location in Setup › Connected services), so a mild day is assumed." else "") {
            StatTiles(
                listOf(
                    Triple("SO FAR", "%.1f L".format(total / 1000), "water + drinks"),
                    Triple("SESSIONS COST", intel.inferredDemandMl?.let { "%.1f L".format(it / 1000) } ?: "—", if (exerciseMinutes > 0) "${exerciseMinutes.roundToInt()} min today" else "no session today"),
                ),
            )
        }

        val water = remember(o) { o.hydrationHistory.mapNotNull { r -> r.ml?.let { LocalDate.parse(r.date) to it / 1000.0 } }.toMap() }
        MetricPanel("WATER · DAILY", water, "L", Fmt.one, minSd = 0.2, color = NutrientColor.Water,
            info = "Water logged each day, in litres. Drinks logged as meals count towards today's total above but are not in this history yet.")

        val goals = remember { NutritionGoalsStore.getGoals(context) }
        val accounting by produceState<List<DayHydration>?>(null, o) {
            value = softLoad { HydrationAccountingRepository(SupabaseClientProvider.client).load(o, MapSettingsStore.getHome(context), goals) }
        }
        Section("INTAKE AGAINST NEED", info = NEED_INFO) {
            val days = accounting?.takeLast(LocalRangeDays.current.coerceAtMost(60))
            when {
                days == null -> EmptyLine("Working out demand from your sessions and the weather…")
                days.none { it.intakeMl != null } -> EmptyLine("No water or drinks logged in this period.")
                else -> {
                    DemandRangeChart(days)
                    val logged = days.filter { it.intakeMl != null }
                    Text("Within the range on ${logged.count { it.status == HydrationStatus.Within }} of ${logged.size} logged days", style = FTType.Caption, color = FT.TextSecondary)
                }
            }
        }

        val caffeineMg = o.todayBeverageItems.mapNotNull { it.caffeineMg }.takeIf { it.isNotEmpty() }?.sum()
        if (caffeineMg != null) {
            val window = remember(o) {
                val at = o.todaysMeals.associate { it.id to it.loggedAt }
                o.todayBeverageItems.mapNotNull { item ->
                    val mg = item.caffeineMg ?: return@mapNotNull null
                    val t = WallClock.read(at[item.mealId]) ?: return@mapNotNull null
                    CaffeineEvent(t, mg, false)
                }.takeIf { it.isNotEmpty() }?.let { computeCaffeineWindow(it) }
            }
            Section("CAFFEINE", info = "A 5-hour half-life model with a 200 mg reference dose, not personalised yet.") {
                StatTiles(
                    listOfNotNull(
                        Triple("TODAY", "%.0f mg".format(caffeineMg), null),
                        window?.let { Triple("STILL IN YOU", "~%.0f mg".format(it.residualNowMg), null) },
                        window?.residualAtBedtimeMg?.let { Triple("AT 23:00", "~%.0f mg".format(it), null) },
                        window?.cutoffHour?.let { Triple("LAST DOSE BY", if (it == 0) "already met" else "%02d:00".format(it), null) },
                    ),
                )
            }
        }

        val key = remember { GeminiApiKeyStore.get(context) }
        if (key != null) {
            val text by produceState<String?>(null, intel) { value = runCatching { HydrationExplanationRepository(key).explain(intel, null) }.getOrNull() }
            Section("IN WORDS", info = "An AI summary of the numbers above. Validated data only; not medical advice.") {
                Text(text ?: "Writing a summary…", style = FTType.BodySmall, color = if (text == null) FT.TextMuted else FT.TextPrimary)
            }
        }
    }
}

@Composable
fun DigestionScreen(onBack: () -> Unit) {
    var stool by remember { mutableStateOf<List<StoolAnalysisRow>?>(null) }
    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) { guard.run { stool = AnalysisRepository(SupabaseClientProvider.client).loadStoolLog() } }
    DetailScreen("DIGESTION", onBack, eyebrow = "FUEL", domain = FT.Domain.Fuel, defaultRange = ChartRange.M1) {
        val rows = stool
        if (rows == null) {
            Loading(guard)
            return@DetailScreen
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val days = LocalRangeDays.current
        val pattern = remember(rows, days) { stoolPattern(rows, today.minusDays((days - 1).toLong()), today, zone) }
        val timing = remember(rows, days) { bowelTiming(rows, today, days, zone) }
        Section("BY FORM", info = "Every logged bowel movement, placed by its form: liquid on top, then loose, healthy and hard at the bottom, joined in time like a hypnogram. One reading means little; the pattern is the signal. Descriptive only, not a diagnosis.") {
            Text(stoolPatternHeadline(pattern), style = FTType.RowTitle, color = FT.TextPrimary)
            StoolHypnogram(pattern, zone)
            ShareBars(
                StoolLane.DRAWN.map { lane ->
                    val n = pattern.counts[lane] ?: 0
                    val types = if (lane.types.first == lane.types.last) "${lane.types.first}" else "${lane.types.first}–${lane.types.last}"
                    Triple("${lane.label} · ${types}", n.toDouble(), "$n")
                },
                pattern.typed.toDouble().coerceAtLeast(1.0),
            )
            val untyped = pattern.marks.size - pattern.typed
            if (untyped > 0) Text("$untyped logged without a type (on the timing view only).", style = FTType.Caption, color = FT.TextMuted)
            Legend(StoolLane.DRAWN.map { it.label to laneColor(it) })
        }
        Section("BY TIME OF DAY", info = "Each day's bowel movements by time of day. The shaded band is your usual window for the first one, learned from the 60 days before. Steady means this period moved no more than usual.") {
            Text(bowelTimingHeadline(timing), style = FTType.RowTitle, color = FT.TextPrimary)
            BowelTimingChart(timing)
        }
    }
}

/** Each day's need as a shaded band and its intake as a dot (green within, amber outside); a grey dot marks a training day. */
@Composable
private fun DemandRangeChart(days: List<DayHydration>) {
    val top = (days.maxOf { maxOf(it.demandHighMl, it.intakeMl ?: 0.0) } * 1.05).coerceAtLeast(1.0)
    Column(Modifier.fillMaxWidth()) {
        Text("L  0–%.1f".format(top / 1000.0), style = FTType.Micro, color = FT.TextMuted)
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val slot = size.width / days.size
            val w = (slot * 0.7f).coerceIn(3.dp.toPx(), 20.dp.toPx())
            fun y(ml: Double) = size.height - (ml / top).toFloat() * size.height
            days.forEachIndexed { i, d ->
                val cx = i * slot + slot / 2f
                drawRect(NutrientColor.Water.copy(alpha = 0.22f), Offset(cx - w / 2f, y(d.demandHighMl)), Size(w, y(d.demandLowMl) - y(d.demandHighMl)))
                d.intakeMl?.let { ml ->
                    val color = when (d.status) { HydrationStatus.Within -> FT.Emerald; else -> FT.Warning }
                    drawCircle(color, 3.5.dp.toPx(), Offset(cx, y(ml)))
                }
                if (d.workoutMinutes > 0) drawCircle(FT.TextMuted, 1.5.dp.toPx(), Offset(cx, size.height - 2.dp.toPx()))
            }
        }
        Text("${days.first().date}  →  ${days.last().date}   ·   band = needed range · dot = intake (green within, amber outside) · grey dot = training day", style = FTType.Micro, color = FT.TextMuted)
    }
}
