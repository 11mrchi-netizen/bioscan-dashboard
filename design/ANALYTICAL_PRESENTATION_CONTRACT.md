# Field Terminal — Analytical Presentation Contract

**Status:** DAV-105 design-system foundation  
**Version:** 1.0  
**Date:** 2026-09-18  
**Visual authority:** [Futuristic Material Visual Contract](./FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md)

## Purpose

This contract is the boundary between analysis output and Field Terminal UI components.

raw observations → analysis / interpretation → MetricPresentation → design-system components

The analysis layer determines what a metric means. The UI only renders the supplied meaning consistently. A component must never infer that a value is good, bad, improving, safe, or reliable from a number, its direction, source, or threshold.

## Amendments (2026-10)

- **Threshold mappings in the presentation layer.** Deterministic, documented threshold-to-`MetricState` mappings (TSB bands, lab flag vs reference range, age-acceleration sign, etc.) are allowed in UI-adjacent code, but they live in one central mapper module (`MetricStateMappers`), never inline in a screen or component, each with its thresholds documented where defined. Components still receive a `MetricState` and render it; state is always pill/icon + label, never color alone.
- **User-set targets are a first-class `TargetRange` source** (e.g. calorie and macro goals saved in Settings). Generic `ReferenceRange` values are only the fallback when no target is set.
- **Hydration is a range, not a single goal.** It is rendered as a `TargetRange` band (user-set min–max, with a documented default), never a fixed hard-coded volume.
- **No invented ceilings.** A bar must not use an arbitrary fixed maximum (e.g. a "weekly km" ceiling) as if it were a reference.
- **Charts of a graded composite show the grade's own factors, never a second score.** The User tab's Healthspan grade (`domain/aging/Healthspan.kt`, documented in `docs/analysis-layer-3/healthspan.md`) is the one cross-metric grade in the app. Its radar (`FTRadarChart`) is only a picture of the same factors: ring position encodes the factor's status (outer favorable, middle neutral, inner opportunity), a factor that could not be assessed is a hollow dashed marker left out of the polygon (never plotted at zero), and no number other than the grade's own "favorable of assessed" count is shown.
- **Skill levels name their basis.** A level (`domain/levels/SkillLevel.kt`) always states what it is measured against (`VS AGE/SEX TABLE`, `VS LIFTER STANDARDS`, `VS YOU`) and is marked `PROVISIONAL` while the evidence is thin; a personal-basis level is never labelled with population wording.

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

## Analysis review amendments (2026-10)

From the analysis review (docs/analysis-review/README.md). Each one changes what upstream supplies, not how a primitive looks.

**R1 · State words.** An evaluation may supply a short word with its semantic state (`StateWord(state, word)`, domain/StateWords.kt): STEADY, ABOVE BASELINE, BELOW BASELINE, MIXED SIGNALS, LOADED. The pill shows the word; the state still sets colour and symbol, and the accessibility summary reads both. Optimal is reserved for a change in the favorable direction (or a value inside a supplied target); a stable reading is Neutral, never Optimal, and a shift's state follows the metric's directionality, so a rise in HRV and a rise in resting heart rate no longer share one ATTENTION pill.

**R2 · Data is not confidence.** A count of observations against what a gate needs is shown as a data chip (DATA: GOOD at 80% of the need or more, PARTIAL at 50%, THIN below), never as CONFIDENCE. The confidence chip is kept for results that carry a real precision (an interval compared with the smallest change that matters). Population comparisons report how well the reference fits the person (confirmed age band and sex), not a constant.

**R3 · Each morning against what was expected.** For HRV, resting heart rate and sleep duration, the morning's reading is placed against a band forecast before it arrived (the `expected_band` result: the person's usual level for that weekday, learnt from their own history). Inside the 80% band reads AS EXPECTED; outside it BELOW or ABOVE EXPECTED, with the size in natural units (a percentage for HRV) and how often a morning that far out comes when nothing has changed, as a natural frequency ("about once a month"), never a percentile. One morning outside the band is ordinary and stays Neutral. A flag needs a morning beyond the 99% band (UNUSUALLY LOW / HIGH) or a run on one side that trips the run chart on the forecast errors (LOW / HIGH LATELY); only a flag on the metric's unfavorable side is Warning (for sleep duration, only outside 7-9 h). Optimal is not used: one morning is not a change. Days inside a logged illness read ILLNESS LOGGED and are not judged. Under the band's chart, the share of recent mornings the 80% band actually held is stated, so the band can be audited. A building band shows how many mornings it still needs; nothing is guessed.

**R4 · Chart rules.** Applied by the shared trend component (DateTrendLine with ChartRules.kt), so no screen has to remember them: a y-axis with at least three labelled values at nice steps; a fixed personal scale per metric when the caller has one (2nd to 98th percentile of the person's own readings), widened only when the data leave it, so an ordinary wobble looks like a wobble; a daily series breaks its line at missing days and the gap is marked on the axis (a session series, a run every few days, stays connected); a per-day band labelled with what it holds; flagged points ringed, so a flag never rests on colour alone; and dated annotations for a logged illness (a span) and a source change (a line). Two units still never share one axis.

**R5 · Verdict words, rungs and the language contract.** Every statement sits on one rung of the evidence ladder: OBSERVED (a reading against what was expected), ASSOCIATED (a pattern in your history, adjusted for weekday, trend and the day before), PREDICTIVE (helps forecast on held-out weeks) or TESTED (a randomised self-experiment). The rung decides the verbs: an association "goes with", "followed" or "came before"; it never "lowers", "raises", "improves" or "causes", and no arrow joins an exposure to an outcome. A tested question carries one of four verdict words, from its 95% interval against a no-effect zone fixed before any data: CLEAR (outside the zone), NEGLIGIBLE (inside it; an answer, not a non-result), UNCLEAR (across an edge, with how many more days would settle it), NOT YET (too few days, shown as progress). The word and the rung badge sit beside every row, so nothing rests on colour. The evidence board draws each question to scale on its outcome's shared axis (estimate, interval, zone, smallest detectable effect), keeps nulls on the board, and orders rows by question, never by p. Exploratory screens (what came before off mornings) say EXPLORATORY and never use the verdict words.

**R6 · Re-checked every night, and confirmed on new days.** A question that is re-tested every night carries two intervals: the 95% interval (the estimate, drawn on the board) and the wider interval its verdict uses while information is short. When they differ, the row says so in words ("re-checked every night; it narrows as days accumulate") instead of letting a drawn interval that clears the zone sit beside UNCLEAR unexplained. Every verdict is kept in a ledger and the detail shows its history. A hint registered as a question says when it was registered and how many new days it has; only those days count. A pooled estimate is labelled as coming from a model of related questions and never replaces the verdict.

**R7 · Tested and observed answers.** A randomised self-experiment is the TESTED rung. Its answer, once the last period or day is read, is one of four phrases against the zone the person set at registration, in the outcome's own direction: CLEAR BENEFIT, CLEAR HARM, NO MEANINGFUL EFFECT, UNCLEAR. It may say "with it" and "without it" and that the order was drawn at random; it still answers for this person under the conditions tested, never for people in general. While it runs, numbers are labelled interim and carry no verdict. What today's draw asks for is shown as an instruction (WITH / WITHOUT, or "take today's organiser dose" when blinded: a blinded schedule is never on screen unless the person asks for it on behalf of whoever fills the organiser), and a without-day of a behaviour rule is "no rule, as you usually would", never an instruction to do the opposite. Before registering, the sheet states what the design can detect in the outcome's unit, against the zone. An intervention read against its forecast from before the start is OBSERVED: "ran +x% against the forecast", "what happened, not why"; it never proposes stopping, cutting back, pausing or stepping up on its own, and the proposal it would have made becomes REASSESS with the way to a tested answer.

**R8 · One vocabulary, one set of cut-offs, and composites that earn their place.** A composite (Today's recovery, READY FOR, the Sleep Index) speaks the vocabulary of the morning band: AS EXPECTED, ABOVE EXPECTED, BELOW EXPECTED, UNUSUALLY LOW (recovery adds MIXED SIGNALS when its contributors disagree). Each metric has one set of cut-offs, defined once and used by every screen and every composite that reads it (the Sleep Index's 75 and 55; one regularity mark for the SRI, 80; the circadian-timing score says STEADY / VARIABLE TIMING, never "regular"). Each composite carries a scorecard from the person's own history: what it should forecast, and whether it does so better than its best single input. A composite whose scorecard reads NO BETTER drops its own word for INPUTS ONLY and shows its inputs; UNCLEAR or NOT YET SCORED keep the word with the scorecard line beside it. A retired composite (the 7-day HRV "condition" band) is not shown, and its place says what replaced it. A model element fitted to the person (the fitness and fatigue spans) is used only when it forecast better on held-out data, and the screen says which spans are in use and why.

**R9 · Health concerns (docs/health-concerns).** A concern's state is one of UNOBSERVED (no check-in for a week, shown
as Unavailable, never as fine), ACTIVE, STABLE, IMPROVING (a favourable change), WORSENING (Warning, the only amber state
besides the safety prompts) or RESOLVED. A day without a check-in is a gap in every chart, never a zero; a symptom-free
check-in is a zero, drawn as a dot on the baseline (hollow when it came from "nothing since"). Episode durations whose
edges fall on days without check-ins are ranges. Findings sit on the ASSOCIATED rung and use these words only: NOT
ENOUGH YET (with what is missing), RECURRING (it came back; nothing about why), NO PATTERN, HINT · RE-CHECKING (one pass,
not shown as a pattern), POSSIBLE · EXPLORATORY, PERSISTENT, FADED, with WEAKENING after one failed re-check. Every
finding shows its base rate (how often the exposure happens on comparable days or sessions), what else could explain it,
how uncertain it is and a next step that is an observation or a trial, never an instruction to train differently. A
trial is OBSERVED: INTERIM until its date, then LOWER SINCE, HIGHER SINCE or NO CLEAR CHANGE. Safety prompts are fixed
rules that suggest seeing a clinician and are never presented as findings or diagnoses.

## Acceptance tests

- The same card renders Warning + Declining + High confidence and Optimal + Improving + High confidence solely by changing presentation input.
- A low-confidence estimate cannot appear as a confirmed measurement.
- Sparse history shows Building/Sparse without an invented trend or personal band.
- Charts retain unit, time window, scale context and visible gaps.
- Every trend shows at least three labelled y values; a daily series with a missing day draws two pieces, not one bridged line.
- A morning outside its 80% band without a flag is Neutral; a flag on the unfavorable side is Warning and carries a word.
- An associated finding never uses a cause verb or an arrow; every evidence row shows its verdict word and rung, and a NEGLIGIBLE row stays on the board.
- A composite whose scorecard reads NO BETTER shows INPUTS ONLY on every screen it appears on; the same cut-off never differs between two screens.
- A randomised experiment shows no verdict before its last period or day is read; a blinded experiment never shows today's arm; an observational read never yields a proposal to stop, cut back, pause or step up.
- A health concern with no check-in for a week reads UNOBSERVED; a missed day is a gap, a symptom-free day a zero; no finding shows without its base rate.
- Range shows its type and never infers desirability from position.
- State uses text and icon/shape as well as color.
- No component receives raw data and makes metric-specific interpretation.
- Training, Fuel, Heart, Labs, Body and Session Detail reuse primitives without bespoke tokens or visual rules.
