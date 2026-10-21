package com.bioscan.fieldterminal.domain.fuel

import kotlin.math.roundToInt

// A race or long-session plan as plain text: to share with a crew, print for a drop bag, or read without signal (the
// event page keeps the last one on the phone).

private fun hm(m: Double): String {
    val t = m.roundToInt()
    return "%d:%02d".format(t / 60, t % 60)
}

fun raceFuelPlanText(plan: RaceFuelPlan, startLabel: String): String = buildString {
    appendLine(plan.title.uppercase())
    appendLine("Start $startLabel · ${hm(plan.durationMin)} planned (${hm(plan.durationLowMin)}-${hm(plan.durationHighMin)})")
    appendLine(
        "Per hour: ${plan.carbsPerHourLow.roundToInt()}-${plan.carbsPerHourHigh.roundToInt()} g carbohydrate · " +
            "${plan.fluidMlPerHourLow.roundToInt()}-${plan.fluidMlPerHourHigh.roundToInt()} ml fluid · ${plan.sodiumMgPerL.roundToInt()} mg sodium per litre",
    )
    plan.heat?.lines?.forEach { appendLine(it) }
    if (plan.warnings.isNotEmpty()) {
        appendLine()
        appendLine("WATCH")
        plan.warnings.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("BEFORE")
    plan.before.forEach { appendLine("${it.whenLabel}: ${it.text}") }
    appendLine()
    appendLine("DURING")
    plan.steps.forEach { s ->
        val where = s.km?.let { " · km %.1f".format(it) }.orEmpty()
        val station = s.aidStation?.let { " · ${it.name}" }.orEmpty()
        appendLine("${hm(s.elapsedMin)}$where$station: ${s.items.joinToString(", ").ifBlank { "-" }}")
    }
    if (plan.legs.size > 1 || plan.legs.firstOrNull()?.items?.isNotEmpty() == true) {
        appendLine()
        appendLine("CARRY")
        plan.legs.forEach { leg ->
            val items = leg.items.entries.joinToString(", ") { "${it.value} × ${it.key}" }.ifBlank { "nothing to eat" }
            appendLine("${leg.from} → ${leg.to} (${hm(leg.minutes)}): $items; ${leg.bottlesMl} ml to drink${leg.note?.let { ". $it" }.orEmpty()}")
        }
    }
    appendLine()
    appendLine("AFTER")
    plan.after.forEach { appendLine("${it.whenLabel}: ${it.text}") }
    appendLine()
    appendLine("Totals: ${plan.totalCarbsG.roundToInt()} g carbohydrate, ${(plan.totalFluidMl / 100).roundToInt() / 10.0} L fluid, ${plan.totalSodiumMg.roundToInt()} mg sodium" +
        if (plan.totalCaffeineMg > 0) ", ${plan.totalCaffeineMg.roundToInt()} mg caffeine." else ".")
    appendLine()
    appendLine("Assumptions")
    plan.assumptions.forEach { appendLine("- $it") }
}
