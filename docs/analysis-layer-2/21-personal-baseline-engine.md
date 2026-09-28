# 21 — Personal baseline and historical distribution engine

**Linear:** [DAV-194](https://linear.app/biodashboard/issue/DAV-194)

## Not new math — one shared implementation of an existing pattern

`domain/comparison/PersonalBaseline.kt`'s `personalBaseline(points, windowDays, asOf)` operates on
the exact `List<Pair<LocalDate, Double>>` shape every existing `evaluate*()` function in this
codebase already takes. `HrvRhrEvaluation.kt`'s 60-day mean/SWC window and `TrainingScreen.kt`'s
4-week average are two of roughly six places this windowed-stats pattern already exists,
independently hand-rolled per file. This ticket gives future comparison code one shared
implementation; it does not require migrating those existing evaluations (per doc 06's own "doesn't
require rewriting `domain/*Evaluation.kt`" rule) — they keep working as-is.

## Real gates, not a manufactured baseline

`MIN_OBSERVATIONS_FOR_BASELINE = 5`: fewer real points in the window returns `null`, never a
baseline computed from too little to mean anything — matches this app's "null means NoData, never
a disguised zero" convention used throughout `AnalysisLayer2Contract.kt`.

`MIN_OBSERVATIONS_FOR_STABLE_BASELINE = 14`: below this, `isStable = false` — the baseline still
computes (useful for display), but downstream comparison (DAV-197) should read this as lower
confidence rather than a fully trustworthy reference, per DAV-194's own "sparse or unstable history
lowers confidence instead of manufacturing a baseline" acceptance criterion.

## `max`/`min`, deliberately not `best`/`worst`

Whether a maximum or a minimum is the "good" extreme depends on the metric's directionality
(doc 20/DAV-195) — resting heart rate's *best* reading is its minimum, HRV's *best* is its maximum.
`PersonalBaseline` itself stays directionality-agnostic (plain descriptive `max`/`min`); the
comparison layer (DAV-197, doc 22) is what decides which one is "personal best" for a given metric.

## Percentiles: nearest-rank, not interpolated

`percentileOf()` uses the simplest defensible method (nearest-rank over an already-sorted list) —
matches this codebase's existing preference for the simplest correct algorithm over a more
elaborate one (e.g. `Stats.kt`'s own choice of Mann-Kendall over a fancier trend test). No
interpolation is needed at the observation counts real personal history reaches (tens to low
hundreds of points, not requiring sub-point precision).

## Verification

`PersonalBaselineTest.kt` — a 10-point arithmetic-sequence fixture (40..58 step 2, not real scraped
HRV floats) chosen specifically so mean/variance/percentiles are exact by hand: mean 49, population
variance 33.0, P25/50/75/90 = 44/48/52/56. Separately verifies the <5-observation null gate, the
14-observation stability gate, and that a point outside the window is excluded.
