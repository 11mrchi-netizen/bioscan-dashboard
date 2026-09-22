package com.bioscan.fieldterminal.domain.analysis

import com.bioscan.fieldterminal.data.model.MealItemRow
import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.EvalState
import java.time.LocalDate

// DAV-180. Pure aggregation from already-fetched meal_items/hydration_daily
// rows into the shared DailyStateObject, mirroring domain/NutritionResolver.kt's
// split (pure math here, I/O in data/NutritionDailyStateRepository.kt).
//
// Every dimension here is a same-day RAW sum of already-resolved meal_items
// values -- no historical baseline is being built, so depth confidence is
// trivially 1/1 once any real value exists and 0/1 on NoData; the real
// uncertainty story for a single day lives entirely in breadth (which real
// source(s) contributed), not depth.

private const val MEALS_SOURCE = "meal_items"
private const val HYDRATION_SOURCE = "hydration_daily"
private const val AGGREGATION_VERSION = "1"

data class NutritionItemObservation(val item: MealItemRow, val loggedAt: String)

data class NutritionDailyState(
    val daily: DailyStateObject,
    val mealCount: Int,
    val estimatedItemCount: Int,
    val confirmedItemCount: Int,
    val fluidMlByBeverageClass: Map<String, Double>,
    val items: List<NutritionItemObservation>,
)

fun buildNutritionDailyState(
    date: LocalDate,
    items: List<MealItemRow>,
    mealLoggedAtById: Map<Long, String>,
    hydrationMl: Int?,
    beverageClassByFoodId: Map<Long, String?>,
): NutritionDailyState {
    fun refs(contributing: List<MealItemRow>) = contributing.mapNotNull { it.id }.map { ObservationRef("meal_items", it.toString()) }

    fun macroDimension(select: (MealItemRow) -> Double?): DimensionState {
        val contributing = items.filter { select(it) != null }
        val value = contributing.mapNotNull(select).takeIf { it.isNotEmpty() }?.sum()
        return DimensionState(
            value = value,
            evalState = if (value == null) EvalState.NoData else EvalState.Stable,
            confidence = if (value == null) Confidence(0, 1) else Confidence(1, 1),
            breadth = InputCompleteness(
                present = if (contributing.isNotEmpty()) setOf(MEALS_SOURCE) else emptySet(),
                ideal = setOf(MEALS_SOURCE),
            ),
            provenance = Provenance(origin = MEALS_SOURCE, algorithm = "sum", algorithmVersion = AGGREGATION_VERSION),
            contributingObservations = refs(contributing),
        )
    }

    val fluidItems = items.filter { it.isBeverage && it.waterMl != null }
    val fluidFromMeals = fluidItems.sumOf { it.waterMl ?: 0.0 }
    val fluidPresent = buildSet {
        if (fluidItems.isNotEmpty()) add(MEALS_SOURCE)
        if (hydrationMl != null) add(HYDRATION_SOURCE)
    }
    val totalFluid = (hydrationMl?.toDouble() ?: 0.0) + fluidFromMeals
    val fluidDimension = DimensionState(
        value = if (fluidPresent.isEmpty()) null else totalFluid,
        evalState = if (fluidPresent.isEmpty()) EvalState.NoData else EvalState.Stable,
        confidence = if (fluidPresent.isEmpty()) Confidence(0, 1) else Confidence(1, 1),
        breadth = InputCompleteness(present = fluidPresent, ideal = setOf(MEALS_SOURCE, HYDRATION_SOURCE)),
        provenance = Provenance(origin = "$MEALS_SOURCE+$HYDRATION_SOURCE", algorithm = "sum", algorithmVersion = AGGREGATION_VERSION),
        contributingObservations = refs(fluidItems) + (hydrationMl?.let { listOf(ObservationRef(HYDRATION_SOURCE, date.toString())) } ?: emptyList()),
    )

    val hydrationModelItems = items.filter { it.effectiveHydrationMl != null }
    val effectiveHydrationDimension = DimensionState(
        value = hydrationModelItems.sumOf { it.effectiveHydrationMl ?: 0.0 }.takeIf { hydrationModelItems.isNotEmpty() },
        evalState = if (hydrationModelItems.isEmpty()) EvalState.NoData else EvalState.Stable,
        confidence = if (hydrationModelItems.isEmpty()) Confidence(0, 1) else Confidence(1, 1),
        breadth = InputCompleteness(
            present = if (hydrationModelItems.isNotEmpty()) setOf(MEALS_SOURCE) else emptySet(),
            ideal = setOf(MEALS_SOURCE),
        ),
        provenance = Provenance(
            origin = MEALS_SOURCE,
            algorithm = "hydration_factor_model",
            algorithmVersion = hydrationModelItems.firstOrNull()?.hydrationModelVersion,
        ),
        contributingObservations = refs(hydrationModelItems),
    )

    val dimensions = mapOf(
        StateDimension.ENERGY_INTAKE to macroDimension { it.calories },
        StateDimension.PROTEIN_INTAKE to macroDimension { it.proteinG },
        StateDimension.CARB_INTAKE to macroDimension { it.carbsG },
        StateDimension.FAT_INTAKE to macroDimension { it.fatG },
        StateDimension.FIBER_INTAKE to macroDimension { it.fiberG },
        StateDimension.SUGAR_INTAKE to macroDimension { it.sugarG },
        StateDimension.SODIUM_INTAKE to macroDimension { it.sodiumMg },
        StateDimension.CAFFEINE_INTAKE to macroDimension { it.caffeineMg },
        StateDimension.FLUID_INTAKE to fluidDimension,
        StateDimension.EFFECTIVE_HYDRATION to effectiveHydrationDimension,
    )

    val fluidMlByBeverageClass = buildMap {
        fluidItems.groupBy { beverageClassByFoodId[it.foodId] ?: "unknown" }
            .forEach { (beverageClass, rows) -> put(beverageClass, rows.sumOf { it.waterMl ?: 0.0 }) }
        hydrationMl?.let { put("manual_log", it.toDouble()) }
    }

    return NutritionDailyState(
        daily = DailyStateObject(date, dimensions),
        mealCount = mealLoggedAtById.size,
        estimatedItemCount = items.count { it.isEstimated },
        confirmedItemCount = items.count { !it.isEstimated },
        fluidMlByBeverageClass = fluidMlByBeverageClass,
        items = items.map { NutritionItemObservation(it, mealLoggedAtById.getValue(it.mealId)) },
    )
}
