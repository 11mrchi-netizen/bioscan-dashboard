# Race fuel rework: pre-race schedule and post-race debrief

Replaces logging one gel at a time (Add entry > Run fuel) with an editable schedule made before the race and a one-sheet debrief after it. Water, water with electrolytes, pre-workouts and mixes are first-class.

## Decisions (agreed)
1. One `fueling_intake_log` row per intake, written on save, with a deterministic `client_key` (`debrief:<session>:<block>:<n>`); a block taken 10 of 12 times marks the last two skipped.
2. The two catalogs are bridged, not merged: a block or recipe ingredient points at a fueling product OR a supplement product. A pre-workout or electrolyte tablet is added once (barcode, DSLD or SuppCo) and read from `supplement_product_ingredients` (per-serving amounts). Food-label products (gels, mixes) go through the nutrition barcode lookup. A debrief writes only `fueling_intake_log`, never also `supplement_log` (double count in `nutrient_intake`).
3. The planner's output seeds the schedule (`seedBlocks`); it can be started empty.
4. Long training runs get the same schedule and debrief (`fuel_schedules.kind = 'planned'` before the run, linked to the activity once synced).
5. Discomfort list: nausea, cramps, bloating, reflux, side stitch, vomiting, dizziness, plus gut 0-10, energy, tiredness, thirst, notes, optional weigh-ins.

## Phases
1. **Data and domain** (done): migration `20261106_fuel_schedule_debrief.sql` (written, not applied), `domain/fuel/FuelSchedule.kt` (blocks, expansion, totals, warnings, seeding, debrief rows, adherence), pre-start intake in `fuelingSummary.ts`.
2. Schedule editor on the event's FUEL tab and on planned long runs.
3. Debrief sheet, the post-race prompt, writing intake rows and the review.
4. Shrink the old per-gel form to "add one intake"; feed symptoms to the tolerance suggestion; docs.

## Open data facts
- Existing supplement products (20) hold no caffeine or sodium ingredients yet; pre-workouts and electrolyte tablets must be added.
- `fuel_plan_reviews` is unique per `(user, event)` and, with the migration, per `(user, exercise_session)`.
- A seeded schedule covers at least the plan's fluid and sodium: a drink mix is whole servings and brings its own fluid and sodium.
