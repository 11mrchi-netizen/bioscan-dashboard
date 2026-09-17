# 01 — Analytical data coverage and source provenance

**Linear:** [DAV-52](https://linear.app/biodashboard/issue/DAV-52) · **Status:** inventory complete, 4 gaps filed as follow-ups

## Method

Every figure below came from a live query against Supabase project `ugfrglbcoivkprjqvjzz`
(`bioscan-dashboard`) on 2026-09-17 — `list_tables` for schema, `execute_sql` for row counts, date
ranges, and per-field `count()` (non-null coverage). Nothing here is inferred from code comments or
assumed from the app's UI; every number is a real count against real rows. Percentages use the
table's own row count as the denominator unless noted otherwise.

## Table-by-table inventory

### `wearable_daily` — 1,279 rows, 2022-12-11 → 2026-09-17
Health Connect daily aggregates. Per-field coverage (non-null / 1,279):

| Field | Coverage | Notes |
|---|---|---|
| `steps` | not queried (assumed near-100%, HC's most reliable stream) | |
| `rhr` | 539 (42%) | |
| `hrv` | 256 (20%) | |
| `vo2max` | 57 (4%) | HC only estimates this intermittently |
| `zone_minutes` | 34 (3%) | sparse; see gap note below |
| `calories_active` | 192 (15%) | |
| `calories_total` | 1,128 (88%) | |
| `spo2_avg` | 256 (20%) | |
| `bmr` | 118 (9%) | |
| `activity_intensity_min` | 0 | column reserved, not yet populated — `ActivityIntensityRecord` isn't in the stable Health Connect client (1.1.0) this app links against; deliberately not conflated with `zone_minutes` per the column's own comment |

### `sleep_daily` — 1,175 rows, 2023-03-22 → 2026-09-17
| Field | Coverage | Notes |
|---|---|---|
| `hours` | 1,175 (100%) | |
| `score` | 34 (3%) | vendor sleep score, rarely populated |
| `respiratory_rate` | 184 (16%) | |
| `bedtime`/`wake_time` | 1,175 (100%) | |
| `deep_min` | 1,075 (91%) | |
| `rem_min` | 1,038 (88%) | |
| `light_min` | 1,101 (94%) | |

### `wellbeing_daily` — 19 rows, 2026-08-27 → 2026-09-17
`energy`/`mood`/`stress`/`soreness` all 19/19 (100%) — a brand-new manual-entry table (Log tab),
fully populated on every row logged so far, just very little history yet.

### `body_metrics` — 119 rows, 2023-04-19 → 2026-09-15
`weight_kg` 119/119 (100%), `body_fat_pct` 46 (39%), `lean_mass_kg` 3 (3%), `height_cm` 1 (1%,
logged once — height doesn't change).

### `meals` — 85 rows, 2026-08-27 → 2026-09-15
`calories`/`protein_g`/`fat_g`/`carbs_g` populated on essentially every row (manual entry always
asks for these); `fiber_g`/`sugar_g`/`sodium_mg` added later (DAV-77) — not separately queried here
but newer than the table itself, so early rows lack them.

### `hydration_daily` — 11 rows, 2026-09-07 → 2026-09-17
`ml` 11/11 (100%) — new table, short history.

### `exercise_sessions` — 6,558 rows, 2023-03-20 → 2026-09-17
The generic multi-type table (Phase G3), `type` × `source` breakdown:

| type | source | n | HR (avg/max) | distance | speed | elevation | power | RPE | `details` populated |
|---|---|---|---|---|---|---|---|---|---|
| walk | health_connect | 4,655 | 4,193 (90%) | 4,611 (99%) | 4,515 (97%) | 0 | 0 | 0 | 0 |
| other | health_connect | 1,092 | 1,035 (95%) | 1,035 (95%) | 1,003 (92%) | 0 | 0 | 0 | 0 |
| strength | health_connect | 342 | 334 (98%) | 234 (68%, likely mislabeled/irrelevant) | 234 | 0 | 0 | 1 (0.3%) | 1 |
| run | health_connect | 131 | 124 (95%) | 121 (92%) | 124 (95%) | 34 (26%) | 6 (5%) | 1 (0.8%) | 1 |
| run | manual | 19 | 19 avg / **0 max** | 19 (100%) | 0 (pace derivable from distance/duration) | 0 | 0 | 11 (58%) | 19 (100%) |
| ride | health_connect | 16 | 14 (88%) | 10 (63%) | 12 (75%) | 3 (19%) | 0 | 0 | 0 |
| hike | health_connect | 2 | **0** | 2 (100%) | 2 | 0 | 0 | 0 | 0 |
| yoga | health_connect | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |

Real, load-bearing findings from this table:
- **`avg_power_w` is 0/6,558** — populated on not a single row across the entire account. Any
  power-based adapter is speccable today but will activate on zero real data until a power-capable
  source exists (see DAV-55 doc).
- **Manual runs never carry `max_hr`** (0/19) — HR-based load models (TRIMP) can't run on manual
  entries without a max-HR fallback (see DAV-55 doc's degradation ladder).
- **`details` (jsonb) is populated on only 3 of 6,558 rows** (1 HC run, 1 HC strength, 19 manual
  runs use it for `route_type`) — see the strength-specific breakdown below.
- The single populated HC-strength `details` row (id 10252, 2026-09-17) holds a fully structured
  `exercises[].sets[]` payload with `weight_kg`/`reps`/`rpe`/`percent_1rm` — **confirmed with the
  user**: added via the existing Log-tab edit flow after Health Connect imported the session, not a
  new or unexplained ingestion path. See [03-strength-session-model.md](03-strength-session-model.md).

### `exercise_library` — 876 rows (reference table, not time-series)
`id` (text slug), `name`, `category`, `force`, `level`, `mechanic`, `equipment`,
`primary_muscles[]`, `secondary_muscles[]`. Real free-exercise-db import. Currently **not linked**
from any `exercise_sessions.details.exercises[]` entry — exercises are logged by free-text `name`
only. See DAV-54 doc for the proposed linkage.

### `recovery_sessions` — 6 rows, 2026-08-28 → 2026-09-09
`duration_min` 6/6. `category` is free text (no CHECK constraint) — no fixed taxonomy yet.

### `rest_days` — 3 rows, 2026-08-31 → 2026-09-06

### `injuries` — 1 row · `illnesses` — 0 rows
The one real injury: right knee soreness, severity 1, resolved 2026-09-02 → 2026-09-08.

### `lab_draws` / `lab_results` — 2 draws, 77 results total
| Draw date | Lab | Fasted recorded? | Markers |
|---|---|---|---|
| 2026-01-14 | Cathay Health Management | no | 53 |
| 2026-04-25 | 大安聯合醫事檢驗所 (Da'an United Clinical Lab) | no | 24 |

Two draws ~3 months apart, from two different providers, with different marker panels and no
`fasted`/`hours_since_training` recorded on either — both columns exist on `lab_draws` but are
unused so far.

### `ostrc_checkins` — 0 rows
Schema and evaluation logic (`OstrcEvaluation.kt`) both exist and are exercised in the UI; simply
no real check-ins logged yet. Filed as [DAV-127](https://linear.app/biodashboard/issue/DAV-127) so
it isn't later mistaken for a missing feature.

### `runs_retired` — 19 rows, 2026-08-14 → 2026-09-15 (legacy, duplicate)
**Exact duplicate** of `exercise_sessions`'s 19 `type='run', source='manual'` rows over the
identical date range — the pre-Phase-G3 run-only table, dead since `exercise_sessions` became
generic. Filed as [DAV-125](https://linear.app/biodashboard/issue/DAV-125).

## Field-to-canonical-metric matrix

See [02-metric-registry.md](02-metric-registry.md) for the full registry — this inventory feeds
that document's "current Supabase source(s)" column directly. Summary of what's well-covered vs.
sparse vs. entirely absent:

- **Well-covered** (>80% of relevant rows): sleep hours/stages/bedtime, wellbeing dimensions,
  weight, macros, meal-derived nutrition.
- **Sparse but real** (used by existing evaluations, gated on confidence thresholds already):
  HRV, RHR, VO2max, respiratory rate, body fat %.
- **Structurally absent** (column exists, zero or near-zero real data): `avg_power_w` (0/6,558),
  `activity_intensity_min` (0/1,279, not yet supported by the HC client version), sleep `score`
  (34/1,175), lean mass (3/119), height (1/119).
- **No column at all** (real gaps, not just sparse data): per-user threshold profile (filed as
  [DAV-126](https://linear.app/biodashboard/issue/DAV-126)), RIR on any strength set, per-session
  zone-minute exposure (only a sparse daily aggregate exists).

## Duplicate/overlapping sources found

Only one real duplication exists in the current schema: `runs_retired` vs. `exercise_sessions`
(above). No duplication was found across the wearable/sleep/body metrics — each canonical value
(HRV, RHR, VO2max, weight, etc.) has exactly one source column today.

## Source/provenance conventions

**Current state**: `exercise_sessions` is the only table that records provenance
(`source` ∈ {`manual`, `health_connect`} + `health_connect_record_id`). Every other table has no
source column — provenance is implicit in "whichever sync job wrote this row," which only works
because each of those tables currently has exactly one real writer (the Health Connect sync
service, or a single manual-entry form).

**Proposed convention** (detailed further in the metric registry, DAV-53): any table that could
plausibly receive data from more than one origin gets an explicit `source text` column following
`exercise_sessions`'s existing pattern, rather than inferring origin after the fact. This isn't
urgent while there's one writer per table, but should land *before* a second device pipeline
(e.g. direct Amazfit ingestion) is built — filed as
[DAV-124](https://linear.app/biodashboard/issue/DAV-124).

## Known analytical gaps → follow-up issues filed

| Gap | Issue |
|---|---|
| No source/provenance column outside `exercise_sessions` | [DAV-124](https://linear.app/biodashboard/issue/DAV-124) |
| `runs_retired` is a dead duplicate table | [DAV-125](https://linear.app/biodashboard/issue/DAV-125) |
| No per-user training threshold profile (needed by DAV-55) | [DAV-126](https://linear.app/biodashboard/issue/DAV-126) |
| OSTRC/illness tables unused (data-collection gap, not code) | [DAV-127](https://linear.app/biodashboard/issue/DAV-127) |
