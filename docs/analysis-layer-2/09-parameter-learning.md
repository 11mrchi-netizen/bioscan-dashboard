# 09 — Individualized gain and time-constant learning

**Linear:** [DAV-63](https://linear.app/biodashboard/issue/DAV-63) · **Status:** design only — gated on data volumes this account doesn't have yet, per [08-backtest-results.md](08-backtest-results.md)

## Why this is gated, not just designed

[08-backtest-results.md](08-backtest-results.md) found that at this account's real data volume, load
vs. HRV/RHR correlations are indistinguishable from zero (r=−0.03 to 0.07, n=250-390) and the one
moderate correlation found (load vs. soreness, r=0.29) has only 19 real data points behind it. Fitting
a per-user parameter means finding the value that best explains a real outcome — if the outcome signal
is that weak or that sparse, "fitting" it doesn't recover a true personal parameter, it fits noise and
reports it with false confidence. Every safeguard below exists because of that finding, not as generic
caution.

## Parameters in scope, by dimension

| Parameter | Current default | Dimension(s) |
|---|---|---|
| Fatigue tau | 7 days (`ATL_TAU_DAYS`) | Endurance (only dimension with a real tau today) |
| Adaptation/capacity tau | 42 days (`CTL_TAU_DAYS`) | Endurance |
| Response gain | Implicit 1.0 (no gain term exists yet) | All — see below |
| Confidence/credible range | N/A — not yet attached to any learned value | All |

Strength and regional dimensions (`WorkoutLoadVector.kt`) have no tau at all yet — `dualEwma()` is
generic and ready for them, but there's nothing to *fit* a strength-specific tau against until
DAV-54's structured-data gap (1 real row, per [07-performance-anchors.md](07-performance-anchors.md))
closes. This doc specs the mechanism for when that's true; it doesn't invent numbers for a dimension
with one real data point.

**Response gain** doesn't exist in the codebase today — `TrainingLoadEvaluation.kt` reports TSB as a
raw number banded into descriptive labels ("Loaded"/"Freshened"), never scaled against a personal
outcome. Adding a gain term (e.g., "this much TSB actually predicts this much next-day
soreness/performance change, for this person") is exactly the thing DAV-62 found no basis for yet.

## Minimum data volume — set from what DAV-62 actually measured, not a round number

Rather than pick an arbitrary threshold, use the real coverage the backtest already has as the
calibration point:

- **1,131 days** of ATL/sleep-hours pairs produced r=0.119 — a real but weak signal. That's the
  largest real n this account has for any outcome pairing, and it still isn't enough to act on.
- **19 days** of soreness data produced a stronger-looking but untrustworthy r=0.294.

Proposed gate: **do not attempt a per-user tau or gain fit for a dimension/outcome pair with fewer
than 180 days of overlapping load+outcome data** (roughly 6 months) — chosen because it's the shortest
window where this account's own strongest real association (sleep, n=1,131 over ~3 years) would still
have had enough points (~180) to be more than a handful of weeks. Below that, report the documented
literature default (Banister 7/42, in this case) with a `Confidence(n, 180)` exactly matching this
project's existing `EvalState.kt` contract — reusing that contract rather than inventing a second
uncertainty representation for this one feature.

## Update cadence

**Quarterly re-fit, not continuous.** Continuous online fitting on data this sparse would let a single
noisy week move a parameter that's supposed to represent a stable personal trait. A quarterly cadence
also keeps every evaluation between re-fits reproducible against one fixed parameter set (see
versioning below), rather than silently drifting day to day.

## Overfitting safeguards

- **Regularize toward the documented default, don't fit freely.** A learned tau should be reported as
  a shrinkage from the Banister default toward the account's own data, weighted by how much data
  exists — e.g., a simple empirical-Bayes-style blend (`n / (n + k)` weight toward the fitted value,
  `k` tuned conservatively) rather than an unconstrained least-squares fit that can wander arbitrarily
  far on 200 noisy points.
- **Hold out data the fit didn't see.** Fit on the first 70% of a user's history, check the fitted
  parameter actually predicts the held-out 30% better than the literature default does — if it
  doesn't, keep the default. Never report a fitted parameter that wasn't checked against data it
  didn't train on, given how easily 08's own small-n correlations could be overfit artifacts.
- **A fit that doesn't beat the default is not shipped** — the default stays active and the attempt is
  logged, not silently applied anyway.

## Confidence / credible range

Every learned parameter carries the same `Confidence(current, gate)` shape every other evaluation in
this codebase already returns (`EvalState.kt`) — `current` = real overlapping data points used,
`gate` = the 180-day threshold above. A learned tau below the confidence gate is never surfaced as "the
user's personal tau" — the evaluation keeps reporting the literature default and states why (same
`Building` state semantics every other under-confidence evaluation in this app already uses, e.g.
`TrainingLoadEvaluation`'s own `CTL_GATE_DAYS` gate).

## Versioning and reproducibility

A learned-parameter change must be a new **versioned, timestamped row**, never an in-place update —
proposed shape:

```
model_parameters(id, dimension, parameter_name, value, fitted_at, data_points_used, source_default)
```

Every evaluation result should record which `model_parameters` version produced it (same principle
[06-output-contract.md](06-output-contract.md) already establishes for confidence/provenance) — so a
TSB value computed in January with that month's fitted tau stays reproducible even after a later
re-fit changes the live parameter. Never overwrite a historical row's inputs; append a new version and
let evaluations reference the version active at evaluation time.
