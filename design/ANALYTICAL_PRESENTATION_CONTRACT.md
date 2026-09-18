# Field Terminal — Analytical Presentation Contract

**Status:** DAV-105 design-system foundation  
**Version:** 1.0  
**Date:** 2026-09-18  
**Visual authority:** [Futuristic Material Visual Contract](./FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md)

## Purpose

This contract is the boundary between analysis output and Field Terminal UI components.

raw observations → analysis / interpretation → MetricPresentation → design-system components

The analysis layer determines what a metric means. The UI only renders the supplied meaning consistently. A component must never infer that a value is good, bad, improving, safe, or reliable from a number, its direction, source, or threshold.

## Non-goals

This contract does not define clinical/performance thresholds; calculate baselines, reference ranges, trends, confidence, or derived values; choose targets, diagnoses, recommendations, or composite scores; change IA/workflow; or promise unsupported precision.

## Canonical presentation model

Use an Android-domain model equivalent to this. Exact package placement follows the existing app architecture; UI components receive this model or a purpose-specific subset.

    data class MetricPresentation(
        val metricId: String,
        val label: String,
        val context: String? = null,
        val value: DisplayValue?,
        val semanticState: MetricState,
        val trend: TrendState = TrendState.Unavailable,
        val confidence: ConfidenceLevel? = null,
        val provenance: List<Provenance> = emptyList(),
        val range: PersonalRange? = null,
        val chart: ChartPresentation? = null,
        val availability: DataAvailability,
        val updatedAt: DisplayTimestamp? = null,
        val accessibilitySummary: String
    )

    data class DisplayValue(
        val primary: String,
        val unit: String? = null,
        val secondary: String? = null
    )

    enum class MetricState { Optimal, Neutral, Warning, Critical, Building, Unavailable }
    enum class TrendState { Improving, Declining, Stable, InsufficientData, Unavailable }
    enum class ConfidenceLevel { High, Medium, Low }
    enum class DataAvailability { Available, Sparse, Building, Unavailable }

Semantic state is mandatory: it gives each component a safe display fallback. Availability says whether there is enough current/history data for the requested representation. Keep it separate from confidence: a measured value can be available but have low-confidence interpretation; an estimate can have medium confidence while history is still building.

## Semantic state rendering

| State | Meaning supplied by upstream | Futuristic Material treatment | Required non-color cue |
|---|---|---|---|
| Optimal | Favorable in supplied metric context | Emerald signal | Optimal label and state icon/shape |
| Neutral | No positive or negative claim | Cool neutral; no emerald glow | Normal or Current label when exposed |
| Warning | Attention warranted | color/state/warning; restrained border/pill | Attention label and warning icon |
| Critical | Urgent/materially adverse state supplied upstream | color/state/critical; highest hierarchy | Critical label and critical icon |
| Building | More data/history/work is required | color/state/analysis; subdued | Building label plus requirement/context |
| Unavailable | No usable presentation value | Muted neutral | Unavailable label plus concise reason if provided |

State colors communicate interpretation, never data domain. Fuel may be cyan as a section identity while a warning metric retains warning treatment. Building and unavailable are not errors; they must be useful and dignified.

## Trend rendering

| Trend | Meaning supplied by upstream | UI behavior |
|---|---|---|
| Improving | Direction favorable for metric/context | Upward indicator; optional state-colored chart stroke |
| Declining | Direction unfavorable for metric/context | Downward indicator; optional state-colored chart stroke |
| Stable | No material directional change | Flat indicator; neutral chart |
| InsufficientData | Direction cannot be responsibly inferred | No arrow; Not enough history microcopy |
| Unavailable | No trend can be displayed | Omit trend affordance |

Trend is not semantic state: declining is not automatically warning, and improving is not automatically optimal.

## Confidence and provenance

Confidence communicates reliability of the supplied interpretation/estimate, not clinical importance.

| Level | Treatment |
|---|---|
| High | Compact telemetry label; no dominant decoration |
| Medium | Compact label with explanation on detail |
| Low | Visible caution label; never tooltip-only |

Use ConfidenceChip for derived/estimated/analytical results or where uncertainty materially changes interpretation. Do not clutter routine direct measurements with redundant labels.

    sealed interface Provenance {
        data object HealthConnect : Provenance
        data object Wearable : Provenance
        data object ImportedActivity : Provenance
        data object Manual : Provenance
        data object Derived : Provenance
        data object ModelEstimated : Provenance
    }

ProvenanceCue uses concise telemetry cues, not competing logos. When sources are multiple, analysis supplies ordered provenance; the UI may summarize but preserves detail where the existing screen supports it.

## Personal range and baseline

    data class PersonalRange(
        val kind: RangeKind,
        val lower: Double?,
        val upper: Double?,
        val baseline: Double?,
        val current: Double?,
        val label: String,
        val comparison: RangeComparison?,
        val sufficientHistory: Boolean,
        val explanation: String? = null
    )
    enum class RangeKind { PersonalBaseline, PersonalRange, TargetRange, ReferenceRange }
    enum class RangeComparison { Below, Within, Above, NotComparable }

Range is optional. Render a band only when upstream confirms sufficient history and comparable values. A baseline is not automatically a target: label its kind. Sparse history uses the supplied Building/Sparse state rather than an invented band. The range indicator differentiates current, baseline and range by marker shape/position and label—not color alone. It does not judge whether below/within/above is desirable; semantic state supplies that interpretation.

## Shared chart framing

    data class ChartPresentation(
        val kind: ChartKind,
        val timeWindow: String?,
        val xAxisLabel: String? = null,
        val yAxisLabel: String?,
        val unit: String?,
        val points: List<ChartPoint>,
        val scale: ChartScale,
        val missingness: MissingDataState,
        val annotation: String? = null
    )
    enum class ChartKind { TrendLine, AreaTrend, Ring, Gauge, DotPlot, Bars, SmallMultiples }
    enum class MissingDataState { None, Gaps, Sparse, Unavailable }

Every chart frame owns title, context/time window, unit, scale, visible missingness treatment and optional provenance/confidence summary. Do not render a temporal chart for one observation; retain missing intervals as gaps rather than fabricating data; use dot/distribution treatment for sparse labs; use rings/gauges only for bounded, target-vs-actual, or deliberately summarized values supplied upstream; and make scale behavior explicit.

## Component responsibilities

| Component | Input | Renders | Must not do |
|---|---|---|---|
| FTMetricValue | Display value | Value/unit/secondary value | Format raw measurement or decide precision |
| FTStatePill | Semantic state | Label/icon/token treatment | Derive state from value/trend |
| FTTrendIndicator | Trend state | Arrow/flat/omitted treatment | Map up to green or down to red |
| FTConfidenceChip | Confidence/explanation | Confidence treatment | Calculate certainty |
| FTProvenanceCue | Ordered provenance | Source cue/summary | Select or rank source |
| FTRangeIndicator | Personal range | Markers/band/comparison | Calculate range or desirable direction |
| FTChartFrame | Chart presentation | Frame/axes/unit/missingness | Invent/interpolate points or chart semantics |
| FTDataState | Availability/reason | Sparse/building/unavailable surface | Treat absence as a permanent loading/error |
| FTAnalyticalCard | Metric presentation + slots | Contract hierarchy | Implement metric-specific business rules |

All components use only Futuristic Material semantic tokens: near-black/material surfaces, Inter interface type, Roboto Mono telemetry, 4 px spacing, approved radii, and state/domain separation. Category screens may not introduce raw hex values, ad-hoc elevation, or bespoke chart framing.

## Composition precedence

1. Unavailable availability: render unavailable state; omit value/trend/range/chart.
2. Building or sparse availability: retain honest current observation if present; prioritize explanation; trend/range may be absent.
3. Critical state overrides domain accent and trend emphasis.
4. Low confidence is visible whenever interpretation, estimate, or range is shown.
5. Trend supports a state; it never becomes the sole state signal.
6. Provenance supports the metric; it never outranks it.

## Accessibility

Pair all semantic color with label plus icon/shape. Expose accessibilitySummary as a complete result, e.g. HRV 58 milliseconds, attention, declining, medium confidence, based on wearable data. No decorative chart is the sole metric representation. Preserve contrast on base and glass, and reserve mono type for compact telemetry.

## Android and Figma mapping

Android centralizes semantic-state-to-token treatment, trend-to-indicator treatment, and availability surfaces in the shared design-system layer. This is presentation logic; analysis constructs the input model.

When Figma quota is available, create or extend:

1. StatePill: Optimal, Neutral, Warning, Critical, Building, Unavailable.
2. TrendIndicator: Improving, Declining, Stable, InsufficientData, Unavailable.
3. ConfidenceChip: High, Medium, Low.
4. ProvenanceCue: Health Connect, Wearable, Imported activity, Manual, Derived, Model estimated.
5. RangeIndicator: Personal baseline/range, target/reference range; below/within/above/not comparable.
6. DataState: Available, Sparse, Building, Unavailable.
7. ChartFrame: labels/unit/time window/missingness, with chart content as a slot.
8. AnalyticalCard: composed primitive slots, not a metric-specific variant matrix.

Bind visual properties to Futuristic Material variables before publishing. Never encode thresholds or metric-specific state decisions in Figma variants.

## Acceptance tests

- The same card renders Warning + Declining + High confidence and Optimal + Improving + High confidence solely by changing presentation input.
- A low-confidence estimate cannot appear as a confirmed measurement.
- Sparse history shows Building/Sparse without an invented trend or personal band.
- Charts retain unit, time window, scale context and visible gaps.
- Range shows its type and never infers desirability from position.
- State uses text and icon/shape as well as color.
- No component receives raw data and makes metric-specific interpretation.
- Training, Fuel, Heart, Labs, Body and Session Detail reuse primitives without bespoke tokens or visual rules.
