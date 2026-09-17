# 05 — Data-quality, missingness and confidence propagation

**Linear:** [DAV-66](https://linear.app/biodashboard/issue/DAV-66) · **Version:** 1.0.0 (2026-09-17)

## Why this exists

`domain/EvalState.kt` already gives every per-metric evaluation a `Confidence(have, need)`
("n of N") and one of six states, `NoData` included. That's the right primitive, but it only
answers *"how much history backs this?"* — it says nothing about *"how many of the signals this
value ideally needs were actually present?"*, and nothing about what happens to confidence once a
value gets folded into something else (a load vector combining five metrics, a rolling average
combining thirty days). This document formalizes both, so DAV-56 through DAV-63's real
implementations inherit one consistent rule instead of each inventing its own.

## Two axes of confidence, not one

**Depth** (already implemented): `Confidence(have, need)` — how much history backs a value. A
7-day HRV baseline with 3 real readings has low depth; with 7, full depth. This axis doesn't
change here.

**Breadth** (new): how many of a derived value's *ideal* inputs were actually available when it
was computed. The registry's own worked example makes this concrete: "HR + power + duration" is a
high-breadth cardiovascular estimate; "HR + duration" alone is the same *kind* of estimate at lower
breadth, not a different one. Represented as:

```
InputCompleteness(present: Set<String>, ideal: Set<String>) {
    val tier: CompletenessTier  // FULL (present == ideal), PARTIAL (proper subset, non-empty), MINIMAL (present.size <= 1)
}
```

`present`/`ideal` name the actual input fields (e.g. `"avg_hr"`, `"avg_power_w"`, `"duration_min"`),
not abstract categories — so a reader can see exactly what was missing, not just a tier label.

## Value-kind sets the ceiling, breadth/depth set the position within it

DAV-53's four value kinds (raw, derived, modeled, inferred) already imply a coarse confidence
ceiling — a raw observation is never *less* trustworthy than a modeled value computed from it,
regardless of breadth/depth. Concretely: **inferred > modeled > derived in uncertainty, always**;
breadth and depth only move a value up or down within its own kind's band, never across into a
more-trusted kind's territory. A `FULL`-breadth, `10/10`-depth *inferred* max-HR estimate is still
an estimate, never presented alongside a real measured max-HR as if equivalent.

## Propagation rule: a derived value's confidence is bounded by its worst input

When a value folds in other values (the multidimensional load vector combining strength +
endurance + regional components; a rolling EWMA combining thirty days of daily states), its own
confidence must never exceed the *minimum* of its inputs' confidence — on both axes independently.
Concretely:

- `combined.depth.have = min(inputs.map { it.depth.have })`, `combined.depth.need = max(inputs.map { it.depth.need })` — a combined value is only as deep as its shallowest real contributor, and needs at least as much as its most demanding one.
- `combined.breadth.tier = min(inputs.map { it.breadth.tier })` (ordinal `MINIMAL < PARTIAL < FULL`) — one `MINIMAL`-breadth input caps the whole combination at `MINIMAL`, even if every other input is `FULL`.

This is a conservative floor, deliberately: it's better for a combined score to under-claim
confidence than to average away one weak contributor's real uncertainty. No implementation in
DAV-56 onward should compute a combined confidence any other way (e.g. averaging tiers) without a
documented, specific reason.

## Missingness is a state, never a value

A day with no HRV row is `NoData` for that day — never `0`, never silently excluded from a rolling
window's numerator while still counting toward its denominator (which would understate the true
average), never imputed. This is already this app's real, established behavior — `SriCard`
(`ui/screens/status/HeartTileScreen.kt`) shows *"Not enough consecutive nights yet"* rather than
fabricating a score when Sleep Regularity Index has too few consecutive nights; `wellbeing_daily`'s 19 real rows out of the account's ~1400-day history render as genuinely
absent days, not zeros, everywhere they're read. DAV-56 through DAV-63 continue this rule for every
new derived/modeled value: a gap in the input series produces a gap in the output series, at the
same resolution, never a filled-in guess.

**Sparse RPE, specifically** (the registry's own example): RPE present on 11 of 19 manual runs and
under 1% of Health-Connect-sourced sessions means sRPE-based load has real gaps most weeks. A
session with no RPE contributes no sRPE-load data point — not a population-average RPE, not a
carried-forward previous value.

## What downstream consumers see

Every derived/modeled value in the [output contract](06-output-contract.md) carries both axes
plus its value-kind, not just a single confidence number — so a UI or P7's future Zepp-metric
consumer can decide for itself whether `PARTIAL` breadth is acceptable for its purpose, rather than
that judgment being baked in and lost at computation time.

## Versioning

v1.0.0. A change to the propagation rule itself (the `min()` floor) is a major-version change,
since every already-computed combined value's stated confidence would need re-evaluating against
the new rule. Adding a new named input to an existing metric's `ideal` set (narrowing what counts
as `FULL` breadth) is a minor version change.
