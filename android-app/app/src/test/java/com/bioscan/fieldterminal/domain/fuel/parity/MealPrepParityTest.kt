package com.bioscan.fieldterminal.domain.fuel.parity

import com.bioscan.fieldterminal.data.WeekPlan
import com.bioscan.fieldterminal.data.weekPlanSnapshot
import com.bioscan.fieldterminal.domain.fuel.BreakfastItem
import com.bioscan.fieldterminal.domain.fuel.CarbCode
import com.bioscan.fieldterminal.domain.fuel.CodedDay
import com.bioscan.fieldterminal.domain.fuel.CodedMeal
import com.bioscan.fieldterminal.domain.fuel.Container
import com.bioscan.fieldterminal.domain.fuel.MealSlotKind
import com.bioscan.fieldterminal.domain.fuel.Plate
import com.bioscan.fieldterminal.domain.fuel.PrepPicks
import com.bioscan.fieldterminal.domain.fuel.PrepSlot
import com.bioscan.fieldterminal.domain.fuel.STAPLE_FOODS
import com.bioscan.fieldterminal.domain.fuel.buildRecipes
import com.bioscan.fieldterminal.domain.fuel.cookSheet
import com.bioscan.fieldterminal.domain.fuel.planContainers
import com.bioscan.fieldterminal.domain.fuel.prepChoices
import com.bioscan.fieldterminal.domain.fuel.prepDay
import com.bioscan.fieldterminal.domain.fuel.prepText
import com.bioscan.fieldterminal.domain.fuel.shoppingList
import com.bioscan.fieldterminal.domain.fuel.slotKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

// Parity with the web's meal prep (supabase/functions/_shared/fuel-core). A week is built from the built-in staples and
// synthetic coded days, written through the same snapshot the phone publishes to fuel_week_plans (fixtures/week.json),
// and the prep for several sets of picks is recorded (fixtures/expected.json). The TypeScript test reads week.json and
// must reproduce expected.json. After a deliberate change: FT_PARITY_WRITE=1 ./gradlew testDebugUnitTest --tests '*MealPrepParityTest*'
class MealPrepParityTest {
    private val dir = File("../../supabase/functions/_shared/fuel-core/fixtures")
    private val zone = ZoneId.of("Asia/Taipei")
    private val monday = LocalDate.of(2026, 11, 23)

    private fun meal(date: LocalDate, kind: MealSlotKind, h: Int, lo: Double, hi: Double, p: Double, f: Double?, before: Boolean = false) =
        CodedMeal(kind, date.atTime(h, 0).atZone(zone), CarbCode.MEDIUM, lo, hi, p, f, "test", before)

    private fun week(): WeekPlan {
        val days = (0 until 7).map { i ->
            val d = monday.plusDays(i.toLong())
            val hard = i == 1 || i == 3
            val carbScale = if (hard) 1.4 else if (i == 4) 0.8 else 1.0
            val meals = buildList {
                add(meal(d, MealSlotKind.BREAKFAST, 8, 60 * carbScale, 80 * carbScale, 30.0, 15.0))
                add(meal(d, MealSlotKind.LUNCH, 12, 70 * carbScale, 95 * carbScale, 40.0, 18.0, before = i == 3))
                if (hard) add(meal(d, MealSlotKind.PRE_SESSION, 16, 30.0, 45.0, 5.0, 3.0))
                add(meal(d, MealSlotKind.SNACK, 15, 25.0, 35.0, 15.0, 6.0))
                add(meal(d, MealSlotKind.DINNER, 19, 80 * carbScale, 110 * carbScale, 45.0, if (i == 6) null else 22.0))
            }
            CodedDay(d, meals, emptyList())
        }
        val foods = STAPLE_FOODS.mapIndexed { i, f -> if (i % 5 == 0) f.copy(yours = true, uses = mapOf(MealSlotKind.LUNCH to i % 3)) else f }
        return WeekPlan(days, foods, 78.4)
    }

    private fun plateJson(p: Plate) = buildJsonObject {
        putJsonArray("portions") { p.portions.forEach { add(buildJsonObject { put("id", it.food.id); put("grams", it.grams); put("label", it.label) }) } }
        putJsonArray("gaps") { p.gaps.forEach { add(JsonPrimitive(it)) } }
    }

    private fun containerJson(c: Container) = buildJsonObject { put("date", c.date.toString()); put("slot", c.slot.name); put("letter", c.letter.toString()); put("plate", plateJson(c.plate)) }

    private fun run(plan: WeekPlan): JsonObject {
        val foods = plan.foods.associateBy { it.id }
        val choices = prepChoices(plan.foods)
        val breakfast = listOf(BreakfastItem("staple:oats", 70.0), BreakfastItem("staple:greek_yoghurt", 200.0), BreakfastItem("staple:banana", 118.0))
        val scenarios = listOf(
            "two_each" to (PrepPicks(listOf("staple:chicken_breast", "staple:salmon"), listOf("staple:broccoli", "staple:greens"), listOf("staple:rice_white", "staple:sweet_potato")) to setOf<String>()),
            "five_each" to (PrepPicks(listOf("staple:chicken_breast", "staple:beef_lean", "staple:salmon", "staple:tofu", "staple:shrimp"),
                listOf("staple:broccoli", "staple:greens", "staple:mixed_veg"), listOf("staple:rice_brown", "staple:pasta", "staple:potato", "staple:couscous", "staple:lentils"))
                to setOf(slotKey(monday.plusDays(2), PrepSlot.DINNER), slotKey(monday.plusDays(4), PrepSlot.LUNCH))),
            "one_three_one" to (PrepPicks(listOf("staple:turkey"), listOf("staple:mixed_veg"), listOf("staple:rice_white", "staple:noodles_rice", "staple:chickpeas")) to setOf(slotKey(monday, PrepSlot.LUNCH))),
        )
        return buildJsonObject {
            put("choices", buildJsonObject {
                put("proteins", buildJsonArray { choices.first.forEach { add(JsonPrimitive(it.id)) } })
                put("vegetables", buildJsonArray { choices.second.forEach { add(JsonPrimitive(it.id)) } })
                put("carbs", buildJsonArray { choices.third.forEach { add(JsonPrimitive(it.id)) } })
            })
            for ((name, s) in scenarios) {
                val (picks, skipped) = s
                val recipes = buildRecipes(picks)
                val containers = planContainers(monday, recipes, skipped, foods) { d, slot ->
                    plan.days.firstOrNull { it.date == d }?.meals?.firstOrNull { it.kind == slot.kind }
                }
                val days = plan.days.take(5).map { d ->
                    prepDay(d, breakfast, containers, PrepSlot.entries.filter { slotKey(d.date, it) in skipped }.toSet(), foods)
                }
                val shopping = shoppingList(containers.map { it.plate } + days.mapNotNull { it.breakfast })
                put(name, buildJsonObject {
                    put("recipes", buildJsonArray { recipes.forEach { r -> add(buildJsonObject { put("letter", r.letter.toString()); put("slot", r.slot.name); put("proteins", JsonArray(r.proteins.map(::JsonPrimitive))); put("carbs", JsonArray(r.carbs.map(::JsonPrimitive))); put("vegetables", JsonArray(r.vegetables.map(::JsonPrimitive))) }) } })
                    put("containers", buildJsonArray { containers.forEach { add(containerJson(it)) } })
                    put("days", buildJsonArray {
                        days.forEach { d ->
                            add(buildJsonObject {
                                put("date", d.date.toString())
                                d.breakfast?.let { put("breakfast", plateJson(it)) }
                                put("snack", buildJsonObject { put("carbG", d.snack.carbG); put("proteinG", d.snack.proteinG); d.snack.fatG?.let { put("fatG", it) } })
                                putJsonArray("notes") { d.notes.forEach { add(JsonPrimitive(it)) } }
                            })
                        }
                    })
                    put("shopping", buildJsonArray { shopping.forEach { add(buildJsonObject { put("id", it.food.id); put("eatenG", it.eatenG); put("rawG", it.rawG); put("label", it.label) }) } })
                    put("cook", buildJsonArray {
                        cookSheet(recipes, containers).forEach { c -> add(buildJsonObject { put("letter", c.recipe.letter.toString()); put("id", c.food.id); put("rawG", c.rawG); put("cookedG", c.cookedG); put("split", JsonArray(c.split.map { JsonPrimitive(it.second) })) }) }
                    })
                    put("text", prepText(monday, recipes, containers, shopping, foods))
                })
            }
        }
    }

    @Test
    fun theWebPrepsTheSameWeek() {
        val plan = week()
        val snapshot = weekPlanSnapshot(plan, zone)
        val actual = run(plan)
        val json = Json { prettyPrint = false }
        val weekFile = File(dir, "week.json")
        val expectedFile = File(dir, "expected.json")
        if (System.getenv("FT_PARITY_WRITE") != null || !expectedFile.exists()) {
            dir.mkdirs()
            weekFile.writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("week_start", monday.toString()); put("plan", snapshot) }) + "\n")
            expectedFile.writeText(json.encodeToString(JsonObject.serializer(), actual) + "\n")
            return
        }
        assertEquals(Json.parseToJsonElement(expectedFile.readText()), actual)
        assertEquals(Json.parseToJsonElement(weekFile.readText()).let { (it as JsonObject)["plan"] }, snapshot)
    }
}
