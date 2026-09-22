# 17 — Finalized nutrition write-back to Health Connect

**Linear:** [DAV-170](https://linear.app/biodashboard/issue/DAV-170) · **Status:** live, verified end-to-end on-device

## Overview

DAV-170 turned out to be a small, surgical extension rather than new architecture: this app already
had a real, working, scheduled write-back mechanism (`data/HealthConnectWriteBackRepository.kt`,
DAV-154) that reads `meals`/`hydration_daily` and writes `NutritionRecord`/`HydrationRecord` to
Health Connect on every app-open via `HealthConnectSyncWorker`. It just hadn't been extended to read
the newer canonical fields DAV-161/164/181 added. This ticket's whole job was that extension:
`meals.fiber_g/sugar_g/sodium_mg` (already populated by `NutritionMealSaveRepository`, just not
selected before) and `meal_items.caffeine_mg`/`water_ml` (which the flat `meals` columns never
carried at all).

## Why not build a new idempotency mechanism

The plan going in assumed `Metadata.manualEntryWithId(clientRecordId)` would provide real
per-record idempotency (store a client-chosen ID, let Health Connect dedupe on it). A comment
already in this codebase (`healthconnect/OneOffNutritionHydrationBackfill.kt`, written during
earlier real on-device testing) says otherwise: *"Real on-device testing showed Health Connect does
NOT honor clientRecordId matching the way it's documented, for either insert-time upsert or a
delete-by-clientRecordId call — both were tried and both left real duplicate records behind."*

`HealthConnectWriteBackRepository` was already built around the actually-reliable alternative: read
every existing record **this app's own package** wrote in the target date range
(`metadata.dataOrigin.packageName == context.packageName` — the platform's own real IDs, not a
client-chosen string), delete all of them, then reinsert fresh from current Supabase state. This is
what makes "re-running sync does not create duplicates," "editing follows a defined update/delete
strategy," and "a deleted meal's HC record disappears" all true simultaneously, without any new
schema or reconciliation table: a meal that no longer exists in Supabase (edited or deleted) simply
isn't in the "reinsert" set on the next run, and its old HC record was already wiped by the
unconditional delete step.

## What changed

In `executeWriteBack`:

1. `WriteBackMealRow` now also selects `fiber_g`/`sugar_g`/`sodium_mg` (already on `meals`,
   unused by this file until now) → mapped onto `NutritionRecord.dietaryFiber`/`sugar`/`sodium`.
2. A new `meal_items` query (one call, `isIn("meal_id", ...)` against every meal already fetched in
   the range — not one query per meal) pulls `is_beverage`/`water_ml`/`caffeine_mg`.
3. Caffeine is summed per meal and mapped onto `NutritionRecord.caffeine` — confirmed via the real
   `connect-client` SDK source (extracted from the AAR, not assumed from docs) that `caffeine: Mass?`
   is a genuine constructor parameter.
4. Beverage `water_ml` is grouped by the meal's local calendar date and **added to**
   `hydration_daily`'s existing per-day total — not instead of it. A plain "500 ml water" log and a
   Coca-Cola logged with lunch are two separate real events, not duplicates of the same fact.
5. `effective_hydration_ml` (DAV-181's modeled estimate) is deliberately **not** written anywhere in
   this path — only `water_ml` (the directly-resolved fluid content) feeds `HydrationRecord.volume`.
   DAV-181's own rule ("effective hydration is a derived estimate, never presented as a measured
   physiological value") would be violated by writing it into a Health Connect record type whose
   whole convention *is* measured fluid volume.

## Verified end-to-end on real Health Connect

Logged "black coffee" (100ml, resolving to 40mg caffeine / 99.9ml water via the manual fixture from
doc 13) through the real review sheet, then triggered the write-back and read the actual records
back through Health Connect's own app UI (`android.health.connect.action.HEALTH_HOME_SETTINGS` →
Data and access):

- **Nutrition entry**: *"Name: black coffee · Caffeine: 0.04 g · Energy: 1 Cal"* — 0.04g = 40mg,
  exactly matching the resolved `meal_items.caffeine_mg`.
- **Hydration entry**: *"2 L"* for the full day — matches `1900ml` (that day's existing
  `hydration_daily` manual log) `+ 99.9ml` (the coffee's `water_ml`) `= 1999.9ml ≈ 2 L`, confirming
  the additive merge works correctly against real data, not just in isolation.

Test data (the logged meal) removed from Supabase afterward; its Health Connect record is cleaned up
automatically by the existing delete-and-reinsert step on the next scheduled write-back.

## Acceptance criteria, addressed

- **Finalized meal is written once / re-running doesn't duplicate**: the pre-existing
  dataOrigin-filtered delete-then-reinsert mechanism, unchanged by this ticket, already guaranteed
  this — now proven correct after adding caffeine/hydration fields too.
- **Never write preliminary AI estimates before confirmation**: structural — this file only ever
  reads `meals`/`meal_items`, which `NutritionMealSaveRepository` only populates post-review-and-save
  (DAV-168); `ai_estimates` (the pre-confirmation table) is never touched here.
- **Edit/delete/retry**: edits and deletes propagate on the next write-back run (within the 7-day
  `writeBackDaily` lookback) since the whole range is always re-derived from current Supabase state.
  Failures return `HealthConnectWriteBackResult.Failed`, surfaced via `HealthConnectSyncStatus` in
  Settings, and retried automatically by `HealthConnectSyncWorker` (WorkManager, up to 5 attempts).
- **Supabase remains canonical**: this file only reads from Supabase (plus one `sync_log` watermark
  write); a Health Connect failure never touches or rolls back a Supabase row.
