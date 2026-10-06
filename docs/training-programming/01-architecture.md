# Training programming engine: audit and architecture (DAV-341)

Version 1.0.0 · 2026-10-06 · status: **proposal for review** (no migration applied, no code written).

Scope: milestone 13. A reusable programming engine with Tactical Barbell as the first methodology. This document locks the architecture; `02-schema-and-definitions.md` holds the proposed tables and the definition format.

Sources read for this audit: the repo (Android app, Supabase schema), Linear DAV-341..350, and the user's own copies of Tactical Barbell II (Conditioning), Mass Protocol and Green Protocol (full text, plus rendered pages for tables). Tactical Barbell III (Kindle) is not yet available; programs from it are added later through the definition format, not through schema changes.

## 1. What exists today

| Area | Today | Consequence for 13 |
|---|---|---|
| Blocks | `training_cycles` (13 rows, Notion import + 1 user-created "Velocity" 2026-08-10..2026-12-07). Block facts for Tactical Barbell live as a JSON **string inside `notes`** (`notion_import_focus`: template, primary, secondary, strength_days, conditioning_days). `focus jsonb` is `[]` on most rows. | Real block tables replace the notes hack. `training_cycles` stays (User page, `v_training_block_metrics`) and is linked, not removed. |
| Block UI | `TrainingBlockFormSheet` (free-text template name, weeks, primary/secondary focus chips, strength days 3 / conditioning days 2 defaults); `TrainingBlocksScreen`. | Strength and conditioning day counts are free numbers, not generated sessions. Becomes the entry point of the block-first setup. |
| Planned sessions | None. "Next session" on the Map tab reads Google Calendar events and classifies them by emoji in the title. | Planned sessions become first-class rows; the calendar becomes a projection of them. |
| Actual sessions | `exercise_sessions` (345 strength via Health Connect, 187 strength from Notion, runs, walks, other). Strength detail is `details.exercises[].sets[]` (reps, `weight_kg`, `percent_1rm`, rpe, rir). 186/187 Notion strength sessions have exercises; only 4/345 Health Connect ones. | Reused as the single actual-session store. No parallel workout database. |
| Logging | Manual exercise logging was removed on 2026-09-15. Exercises are added **after the fact** through the edit form on a synced session (`ExerciseDetailsForm`). | There is no live logger. Completion is "confirm planned session + enter real details afterwards" (user decision), matched to the synced session. |
| Maxes | None stored. Epley estimate with a reps <= 12 gate exists (`domain/StrengthLoad.kt`). The user logs `percent_1rm` on sets, which implies the max they used (Standing Military Press 42.5 kg at 75% -> about 56.7 kg; 47.5 kg at 85% -> about 55.9 kg). | A `athlete_maxes` table with provenance; implied-from-logged-percent is a first-class source. |
| Calendar | Read-only (`calendar.readonly` + `drive.readonly`), incremental sync into `calendar_events` for the primary calendar. | Pushing needs the `calendar.events` scope (one-time re-consent) and a second synced calendar. |
| Wearables | `exercise_sessions` carries Health Connect sessions; `zepp_workout_detail.exercise_session_id` links Zepp detail to a session. | One FK from the planned session to `exercise_sessions` covers both sources. |
| Bodyweight | `body_metrics.weight_kg` (dated). | Source for weighted pull-ups: total load = bodyweight + added weight. Logs keep storing added weight only. |
| Vocabulary | `FocusQuality` enum (hypertrophy, strength, power, aerobic_base, race_specific_endurance, peaking_realization, sport_skill, recovery_deload, maintenance) used by analysis; `TbFocus` in the block form (max_strength, strength_maintenance, hypertrophy, work_capacity, aerobic_base, vo2max, threshold, endurance, speed_power, deload). | One domain vocabulary with an explicit mapping to `FocusQuality`, so existing analysis keeps working. |

### Where the current model wrongly assumes strength

1. `TrainingBlockFormSheet` defaults `strengthDays = 3`; a block is described as strength days + conditioning days.
2. `v_training_block_metrics` reports strength-session counts as a headline for every block.
3. The Notion-hack `primary` / `secondary` pair has no way to say "strength is maintenance, at a floor of N sessions per week".

None of these break a block with zero strength sessions, but none can express it either. The new model makes domains and their roles explicit.

## 2. What the books show (and what it forces)

1. **Programs are layered, not flat.** Green Protocol is a system (Foundation: Capacity, Velocity, Outcome; Continuation: Hybrid, C/CAT, I/CAT, Block Training, Mike India). Tactical Barbell II has Base Building, then Black or Green continuation. Each *template* is a week x day grid whose cells reference reusable *modules* (a strength template, a conditioning session type) with parameters.
2. **Cells are references with parameters.** A template week is a row of cells such as a strength-template reference (`FT`), a conditioning session with a distance or duration (`LSS <miles>`, `LSS <minutes>`), a session with rounds, deload and taper weeks, back-to-back long runs, two-a-days, a load that changes by week (ruck weight), and a benchmark at the end. The same module is reused across templates (`OP` means "whichever Operator variant you chose").
3. **A strength module has its own cycle, independent of the template.** Fighter and Operator rotate on a short intensity wave regardless of the template week; progression (small increments to the maxes after a number of weeks, then recalculate, or explicitly no change) happens at the module level.
4. **Prescriptions are not always a fixed load.** Seen in the books: sets as ranges (`3-5 x 5`); optional assistance; "work up to a 2-3RM" (self-regulated, no fixed target); peak-week options (single set then add weight, AMRAP, AMSAP); percentage of **maximum reps** for bodyweight work (pull-ups, SE); weighted pull-ups at a percentage of a 1RM that **includes bodyweight**; a Training Max (a fixed fraction of the 1RM) in Base Building strength-first; strength-endurance as `sets x % of max reps` with a 4th deload week and retest.
5. **Clusters are chosen by the user per block**, with a standard cluster and documented alternates ("a press, a pull, and legs"); A/B clusters (Zulu, Operator I/A: A-B-A, never A-B-A / B-A-B).
6. **Conditioning is a library.** TB II's Training Vault has 50+ numbered sessions in categories (Endurance, HIC aerobic-anaerobic, hills, general conditioning, power development, core + grip, challenge), each with Basic / Standard / Advanced versions. Green has about 15 named run/ruck sessions with their own prescription syntax (`400/10` = rounds, `Tempo 3`, `LSS 30-60`).
7. **Days are positions, not weekdays** ("Day 1, 2, 4, 5"). The user assigns weekdays at setup.
8. **Protocols state minimums, not only grids** (minimum counts of high-intensity and endurance sessions per week, a rest day, an easy week at a set interval). These become checks in the block preview, not generation rules.
9. **Benchmarks gate transitions** (a distance-in-time run or ruck test at the end of a template before the next one). They map to the endurance goal and to the block review (DAV-348).

## 3. Architecture

### 3.1 Layers and ownership

| Layer | What it is | Mutability | Storage |
|---|---|---|---|
| Methodology definition | Modules (strength, SE, conditioning session types) and templates (week x day grids) transcribed from the books, with book and page. | Immutable per `(key, version)` once reviewed; edits create a new version. | `training_definitions` (jsonb document per row), private per user. |
| Strategic plan | The user's intended sequence of blocks over time ("Capacity, then Velocity, benchmark 2026-12-07"). | Editable. | `training_plans`, ordered `training_blocks`. |
| Block | One template instance for a date range, with the user's choices (clusters, weekday map, conditioning preferences, endurance goal) and its domain composition. | Choices editable while `draft`; frozen at activation. Carries a **frozen copy** of the definitions it used (`definition_snapshot`). | `training_blocks`, `training_block_domains`. |
| Domain composition | Which domains the block develops and in what role: primary, secondary, maintenance, with target exposure (min/target/max sessions per week). Zero strength domains is valid. | Same as block. | `training_block_domains`; mirrored into `training_cycles.focus` for existing screens. |
| Week | Index and kind (normal, deload, taper, test, easy) read from the snapshot. | Derived. | No table (see 3.4). |
| Planned session | One executable session: sequence position, week, day slot, domain, module, resolved prescription, current calendar slot. | `sequence_no`, `original_date`, `prescription` immutable; `scheduled_*` and status change, each change recorded as a deviation. | `planned_sessions`. |
| Prescription | Ordered items inside a planned session: exercise, sets and reps (ranges allowed), semantic load plus the resolved kg and plates. | Frozen at generation. | jsonb array on the session (DTO-locked, `schema_version`). |
| Max snapshot | An exercise max with kind (1RM, e1RM, rep-derived, Training Max, max reps), value, date, source and derivation. | Append-only. | `athlete_maxes`. |
| Deviation | Moved, skipped, substituted, modified, or moved in Google Calendar. | Append-only. | `planned_session_deviations`. |
| Actual session | What was performed, with sets and wearable telemetry. | Existing rules. | `exercise_sessions` (+ `zepp_workout_detail`). Unchanged. |
| Link | Planned session to actual session, with method and confidence. | Set on match; may be re-pointed by the user. | Columns on `planned_sessions`. |

Rules that fall out of this:

- Semantic over absolute: a prescription keeps `75% of 1RM` **and** the resolved 42.5 kg with the max snapshot it came from. A later max change never rewrites an old session.
- Calendar date and program sequence are separate: `sequence_no` never changes; `scheduled_date` does.
- Skipped is lost (user decision): status `skipped`, a deviation row, no repeat. Postponed moves only that session: new `scheduled_date`, a deviation row with from/to.
- Planned data is never overwritten by actuals. Planned-vs-actual is a read-time join.

### 3.2 Definition format (summary)

A definition is a JSON document, validated by Kotlin DTOs and unit tests, with `schema_version`. Four kinds: `strength_module`, `se_module`, `conditioning_session`, `template`. A template cell is `{ref, params}`. Full shapes and the Fighter, Velocity and Zulu/HT examples are in `02-schema-and-definitions.md`.

Why documents and not normalised rows: definitions are read as a whole, never queried by cell, and each book adds forms nobody can predict (the grids above differ from each other in structure). A normalised model would need a migration per new program; a validated document needs a new definition and, at most, a new `ref` type in the engine.

### 3.3 Generation (pure Kotlin, deterministic)

Inputs: template + modules (from the snapshot), the user's choices, start date, max snapshot, equipment settings, latest bodyweight.
Steps: expand template weeks -> resolve each cell's module cycle position -> assign weekday and time from the weekday map -> resolve prescriptions (below) -> emit `planned_sessions` rows with `sequence_no`.

Prescription resolution by load kind:

| Kind | Example | Resolution |
|---|---|---|
| `pct_1rm` / `pct_tm` | `3-5 x 5 @ 75%` | `target = pct x max`, rounded to the loadable weight, plate composition per side. Sets stay a range. |
| `pct_max_reps` | pull-ups `3-5 x 60%` | `reps = round(pct x max reps)`; weighted pull-ups switch to `pct_1rm` on total load once the prescribed load exceeds bodyweight. |
| `work_up_rm` | `work up to a 2-3RM` | No fixed target. Shows reference percentages; the actual is recorded. |
| `rpe` / `bodyweight` / `none` | SE circuits, plyometrics | Passed through. |
| peak options | AMRAP / AMSAP / peaking | Kept as a labelled option on the item; user picks at execution. |

Loadable weight: `bar_kg + 2 x sum(plates per side)` from the user's plates (1.25, 2.5, 5, 10, 15, 20 kg), so the step is **2.5 kg total**. Default bar 20 kg; a trap bar is a second bar with its own weight (configurable). Rounding is to the nearest loadable weight; ties round down for the heaviest week of a wave (no silent overloading), up otherwise. Plate composition is the greedy largest-first fit per side, with the exact fit verified in tests.

Maxes, in priority order: (1) a recorded test, (2) implied by the user's own logged `percent_1rm` (weight / percent), (3) Epley estimate on reps <= 12, (4) manual entry. Every snapshot stores its derivation (formula, source session ids). A Training Max is stored as its own snapshot (`kind = training_max`, a configurable fraction of the 1RM; the fraction the book recommends is the default).

Progression at block end: a proposal (not a mutation) using the increments the definition states for upper and lower lifts, converted to the user's 2.5 kg step, or "no change" (the books explicitly allow it). The user confirms; a new snapshot is appended.

Test weeks are ordinary planned sessions of `work_kind = test` and are schedulable like any other session.

### 3.4 Weeks are derived, not stored

A `training_weeks` table would hold only an index and a kind that the snapshot already defines. Weeks are computed from `start_date` and the snapshot. If week-level data appears later (a per-week note, readiness override), add the table then.

### 3.5 Completion and matching

States: `planned` -> `confirmed` (the user marked it done and, optionally, entered details) -> `matched` when linked to an `exercise_sessions` row. `skipped` is terminal.

1. User opens a planned session; the form is prefilled from the prescription (sets, reps, kg). The user edits the real values and RPE and confirms.
2. The confirmed details are held in `planned_sessions.actual_draft` until there is a synced session to attach to. Manual exercise sessions are **not** created (that would duplicate when Health Connect syncs the same workout).
3. Matcher, run on confirm and after every sync: candidates are `exercise_sessions` on the same local day with a compatible type (strength <-> `strength`; run/ruck/hill <-> `run`/`walk`/`hike`/`other`; the Zepp sport type is read through `zepp_workout_detail`), ranked by overlap with the scheduled window and proximity of start time. One clear candidate above a threshold links automatically; several or none leaves it `pending` for a one-tap confirm.
4. On link, `actual_draft.exercises` is merged into the session's `details.exercises` through the existing edit path (only when that field is empty; never overwriting existing exercises).
5. A wearable session in a block's date range with no planned match is shown as **unplanned**, not discarded.
6. Open question: if no wearable session appears (watch off), allow "confirm without wearable data" after a grace period, creating a clearly marked manual session. Not built until you decide.

### 3.6 Google Calendar

- Separate calendar named "Training", created by the app; default 07:00-08:00; a second session of a day defaults to 20:00-22:00.
- One event per planned session. Title carries the existing emoji convention (so the Map tab's classification keeps working); description carries the prescription summary. `extendedProperties.private` carries `planned_session_id` and `block_id`; the event id and a content hash are stored on the row.
- Push on generate (batch insert), on postpone (patch), on skip (patch the title to mark it skipped; not a delete, so history stays visible in Google).
- Detection: the existing incremental sync also syncs the Training calendar. A changed start time with an unchanged pushed hash is an **external move**: recorded as a deviation (`source = calendar`) and the session's `scheduled_date` follows. If the app and Google both changed since the last push, the session is flagged as a conflict and the user chooses. A deleted event is a proposal to skip, never an automatic skip.
- Requires the `calendar.events` scope (the app currently has `calendar.readonly`): a one-time re-consent through the existing `GoogleAuthorizationManager`.

## 4. Existing code touched

- `TrainingBlockFormSheet`: becomes the block-first setup (program -> dates -> weekday map -> clusters -> conditioning preferences -> endurance goal -> preview). Free-text template name and strength/conditioning day counts are removed from new blocks.
- `TrainingCyclesRepository`: stop reading `notion_import_focus` for new blocks; keep reading it for imported ones until backfill.
- `TrainingCycle` / `FocusQuality`: unchanged; `training_cycles.focus` is written from block domains.
- `GoogleAuthorizationManager`: add the `calendar.events` scope.
- New (pure Kotlin, `domain/training/`): `Definitions` (DTOs + parser), `PlateMath`, `MaxEstimator`, `PrescriptionResolver`, `BlockGenerator`, `SessionMatcher`.
- `v_training_block_metrics`: untouched in 13; replaced by planned-vs-actual outputs in DAV-349.

## 5. Compatibility with existing data

- The 13 `training_cycles` rows become `training_blocks` with `origin = 'imported'`, `definition_snapshot = null`, template and focus parsed from `notes`, and `cycle_id` pointing at the original row. The notes JSON is left in place (read-only history).
- The current "Velocity" block (2026-08-10 .. 2026-12-07) is a Green Protocol template. It is the **backtest case**: generate Velocity from 2026-08-10, then compare against the logged sessions and the calendar events it was actually run from.
- Notion strength sessions (187, with `percent_1rm`) are the source for implied maxes and for backtesting the load engine.

## 6. Decisions recorded (from the user)

| # | Decision |
|---|---|
| 1 | Definitions come from the user's books; they are stored privately in the database, never in the git repository. |
| 2 | Cluster exercises are chosen once per block; blocks may use A/B, upper/lower or full-body splits (several clusters). |
| 3 | Maxes are estimated by the app or set by test weeks; test weeks are schedulable. |
| 4 | Plates 1.25, 2.5, 5, 10, 15, 20 kg; 20 kg bar; a trap bar is available (weight configurable); plate composition is shown. Weighted-pull-up logs hold added weight only. |
| 5 | Initial setup: pick 2-3 weekly weekdays; each session can be modified. |
| 6 | At setup the user assigns each weekday to strength, conditioning or endurance (or SE / other specialties); an endurance day's goal is its character (tempo, long run, easy run, trail). |
| 7 | Default 07:00-08:00; second session 20:00-22:00; a move in Google Calendar is recorded in the app. |
| 8 | Postponing moves only that session; skipped is lost. |
| 9 | Details are filled in after the workout. |
| 10 | Matching uses Health Connect and the Zepp integration. |
| 11 | Build from Tactical Barbell III; the older core book is not needed. |

## 7. Open questions

1. **Tactical Barbell III contents.** A screenshot of the table of contents, then the program tables for the templates the user will run. The Operator variants (standard, Op/PRO, Op/DUP), Fighter, Zulu/HT, Gladiator, Grey Man, Base Building and Black are described in the books already read; TB III may change numbers.
2. **Trap bar weight** (default 25 kg suggested, confirm).
3. **No-wearable fallback** (3.5, item 6).
4. **Second calendar on the same account**: the "Training" calendar is created by the app; confirm it is acceptable to create one.

## 8. Delivery plan

| Step | Issue | Output |
|---|---|---|
| 0 | DAV-341 | This document, reviewed. |
| 1 | DAV-342, DAV-350 | Migration for the tables in `02`; backfill of the 13 cycles; RLS and defaults verified in SQL. |
| 2 | DAV-343 | `PlateMath`, `MaxEstimator`, `PrescriptionResolver` with tests; implied-max check against the 187 Notion sessions. |
| 3 | DAV-344 | Definition parser and validator; first definitions: Fighter, Zulu/HT, Op/PRO, Op/DUP, SE 2/3-day, the Green conditioning sessions, the Velocity template; rendered review of each. |
| 4 | DAV-345 | Block-first setup UI and preview. |
| 5 | DAV-346 | Today/next surface, prefilled completion form, matcher. |
| 6 | DAV-347 | Calendar push and sync, postpone and skip, conflict handling. |
| 7 | DAV-348, DAV-349 | Block review per domain, next-block proposal, planned-vs-actual published to analysis. |

Backtest gate before step 4 ships: regenerated Velocity from 2026-08-10 matches the logged block's structure (sessions per week, long-run progression, deload weeks), with every difference explained.

## 9. Risks

- **Transcription accuracy.** Book tables become data. Mitigation: rendered pages for every table, a readable rendering of each definition for the user to confirm, unit tests that regenerate known weeks.
- **Self-regulated prescriptions** (work up to a 2-3RM) cannot be pre-computed; the model records the outcome and never fakes a target.
- **Matching ambiguity** (split or mistyped wearable sessions): ranked candidates plus a one-tap confirm, never silent re-linking.
- **Calendar two-way edits:** conflicts are surfaced, not resolved silently.
- **Re-consent / Google verification** for `calendar.events`: expected to be a non-issue for a single personal account; verify on the phone.
- **Copyright:** definitions are the user's private transcription for personal use; nothing from the books enters the repository or any shared artifact.
