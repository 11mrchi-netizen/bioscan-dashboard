package com.bioscan.fieldterminal.ui.screens.fuel

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.MealPrepRepository
import com.bioscan.fieldterminal.data.MealPrepWeek
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.WeekPlan
import com.bioscan.fieldterminal.domain.fuel.BreakfastItem
import com.bioscan.fieldterminal.domain.fuel.Container
import com.bioscan.fieldterminal.domain.fuel.FoodOption
import com.bioscan.fieldterminal.domain.fuel.MAX_PICKS_PER_GROUP
import com.bioscan.fieldterminal.domain.fuel.MealSlotKind
import com.bioscan.fieldterminal.domain.fuel.PREP_DAYS
import com.bioscan.fieldterminal.domain.fuel.PrepPicks
import com.bioscan.fieldterminal.domain.fuel.PrepSlot
import com.bioscan.fieldterminal.domain.fuel.Recipe
import com.bioscan.fieldterminal.domain.fuel.StoredContainer
import com.bioscan.fieldterminal.domain.fuel.Taste
import com.bioscan.fieldterminal.domain.fuel.buildRecipes
import com.bioscan.fieldterminal.domain.fuel.containerFromStored
import com.bioscan.fieldterminal.domain.fuel.cookSheet
import com.bioscan.fieldterminal.domain.fuel.currentPrepWeek
import com.bioscan.fieldterminal.domain.fuel.letterFor
import com.bioscan.fieldterminal.domain.fuel.planContainers
import com.bioscan.fieldterminal.domain.fuel.plateTarget
import com.bioscan.fieldterminal.domain.fuel.prepChoices
import com.bioscan.fieldterminal.domain.fuel.prepText
import com.bioscan.fieldterminal.domain.fuel.recipeName
import com.bioscan.fieldterminal.domain.fuel.scaleBreakfast
import com.bioscan.fieldterminal.domain.fuel.shoppingList
import com.bioscan.fieldterminal.domain.fuel.slotKey
import com.bioscan.fieldterminal.domain.fuel.upcomingPrepWeek
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Fuel › Prep (fuel planning phase 11): Sunday meal prep in four steps (PICK, RECIPES, WEEK, SHOP & COOK) and the fixed
// breakfast. The numbers come from domain/fuel/MealPrep.kt, portioned for each day's plan; only the choices and, once
// cooked, the containers' grams are stored (MealPrepRepository).

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
fun MealPrepPage(overview: NutritionOverview?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { MealPrepRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    val thisWeek = remember { currentPrepWeek(today) }
    val nextWeek = remember { upcomingPrepWeek(today) }
    // From Monday to Friday the week being eaten is the one shown first; on the weekend, the coming one.
    var weekStart by remember { mutableStateOf(if (today.dayOfWeek.value <= PREP_DAYS) thisWeek else nextWeek) }
    var plan by remember { mutableStateOf<WeekPlan?>(null) }
    var week by remember { mutableStateOf<MealPrepWeek?>(null) }
    var breakfast by remember { mutableStateOf<List<BreakfastItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(weekStart, attempt, overview) {
        error = null
        loaded = false
        try {
            plan = FuelPlanRepository(SupabaseClientProvider.client).loadWeekPlan(context, overview, weekStart)
            week = runCatching { repo.loadWeek(weekStart) }.getOrNull()
            breakfast = runCatching { repo.loadBreakfast() }.getOrDefault(emptyList())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
        loaded = true
    }

    fun save(w: MealPrepWeek) {
        week = w
        scope.launchSafe(onError = { error = describeLoadFailure(it) }) { repo.saveWeek(w) }
    }

    Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SegmentedToggle(
            listOf(thisWeek, nextWeek).distinct(), weekStart,
            { if (it == thisWeek) "THIS WEEK" else "NEXT WEEK" }, { weekStart = it }, accentColor = FT.DomainFuel,
        )
        error?.let {
            Text("Couldn't load or save: $it", style = FTType.BodySmall, color = FT.Critical)
            FTButton("RETRY", emphasis = FTButtonEmphasis.Tertiary) { attempt++ }
        }
        val p = plan
        if (!loaded) Text("Planning the week…", style = FTType.BodySmall, color = FT.TextSecondary)
        if (loaded && p == null && error == null) Text("Log a body weight (Fuel › Body) to portion the week.", style = FTType.BodySmall, color = FT.TextSecondary)
        if (p == null) return@Column
        val foods = remember(p) { p.foods.associateBy { it.id } }
        val w = week ?: MealPrepWeek(weekStart, PrepPicks(emptyList(), emptyList(), emptyList()), emptyList(), emptySet(), null)

        PickCard(p.foods, w.picks, enabled = !w.cooked) { picks -> save(w.copy(picks = picks, recipes = if (picks.ready) buildRecipes(picks) else emptyList())) }
        if (w.recipes.isNotEmpty()) {
            RecipesCard(w, foods, enabled = !w.cooked) { save(w.copy(recipes = it)) }
            WeekCard(w, foods, enabled = !w.cooked) { save(w.copy(skipped = it)) }
            val containers = remember(w, p) { containersFor(w, p, foods) }
            ShopCookCard(context, w, containers, breakfastPlates(breakfast, p, foods), foods, onCooked = { cooked ->
                save(w.copy(containers = if (cooked) containers.map { c -> StoredContainer(c.date, c.slot, c.letter, c.plate.portions.associate { it.food.id to it.grams }) } else null))
            })
        }
        BreakfastCard(p.foods, breakfast, onSave = { items ->
            breakfast = items
            scope.launchSafe(onError = { error = describeLoadFailure(it) }) { repo.saveBreakfast(items) }
        })
    }
}

private fun mealFor(p: WeekPlan, date: LocalDate, slot: PrepSlot) = p.days.firstOrNull { it.date == date }?.meals?.firstOrNull { it.kind == slot.kind }

private fun containersFor(w: MealPrepWeek, p: WeekPlan, foods: Map<String, FoodOption>): List<Container> {
    val stored = w.containers
    return if (stored != null) {
        stored.mapNotNull { s -> mealFor(p, s.date, s.slot)?.let { containerFromStored(s, foods, it.plateTarget()) } }
    } else {
        planContainers(w.weekStart, w.recipes, w.skipped, foods) { d, s -> mealFor(p, d, s) }
    }
}

private fun breakfastPlates(template: List<BreakfastItem>, p: WeekPlan, foods: Map<String, FoodOption>) =
    if (template.isEmpty()) emptyList() else p.days.mapNotNull { d ->
        d.meals.firstOrNull { it.kind == MealSlotKind.BREAKFAST }?.let { scaleBreakfast(template, it.plateTarget(), foods) }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<FoodOption>, selected: List<String>, enabled: Boolean, max: Int, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { f ->
            val on = f.id in selected
            val shape = RoundedCornerShape(FT.RadiusSmall)
            val canAdd = on || selected.size < max
            Box(
                Modifier
                    .background(if (on) FT.DomainFuel.copy(alpha = 0.22f) else Color.Transparent, shape)
                    .border(FT.BorderWidth, if (on) FT.DomainFuel else FT.GlassBorder, shape)
                    .ftClickable(enabled = enabled && canAdd, shape = shape) { onToggle(f.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    f.name.uppercase() + if (f.usual) " ·" else "",
                    style = FTType.LabelCaps, color = if (canAdd || on) FT.TextPrimary else FT.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun PickCard(all: List<FoodOption>, picks: PrepPicks, enabled: Boolean, onChange: (PrepPicks) -> Unit) {
    val (proteins, vegetables, carbs) = remember(all) { prepChoices(all) }
    fun toggle(list: List<String>, id: String) = if (id in list) list - id else list + id
    FTCard(
        title = "1 · PICK",
        emphasis = FTCardEmphasis.Hero,
        info = "Up to five proteins, vegetables and carbohydrates for the week's lunches and dinners. Foods marked · are ones " +
            "your meals already name. Four recipes are built from them: A and B for lunch, C and D for dinner.",
    ) {
        Text("PROTEINS · ${picks.proteins.size}/$MAX_PICKS_PER_GROUP", style = FTType.LabelCaps, color = FT.TextSecondary)
        Chips(proteins, picks.proteins, enabled, MAX_PICKS_PER_GROUP) { onChange(picks.copy(proteins = toggle(picks.proteins, it))) }
        Text("VEGETABLES · ${picks.vegetables.size}/$MAX_PICKS_PER_GROUP", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        Chips(vegetables, picks.vegetables, enabled, MAX_PICKS_PER_GROUP) { onChange(picks.copy(vegetables = toggle(picks.vegetables, it))) }
        Text("CARBS · ${picks.carbs.size}/$MAX_PICKS_PER_GROUP", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        Chips(carbs, picks.carbs, enabled, MAX_PICKS_PER_GROUP) { onChange(picks.copy(carbs = toggle(picks.carbs, it))) }
        if (!picks.ready) Text("Pick at least one of each.", style = FTType.Caption, color = FT.TextSecondary)
    }
}

/** The next pick in [pool] after [current], for tapping a recipe's food to swap it. */
private fun nextOf(pool: List<String>, current: List<String>): List<String> {
    if (pool.isEmpty()) return current
    val i = pool.indexOf(current.firstOrNull())
    return listOf(pool[(i + 1).mod(pool.size)]) + current.drop(1)
}

@Composable
private fun RecipesCard(w: MealPrepWeek, foods: Map<String, FoodOption>, enabled: Boolean, onChange: (List<Recipe>) -> Unit) {
    FTCard(title = "2 · RECIPES", info = "Tap a food to swap it for the next one you picked. Oil is added where the day's fat target needs it.") {
        w.recipes.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${r.letter} · ${r.slot.label}", style = FTType.LabelCaps, color = FT.DomainFuel)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple(r.proteins, w.picks.proteins) { l: List<String> -> r.copy(proteins = l) },
                        Triple(r.carbs, w.picks.carbs) { l: List<String> -> r.copy(carbs = l) },
                        Triple(r.vegetables, w.picks.vegetables) { l: List<String> -> r.copy(vegetables = l) },
                    ).forEach { (current, pool, with) ->
                        FTButton(
                            current.mapNotNull { foods[it]?.name }.joinToString(" + ").uppercase().ifBlank { "—" },
                            emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f), enabled = enabled,
                        ) {
                            onChange(w.recipes.map { if (it.letter == r.letter) with(nextOf(pool, current)) else it })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekCard(w: MealPrepWeek, foods: Map<String, FoodOption>, enabled: Boolean, onChange: (Set<String>) -> Unit) {
    FTCard(title = "3 · WEEK", info = "Lunch A-B-A-B-A and dinner C-D-C-D-C. Tap a meal you'll eat out: it drops out of the containers and the shopping list.") {
        (0 until PREP_DAYS).forEach { d ->
            val date = w.weekStart.plusDays(d.toLong())
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(date.format(DAY).uppercase(), style = FTType.MonoCaption, color = FT.DomainFuel, modifier = Modifier.width(92.dp))
                PrepSlot.entries.forEach { slot ->
                    val key = slotKey(date, slot)
                    val out = key in w.skipped
                    val letter = letterFor(w.weekStart, date, slot)
                    FTButton(
                        "${slot.label} ${if (out) "OUT" else letter}",
                        emphasis = if (out) FTButtonEmphasis.Tertiary else FTButtonEmphasis.Secondary,
                        modifier = Modifier.weight(1f), enabled = enabled,
                    ) { onChange(if (out) w.skipped - key else w.skipped + key) }
                }
            }
        }
        w.recipes.forEach { r -> Text("${r.letter}: ${recipeName(r, foods)}", style = FTType.Caption, color = FT.TextSecondary) }
    }
}

@Composable
private fun ShopCookCard(
    context: Context,
    w: MealPrepWeek,
    containers: List<Container>,
    breakfasts: List<com.bioscan.fieldterminal.domain.fuel.Plate>,
    foods: Map<String, FoodOption>,
    onCooked: (Boolean) -> Unit,
) {
    var confirmUndo by remember { mutableStateOf(false) }
    val shopping = remember(containers, breakfasts) { shoppingList(containers.map { it.plate } + breakfasts) }
    val sheet = remember(w, containers) { cookSheet(w.recipes, containers) }
    FTCard(
        title = "4 · SHOP & COOK",
        info = "What to buy for the containers and a week of your breakfast, in raw weight (USDA cooking yields), rounded up. " +
            "MARK AS COOKED fixes the containers: if training changes, breakfast and the snack take up the difference.",
    ) {
        Text(if (w.cooked) "COOKED · CONTAINERS FIXED" else "PLANNED", style = FTType.LabelCaps, color = FT.DomainFuel)
        Text("SHOPPING${if (breakfasts.isEmpty()) "" else " (WITH ${breakfasts.size} BREAKFASTS)"}", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        shopping.forEach { Text("· ${it.label}", style = FTType.BodySmall, color = FT.TextPrimary) }
        Text("COOK", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        sheet.forEach { s ->
            val raw = if (s.rawG.roundToInt() != s.cookedG.roundToInt()) "${s.rawG.roundToInt()} g raw/dry → " else ""
            Text(
                "${s.recipe.letter} · ${s.food.name}: $raw${s.cookedG.roundToInt()} g, split ${s.split.joinToString(" / ") { it.second.roundToInt().toString() }}",
                style = FTType.Caption, color = FT.TextSecondary,
            )
        }
        Text("CONTAINERS", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        containers.forEach { c ->
            Column(Modifier.padding(vertical = 2.dp)) {
                Text("${c.date.format(DAY).uppercase()} · ${c.slot.label} (${c.letter})", style = FTType.MonoCaption, color = FT.DomainFuel)
                Text(c.plate.label, style = FTType.BodySmall, color = FT.TextPrimary)
                Text(
                    "C ${c.plate.carbG.roundToInt()} · P ${c.plate.proteinG.roundToInt()} · F ${c.plate.fatG.roundToInt()} G against " +
                        "C ${c.target.carbMidG.roundToInt()} · P ${c.target.proteinG.roundToInt()}" + (c.target.fatG?.let { " · F ${it.roundToInt()}" } ?: ""),
                    style = FTType.MonoCaption, color = FT.TextSecondary,
                )
            }
        }
        if (confirmUndo) {
            FTConfirmDestructive(
                message = "Work the containers out again from the current plan instead of what you cooked?",
                confirmLabel = "RE-PORTION",
                onCancel = { confirmUndo = false },
                onConfirm = { confirmUndo = false; onCooked(false) },
            )
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            FTButton("SHARE", emphasis = FTButtonEmphasis.Secondary, modifier = Modifier.weight(1f)) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prepText(w.weekStart, w.recipes, containers, shopping, foods))
                runCatching { context.startActivity(Intent.createChooser(send, "Share the prep list")) }
            }
            if (w.cooked) {
                FTButton("NOT COOKED YET", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f)) { confirmUndo = true }
            } else {
                FTButton("MARK AS COOKED", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) { onCooked(true) }
            }
        }
    }
}

@Composable
private fun BreakfastCard(all: List<FoodOption>, saved: List<BreakfastItem>, onSave: (List<BreakfastItem>) -> Unit) {
    var items by remember(saved) { mutableStateOf(saved) }
    val foods = remember(all) { all.associateBy { it.id } }
    val choices = remember(all) {
        all.filter { MealSlotKind.BREAKFAST in it.slots && it.taste != Taste.SAVOURY }
            .sortedWith(compareByDescending<FoodOption> { it.usesIn(MealSlotKind.BREAKFAST) * 10 + it.totalUses }.thenBy { it.id })
    }
    FTCard(
        title = "BREAKFAST",
        info = "Your usual breakfast, the same foods every day. Each day the amounts move between half and 1.75 times these to fit " +
            "the day's plan, and a week of it goes on the shopping list.",
    ) {
        if (items.isEmpty()) Text("Pick the foods of your usual breakfast below.", style = FTType.BodySmall, color = FT.TextSecondary)
        items.forEach { i ->
            val f = foods[i.foodId] ?: return@forEach
            val step = f.serving?.let { it.grams * it.step } ?: f.stepG
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(com.bioscan.fieldterminal.domain.fuel.Portion(f, i.grams).label, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                FTButton("−", emphasis = FTButtonEmphasis.Tertiary) {
                    items = items.map { if (it.foodId == i.foodId) it.copy(grams = (it.grams - step).coerceAtLeast(step)) else it }
                }
                FTButton("+", emphasis = FTButtonEmphasis.Tertiary) {
                    items = items.map { if (it.foodId == i.foodId) it.copy(grams = it.grams + step) else it }
                }
                FTButton("×", emphasis = FTButtonEmphasis.Tertiary) { items = items.filter { it.foodId != i.foodId } }
            }
        }
        Text("ADD", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        Chips(choices.filter { c -> items.none { it.foodId == c.id } }.take(16), emptyList(), true, 8) { id ->
            foods[id]?.let { f -> if (items.size < 8) items = items + BreakfastItem(id, f.typicalG) }
        }
        FTButton("SAVE BREAKFAST", emphasis = FTButtonEmphasis.Primary, enabled = items.isNotEmpty() && items != saved) { onSave(items) }
    }
}
