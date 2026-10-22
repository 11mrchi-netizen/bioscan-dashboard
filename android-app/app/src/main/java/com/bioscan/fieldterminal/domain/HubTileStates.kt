package com.bioscan.fieldterminal.domain

import java.time.LocalTime

// The opening page's four tiles show one coloured dot each, and the dot is the tile's only indicator: how things are
// going inside that tile today. Each rule below reuses a state the tile's own pages already compute where one exists;
// the Nutrition pace and the Lab supplement rules are product-defined.

// Severity, worst first: what decides a dot made of several parts. Building and Unavailable never outrank a real state.
fun MetricState.severity(): Int = when (this) {
    MetricState.Critical -> 4
    MetricState.Warning -> 3
    MetricState.Neutral -> 2
    MetricState.Optimal -> 1
    MetricState.Building -> 0
    MetricState.Unavailable -> -1
}

fun worstOf(a: MetricState, b: MetricState): MetricState = if (a.severity() >= b.severity()) a else b

// HEALTH: the worst of HRV/RHR (cardio), sleep (recovery) and this morning's HRV against its expected band. A part that
// is still building or missing counts only when every part is.
fun healthHubState(vararg parts: MetricState?): MetricState =
    parts.filterNotNull().reduceOrNull(::worstOf) ?: MetricState.Unavailable

// NUTRITION: what is logged so far against where the day's targets put you by now. The waking day runs 07:00-21:00 and
// the targets are spread evenly over it; before 10:00 too little of the day has gone to judge (except calories already
// past the goal).
val HUB_DAY_START: LocalTime = LocalTime.of(7, 0)
val HUB_DAY_END: LocalTime = LocalTime.of(21, 0)
val HUB_JUDGE_FROM: LocalTime = LocalTime.of(10, 0)
const val HUB_OVER_GOAL = 1.10 // calories more than 10 % past the goal
const val HUB_BEHIND = 0.5 // under half of what the pace expects by now
const val HUB_ON_PACE = 0.8 // at least 80 % of it

fun hubDayShare(now: LocalTime): Double {
    val total = (HUB_DAY_END.toSecondOfDay() - HUB_DAY_START.toSecondOfDay()).toDouble()
    return ((now.toSecondOfDay() - HUB_DAY_START.toSecondOfDay()) / total).coerceIn(0.0, 1.0)
}

// kcal and waterMl are what is logged today (null = nothing logged); kcalGoal null = no calorie goal set, so water is
// judged alone. waterTargetMl is the lower end of today's hydration range. loaded false = the day could not be read.
fun nutritionHubState(loaded: Boolean, kcal: Double?, kcalGoal: Double?, waterMl: Double?, waterTargetMl: Double, now: LocalTime): MetricState {
    if (!loaded) return MetricState.Unavailable
    if (kcalGoal != null && kcalGoal > 0 && (kcal ?: 0.0) > kcalGoal * HUB_OVER_GOAL) return MetricState.Warning
    if (now < HUB_JUDGE_FROM) return MetricState.Neutral
    val share = hubDayShare(now)
    val ratios = listOfNotNull(
        kcalGoal?.takeIf { it > 0 }?.let { (kcal ?: 0.0) / (it * share) },
        waterTargetMl.takeIf { it > 0 }?.let { (waterMl ?: 0.0) / (it * share) },
    )
    if (ratios.isEmpty()) return MetricState.Unavailable
    return when {
        ratios.any { it < HUB_BEHIND } -> MetricState.Warning
        ratios.all { it >= HUB_ON_PACE } -> MetricState.Optimal
        else -> MetricState.Neutral
    }
}

// LAB: today's supplements (SupplementsToday, the reminder's own "due" rule). Nothing due by now is on track; this time
// of day's stack being due is normal; an earlier stack still due was missed.
fun labHubState(s: SupplementsToday?, now: LocalTime): MetricState = when {
    s == null || s.scheduledCount == 0 -> MetricState.Unavailable
    s.suggestedBucket == null -> MetricState.Optimal
    s.suggestedBucket == supplementBucketAt(now) -> MetricState.Neutral
    else -> MetricState.Warning
}
