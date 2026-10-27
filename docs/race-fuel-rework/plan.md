# Race fuel rework: pre-race schedule and post-race debrief

Replaces logging one gel at a time (Add entry > Run fuel) with an editable schedule made before the race and a one-sheet debrief after it. Water, water with electrolytes, pre-workouts and mixes are first-class.

## Decisions (agreed)
1. One `fueling_intake_log` row per intake, written on save, with a deterministic `client_key` (`debrief:<session>:<block>:<n>`); a block taken 10 of 12 times marks the last two skipped.
2. The two catalogs are bridged, not merged: a block or recipe ingredient points at a fueling product OR a supplement product. A pre-workout or electrolyte tablet is added once (barcode, DSLD or SuppCo) and read from `supplement_product_ingredients` (per-serving amounts). Food-label products (gels, mixes) go through the nutrition barcode lookup. A debrief writes only `fueling_intake_log`, never also `supplement_log` (double count in `nutrient_intake`).
3. The planner's output seeds the schedule (`seedBlocks`); it can be started empty.
4. Long training runs get the same schedule and debrief (`fuel_schedules.kind = 'planned'` before the run, linked to the activity once synced).
5. Discomfort list: nausea, cramps, bloating, reflux, side stitch, vomiting, dizziness, plus gut 0-10, energy, tiredness, thirst, notes, optional weigh-ins.

## Phases
1. **Data and domain** (done): migration `20261106_fuel_schedule_debrief.sql` (applied 2026-10-27), `domain/fuel/FuelSchedule.kt` (blocks, expansion, totals, warnings, seeding, debrief rows, adherence), pre-start intake in `fuelingSummary.ts`.
2. **Schedule editor** (done): `FuelScheduleCard` on the event's FUEL tab (seeded from the plan or empty) and on planned runs of 90 minutes or more in the day plan (empty start; seeding from a long-run plan is not built). Blocks come from the fuel catalog, the supplement catalog (caffeine, sodium, carbohydrate read from the product's ingredients) or are typed.
3. **Debrief** (done): `FuelDebriefSheet` (event page, FUEL DEBRIEF) and `RunFuelEntry` (Add entry > Run fuel: pick the activity, then the debrief). One tap per block (as planned, less, more, none), extras, discomfort ticks, gut, energy, tiredness, thirst, weigh-ins, notes. Writes one `fueling_intake_log` row per intake (key `debrief:<session>:<block>:<n>`; rows an earlier save wrote and this one does not, and the retired plan-step rows `plan:<event>:*`, are removed), the review row, and a sweat test with both weights.
4. **Cleanup** (done): the old plan-step review sheet is gone; logging one intake stays as "log one intake instead"; the tolerance suggestion sees the ticked discomfort (`effectiveGutScore`).

Not built: the post-race notification does not open the debrief directly; a schedule for a planned run is found again from its synced activity only when `planned_sessions.exercise_session_id` links them; calendar-only long runs are keyed by their calendar id and are not found from the activity.

## Open data facts
- Existing supplement products (20) hold no caffeine or sodium ingredients yet; pre-workouts and electrolyte tablets must be added.
- `fuel_plan_reviews` is unique per `(user, event)` and, with the migration, per `(user, exercise_session)`.
- A seeded schedule covers at least the plan's fluid and sodium: a drink mix is whole servings and brings its own fluid and sodium.
