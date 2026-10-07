# 23 — Percentile bands, rank semantics and comparison states

**Linear:** [DAV-199](https://linear.app/biodashboard/issue/DAV-199)

## Bands are metadata over a real percentile, never a replacement

`domain/comparison/ComparisonBands.kt`'s `percentileBand()` maps a raw percentile into one of five
descriptive bands (top-decile/above-average/average/below-average/bottom-decile) — a caller that
wants the exact number still reads `ComparisonResult.percentile` directly; the band is a rendering
convenience layered on top, never a substitute, per the ticket's own explicit acceptance criterion.

## Every `ComparisonState` renders real text

`presentComparison()` gives all six `ComparisonState` values (defined in doc 19) a real label —
`NO_REFERENCE`, `INSUFFICIENT_DATA`, `LOW_CONFIDENCE`, `NON_COMPARABLE`, `STALE_REFERENCE` each say
what's actually true, matching this session's own established convention (the TRAIL card's
not-ready messaging, the STEPS card's honest omission of a metric with no evaluation function) —
never a hidden row.

## A band only ever exists for a fully `OK`, monotonic comparison

Two real gates before `presentComparison()` computes a band at all:

- `state != OK` → no band. A low-confidence or non-comparable result cannot produce a falsely
  precise ranking, per the ticket's own acceptance criterion.
- `directionality ∈ {OPTIMAL_RANGE, TARGET_VALUE}` → no band, even when `state == OK`. There is no
  real per-metric optimum threshold stored anywhere in this app yet (e.g. sleep duration's actual
  ~7-9h physiological optimum) — inventing one now to force a band would be exactly the kind of
  fabricated number this project's own discipline (every trail formula cited a real source) rules
  out. This is a real, named gap for a future ticket once an optimal-range table exists per metric,
  not solved by guessing here.

## Verification

`ComparisonBandsTest.kt`: band thresholds at each boundary (95/60/50/15/5), `OK` state produces a
band, every non-`OK` state produces `null`, `OPTIMAL_RANGE`/`TARGET_VALUE` never produce a band even
when `OK`, and every one of the six `ComparisonState` values has non-blank label text.
