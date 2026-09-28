# 25 — Population benchmark registry and comparison engine

**Linear:** [DAV-193](https://linear.app/biodashboard/issue/DAV-193) · [DAV-196](https://linear.app/biodashboard/issue/DAV-196) · **Status:** schema + engine live, first real artifact seeded (steps)

## Artifacts, not raw datasets

Per DAV-193's own "Implementation model," `benchmark_artifacts` (new Supabase table, own-schema
migration `create_benchmark_artifacts`) stores **versioned artifacts** — published quantile tables,
reference curves, or reference equations with coefficients and an applicability domain — never a
replica of the underlying raw participant-level dataset. Read-only from the app (RLS: any
authenticated user can `select`, nothing else), seeded by migration/research, the same precedent
`exercise_library`'s own 876 rows already established. Every row carries source tier (A–D per
DAV-193's own framework), population definition (sampling frame/geography/age band/sex), protocol,
sample size, an explicit `supports_percentile` flag, and version/supersession fields so a refreshed
source never rewrites a past comparison's own recorded artifact version.

## Resolution: most specific wins, never guesses across metrics

`domain/comparison/PopulationComparison.kt`'s `resolveArtifact()` scores each candidate artifact for
the same `metric` by how well its population (age band, sex, geography) matches the observation's
own context, per DAV-196's own resolution strategy. No match at all → `null`, surfaced as
`ComparisonState.NO_REFERENCE` — never a silently wrong reference.

## Percentile interpolation is conservative, never extrapolated

`interpolatePercentile()` linearly interpolates between an artifact's own published quantile points.
A value at or beyond the table's own min/max clamps to that boundary percentile rather than
projecting past what the source actually measured — DAV-196's own explicit rule.

## Not every real reference is percentile-shaped

Found while sourcing the first real artifact: Tudor-Locke & Bassett (2004, *Sports Medicine*
34(1):1-8, "How Many Steps/Day Are Enough? Preliminary Pedometer Indices for Public Health") defines
five well-known, widely-cited step-count categories for healthy adults — sedentary (<5,000),
low active (5,000-7,499), somewhat active (7,500-9,999), active (10,000-12,499), highly active
(≥12,500) — a synthesized expert threshold scheme, not a percentile distribution from one sampled
population. DAV-193 itself frames steps this way ("WHO global estimates for worldwide prevalence
rather than individual percentile ranking").

Forcing this into a percentile would either fabricate a number the source doesn't support, or (an
earlier draft of this engine) demote it to `LOW_CONFIDENCE`, which mischaracterizes a perfectly
good categorical reference as deficient. `ComparisonResult` gained a `bandLabel: String?` field
instead — populated by `matchThresholdBand()` when an artifact defines named bands, with `state = OK`
and full confidence, `percentile` staying honestly null. Seeded as `artifact_type = 'threshold_range'`,
`source_tier = 'D'` (a guideline/reference range, per DAV-193's own tier-D definition — "stored
separately from population percentiles... never relabeled as population rank"), `supports_percentile
= false`.

## Sequenced by this account's real data, not DAV-193's generic priority order

DAV-193 ranks VO₂peak first for benchmarking generally; this account's own coverage
(`docs/analysis-layer-2/02-metric-registry.md`) is nearly inverted (steps: 1,128 real days; VO2max:
4%). Steps shipped first as the highest-value pairing of tractable source + real personal data;
resting heart rate, HRV (Nunan et al. 2010's age-banded meta-analysis), and VO₂peak (FRIEND/
FRIEND-I) are queued next, each needing its own real-source research pass before encoding anything.

## UI

`ComparisonStrip` (doc 24) now takes independent optional `personal`/`population` params — a metric
can have either, both, or neither wired, rendering only what's real. First wiring: the Health tab's
STEPS card shows today's count against the Tudor-Locke category.

## Verification

Hand-fixture tests (`PopulationComparisonTest.kt`) use a small synthetic artifact (p10=100,
p50=200, p90=300) for exact-by-hand interpolation checks, plus the real Tudor-Locke band boundaries
for `matchThresholdBand()`. `./gradlew compileDebugKotlin testDebugUnitTest` green. Live phone check
pending: open the Health tab's Cardio sub-tab, confirm the STEPS card shows a real category label.
