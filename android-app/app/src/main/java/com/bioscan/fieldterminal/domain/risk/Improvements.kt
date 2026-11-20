package com.bioscan.fieldterminal.domain.risk

import java.time.LocalDate

// Risk profile R5 (docs/risk-profile/01-plan.md): a way to improve, started as a Lab intervention, and read back against the
// air days it was meant to change: the 4 weeks before its start against the days since, metric by metric. Descriptive
// before and after, not a trial: the weather and the season move air too, and the line says so. Pure.

/** subject_type of the interventions a way to improve starts (interventions.subject_type). */
const val IMPROVEMENT_SUBJECT = "risk_improvement"
/** How long before an improvement is read: 4 weeks, as the summary window. */
const val IMPROVEMENT_EVALUATE_DAYS = 28L
/** Days of data needed on each side before a before/after is shown. */
const val IMPROVEMENT_MIN_DAYS = 7

/** The air metrics an improvement's `lowers` can name, with the words and unit shown. */
enum class ImprovementMetric(val key: String, val label: String, val unit: String) {
    PM25("pm25", "PM2.5 breathed", "µg/m³"),
    PM25_INDOOR("pm25_indoor", "PM2.5 indoors at home", "µg/m³"),
    NO2("no2", "NO₂ where you were", "µg/m³"),
    O3("o3", "Ozone, highest 8 h", "µg/m³"),
    CO2("co2", "CO₂ at night", "ppm"),
    UV("uv", "UV index, daytime peak", ""),
}

/** Which metrics an improvement's `lowers` list covers (pm25 also reads the indoor level when there is one). */
fun metricsFor(lowers: List<String>): List<ImprovementMetric> = lowers.flatMap { l ->
    when (l) {
        "pm25" -> listOf(ImprovementMetric.PM25, ImprovementMetric.PM25_INDOOR)
        "no2" -> listOf(ImprovementMetric.NO2)
        "o3" -> listOf(ImprovementMetric.O3)
        "co2" -> listOf(ImprovementMetric.CO2)
        "uv" -> listOf(ImprovementMetric.UV)
        else -> emptyList()
    }
}.distinct()

/** One day's values, by metric key (the air days of air_exposure_daily). */
data class AirDayValues(val date: LocalDate, val values: Map<String, Double>)

data class BeforeAfter(val metric: ImprovementMetric, val before: Double?, val after: Double?, val beforeDays: Int, val afterDays: Int) {
    /** Shown only with enough days on both sides. */
    val readable: Boolean get() = before != null && after != null && beforeDays >= IMPROVEMENT_MIN_DAYS && afterDays >= IMPROVEMENT_MIN_DAYS
    /** after minus before, as a share of before; negative is lower. */
    val change: Double? get() = if (readable && before!! != 0.0) (after!! - before) / before else null
}

fun beforeAfter(days: List<AirDayValues>, start: LocalDate, metrics: List<ImprovementMetric>, today: LocalDate): List<BeforeAfter> = metrics.map { m ->
    val before = days.filter { !it.date.isBefore(start.minusDays(IMPROVEMENT_EVALUATE_DAYS)) && it.date.isBefore(start) }.mapNotNull { it.values[m.key] }
    val after = days.filter { !it.date.isBefore(start) && !it.date.isAfter(today) }.mapNotNull { it.values[m.key] }
    BeforeAfter(m, before.takeIf { it.isNotEmpty() }?.average(), after.takeIf { it.isNotEmpty() }?.average(), before.size, after.size)
}

/** A started improvement as the card shows it. */
data class StartedImprovement(
    val interventionId: String,
    val improvementId: String,
    val name: String,
    val state: String,
    val started: LocalDate?,
    val evaluateOn: LocalDate?,
)

/** The improvement id an intervention was started from: subject_key "air:<id>". */
fun improvementKey(id: String) = "air:$id"
