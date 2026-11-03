# Regimen model

The supplement roster (`supplements`) is the roster of everything taken on purpose: supplements, medications, peptides,
hormones. Inspired by Regimen (helloregimen.com, Awaken Labs): a structured dose and schedule, cycles, planned dose
changes, and a history of every change that the rest of the app can draw on.

## Phase 1 (built)

- **Data** (`supabase/migrations/20261115_regimen_model.sql`): `kind`, `form`, `route`, `dose_amount` + `dose_unit`,
  `slots` (morning / afternoon / night; several = several doses a day), `schedule` (daily, every N days, weekdays,
  as needed, with an optional on/off `cycle`), `dose_steps` (titration or taper). The old columns (`dose` text,
  `time_of_day`, `every_n_days`) stay and are kept filled for the web dashboard, the log agent and the analysis functions;
  a before-trigger derives the new columns when a client writes only the old ones.
- **History**: `supplement_changes`, written by a trigger on every start, stop, restart, dose or schedule change (any
  client). Backfilled with each item's start and end dates.
- **Domain** (`domain/Regimen.kt`): one place answers "due today?", "today's dose", "doses per day on average",
  "planned days in a window" and the words for a schedule, so reminders, the logging sheet, the TODAY tile, the pantry,
  the stack totals and the log's expected marks agree.
- **UI**: the regimen form (`ui/screens/status/RegimenFields.kt`) inside the existing sheet; the list shows kind, today's
  dose, the schedule and the cycle or step state; ended items can be restarted; each item shows its history.

## Phase 1b (built): reference cards and source review

- **Reference cards** (`regimen_references`, `supabase/functions/regimen-reference-lookup`): looked up only when the
  person asks, kept only for items in their roster (no catalogue). Medications, hormones and approved peptides: RxNorm
  (standard id, ingredient) then the FDA label (openFDA: boxed warning, contraindications, drug interactions, warnings,
  how the body handles it, forms), prescription labels first; the half-life is read from the label with the label's own
  words kept beside it. Research peptides have no label: the card keeps the most relevant PubMed studies and says they
  are research, not dosing guidance. Supplements keep their DSLD / SuppCo snapshots. NIH retired RxNav's interaction
  API in 2024; the label's interaction section replaces it.
- **Label mentions**: the other roster items a label names, with the sentence, in the item sheet and as "From the
  labels" on the list. Reported as what the label says, never as a verdict.
- **Source review** (`registry_reviews`): the owner confirms or disputes each evidence and interaction record with its
  cited source. Confirmed rows show as reviewed; a disputed interaction keeps its flag with the note; a disputed
  expected-effect claim is no longer shown. A review holds for the row version it was given on.

## Next

2. Changes drawn as bands on metric and lab charts; lab watch rules per substance (copper with zinc, ferritin with iron,
   25-OH-D with vitamin D, hematocrit with testosterone).
3. "Since you changed X" readings from the baseline engine, worded as observations, with a self-experiment offered.
4. Medication pairs in the interaction registry; interaction timing for items taken at several times of day.
5. Estimated level curves from half-lives, for medications and peptides only, labelled as estimates.
6. Injectables: injection site rotation, vials with concentration, stock counted in syringe units as prescribed. The app
   never suggests doses.
