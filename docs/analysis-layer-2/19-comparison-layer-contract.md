# 19 — Comparison-layer contract and reference taxonomy

**Linear:** [DAV-192](https://linear.app/biodashboard/issue/DAV-192) · **Version:** 1.0.0 · **Contract only**

## Why this exists

Milestone 10A ("Comparison & Benchmarking") formally consumes milestone 09's validated evaluation
outputs — but milestone 09 is 10% built. What's real today is "Analysis Layer 1": a dozen
`domain/*Evaluation.kt` files (`HrvRhrEvaluation.kt`, `TrainingLoadEvaluation.kt`, `SleepEvaluation.kt`,
...) that already do ad hoc personal-baseline comparison per metric, each with its own window,
its own band logic, its own confidence gate. 10A's job is to generalize that existing pattern into
one stable, versioned shape — not invent comparison from a blank page.

## The contract

`domain/analysis/AnalysisLayer2Contract.kt` gains:

```kotlin
enum class ComparisonType { POPULATION, COHORT, PERSONAL_HISTORY, CONTEXTUAL, EFFORT_EFFICIENCY }
enum class Directionality { HIGHER_BETTER, LOWER_BETTER, OPTIMAL_RANGE, TARGET_VALUE, NON_DIRECTIONAL }
enum class ComparisonState { OK, NO_REFERENCE, INSUFFICIENT_DATA, LOW_CONFIDENCE, NON_COMPARABLE, STALE_REFERENCE }

data class ComparisonResult(
    val metric: String, val comparisonType: ComparisonType, val state: ComparisonState,
    val rawValue: Double?, val normalizedValue: Double?, val referenceValue: Double?,
    val delta: Double?, val standardizedDelta: Double?, val percentile: Double?,
    val rank: Int?, val rankDenominator: Int?, val directionality: Directionality,
    val referenceIdentity: String, val referenceVersion: String,
    val confidence: Confidence, val breadth: InputCompleteness, val provenance: Provenance,
)
```

`Provenance`/`Confidence`/`InputCompleteness` are reused unchanged from doc 06's output contract —
a comparison result is trustworthy exactly as far as the raw observations behind it, and this
contract never lets a consumer read a percentile without also seeing how much to trust it.

## Metric identity: a string key into doc 02, not a second enum

`ComparisonResult.metric` is a plain string (`"hrv"`, `"resting_heart_rate"`, `"sleep_duration"`) —
the exact canonical names `docs/analysis-layer-2/02-metric-registry.md` (DAV-53) already defines.
That doc is already this project's canonical-name authority; forking a second `StateDimension`-style
enum just for comparison would create two places a metric's identity could drift apart. Doc 02 gains
one new column (directionality, see doc 20/DAV-195) rather than being superseded.

## Explicit non-OK states, not silent omission

Per DAV-192/199's own requirement, an invalid or insufficient comparison says so:

- `NO_REFERENCE` — nothing to compare against exists at all (e.g. no `benchmark_references` row for
  this metric yet — true for every metric until Phase 2 seeds real published data).
- `INSUFFICIENT_DATA` — a reference exists in principle, but this account's own history (or this
  one observation) doesn't clear the data-density gate `PersonalBaseline`/`ComparableSet` define.
- `NON_COMPARABLE` — a reference exists, but DAV-198's eligibility rules reject this specific
  observation (wrong sport, incompatible distance band, etc.).
- `STALE_REFERENCE` — the reference itself has aged out of its own validity period.

A consumer renders each of these with real text (matching this app's own established convention —
the TRAIL card's "needs consent"/"no route" messaging, the STEPS card's honest omission of a metric
with no evaluation function) rather than hiding the row.

## What this contract deliberately does not do

Same three exclusions doc 06 already states for the daily/session contract, restated for
comparison specifically:

- No universal score. `ComparisonResult` is per-metric; nothing here ever averages percentiles
  across metrics into one number.
- No UI. This is a data shape, not a rendering rule (doc 22 covers presentation).
- No collapsing of population vs. personal comparison into one result — a metric with both gets
  two separate `ComparisonResult`s (`comparisonType` distinguishes them), shown side by side per
  DAV-200's own acceptance criterion, never merged.

## Versioning

v1.0.0. Adding a `ComparisonType`/`Directionality`/`ComparisonState` case is a minor version change.
Changing `ComparisonResult`'s field meaning is major, per doc 06's own precedent.
