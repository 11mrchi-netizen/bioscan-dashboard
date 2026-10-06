# Training programming: schema and definition format (DAV-342, DAV-344, DAV-350)

Version 1.0.0 · 2026-10-06 · status: **proposal for review**. Nothing here is applied. On approval the DDL is applied with the Supabase migration tool (the repo tracks no SQL; each migration is noted in ROADMAP.md like earlier ones) and then verified in SQL: owner default `auth.uid()`, four policies per table, CHECK constraints reject bad values.

All numbers in the JSON examples below are **placeholders chosen to show structure**. They are not taken from any book. Real program content lives only in the private `training_definitions` rows.

## 1. Conventions

- Same as the rest of the schema: `user_id uuid not null default auth.uid()`, RLS on, four owner policies using `(select auth.uid())`, FK indexes, CHECKs on every enum.
- Dates are `date`, times are `time` plus `duration_min`; the instant is built with the user's time zone (stored in settings, defaulting to the device zone), matching the app-wide convention.
- Domain vocabulary (one list, used by `training_block_domains` and `planned_sessions`):
  `max_strength, hypertrophy, strength_endurance, power, aerobic_base, anaerobic_capacity, speed, work_capacity, specific_endurance, sport_skill, recovery`.

## 2. Proposed tables

```sql
-- One row per user: equipment, scheduling defaults, Google "Training" calendar.
create table public.training_settings (
  user_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  bars jsonb not null default '[{"key":"olympic","name":"Olympic bar","kg":20},{"key":"trap","name":"Trap bar","kg":25}]',
  plates_kg jsonb not null default '[1.25,2.5,5,10,15,20]',          -- per side, one of each size assumed unlimited
  training_max_fraction numeric not null default 0.9 check (training_max_fraction between 0.5 and 1),
  default_start_time time not null default '07:00',
  default_duration_min int not null default 60 check (default_duration_min between 10 and 360),
  second_start_time time not null default '20:00',
  second_duration_min int not null default 120 check (second_duration_min between 10 and 360),
  calendar_id text,                                                  -- set when the app creates the calendar
  timezone text,
  updated_at timestamptz not null default now()
);

-- Methodology content transcribed from the user's books. Private. Immutable per (key, version) once reviewed.
create table public.training_definitions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  methodology text not null,                                         -- 'tactical_barbell'
  kind text not null check (kind in ('strength_module','se_module','conditioning_session','template','system')),
  key text not null,
  version int not null default 1 check (version >= 1),
  schema_version int not null default 1,
  title text not null,
  body jsonb not null,
  source_ref text,                                                   -- book + page(s), free text
  status text not null default 'draft' check (status in ('draft','reviewed','retired')),
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (user_id, methodology, kind, key, version)
);

create table public.training_plans (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  methodology text not null,
  start_date date, end_date date,
  status text not null default 'draft' check (status in ('draft','active','completed','archived')),
  notes text,
  created_at timestamptz not null default now()
);

create table public.training_blocks (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  plan_id bigint references public.training_plans(id) on delete set null,
  position int,                                                      -- order inside the plan
  cycle_id bigint references public.training_cycles(id) on delete set null,  -- keeps User page / metrics view working
  origin text not null default 'generated' check (origin in ('generated','imported')),
  name text not null,
  template_key text, template_version int,
  definition_refs jsonb not null default '[]',                       -- [{kind,key,version}]
  definition_snapshot jsonb,                                         -- frozen copy used to generate; null for imported
  choices jsonb not null default '{}',                               -- see section 4
  start_date date not null,
  end_date date not null check (end_date >= start_date),
  status text not null default 'draft' check (status in ('draft','scheduled','active','completed','abandoned')),
  benchmark jsonb, review jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Block composition. A block may have no strength domain at all.
create table public.training_block_domains (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  block_id bigint not null references public.training_blocks(id) on delete cascade,
  domain text not null check (domain in ('max_strength','hypertrophy','strength_endurance','power','aerobic_base',
    'anaerobic_capacity','speed','work_capacity','specific_endurance','sport_skill','recovery')),
  role text not null check (role in ('primary','secondary','maintenance')),
  min_per_week numeric, target_per_week numeric, max_per_week numeric,
  unique (block_id, domain)
);

-- Append-only max records with provenance.
create table public.athlete_maxes (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  exercise_key text not null,                                        -- exercise_library.id or a movement key ('pull_up')
  exercise_name text not null,
  kind text not null check (kind in ('1rm','e1rm','rm_derived','training_max','max_reps')),
  value numeric not null check (value > 0),
  unit text not null check (unit in ('kg','reps')),
  as_of date not null,
  source text not null check (source in ('test','implied_logged_percent','estimate','manual','progression')),
  derivation jsonb not null default '{}',                            -- formula, source exercise_session ids
  block_id bigint references public.training_blocks(id) on delete set null,
  created_at timestamptz not null default now()
);
create index athlete_maxes_lookup on public.athlete_maxes (user_id, exercise_key, as_of desc);

create table public.planned_sessions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  block_id bigint not null references public.training_blocks(id) on delete cascade,
  revision int not null default 1,
  sequence_no int not null,                                          -- program order; never changes
  week_index int not null, day_slot int not null, slot_in_day int not null default 1,
  domain text not null check (domain in ('max_strength','hypertrophy','strength_endurance','power','aerobic_base',
    'anaerobic_capacity','speed','work_capacity','specific_endurance','sport_skill','recovery')),
  work_kind text not null default 'progression' check (work_kind in ('progression','maintenance','test','deload','recovery')),
  module_ref text not null,                                          -- 'strength:<key>' | 'cond:<key>' | 'se:<key>' | 'test:<what>'
  title text not null,
  prescription jsonb not null default '[]',                          -- frozen at generation (section 4)
  prescription_version int not null default 1,
  original_date date not null,                                       -- never changes
  scheduled_date date not null, start_time time, duration_min int,   -- current slot; changes are deviations
  status text not null default 'planned' check (status in ('planned','confirmed','skipped','superseded')),
  confirmed_at timestamptz,
  actual_draft jsonb,                                                -- details entered before a wearable session exists
  exercise_session_id bigint references public.exercise_sessions(id) on delete set null,
  match_status text not null default 'none' check (match_status in ('none','pending','matched','manual')),
  match_confidence numeric check (match_confidence between 0 and 1),
  match_method text,
  gcal_calendar_id text, gcal_event_id text, gcal_pushed_hash text, gcal_pushed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (block_id, revision, sequence_no)
);
create index planned_sessions_user_date on public.planned_sessions (user_id, scheduled_date);
create index planned_sessions_block on public.planned_sessions (block_id);
create unique index planned_sessions_one_per_actual on public.planned_sessions (exercise_session_id)
  where exercise_session_id is not null and status <> 'superseded';

-- Append-only history of every change to a planned session's calendar slot or outcome.
create table public.planned_session_deviations (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  planned_session_id bigint not null references public.planned_sessions(id) on delete cascade,
  kind text not null check (kind in ('moved','skipped','substituted','modified','calendar_deleted')),
  source text not null default 'app' check (source in ('app','calendar')),
  from_date date, to_date date, reason text,
  detail jsonb not null default '{}',
  occurred_at timestamptz not null default now()
);
create index planned_session_deviations_session on public.planned_session_deviations (planned_session_id);
```

Immutability is enforced by a `BEFORE UPDATE` trigger on `planned_sessions` that rejects changes to `sequence_no`, `original_date`, `prescription` and `block_id` (changing a plan means a new `revision`, with the old rows set to `superseded`). `training_definitions.body` is rejected on update once `status = 'reviewed'`.

Weeks are not a table (derived from `start_date` and the snapshot; see `01-architecture.md` 3.4).

### Existing tables

- `exercise_sessions`: unchanged. The link lives on `planned_sessions.exercise_session_id`; one actual session per planned session (partial unique index).
- `training_cycles`: unchanged. A new block writes or links one row so the User page and `v_training_block_metrics` keep working; `focus` is written from the block domains using the coarse mapping below.

### Domain to `FocusQuality` (coarse by design; proposed, review)

| Block domain | FocusQuality |
|---|---|
| max_strength | strength |
| hypertrophy | hypertrophy |
| strength_endurance | strength |
| power, speed | power |
| aerobic_base | aerobic_base |
| specific_endurance, anaerobic_capacity | race_specific_endurance |
| work_capacity | maintenance |
| sport_skill | sport_skill |
| recovery | recovery_deload |

Role mapping: `primary` -> `primary`; `secondary` and `maintenance` -> `maintained`. The lossless domain data stays in `training_block_domains`.

### Backfill of the 13 existing cycles

One `training_blocks` row per `training_cycles` row (`origin = 'imported'`, `cycle_id` set, dates copied, `definition_snapshot` null). Template name, primary and secondary focus come from `notes -> notion_import_focus`, parsed with a guarded cast (a row whose `notes` is not valid JSON gets only its dates). Primary and secondary become `training_block_domains` rows using the existing `TbFocus` keys: max_strength -> max_strength, strength_maintenance -> max_strength (maintenance), hypertrophy -> hypertrophy, work_capacity -> work_capacity, aerobic_base -> aerobic_base, vo2max and threshold -> anaerobic_capacity, endurance -> specific_endurance, speed_power -> power, deload -> recovery. The `notes` JSON is left untouched.

## 3. Definition format

Every definition is `{schema_version, kind, key, version, title, source_ref, ...}` plus the kind-specific body. Validation lives in Kotlin DTOs (unknown fields rejected, required fields enforced) with tests; nothing is interpreted from free text.

### 3.1 `strength_module` (placeholder values)

```json
{
  "schema_version": 1, "kind": "strength_module", "key": "example.two_day", "version": 1,
  "title": "Example two-day strength module", "source_ref": "book, page",
  "cycle_weeks": 3,
  "sessions_per_week": 2,
  "slots": [
    { "id": "A", "role": "press",  "standard": "<exercise_library id>", "alternates": ["<id>", "<id>"] },
    { "id": "B", "role": "squat",  "standard": "<id>" },
    { "id": "C", "role": "pull",   "standard": "<id>", "bodyweight_included_in_max": true }
  ],
  "weeks": [
    { "week": 1, "sessions": [
        { "items": [ { "slot": "A", "sets": {"min": 3, "max": 5}, "reps": 5, "load": {"kind": "pct_1rm", "value": 70} },
                     { "slot": "C", "sets": {"min": 3, "max": 5}, "load": {"kind": "pct_max_reps", "value": 70} } ] },
        { "items": [ { "slot": "B", "sets": {"min": 3, "max": 5}, "reps": 5, "load": {"kind": "pct_1rm", "value": 70} } ] }
    ] }
  ],
  "options": [ { "id": "peak", "weeks": [3], "kinds": ["amrap", "amsap", "peaking"] } ],
  "progression": { "unit": "kg", "upper": {"min": 2.5, "max": 2.5}, "lower": {"min": 5, "max": 5}, "after_weeks": {"min": 3, "max": 6}, "allow_no_change": true },
  "max_ref": { "default": "pct_1rm", "training_max": false }
}
```

Load kinds: `pct_1rm`, `pct_tm`, `pct_max_reps`, `work_up_rm` (`{rm: {min, max}}`, reference only), `rpe`, `bodyweight`, `none`. Sets and reps accept a number or `{min, max}`. An item may be `optional` or `option_of` an option id.

### 3.2 `se_module`

```json
{
  "schema_version": 1, "kind": "se_module", "key": "example.se_2day", "version": 1, "title": "...", "source_ref": "...",
  "cluster": { "min_exercises": 3, "max_exercises": 5 },
  "weeks": [ { "week": 1, "sessions": [ { "sets": {"min": 4, "max": 5}, "load": {"kind": "pct_max_reps", "value": 50} } ] } ],
  "deload_week": { "week": 4, "load": {"kind": "pct_max_reps", "value": 30} },
  "retest": true
}
```

### 3.3 `conditioning_session`

```json
{
  "schema_version": 1, "kind": "conditioning_session", "key": "example.steady_run", "version": 1,
  "title": "Example steady run", "source_ref": "book, page",
  "category": "endurance",
  "domains": ["aerobic_base"],
  "prescription": { "by": ["minutes", "distance_mi"] },
  "structure": { "type": "continuous" },
  "intensity": { "rpe": {"min": 4, "max": 5}, "hr_bpm": {"min": 120, "max": 150} },
  "versions": { "basic": {"note": "..."}, "standard": {"note": "..."}, "advanced": {"note": "..."} },
  "modes": ["run", "ride", "swim", "row"]
}
```

`structure.type` is one of `continuous`, `repeats` (`{rounds, work: {distance_m|minutes}, rest: {...}}`), `tempo` (`{warmup, work, cooldown}`), `circuit`, `ladder`, `custom` (ordered steps). The Training Vault sessions (numbered, Basic/Standard/Advanced) map onto these; anything that does not fit stays `custom` with explicit ordered steps rather than free text.

### 3.4 `template`

```json
{
  "schema_version": 1, "kind": "template", "key": "example.block", "version": 1,
  "title": "Example block", "source_ref": "book, page",
  "weeks": 6,
  "domains": [ {"domain": "max_strength", "role": "primary"}, {"domain": "aerobic_base", "role": "secondary"} ],
  "days_per_week": 4,
  "grid": [
    { "week": 1, "kind": "normal", "days": [
        { "day": 1, "cells": [ { "ref": "strength:$OP" } ] },
        { "day": 2, "cells": [ { "ref": "cond:example.steady_run", "params": {"minutes": {"min": 30, "max": 60}} } ] },
        { "day": 3, "cells": [ { "ref": "strength:$OP" } ] },
        { "day": 4, "cells": [ { "ref": "cond:example.steady_run", "params": {"distance_mi": 8} } ] }
    ] },
    { "week": 4, "kind": "deload", "days": [ { "day": 2, "cells": [ { "ref": "cond:example.steady_run", "params": {"minutes": 30} } ] } ] }
  ],
  "variables": { "OP": { "kind": "strength_module", "choose_from": ["example.two_day"] } },
  "minimums": [ { "category": "endurance", "per_week": 3 } ],
  "benchmark": { "id": "b1", "week": 6, "kind": "run", "distance_mi": 6, "max_minutes": 60 },
  "variants": [ { "key": "abbreviated", "start_week": 3 } ],
  "ramps": [ { "param": "ruck_kg", "by_week": { "1": 10, "5": 12 } } ]
}
```

Cell reference grammar: `strength:<key|$VAR>`, `se:<key|$VAR>`, `cond:<key>`, `test:<what>`, `rest`. `params` override or add to the session type's prescription (`minutes`, `distance_mi`, `distance_km`, `rounds`, `ruck_kg`, `rpe`), each a number or `{min, max}`. Two cells in one day are a two-a-day (`slot_in_day` 1 and 2). Days are positions; weekdays come from the block's choices.

### 3.5 `system`

An ordered or optional list of templates with transition rules (benchmark that gates the next one, allowed skips, start-later variants). It drives the strategic-plan suggestions in the UI; blocks are generated from templates, not from a system.

## 4. Block choices and resolved prescriptions

`training_blocks.choices`:

```json
{
  "weekday_map": { "1": "mon", "2": "tue", "3": "thu", "4": "sat" },
  "slot_times":  { "2": {"start": "07:00", "duration_min": 60}, "4": {"start": "20:00", "duration_min": 120} },
  "variables":   { "OP": "example.two_day" },
  "clusters":    { "example.two_day": { "A": "<exercise_library id>", "B": "<id>", "C": "<id>" } },
  "bar":         { "A": "olympic", "B": "olympic", "C": "olympic" },
  "conditioning": { "speed": ["cond.tempo", "cond.repeats_800"], "hill": ["cond.hills"] },
  "endurance_goal": { "character": "long_run", "terrain": "trail", "target": { "distance_mi": 20, "by": "2026-12-07" } },
  "max_snapshot_ids": [123, 124, 125]
}
```

A resolved prescription item (in `planned_sessions.prescription`, frozen):

```json
{
  "slot": "A",
  "exercise": { "library_id": "<id>", "name": "<name>" },
  "sets": { "min": 3, "max": 5 }, "reps": { "min": 5, "max": 5 },
  "load": { "kind": "pct_1rm", "pct": 70,
            "max": { "id": 123, "kind": "1rm", "kg": 56.0, "source": "implied_logged_percent" },
            "target_kg": 39.2, "loadable_kg": 40.0,
            "bar": "olympic", "plates_per_side_kg": [10] },
  "optional": false,
  "option_of": null
}
```

Bodyweight pull-up items carry `reps` resolved from the max-reps snapshot and, once the prescribed load exceeds bodyweight, `added_kg` (total load minus the bodyweight on the session date). The log keeps storing added weight only.

## 5. What this model deliberately does not do

- No normalised week or cell tables, and no per-set planned rows: the session document is read whole and compared with `details.exercises` at read time.
- No automatic mutation of maxes or plans: progression and regeneration are proposals the user confirms.
- No parallel workout store and no manually created wearable-less sessions until the open question in `01-architecture.md` 3.5 is decided.
