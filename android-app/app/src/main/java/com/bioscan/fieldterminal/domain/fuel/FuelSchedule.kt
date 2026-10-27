package com.bioscan.fieldterminal.domain.fuel

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

// The pre-race fuel schedule and its post-race debrief (docs/race-fuel-rework). A schedule is a list of BLOCKS: one product
// (a gel, a drink mix, plain water, water with electrolytes, a pre-workout...), the nutrients in one intake of it, when the
// first intake is, how often, and how many. Blocks expand into dated intakes; the debrief says how many of each block were
// taken and turns that into one fueling_intake_log row per intake (the single-counting ledger every other fuel entry uses).
// Pure, so the arithmetic is unit-tested.

enum class FuelForm(val key: String, val label: String) {
    GEL("gel", "GEL"), DRINK_MIX("drink_mix", "DRINK MIX"), CHEW("chew", "CHEWS"), BAR("bar", "BAR"),
    WATER("water", "WATER"), ELECTROLYTE_DRINK("electrolyte_drink", "WATER + ELECTROLYTES"),
    PREWORKOUT("preworkout", "PRE-WORKOUT"), TABLET("tablet", "TABLET"), OTHER("other", "OTHER");

    companion object {
        fun of(key: String): FuelForm = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * One line of the schedule. The nutrient values are for ONE intake. [startMin] is minutes from the start (negative: before
 * it, a pre-workout at -30); [count] intakes follow every [intervalMin] minutes from there (an interval of 0 only makes
 * sense for a count of 1).
 */
@Serializable
data class FuelBlock(
    val id: String,
    val label: String,
    val form: String,
    val startMin: Int,
    val intervalMin: Int,
    val count: Int,
    val carbsG: Double = 0.0,
    val fluidMl: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val caffeineMg: Double = 0.0,
    /** The catalog entry the nutrients came from: a fueling product, or a supplement product (a pre-workout, an electrolyte tablet). */
    val fuelingProductId: Long? = null,
    val supplementProductId: Long? = null,
) {
    val fuelForm: FuelForm get() = FuelForm.of(form)
    val endMin: Int get() = startMin + intervalMin * max(0, count - 1)
}

data class ScheduledIntake(val blockId: String, val index: Int, val elapsedMin: Int)

/** Every intake of every block, in time order (ties keep the blocks' order). */
fun expandSchedule(blocks: List<FuelBlock>): List<ScheduledIntake> =
    blocks.flatMapIndexed { b, block -> (0 until block.count).map { i -> Triple(b, i, block.startMin + i * block.intervalMin) } }
        .sortedWith(compareBy({ it.third }, { it.first }, { it.second }))
        .map { (b, i, t) -> ScheduledIntake(blocks[b].id, i, t) }

data class ScheduleTotals(
    val carbsG: Double,
    val fluidMl: Double,
    val sodiumMg: Double,
    val caffeineMg: Double,
    val intakes: Int,
    /** Carbohydrate before the start; in the totals but not in the per-hour rate. */
    val preStartCarbsG: Double,
    val carbsPerHour: Double?,
    val fluidPerHour: Double?,
    val sodiumPerHour: Double?,
)

/** What the schedule adds up to over a run of [durationMin]; rates count only intakes from the start on. */
fun scheduleTotals(blocks: List<FuelBlock>, durationMin: Int): ScheduleTotals {
    val byId = blocks.associateBy { it.id }
    val all = expandSchedule(blocks).map { byId.getValue(it.blockId) to it.elapsedMin }
    fun sum(inRunOnly: Boolean = false, pick: (FuelBlock) -> Double) = all.filter { !inRunOnly || it.second >= 0 }.sumOf { pick(it.first) }
    val hours = durationMin / 60.0
    fun rate(v: Double) = if (durationMin > 0) v / hours else null
    return ScheduleTotals(
        carbsG = sum { it.carbsG }, fluidMl = sum { it.fluidMl }, sodiumMg = sum { it.sodiumMg }, caffeineMg = sum { it.caffeineMg },
        intakes = all.size, preStartCarbsG = all.filter { it.second < 0 }.sumOf { it.first.carbsG },
        carbsPerHour = rate(sum(true) { it.carbsG }), fluidPerHour = rate(sum(true) { it.fluidMl }), sodiumPerHour = rate(sum(true) { it.sodiumMg }),
    )
}

/** Why a schedule deserves a second look before the start; empty when nothing does. */
fun scheduleWarnings(blocks: List<FuelBlock>, durationMin: Int, toleratedGPerHour: Double): List<String> = buildList {
    val t = scheduleTotals(blocks, durationMin)
    t.carbsPerHour?.let { if (it > toleratedGPerHour * 1.1) add("%.0f g/h of carbohydrate is above the %.0f g/h you tolerate.".format(it, toleratedGPerHour)) }
    blocks.forEach { b ->
        if (b.count > 1 && b.intervalMin <= 0) add("${b.label}: set how often (minutes between intakes).")
        if (b.endMin > durationMin) add("${b.label}: the last intake is at ${b.endMin} min, after the planned finish.")
    }
    if (t.caffeineMg > 400) add("%.0f mg of caffeine is above the 400 mg a day usually kept to.".format(t.caffeineMg))
}

// ---- Seeding from the planner ---------------------------------------------------------------------------------------

private fun roundTo5(v: Double) = max(5, (v / 5).roundToInt() * 5)

/**
 * The planner's plan as editable blocks: one block per product (its servings spread between its first and last step), the
 * rest of the fluid as water or, where the plan asks for sodium the products do not bring, water with electrolytes, and each
 * caffeine dose as its own block. Nutrient totals match the plan's (carbohydrate, fluid, sodium, caffeine) up to the planner's
 * own rounding. Built from the structured parts of each step, never from its display strings.
 */
fun seedBlocks(plan: RaceFuelPlan): List<FuelBlock> {
    val blocks = mutableListOf<FuelBlock>()
    val steps = plan.steps
    val byProduct = steps.flatMap { s -> s.parts.map { s.elapsedMin to it } }.groupBy { it.second.product.name }
    var drinkFluid = 0.0
    var productSodium = 0.0
    byProduct.values.forEachIndexed { n, uses ->
        val p = uses.first().second.product
        val servings = uses.sumOf { it.second.count }
        val first = uses.first().first
        val last = uses.last().first
        val interval = if (servings > 1) roundTo5((last - first) / (servings - 1)) else 0
        blocks += FuelBlock(
            id = "seed-p$n", label = p.name, form = p.kind, startMin = first.roundToInt(), intervalMin = interval, count = servings,
            carbsG = p.carbsG, fluidMl = p.fluidMl ?: 0.0, sodiumMg = p.sodiumMg ?: 0.0, caffeineMg = p.caffeineMg ?: 0.0, fuelingProductId = p.id,
        )
        drinkFluid += servings * (p.fluidMl ?: 0.0)
        productSodium += servings * (p.sodiumMg ?: 0.0)
    }
    val drinkSteps = steps.filter { it.fluidMl > 0 }
    val waterMl = max(0.0, plan.totalFluidMl - drinkFluid)
    val sodiumNeeded = max(0.0, plan.totalSodiumMg - productSodium)
    if (drinkSteps.isNotEmpty() && waterMl > 0) {
        val n = drinkSteps.size
        val withSalt = sodiumNeeded >= 100
        blocks += FuelBlock(
            id = "seed-water", label = if (withSalt) "Water with electrolytes" else "Water", form = (if (withSalt) FuelForm.ELECTROLYTE_DRINK else FuelForm.WATER).key,
            startMin = drinkSteps.first().elapsedMin.roundToInt(), intervalMin = if (n > 1) STEP_MIN.roundToInt() else 0, count = n,
            fluidMl = waterMl / n, sodiumMg = if (withSalt) sodiumNeeded / n else 0.0,
        )
    }
    steps.filter { it.caffeineMg > 0 }.forEachIndexed { i, s ->
        blocks += FuelBlock(id = "seed-caf$i", label = "Caffeine", form = FuelForm.TABLET.key, startMin = s.elapsedMin.roundToInt(), intervalMin = 0, count = 1, caffeineMg = s.caffeineMg)
    }
    return blocks.sortedBy { it.startMin }
}

// ---- Debrief ----------------------------------------------------------------------------------------------------------

/**
 * What was taken of one block: [taken] intakes (null = as planned) of [amountScale] times the planned amount each (0.5: half a
 * gel). More than planned adds intakes after the last planned one, at the same interval.
 */
data class BlockActual(val blockId: String, val taken: Int? = null, val amountScale: Double = 1.0)

/** One fueling_intake_log row to write: status consumed or skipped, never planned (the schedule is the plan). */
data class DebriefRow(
    val clientKey: String,
    val description: String,
    val elapsedSeconds: Int,
    val status: String,
    val quantity: Double,
    val carbsG: Double?,
    val fluidMl: Double?,
    val sodiumMg: Double?,
    val caffeineMg: Double?,
    val blockId: String,
)

/** The key prefix every debrief row of one activity carries, so a re-save can find (and remove) rows it no longer produces. */
fun debriefKeyPrefix(sessionId: Long) = "debrief:$sessionId:"

/**
 * Rows for a debrief: for each block the first `taken` planned intakes are consumed (scaled), the remaining planned ones
 * skipped, and extra intakes beyond the plan consumed after the last. A key is `debrief:<session>:<block>:<n>`, so saving
 * the same debrief again updates the same rows. A block taken zero times is all skipped.
 */
fun debriefRows(sessionId: Long, blocks: List<FuelBlock>, actuals: List<BlockActual>): List<DebriefRow> {
    val actualById = actuals.associateBy { it.blockId }
    return blocks.flatMap { b ->
        val a = actualById[b.id]
        val taken = (a?.taken ?: b.count).coerceAtLeast(0)
        val scale = (a?.amountScale ?: 1.0).coerceAtLeast(0.0)
        val rows = max(b.count, taken)
        (0 until rows).map { i ->
            val consumed = i < taken
            val amount = if (consumed) scale else 1.0
            DebriefRow(
                clientKey = debriefKeyPrefix(sessionId) + "${b.id}:$i",
                description = b.label,
                elapsedSeconds = (b.startMin + i * b.intervalMin) * 60,
                status = if (consumed) "consumed" else "skipped",
                quantity = amount,
                carbsG = b.carbsG * amount, fluidMl = b.fluidMl * amount, sodiumMg = b.sodiumMg * amount, caffeineMg = b.caffeineMg * amount,
                blockId = b.id,
            )
        }
    }
}

enum class ScheduleVerdict(val label: String) { HELD("HELD"), PARTLY("PARTLY HELD"), NOT_HELD("NOT HELD") }

data class BlockAdherence(val blockId: String, val label: String, val planned: Int, val taken: Int, val share: Double)

data class ScheduleAdherence(
    /** Carbohydrate taken from the PLANNED intakes over the carbohydrate planned, 0..1; extras do not count. Null when no carbohydrate was planned. */
    val carbShare: Double?,
    val verdict: ScheduleVerdict?,
    val plannedCarbsG: Double,
    val takenCarbsG: Double,
    val extraCarbsG: Double,
    val takenFluidMl: Double,
    val takenSodiumMg: Double,
    val takenCaffeineMg: Double,
    /** Blocks where less than 90 % of the planned intakes were taken, worst first. */
    val slipped: List<BlockAdherence>,
)

const val HELD_SHARE = 0.9
const val PARTLY_SHARE = 0.6

fun scheduleAdherence(blocks: List<FuelBlock>, actuals: List<BlockActual>): ScheduleAdherence {
    val actualById = actuals.associateBy { it.blockId }
    var plannedCarbs = 0.0
    var takenPlannedCarbs = 0.0
    var extraCarbs = 0.0
    var fluid = 0.0
    var sodium = 0.0
    var caffeine = 0.0
    val adherence = blocks.map { b ->
        val a = actualById[b.id]
        val taken = (a?.taken ?: b.count).coerceAtLeast(0)
        val scale = (a?.amountScale ?: 1.0).coerceAtLeast(0.0)
        val inPlan = minOf(taken, b.count)
        plannedCarbs += b.carbsG * b.count
        takenPlannedCarbs += b.carbsG * inPlan * scale
        extraCarbs += b.carbsG * (taken - inPlan) * scale
        fluid += b.fluidMl * taken * scale
        sodium += b.sodiumMg * taken * scale
        caffeine += b.caffeineMg * taken * scale
        BlockAdherence(b.id, b.label, b.count, taken, if (b.count > 0) inPlan * scale / b.count else 1.0)
    }
    val share = if (plannedCarbs > 0) (takenPlannedCarbs / plannedCarbs).coerceIn(0.0, 1.0) else null
    return ScheduleAdherence(
        carbShare = share,
        verdict = share?.let { if (it >= HELD_SHARE) ScheduleVerdict.HELD else if (it >= PARTLY_SHARE) ScheduleVerdict.PARTLY else ScheduleVerdict.NOT_HELD },
        plannedCarbsG = plannedCarbs, takenCarbsG = takenPlannedCarbs + extraCarbs, extraCarbsG = extraCarbs,
        takenFluidMl = fluid, takenSodiumMg = sodium, takenCaffeineMg = caffeine,
        slipped = adherence.filter { it.planned > 0 && it.share < HELD_SHARE }.sortedBy { it.share },
    )
}

/** Discomfort a debrief can tick (stored as these keys in fuel_plan_reviews.symptoms). */
enum class FuelSymptom(val key: String, val label: String) {
    NAUSEA("nausea", "Nausea"), CRAMPS("cramps", "Stomach cramps"), BLOATING("bloating", "Bloating"), REFLUX("reflux", "Reflux"),
    SIDE_STITCH("side_stitch", "Side stitch"), VOMITING("vomiting", "Vomiting"), DIZZINESS("dizziness", "Dizziness");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}
