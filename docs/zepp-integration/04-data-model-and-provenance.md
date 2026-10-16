# 04 — Canonical Zepp data model and provenance

**Linear:** DAV-113 · **Status:** written up against the real, current schema and code
(2026-09-30), correcting an earlier audit pass that missed one already-existing column

## Why this exists

DAV-113 asked for a normalized Zepp data model — source/platform/device fields, record
identifiers, timestamps/timezones, units/resolution, raw-vs-normalized separation, Zepp-native
metrics kept distinguishable from generic equivalents, and metric-definition versioning — without
losing manufacturer-specific semantics. An initial audit pass this session (an Explore agent, not
a direct schema check) concluded `wearable_daily` had "no source column at all." That was wrong —
checked directly against the live Supabase schema below, the column exists. This doc replaces
that audit with one verified against the real database and code, and states plainly what's still
a genuine gap versus what only looked like one.

## What already exists, verified directly

**Raw vs normalized/decoded — real, in `zepp_workout_detail`.** Separate `raw` (the untouched
API response) and `decoded` (structured per-second series + summary) jsonb columns
(`supabase/functions/zepp-extract/index.ts`'s `reconcileWorkout()`). Never blended.

**Record identifiers — real.** `zepp_workout_detail.zepp_track_id`/`zepp_source`;
`zepp_raw_extracts.track_id`. Both trace back to Zepp's own workout identifiers, not a
locally-generated id.

**Timestamps/timezones — real, and a documented bug fix.** `zepp_raw_extracts.fetched_at`, plus
`toLocalLabeledUtcIso()`/`FALLBACK_TIMEZONE` explicitly reproducing Health Connect's own
"local time, UTC-labeled" convention for Zepp rows (`index.ts`'s own comment explains the 8-hour
mismatch this fixed for a real account).

**Source discrimination — real, but unused.** `wearable_daily.source` is a real column:
`text NOT NULL DEFAULT 'health_connect'`. Every one of this account's 3,658 rows is correctly
`'health_connect'` today (verified via SQL) — the default is doing the right thing for the only
source that writes to this table right now. But **no Kotlin code reads or explicitly sets this
column** — no model class has a `source` field for `wearable_daily`, and
`HealthConnectDailySyncRepository.kt`'s upserts never mention it. This is a live landmine, not an
active bug: the day Zepp's daily endpoints (`training_load`, `vo2max`, `heart_rate`, `hrv`,
`sleep`, `spo2` — see `SourceCapabilityRegistry.kt`, all currently `BROKEN`) start returning real
data and something writes them into `wearable_daily`, that write **must** explicitly pass
`source = 'zepp'` or the silent default will mislabel it as Health Connect. Flagging this here so
whoever wires that write remembers — not fixing it now, since there's no real write path to fix
yet and adding one speculatively would be exactly the kind of gap-filling this project's
conventions avoid.

**Extractor versioning — real, but whole-extractor not per-metric.** `EXTRACTOR_VERSION`
(currently `"8"`) is stored per-row in `zepp_raw_extracts.extractor_version`, so every row is
traceable to the extractor code that produced it. It versions the whole function, not one metric's
definition independently — a real simplification versus DAV-113's "metric-definition/version
metadata" ask, acceptable because today's extractor changes (endpoint fixes, header fixes) have
so far always affected the whole function's request shape, never one metric's decode logic in
isolation.

## Run distance: Zepp's own, on every copy

Health Connect stored most runs from January to mid-August 2026 at about twice their real distance
(a 37.4 km "longest run" Zepp has at a little over 18 km; a 2:45/km "fastest run" that was a treadmill session).
The source app wrote some workouts, and their distance records, twice (some sessions are stored twice
with identical times), and the per-app rule (largest single app's total) cannot see duplicates from
one app. Fixes (`zepp-extract/workoutDistance.ts`, `domain/HealthConnectDistance.kt`):

- The Zepp sync writes Zepp's distance: the GPS track's, else the summary's. The summary distance was
  read from a `distance` key that sport_history entries do not have; they carry `dis` and
  `highPrecisionDistance` (metres). A treadmill run (no track) is now corrected too.
- The distance goes to every row of the same session (same type, start within two minutes), so a
  duplicated session can no longer keep a wrong copy.
- Health Connect ingestion counts each stretch of time once within one app, and a record reaching
  outside the workout only for its part inside.
- Settings › Connected services › RE-CHECK RUN HISTORY WITH ZEPP re-reads every workout back to the
  first run, 31 days per call: the one-off repair for the stored history. Runs Zepp does not have keep
  their Health Connect distance.

## Strength exercises: who owns the list

A strength workout's `exercise_sessions.details.exercises` is written by the Zepp import from the
workout's laps. A workout corrected in the Zepp app after the first sync (exercises not picked on
the watch) used to stay wrong here: every re-sync fetched the corrected detail but skipped the
write because "exercises already present" could not tell Zepp's own earlier import from an edit in
the app. Now (`zepp-extract/strengthImport.ts`, `domain/StrengthImport.kt`):

- `details.exercises_source` is `zepp` (written by the import) or `app` (exercises changed in the
  edit form); `details.zepp_fingerprint` is the fingerprint (exercises in order, each set's reps and
  weight) of the Zepp version the list is based on. Rows from before the markers: a list with an
  `exercise_code` on every exercise is Zepp's (the edit form used to drop the codes).
- A Zepp list is replaced on the next sync whenever Zepp's fingerprint changes. An app list is never
  overwritten by a sync.
- Every sync stores Zepp's current version and fingerprint in
  `zepp_workout_detail.decoded.strengthData` (`exercises`, `fingerprint`). When an app edit and
  Zepp's version differ, the session shows ZEPP HAS A DIFFERENT VERSION with USE ZEPP'S VERSION /
  KEEP MINE; KEEP MINE records the fingerprint seen, so it is asked again only if Zepp changes.
- The app re-syncs the last three days when it opens; RE-IMPORT FROM ZEPP on a strength session
  pulls the days around that workout for an older correction.
- The edit form carries the watch-measured fields (set duration, heart rate, detected reps, the
  exercise code unless renamed) through a save, and saving only RPE or notes keeps the list's
  source, so it keeps following Zepp.

## What's a genuine gap, left open

- **Units/resolution as queryable metadata.** Units are correct but live only in code comments
  (e.g. altitude in centimetres, `index.ts:114-118`) — nothing stores them as data. No current
  consumer branches on a stored unit, so this stays a documentation-only gap until one does.
- **Zepp-native metrics kept distinguishable from generic equivalents.** Currently moot:
  Zepp's own `training_load`/`vo2max` endpoints are `BROKEN` (`SourceCapabilityRegistry.kt`), so
  nothing has landed anywhere to distinguish from Health Connect's version yet. When they work,
  the fix is exactly the `wearable_daily.source` column above, used correctly — not a new
  mechanism.
- **A formal source-quality contract** (missingness, quality flags) beyond what's described here.
  Deferred until a second source actually writes daily metrics and a real reconciliation need
  appears — same reasoning DAV-305 already applied to Aging Profile's deferred scope.

## Related docs

- `01-auth-and-token-lifecycle.md`, `02-token-capture-and-data-extraction.md` — auth/endpoint
  reverse-engineering.
- `03-workout-detail-field-decode.md` — per-second field decoding for `detail.json`.
- `docs/analysis-layer-2/02-metric-registry.md` — the cross-source canonical metric registry this
  doc's units/coverage facts are drawn from.
