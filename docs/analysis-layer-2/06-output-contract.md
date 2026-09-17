# 06 — Analysis output contract for downstream interpretation/UI

**Linear:** [DAV-67](https://linear.app/biodashboard/issue/DAV-67) · **Version:** 1.0.0 (2026-09-17) · **Contract only — no implementation here**

## Why this exists

DAV-56 through DAV-63 are about to produce a real load vector, a time-dynamics engine, performance
anchors, and personalized parameters. Without a stable shape agreed *before* those are built, each
would need retrofitting once a shape is finally chosen — the same problem DAV-53 solved for raw
metrics, one layer up. This contract is what any consumer (this app's own UI, a future analysis
screen, or P7's DAV-117 exposing Zepp-native metrics) reads — never the database, never a
source-specific payload. P7's own DAV-117 states this exact requirement independently ("analysis
consumes canonical data, not Zepp API payloads") — this contract is the thing that makes that true.

This does not require rewriting the `domain/*Evaluation.kt` files already shipped. They keep
working as they are; DAV-56 onward's *new* outputs conform to this contract from the start, and
existing evaluations can be adapted to it incrementally, not all at once.

## The four output shapes

Every shape carries `provenance` (DAV-53's tag: origin + algorithm + version) and `confidence`
(DAV-66's two axes) as named fields, never bolted on separately — a consumer should never be able
to read a value without also seeing how much to trust it.

### 1. `DailyStateObject` — one per calendar day

The longitudinal view: what today's recovery/baseline state looks like across every tracked
dimension, per DAV-60.

```
DailyStateObject(
    date: LocalDate,
    dimensions: Map<StateDimension, DimensionState>,  // e.g. HRV, RHR, SLEEP_DURATION, ENERGY, STRESS, SORENESS
)

DimensionState(
    value: Double?,                 // null, not 0, when NoData -- see 05-confidence-propagation.md
    evalState: EvalState,           // reuses domain/EvalState.kt unchanged
    confidence: Confidence,         // depth axis, domain/EvalState.kt unchanged
    breadth: InputCompleteness,     // new, 05-confidence-propagation.md
    provenance: Provenance,
    contributingObservations: List<ObservationRef>,  // see "Explanation", below
)
```

### 2. `SessionLoadVector` — one per `exercise_sessions` row

DAV-56's multidimensional load, one vector per real workout. Dimensions are independent — a
session can be `HIGH` muscular and `LOW` cardiovascular at once, never collapsed into one number.

```
SessionLoadVector(
    sessionId: Long,               // exercise_sessions.id
    date: LocalDate,
    dimensions: Map<LoadDimension, DimensionLoad>,  // CARDIOVASCULAR, MUSCULAR, MECHANICAL, METABOLIC, NEUROMUSCULAR, PERCEPTUAL
    regional: Map<BodyRegion, Double>?,   // DAV-65's anatomical vector, strength/endurance sessions only where inferrable
    competingModels: Map<String, ModeledValue>,  // e.g. "trimp" / "srpe" / "pace_relative" all present at once for one endurance session -- DAV-58's parallel models, never collapsed to one
)

DimensionLoad(
    value: Double,
    unit: String,
    confidence: Confidence,
    breadth: InputCompleteness,
    provenance: Provenance,
)

ModeledValue(value: Double, unit: String, confidence: Confidence, provenance: Provenance)
```

### 3. `RollingStateSeries` — a named, versioned time series

DAV-59's EWMA/impulse-response output, and any rolling window (7/14/28/42-day) view. One series
per (dimension, model) pair — CTL/ATL/TSB and a future competing model for the same dimension are
two separate `RollingStateSeries`, per DAV-58/DAV-62's "parallel models, not one universal score"
principle.

```
RollingStateSeries(
    dimension: String,             // e.g. "training_load", "hrv_baseline"
    modelName: String,             // e.g. "banister_ctl_atl_tsb", "ewma_tau7"
    modelVersion: String,
    parameters: Map<String, Double>,  // e.g. {"tau_days": 42.0} -- the tunable priors DAV-59/63 govern
    points: List<StatePoint>,
)

StatePoint(date: LocalDate, value: Double?, confidence: Confidence, breadth: InputCompleteness)
```

### 4. `PerformanceAnchor` — DAV-61's validation targets

Kept separate from modeled capacity, per DAV-61's own instruction not to conflate observed
performance with a model's estimate of it.

```
PerformanceAnchor(
    anchorType: String,            // e.g. "run_pace_at_hr", "estimated_1rm", "vo2max_trend"
    date: LocalDate,
    observedValue: Double?,        // a real, measured outcome -- null if not observed on this date
    modeledValue: Double?,         // what a candidate model predicted for the same date, for DAV-62's backtest
    confounders: List<String>,     // e.g. "heat", "incomplete_warmup" -- named, not silently absorbed into the number
    provenance: Provenance,
)
```

## Shared types

```
Provenance(origin: String, algorithm: String?, algorithmVersion: String?)  // DAV-53
Confidence(have: Int, need: Int)  // domain/EvalState.kt, unchanged
InputCompleteness(present: Set<String>, ideal: Set<String>)  // DAV-66
ObservationRef(table: String, id: String)  // e.g. ("wearable_daily", "2026-09-17"), ("exercise_sessions", "10236")
```

## "Explanation": traceability without database access

Every derived/modeled value's `contributingObservations` (or equivalent) lists the real raw rows
that fed it, as `ObservationRef`s — a table name and a natural key (a date for daily-aggregate
tables, a row id for `exercise_sessions`/`lab_results`). A consumer can show *"this HRV baseline
used 5 real readings from wearable_daily between Sep 10-17"* without querying Supabase directly or
knowing its schema. This is what makes the contract a real boundary rather than a thin wrapper
around the database.

## What this contract deliberately does not do

- No UI. Nothing here describes how a value renders — only what a value *is*.
- No new database tables. These shapes are read-time constructs (a repository or domain function
  builds one from existing tables + `analysis_results`-style storage if DAV-59/62 end up needing to
  persist computed series — that persistence design, if any, belongs to those tickets, not this
  contract).
- No collapsing of parallel models. `SessionLoadVector.competingModels` and per-model
  `RollingStateSeries` are the enforcement mechanism for DAV-53/55/58/62's shared principle that
  multiple models for one concept coexist rather than one winning silently.

## Versioning

v1.0.0. Adding a new `StateDimension`/`LoadDimension`/`BodyRegion` enum case is a minor version
change (existing consumers ignore cases they don't know about). Changing an existing shape's field
meaning or removing a field is a major version change — flag it in this document's own changelog
before any implementation ships it, since P7 and any future consumer read this contract, not the
code that happens to produce it today.
