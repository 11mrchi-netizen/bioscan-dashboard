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
