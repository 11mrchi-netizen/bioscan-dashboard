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

## Phases 2–6 (built)

- **Changes on the charts**: every detail-page chart and ME › BODY draws starts, stops and dose changes as dashed lines
  with the newest in words; a REGIMEN chip hides them.
- **Lab watch** (`lab_watch_rules`, five rules each checked against its PubMed record, seeded unreviewed): zinc above
  25 mg a day → copper; testosterone → hematocrit and testosterone; creatine → creatinine/eGFR read with care; vitamin D
  above 600 IU → no routine test, discuss the dose; iron → ferritin and iron status. ME › BLOODWORK opens with what
  applies, the latest result or "never measured", and the source; the rules join the source review.
- **Since you changed it** (`domain/RegimenEffects.kt`): three weeks before against the weeks after an item's latest
  start or dose change, for seven metrics, shown only beyond the metric's own noise, worded as what happened alongside,
  with other changes made within two weeks listed and a self-experiment pointed to.
- **Interaction timing** uses every time of day an item is taken.
- **Amount in the body** (`domain/AmountInBody.kt`): logged doses decaying by the label's half-life, over about five
  half-lives, labelled as an outline, not a blood level.
- **Injectables** (`supplement_log.injection_site`, `supplements.syringe_units`, `regimen_vials`): the site of each
  dose with the one rested longest suggested; the open vial's concentration, doses left, discard date and spares; the
  prescriber's syringe units shown as given and cross-checked against the mix, with a warning when they disagree. The
  app never works out or suggests a dose.

## Not built

- PSA (the testosterone guideline's prostate check) is not read from lab results yet.
- Lab watch has five rules; more are added by migration, each checked against its source first.
