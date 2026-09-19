package com.bioscan.fieldterminal.domain

/**
 * Presentation-only output from the analysis layer.
 *
 * Analysis decides state, trend, confidence and range. UI components render
 * the supplied values and must not infer meaning from raw numbers.
 */
data class MetricPresentation(
    val metricId: String,
    val label: String,
    val context: String? = null,
    val value: DisplayValue? = null,
    val semanticState: MetricState,
    val trend: TrendState = TrendState.Unavailable,
    val confidence: ConfidenceLevel? = null,
    val provenance: List<Provenance> = emptyList(),
    val range: PersonalRange? = null,
    val chart: ChartPresentation? = null,
    val availability: DataAvailability,
    val updatedAtLabel: String? = null,
    val accessibilitySummary: String,
)

data class DisplayValue(
    val primary: String,
    val unit: String? = null,
    val secondary: String? = null,
)

enum class MetricState { Optimal, Neutral, Warning, Critical, Building, Unavailable }
enum class TrendState { Improving, Declining, Stable, InsufficientData, Unavailable }
enum class ConfidenceLevel { High, Medium, Low }
enum class DataAvailability { Available, Sparse, Building, Unavailable }

enum class Provenance {
    HealthConnect,
    Wearable,
    ImportedActivity,
    Manual,
    Derived,
    ModelEstimated,
}

data class PersonalRange(
    val kind: RangeKind,
    val lower: Double? = null,
    val upper: Double? = null,
    val baseline: Double? = null,
    val current: Double? = null,
    val label: String,
    val comparison: RangeComparison = RangeComparison.NotComparable,
    val sufficientHistory: Boolean,
    val explanation: String? = null,
)

enum class RangeKind { PersonalBaseline, PersonalRange, TargetRange, ReferenceRange }
enum class RangeComparison { Below, Within, Above, NotComparable }

data class ChartPresentation(
    val kind: ChartKind,
    val timeWindow: String? = null,
    val xAxisLabel: String? = null,
    val yAxisLabel: String? = null,
    val unit: String? = null,
    val points: List<ChartPoint> = emptyList(),
    val scale: ChartScale = ChartScale.Auto,
    val missingness: MissingDataState = MissingDataState.None,
    val annotation: String? = null,
)

data class ChartPoint(
    val x: Float,
    val y: Float,
    val label: String? = null,
)

sealed interface ChartScale {
    data object Auto : ChartScale
    data class Fixed(val minimum: Float, val maximum: Float) : ChartScale
}

enum class ChartKind { TrendLine, AreaTrend, Ring, Gauge, DotPlot, Bars, SmallMultiples }
enum class MissingDataState { None, Gaps, Sparse, Unavailable }
