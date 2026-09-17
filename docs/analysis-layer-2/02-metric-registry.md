# 02 — Canonical observation and metric registry

**Linear:** [DAV-53](https://linear.app/biodashboard/issue/DAV-53) · **Version:** 1.0.0 (2026-09-17, first version — supersedes nothing)

## Why this exists

`domain/*.kt` in the Android app already implements real evaluations — HRV/RHR SWC bands
(`HrvRhrEvaluation.kt`), training load CTL/ATL/TSB (`TrainingLoadEvaluation.kt`), sleep, body
composition, subjective wellbeing, bloodwork, OSTRC — each against a shared
`EvalState`/`Confidence` contract (`domain/EvalState.kt`). What's never been written down is the
layer underneath: what counts as a "canonical metric" precisely enough that adding metric #20 does
not require re-deriving naming, units, and provenance conventions from scratch. This registry is
that contract.

## Value-kind taxonomy

Every metric in this registry is exactly one of:

- **raw observation** — a value as recorded by a device or a person, unmodified (e.g. `wearable_daily.hrv`, a logged `weight_kg`).
- **derived** — a deterministic transform of one or more raw observations with no free parameters beyond the transform itself (e.g. pace = distance/duration, BMI from weight+height).
- **modeled** — the output of a named algorithm with tunable parameters and a real chance of disagreement between two reasonable implementations (e.g. CTL/ATL/TSB with a chosen time constant, TRIMP with a chosen HR-reserve exponent, SWC bands with a chosen baseline window).
- **inferred** — a value estimated from indirect signal, carrying meaningfully more uncertainty than "modeled" (e.g. a max-HR estimate from age when no measured max-HR exists, a threshold pace back-calculated from a race result).

This distinction matters because raw observations are the only kind that should ever be
persisted as fact in a source table — everything past that point is a *view* over the raw data,
computed on demand or cached, never overwriting the observation it was computed from.

## Core principle: raw observations are never overwritten by derived computation

`TrainingLoadEvaluation.kt` already demonstrates why this matters in practice: the Android app's
CTL/ATL/TSB model replaces ACWR for its own UI, but the separate web dashboard (`index.html`) still
computes ACWR from the same underlying session data, unreconciled — two real models of "training
load" coexisting today, currently as an unintentional side effect of a platform migration rather
than a designed feature. This registry makes that pattern *intentional*: a metric's raw
observations live untouched in their source table; each modeled value is its own named,
independently versioned series, never collapsed into one number. DAV-55 (endurance adapters)
applies this explicitly — TRIMP-based, pace-based, and sRPE-based load stay three parallel series,
not a blend.

## Provenance & confidence conventions

**Confidence** — reuse the existing convention exactly as implemented, don't reinvent it:
`Confidence(have: Int, need: Int)` (`domain/EvalState.kt:13-16`), rendered as `"$have/$need"`, with
`.met` gating whether a real state renders at all. Every metric in this registry that feeds an
evaluation card uses this, unchanged.

**State** — reuse `EvalState { NoData, Building, Stable, ShiftUp, ShiftDown, Unstable }`
(`domain/EvalState.kt:7`) for any metric that resolves to a directional judgment. Not every
canonical metric needs a state (a raw observation like weight doesn't; a modeled trend like the
weight EMA does).

**Provenance tag** (new — nothing like this exists yet, per the DAV-52 inventory finding that only
`exercise_sessions` tracks source at all): every metric's registry entry below carries a provenance
shape describing where its value(s) come from —

```
provenance:
  origin: health_connect | manual | amazfit (future) | computed
  algorithm: <name>       # only for modeled/inferred values, e.g. "banister-ctl-atl-tsb"
  algorithm_version: <v>  # bump when the transform's parameters or formula change
```

`computed` origin values still name their algorithm — e.g. TRIMP's `origin: computed`,
`algorithm: banister-trimp`, sourced from `exercise_sessions.avg_hr` + `wearable_daily.rhr`.

## Registry

| Canonical name | Kind | Unit | Temporal grain | Aggregation | Current Supabase source(s) | Status |
|---|---|---|---|---|---|---|
| Heart rate (instantaneous) | raw | bpm | per-sample | none | not stored (HC session-level only) | gap |
| Heart rate (session average) | raw | bpm | per-session | mean | `exercise_sessions.avg_hr` | mapped |
| Heart rate (session max) | raw | bpm | per-session | max | `exercise_sessions.max_hr` | mapped (sparse on manual entries — 0/19) |
| HRV | raw | ms | daily | vendor-defined | `wearable_daily.hrv` | mapped (20% coverage) |
| Resting heart rate | raw | bpm | daily | vendor-defined | `wearable_daily.rhr` | mapped (42% coverage) |
| VO2max | raw | ml/kg/min | daily (intermittent) | vendor-estimated | `wearable_daily.vo2max` | mapped (4% coverage) |
| Session duration | raw | minutes | per-session | none | `exercise_sessions.duration_min` | mapped |
| Distance | raw | km | per-session | none | `exercise_sessions.distance_km` | mapped |
| Pace | derived | min/km | per-session | distance/duration | derived from `distance_km`+`duration_min` when `avg_speed_kmh` absent (all 19 manual runs) | mapped |
| Speed | raw | km/h | per-session (HC), derived (manual) | vendor / distance÷duration | `exercise_sessions.avg_speed_kmh` | mapped |
| Power | raw | watts | per-session | vendor | `exercise_sessions.avg_power_w` | **schema exists, 0/6,558 populated** |
| Cadence | raw | steps or rpm /min | per-session | vendor | not stored | gap |
| Elevation gain | raw | meters | per-session | vendor | `exercise_sessions.elevation_gain_m` | mapped (sparse — 26% on runs, 19% on rides) |
| RPE | raw | 0–10 | per-session | user-entered | `exercise_sessions.rpe` | mapped (very sparse outside manual runs) |
| RIR | raw | reps | per-set | user-entered | not stored | gap (see DAV-54 doc) |
| Sets/reps/load | raw | reps, kg | per-set | none | `exercise_sessions.details.exercises[].sets[]` (jsonb) | mapped, edit-only (see DAV-54 doc) |
| Sleep duration | raw | hours | daily | vendor | `sleep_daily.hours` | mapped (100%) |
| Sleep stages (deep/rem/light) | raw | minutes | daily | vendor | `sleep_daily.deep_min`/`rem_min`/`light_min` | mapped (88–94%) |
| Respiratory rate (sleep) | raw | breaths/min | daily | vendor | `sleep_daily.respiratory_rate` | mapped (16% coverage) |
| Sleep regularity index (SRI) | modeled | 0–100 | rolling | `SriEvaluation.kt`, needs consecutive nights | computed from `sleep_daily.bedtime`/`wake_time` | mapped |
| Body weight | raw | kg | daily (intermittent) | none | `body_metrics.weight_kg` | mapped (100% of logged rows) |
| Body weight EMA | modeled | kg | daily | cadence-adaptive EMA (`BodyCompositionEvaluation.kt`) | computed from `body_metrics.weight_kg` | mapped |
| Body fat % | raw | percent | intermittent | none, gated on ≥30-day-apart readings (LSC) | `body_metrics.body_fat_pct` | mapped (39% coverage) |
| Lean mass | raw | kg | intermittent | none | `body_metrics.lean_mass_kg` | mapped (3% coverage) |
| Macros (protein/carbs/fat/fiber/sugar/sodium) | raw | grams (mg for sodium) | per-meal | none | `meals.*` | mapped |
| Hydration | raw | ml | daily | sum | `hydration_daily.ml` | mapped |
| Wellbeing (energy/mood/stress/soreness) | raw | 0–10 | daily | none | `wellbeing_daily.*` | mapped |
| Training load (CTL/ATL/TSB) | modeled | arbitrary load units | daily, EWMA | Banister two-compartment (`TrainingLoadEvaluation.kt`) | computed from `exercise_sessions` | mapped, Android-only (web dashboard still runs ACWR — see Core principle above) |
| TRIMP | modeled | arbitrary | per-session | HR-reserve weighted | computed from `avg_hr` + `wearable_daily.rhr` (+ max HR) | **spec'd in DAV-55 doc, not yet implemented** |
| Threshold-relative intensity | modeled | percent of threshold | per-session | — | needs a threshold profile that doesn't exist yet | gap — [DAV-126](https://linear.app/biodashboard/issue/DAV-126) |
| sRPE load | derived | RPE·minutes | per-session | rpe × duration | `exercise_sessions.rpe` × `duration_min` | mapped (fallback model, see DAV-55 doc) |
| Lab marker (generic) | raw | marker-specific | per-draw | none | `lab_results.marker_name`/`value`/`unit`/`ref_low`/`ref_high`/`flag` | mapped — per-marker themes already exist in `domain/LabMarkerThemes.kt`, not re-enumerated here |
| Injury/illness severity | raw | 0–10 | event-scoped | none | `injuries.severity` / `illnesses.severity` | mapped |

## Explicit gaps (not solved by this registry, tracked separately)

- **RIR** — no column anywhere; proposed as an additive jsonb field in the DAV-54 strength schema, not a new table.
- **Per-user threshold profile** (threshold HR/pace, FTP) — [DAV-126](https://linear.app/biodashboard/issue/DAV-126).
- **Per-session zone-minute exposure** — only a sparse *daily* aggregate exists (`wearable_daily.zone_minutes`, 3% coverage); no per-session breakdown is derivable from current data.
- **Cross-table source/provenance column** outside `exercise_sessions` — [DAV-124](https://linear.app/biodashboard/issue/DAV-124).
- **Cadence** — not stored anywhere (running cadence, cycling RPM); no known ingestion path yet.

## Versioning

This is v1.0.0. Bump the minor version when adding a canonical metric; bump the patch version for
wording/source-mapping corrections that don't change the contract's shape; bump the major version
only if an existing canonical name's meaning or unit changes (which should be rare and needs a
migration note for anything already computed/cached under the old meaning).
