# 20 — Metric normalization and comparison directionality

**Linear:** [DAV-195](https://linear.app/biodashboard/issue/DAV-195)

## Scope, and why it's small

Doc 02 (DAV-53) already fixes one canonical unit per metric — there is no unit-conversion work
left for this ticket to do. What's left is exactly directionality: `domain/comparison/
MetricDirectionality.kt`'s `METRIC_DIRECTIONALITY` map, keyed by doc 02's own canonical names, and
one pure `normalize(rawValue, directionality): Double` transform (sign-flip for `LOWER_BETTER`,
identity otherwise) so every downstream ranking calculation can treat "higher normalized = better"
uniformly without re-deriving direction per metric.

Directionality is assigned by hand, never inferred from a metric's name — per DAV-195's own
explicit rule. Two calls worth recording:

- **Sleep duration is `OPTIMAL_RANGE`, not `HIGHER_BETTER`.** Real sleep-medicine convention: ~7-9h
  is the physiological optimum, not "more is always better."
- **Body weight is `NON_DIRECTIONAL`.** No goal weight is stored anywhere in `body_metrics` (or
  anywhere else in this schema) — `TARGET_VALUE` would need one to mean anything.

A metric absent from the map is a real gap (unsupported for comparison), not a silent default —
`ComparisonBands.kt` (doc 23/DAV-199) reads a missing key as `NO_REFERENCE`-adjacent, never guesses.

## Verification

Hand-fixture test (`MetricDirectionalityTest.kt`): `normalize()` sign-flips `resting_heart_rate`
(LOWER_BETTER) and passes `hrv` (HIGHER_BETTER) through unchanged.
