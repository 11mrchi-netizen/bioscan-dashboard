# 22 — Comparable sets and personal historical comparison

**Linear:** [DAV-198](https://linear.app/biodashboard/issue/DAV-198) · [DAV-197](https://linear.app/biodashboard/issue/DAV-197)

## Comparable sets: a filter, not a taxonomy

`domain/comparison/ComparableSet.kt`'s `comparableSet(history, isComparable)` is a generic
higher-order filter — what "comparable" means is metric-specific (a daily wearable reading has no
real context to restrict on; an exercise session has type, route type, distance). Rather than
building DAV-198's full listed eligibility taxonomy (sport/terrain/environmental/protocol/effort
band/measurement method) speculatively before any metric actually needs all of it, this ticket
ships the filter plus the one concrete rule this app's real data supports today:

`isComparableSessionDistance()` reuses `domain/trail/Durability.kt`'s own established comparability
rule (`COMPARABLE_LENGTH_RATIO_MAX = 2.0`) rather than inventing a second one — a 2km jog and a
marathon aren't comparable paces regardless of fitness, the same "not wildly different in scale"
principle trail durability already applies to climb/descent segment pairing.

Every comparable-set result reports `comparableCount`/`totalHistoryCount` per DAV-198's own
acceptance criterion — pairs directly with `PersonalBaseline.observationCount`.

## Personal comparison: wiring, not new arithmetic

`domain/comparison/PersonalComparison.kt`'s `comparePersonal()` takes a metric name, the current
value, and an already-comparable history (the caller applies `comparableSet()` first — this
function has no eligibility opinion of its own), and produces one `ComparisonResult`:

- No `Directionality` entry for the metric → `NO_REFERENCE` (a real gap, not a guess).
- Fewer than 5 comparable historical points → `INSUFFICIENT_DATA`, confidence `have/need` reads the
  real count against the real gate.
- Otherwise: percentile rank (inclusive — a value equal to every historical point reads 100th, not
  under), delta and standardized delta (`delta / stdDev`, undefined and left null when `stdDev` is
  zero), `state = OK` when the underlying baseline is stable (14+ points) else `LOW_CONFIDENCE` —
  never a fabricated full-confidence read from a thin history.

`personalBest()` is kept separate from `PersonalBaseline` itself (see doc 21) — it reads
`Directionality` to decide whether `max` or `min` is "best," returning `null` for
`OPTIMAL_RANGE`/`TARGET_VALUE`/`NON_DIRECTIONAL` metrics where neither extreme is meaningfully
"best" (sleep duration's max isn't better than its min; both can be equally wrong).

## Verification

`PersonalComparisonTest.kt` reuses `PersonalBaselineTest`'s exact 10-point fixture (mean 49,
stdDev √33) so the two hand-calculations stay consistent with each other: a `current = 60` reads
delta 11, standardized delta `11/√33`, 100th percentile (above all 10 historical points), and
`LOW_CONFIDENCE` (only 10 of the 14 needed for a stable baseline) — plus separate tests for the
no-directionality and sparse-history branches, `personalBest()`'s directionality handling, and
`isComparableSessionDistance()`'s 2x ratio gate.
