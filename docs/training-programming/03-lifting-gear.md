# 03 — Lifting gear (straps, belt, sleeves, wraps, chalk, hook grip, lifting shoes)

Requested 27 Nov 2026. Phone and web together.

## Data

- **Per set:** an optional `gear` list on each set in `exercise_sessions.details.exercises[].sets[]`, tags from a fixed list:
  `straps, belt, sleeves, wraps, chalk, hook, shoes`. **Absent = not recorded** (all 2,969 sets logged before this), **`[]` = recorded as
  no gear.** Nothing may read "absent" as "no gear"; the comparison uses recorded sets only. No column or migration for this: it is
  the existing `details` jsonb (see `docs/analysis-layer-2/03-strength-session-model.md`).
- **Rules:** table `gear_rules` (migration `20261127004134_gear_rules.sql`), one row per lift and gear: `movement_key`
  (`movementKey(name)`, so "Deadlift" and "Barbell Deadlift" share rules), `gear`, `from_kg` (null: at any load). Own-row RLS.
  "Straps from 140 kg, belt from 160 kg" is two rows. Rules are for the person's own sets, so they are not part of any plan or Zepp template.

## Behaviour

- A **new set** pre-fills its gear from the rules as its weight is typed (`suggestGear`): the gear whose threshold the weight
  reaches. A lift with **no rule at all** is left *not recorded*, not logged as "none"; a lift with rules and a weight below every
  threshold is a real "none". Picking a chip by hand stops the pre-fill for that set. A new set copied from the previous one (phone) keeps its gear.
- **Sets from before** stay *not recorded* until picked by hand or `APPLY MY GEAR RULES` (per exercise) is used. Nothing is back-filled.
- Changing only gear counts as the person's edit (`exercises_source` becomes `app`), the same as changing reps: a Zepp sync no longer overwrites the list.
- **Where:** phone: Log edit form (gear chips under each set), Setup › Training setup › GEAR RULES, the PLANNED card of a workout (rule and gear logged);
  web: Review › session (chips), Plan › Gear (rules and the comparison), Plan › Sessions › a planned session (the rule beside each lift).

## Analysis

`gearComparisons` (`supabase/functions/_shared/training-core/gear.ts`, tests `gear.test.ts`): for each lift and gear with recorded
sets both with and without, the sets, mean reps, mean RPE and best e1RM (Epley) **inside the load range both sides share**, because gear goes on
at the heavy end and a plain average would set heavy against light. It reads nothing until each side has 8 sets in that range
(`MIN_SETS_PER_SIDE`) and says it is association, not cause. Phone mirror of the rule: `domain/training/Gear.kt`, `GearTest.kt` (same cases).

Not built: the max estimator and personal records do not yet separate sets by gear; an Edge Function / analysis-run finding; custom
gear tags (the list is fixed, matching the database check).
