# 03 — Strength-session data model and ingestion gaps

**Linear:** [DAV-54](https://linear.app/biodashboard/issue/DAV-54) · **Migration required:** none

## Current state

`exercise_sessions` has 342 real `type='strength'` rows, all `source='health_connect'`. Health
Connect's `ExerciseSessionRecord` only exposes session-level bounds (type, start/end, title,
optional route) — confirmed against the real linked AAR, not assumed from docs
(`HealthConnectManager.kt:29-41`'s established discipline for this app). It cannot carry per-set
structured data, so 341 of the 342 rows have an empty `details` jsonb (`'{}'`).

The one exception (row id 10252, 2026-09-17) has a fully structured payload:

```json
{"exercises":[
  {"name":"Standing Military Press","sets":[{"rpe":5,"reps":5,"weight_kg":45,"percent_1rm":80}, ...]},
  {"name":"Front Barbell Squat","sets":[...]},
  {"name":"Weighted Pull Ups","sets":[...]}
]}
```

**Confirmed with the user**: this was added through the app's existing Log-tab edit flow
(`ui/screens/AddEntrySheet.kt`'s `ExerciseEditor`, wired via
`AddEntryRepository.updateExerciseDetails()`) *after* Health Connect had already imported the
session — not a new or unexplained ingestion path, and not a bug. This is exactly the feature that
shipped 2026-09-16 (`ROADMAP.md:2246-2269`): tap an HC-synced strength session → EDIT → fill in
sets/reps/weight/RPE/%1RM → save. That shipped feature already implements most of what this ticket
asks to design; this document formalizes its schema as the canonical one rather than proposing a
new shape, and scopes the two real gaps it left open (exercise identity, RIR).

## Decision: keep structured strength data edit-only

Manual creation of a brand-new exercise session (one that never touched Health Connect) was
deliberately removed on 2026-09-15 (`ROADMAP.md:2250`). Reinstating it was considered explicitly
for this ticket and **rejected** — the existing edit-only flow stays as is. A structured strength
session can only exist by editing a session Health Connect already created; there is currently no
way to log an entirely new strength session with sets that never passed through Health Connect.
(This means: if HC hasn't synced a workout yet, or misses it entirely, there is no manual fallback
for entering it with structured sets — accepted as-is, not a gap to close here.)

## Canonical schema

Formalizing the already-implemented shape as the strength-session schema (no new table, no
migration — it's the existing `exercise_sessions.details` jsonb column):

```json
{
  "exercises": [
    {
      "name": "string, required",
      "exercise_id": "string, optional — new field, see below",
      "sets": [
        {
          "reps": "int, required",
          "weight_kg": "number, required",
          "rpe": "number 0-10, optional",
          "rir": "int, optional — new field, see below",
          "percent_1rm": "number, optional",
          "rest_sec": "int, optional — new field, see below"
        }
      ]
    }
  ]
}
```

Matches `StrengthSetDto` (`data/model/ExerciseSessionModels.kt:70-75`) plus three additive,
optional fields. All three are new jsonb keys — none require a schema migration.

### `exercise_id` (new)

Links a logged exercise to `exercise_library.id` (876 real rows: name, category, force, level,
mechanic, equipment, primary/secondary muscles). Today, exercises are logged by free-text `name`
only — no linkage exists. `ROADMAP.md:2242-2244` already flagged this exact gap as deferred future
work ("fuzzy-matching a logged free-text exercise name against this library's canonical names").

Proposal: extend `ExerciseEditor`'s exercise-name field with a fuzzy-match picker against
`exercise_library.name` (client-side substring/fuzzy match against 876 rows is cheap — no search
index needed). When a confident match exists, store both `name` (as typed, preserved verbatim)
and `exercise_id`; when no match is confident enough, store `name` only and leave `exercise_id`
absent — never force a match. This unlocks queries like "every session that included Front Barbell
Squat" and category/muscle-group rollups later, without this ticket needing to build those
consumers now.

### `rir` (new)

Mirrors `rpe` — optional per-set integer. No schema change; purely an additive jsonb key in the
existing `ExerciseEditor` sets form.

### `rest_sec` (new, placeholder)

Optional per-set integer, seconds of rest before the set. **Explicitly unavailable from any current
ingestion path** — Health Connect doesn't expose it, and there's no reason a user would type rest
duration manually for every set. Included in the schema now (per this ticket's own instruction to
separate "unavailable data" from "missing ingestion") so a future source (e.g. a wearable that
tracks rest via HR return-to-baseline, or a future Amazfit-side strength app) can populate it
without a schema change. Not wired into `ExerciseEditor`'s UI in this proposal — schema-only.

### Velocity — explicitly excluded

Not added to the schema at all, unlike `rest_sec`. There is no current or foreseeable ingestion
path (no velocity-based-training device integration exists or is planned) — adding a field that
will permanently read `null` is worse than omitting it; it invites code to branch on a case that
can never occur. Revisit only if a VBT device integration is ever scoped.

## Compatibility

No changes to any top-level `exercise_sessions` column. `details` stays a free-form jsonb; non-
strength `type` values (run/ride/walk/hike/yoga/other) are entirely unaffected — they already use
`details` for their own unrelated keys (`route_type`, `run_type`).

## Migration scope

**None required.** Every change in this proposal is an additive jsonb key populated through the
existing edit UI — no `ALTER TABLE`, no new table, no backfill. If `exercise_id` linkage later
needs a fast reverse lookup (e.g. "all sessions containing exercise X" without scanning jsonb), a
`GIN` index on `details` would be a small, separately-scoped follow-up — not needed for the schema
itself to work.
