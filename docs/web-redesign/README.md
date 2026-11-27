# Web redesign: one app, built around analysis

**Date:** 2026-11-24 · **Branch:** `claude/friendly-archimedes-4kbb27` · **Supersedes:** the navigation of
[`analysis-console/IA.md`](../analysis-console/IA.md) §3.1 and §4, and the two-surface split (Dashboard + Console) of the
web transition plan.

## Why

The web had two apps behind two entry pages (a Dashboard for Today and Me; a Console with 23 routes in five groups), with
two shells, two URL schemes and a link between them. Analysis was spread over five rail groups; going from a score to its
detail left one app for the other; comparing two metrics was a separate workspace that did not know about scores. The goal
is analysis first; planning, logging and editing are secondary.

## The shell: one top bar, a side menu

One top bar (the menu button, a SECTION › TAB › VIEW breadcrumb, the range where a page reads it, Views, the account) and a side menu
that lists the sections, the active section's tabs and the active tab's views. `console/src/lib/nav.ts` is the one description of
the tree (tested); the menu (`SideMenu.tsx`) and the breadcrumb read only from it, and its defaults match each page's own. On a wide
screen the button hides the menu (remembered); below 1280 px it is a drawer over the page, closed by choosing a page, the scrim or
Escape, with the account in its foot. A menu link that changes only the tab or the view keeps the page's other parameters. What stays
in a page, because it is a parameter and not a place: range chips, Sessions' window and status filters, Me's 28D/90D.

## Web desk, W1: the places by job (26 Nov 2026)

The web is being decoupled from the phone: a planning and analysis desk, not a copy of the phone's tabs (proposal and milestones:
the project's "Web as a planning and analysis desk" and "Web desk: plan and milestones" documents). W1 only moves things, no page
changes content. The menu is now **Today, Me, Plan, Analyse, Review** and the **Data** gear:

| Place | Route | Holds |
|---|---|---|
| Analyse (was Lab) | `#/analyse/<tab>` | Compare, Evidence, Experiments, Regimen, Tests, Routine (the last three stay until the topic pages of W5) |
| Review | `#/review/<tab>` | Reports (was Lab › Reports), Sessions (the recorded workouts, was Plan › Sessions › Recorded; one workout is `#/review/sessions/<id>`), Log (was `#/log`) |
| Plan | `#/plan/<tab>` | Overview, Training, Food, Sessions (the planned ones only), Gear (rules + with/without), Races |
| Data (was Setup) | `#/data/<tab>` | Integrity, Rows (was Data), Connections |

Every old address redirects with its query kept (`#/lab/...`, `#/setup/...`, `#/log`, `#/plan/sessions/<id>`, `?view=recorded`), saved
views under the old names still open, and the sign-in return path accepts the old ones. The Desk (landing) arrives in W3; until then
the web opens on Today. Where the sections below say Lab, Setup or Log, read Analyse, Data and Review.

## The structure

One app, one shell, one router (`console/src/App.tsx`). Five tabs in the phone's order, and a gear:

| Tab | Route | What |
|---|---|---|
| **Today** | `#/today?day=` | The phone's TODAY for any day: CHARGE and the five rings, the overlays behind them, the day's plan. A ring opens its inputs; an input opens its metric page. |
| **Me** | `#/me?tab=` | The phone's eight tabs, drawn from what the phone worked out (`me_views`). BODY carries the scale analysis below it; EXPOSURES carries the risk analysis. INTIMACY is drawn by the web itself from the tables (see below). |
| **Lab** | `#/lab/<tab>` | Ask, compare, test, decide. COMPARE, EVIDENCE, EXPERIMENTS, REGIMEN, TESTS, ROUTINE. |
| **Plan** | `#/plan/<tab>` | OVERVIEW, TRAINING (block planner), FOOD (meal prep), SESSIONS, GEAR (lifting gear rules and the with/without comparison, `docs/training-programming/03-lifting-gear.md`), RACES. The phone's tab names. |
| **Log** | `#/log?day=` | The day's log, and the fixes (editing is secondary). |
| ⚙ **Setup** | `#/setup/<tab>` | INTEGRITY, DATA, CONNECTIONS: the plumbing. |

Lab, Plan and Setup are built from one component (`components/layout/TabbedPage.tsx`): the tab is in the path, a sub-view in
the query (`#/lab/evidence?view=board`), so any view can be linked.

### Lab

- **COMPARE**: DAYS (default) is the metric page's compare, embedded: any score, input or series on the same days, a pair at
  a lag. PERIODS is Explore, unchanged: weekly and monthly grain over ten years, drill-down, the Inspector. Two views until a
  later pass unifies them; Explore does things the days view does not (period grain, drill-down, Inspector, faded
  low-coverage points).
- **EVIDENCE**: DECISIONS (queue and workbench), EVIDENCE BOARD, LEDGER, EVENTS, DISCOVERY, TRUST (was Models).
- **EXPERIMENTS**: RANDOMISED, INTERVENTIONS, REPEATABLE, FUELLING.

## From a score to its detail, and compare

Every tappable number opens **the metric page**, `#/m/<ref>` (`workspaces/Metric.tsx`, addresses in `lib/metrics.ts`), as the
phone's `MetricHistoryOverlay` does: the latest value and where it sits, 30 days to all of it with your usual band, the
numbers of the window, what it is, the days behind it.

| Ref | Is | Source |
|---|---|---|
| `charge`, `sleep`, `recovery`, `load`, `fuel`, `balance` | a ring | `score_history`, since Mar 2026 |
| `recovery.hrv`, `sleep.hours`, ... | a score input | `score_snapshots` (value and your usual), since 6 Oct 2026 |
| `series:hrv`, ... | a daily series of the contract file | its table, the whole history |
| `me:BODY:SECTION::Weight` | a tile or chart of a Me tab | the points the phone sent with it |
| `lab:Ferritin` | a blood marker (Lab › Tests) | `lab_results`, one point per draw, with the lab's reference range |
| `test:grip:left`, `test:grip`, `test:sweat_rate` | a test taken on the phone | `grip_strength_log` (best of the day), `sweat_tests` |

Eight inputs are read straight from a raw column (`INPUT_SERIES` in `lib/metrics.ts`); their page offers the whole history.
Day keying was checked against the live data: an input and its raw series match on the same day.

Numbers outside Today and Me link here too: each value on Lab › Tests (blood markers, grip, sweat rate), and a recorded
session's duration, distance, average heart rate and RPE where a series follows them for its type (`sessionSeries`).
A click on a point of the chart marks its day and opens what is behind it in the Inspector: the day's records of a series (or of
the series behind an input), the one result of a blood marker.

`+ COMPARE` draws up to three more metrics on the same days (`?with=a&with=b`, repeated parameters: refs hold commas and
colons). One panel per unit, one time axis, each metric in its own hue (`--m-*`); two metrics of one hue are told apart by
the line, never by a new colour. With one extra the page reads the pair as the phone's Lab › Explore does
(`lib/compare.ts`, a port of `domain/Correlation.kt`: Pearson's r, its Fisher 95% interval, descriptive only). The evidence
board is the corrected, registered version of the same question, and the page says so.

## Sessions and editing the plan

**Recorded workouts** (`#/plan/sessions/<id>`, list: Plan › Sessions › Recorded): what the watch measured is shown read-only; what is the
person's is editable for every source, as on the phone (`lib/sessionEdit.ts`): RPE 0-10, notes, a run's route and run type, a strength
session's exercises and sets. `details` is written from the row as it is when saving with only the owned keys changed; changing the
exercises makes them the person's own (`exercises_source: app`) so no Zepp sync overwrites them. A still-open plan of the day can be
linked to a workout (the phone's `linkPlanned`).

**Planned sessions** (`#/plan/sessions/planned/<id>`): while a session is still planned, unlinked and dated today or later, in a block that has
not ended: its date, time, length and a note (the title is the program's key and is not edited); sets, reps and load of each lift; take it
out of the plan. Blocks that have not begun can be moved (Plan › Overview). The rules are the database's
(`supabase/migrations/20261124063309_plan_edit.sql`, tested on a real Postgres engine in `supabase/tests/plan_edit.pglite.mjs`):

- `planned_sessions` refuses changes to `prescription`, `original_date`, `sequence_no` and `block_id`. A move changes `scheduled_date` only; a
  prescription change is a **new revision** (the old row is set aside); "delete" **sets a session aside** (`superseded`, never deleted:
  every reader already skips it, and the phone takes its Zepp template off the watch). A hard delete would orphan the template.
- A block's start moves with all its sessions in one transaction, and is refused when it would overlap another block, fall outside its
  training cycle, or when the block has begun. The shift is recorded in the block's `choices.start_shift_days` so "moved from" stays honest.
- A changed session reaches the watch the next time the phone sends (it replaces a template whose hash changed, a week ahead). Nothing is
  pushed to a calendar by this schema.
- The `note` is web-only: the phone shows no note on a planned session.
- Found on the way: the phone's and the web's accessory update (`updateBlockAccessories`) write `prescription` directly, which the trigger
  refuses (the database logs show it). It needs the revision path; the Android side is not changed here.

## Me › Intimacy

`#/me?tab=INTIMACY&view=` (`workspaces/Intimacy.tsx`, rules in `lib/intimacy.ts`, reads and writes in `lib/intimacyData.ts`): the phone's
ME › INTIMACY in more depth, read straight from `encounters`, `people`, `sexual_activity_daily` (solo), `sti_tests` and the daily series.
Seven views, each a link (`INTIMACY_VIEWS` in `lib/nav.ts`): OVERVIEW (tiles, active days by week, gaps and streaks, a year calendar, weekday
by hour), ENCOUNTERS (every encounter, filters, a completeness strip; a row opens a panel where an untagged encounter's tags and rating can be
filled in), MIX (how often, month by month, what goes together, each tag against your rating and orgasm intensity with an interval), SOLO,
PARTNERS (every profile, breakdowns, repeat rate, a timeline; `&person=<id>` is one partner, with the profile editable), HEALTH (STI tests as
the phone keeps them: log a panel, mark treated, delete; morning erection and arousal with the phone's correlations) and EFFECTS (each daily
measure on the days around partnered sex, solo or any, against quiet days, with an interval).

- **Discreet mode** is on unless turned off on that browser: partners by their initials, notes folded, search by name off.
- Writes touch only the columns the page edits: an encounter's tag lists and `my_rating`; a person's profile fields (not the activities and
  interests lists); `sti_tests` rows. The vocabularies are the phone's, checked against the Kotlin by `intimacy.test.ts`.
- Partnered and solo have their own metric hues, `--m-sex-partnered` and `--m-sex-solo` (`FT.Metric.SexPartnered` / `SexSolo` on the phone).
- `e2e/intimacy.mts` walks every view against invented people and encounters (never real ones).

## Old routes

Every old route redirects with its query kept as written (`components/layout/Redirect.tsx`, the `MOVED` table in `App.tsx`):
`#/explore` → Lab › Compare › Periods, `#/evidence` → Lab › Evidence, `#/models` → Lab › Evidence › Trust,
`#/experiments|interventions|regimen|routine|tests` → Lab, `#/plan-block|meal-prep|sessions|races` → Plan,
`#/data|integrity|connections` → Setup, `#/body` and `#/risk` → Me, `#/history` → the metric page, `/console/#/...` → the root.
The link builders (`lib/links.ts`) write the new paths directly.

## Fuel plan (Plan › Food)

Food has two views: **Fuel plan** (the default) and **Meal prep**. The fuel plan shows the next 17 days: for each, the carbohydrate,
protein, fat and energy, why (the training of the next 72 hours, loading before a race, the goal), the day's sessions with what to
take during them, and the meals that carry the day, each with a carbohydrate code and range. It is worked out in the browser with the
phone's own rules, ported to `supabase/functions/_shared/fuel-core` and checked against the Kotlin code by fixtures
(`*ParityTest` in `android-app`, `*.parity.test.ts` here): `day.ts` (targets), `plannedDay.ts` (the training of a day from events,
planned sessions, the calendar and recordings), `weekEnergy.ts` (goal, observed or estimated maintenance), `mealCodes.ts` (the
meals). `console/src/lib/fuelInputs.ts` builds the engine's inputs from table rows; `fuelDay.ts` reads them. Read only: `fuel_plans`
stays the phone's record.

The phone's fuel settings (meal times, train low, the carbohydrate the gut tolerates, caffeine, salty sweat) are kept with the account
(`user_profile.fuel_settings`, synced by the phone's `FuelSettingsStore` on every open and on save) and read here by `lib/fuelSettings.ts`;
until the phone has sent them the defaults are used and the page says so. A difference from the phone to know about: the phone's answers to "what is this?" for calendar entries are read
(`calendar_event_overrides`) but cannot be given here. To regenerate the fixtures after a deliberate rule change:
`FT_PARITY_WRITE=1 ./gradlew :app:testDebugUnitTest --tests '*ParityTest*'` in `android-app`.

**Races** (Plan › Races › FUEL PLAN on an event): the plan for a race or long session, by time and by aid station: carbohydrate, fluid and sodium per hour and in all,
the steps down the course, what to carry between stations, before and after, the weather (Open-Meteo: the forecast within 16 days, else the typical weather of those
dates over ten years), what it assumes and what to watch for. Engine: `heat.ts`, `sweat.ts`, `course.ts`, `raceFuel.ts`; reads: `raceFuelData.ts`, `weather.ts`.
An event with no start point takes the weather at the account's Home place (`places`, category home, not stopped, the oldest; the phone's
`PlacesRepository.home` reads the same, then falls back to Setup's home location on the phone); no place at all means no weather. Long-session
schedules are seeded with the weather at home too, and all three use the phone's fuel settings.

**The record (`fuel_plans`)**: opening the fuel plan keeps today's plan, and opening an event's plan keeps that event's, as the phone does when it shows them: one
append-only row (`kind`, `ref_key`, `engine_version` 4, `inputs_hash`, `plan`). `fuel-core/snapshot.ts` makes the snapshot and the hash the phone makes (Java's
`String.hashCode` of the Kotlin JSON text; it reproduces all 83 hashes the phone has stored, and Kotlin fixtures cover the rest). The web never competes with the
phone for a row: it writes a first copy when there is none, and a changed copy only over a row it wrote itself (`plan.by = "web"`, which the phone ignores); when the
latest row is the phone's and differs, it writes nothing and says what differs. Rules: `fuelPlansDecide.ts`, `fuelPlansStore.ts`.

On today's plan each meal also says what was logged against it (by the meal's own slot label, else the nearest meal within two hours: `mealForLog` / `adherence`, parity-tested).

**Long sessions** (on the fuel plan, a session of 90 minutes or more that is not an event): FUEL SCHEDULE opens the editor for what to take and when, as blocks
(`schedule.ts`: the phone's `FuelBlock`, parity-tested: expansion, totals, per-hour rates, warnings, the plan as blocks). It is kept in `fuel_schedules` under the
plan item, as the phone keeps it (`fuelScheduleKey.ts`), so the phone shows and debriefs the same schedule. Start from the planner's plan, from a standard, or empty.
**FILL** adds a rule in one step ("one serving of the gel every 30 minutes from the 30th, none in the last 10"). **Standards** (`standards.ts`, web only, table
`fuel_standards`: migration `20261124111137_fuel_standards.sql`, apply it before standards can be saved) are reusable rules, applied to a session of any length;
SAVE AS A STANDARD takes the shape of the schedule on screen. Not here: the debrief (the phone's), supplements in the product list, the phone's home place for weather.

## Look

`ftSkin.css`: one class, `ft-skin`, bridges the console's variables (`--color-*`) to the Dashboard's tokens
(`design/tokens`): domain wash behind, neutral glass cards, selection and the primary action platinum, status as Ok/Warn/Bad,
ink the metric's. Applied to the Lab, to Body and Risk inside Me, and to the shell's chrome and Inspector. **Not yet applied
to Plan, Log, Setup and Login content**: add `skin` to their `TabbedPage` (Plan, Setup) or the class to their root.
Canvas charts read the tokens at draw time (`lib/palette.ts`); Explore, Body, Risk and the questionnaires use the metric
tokens. Other charts (Evidence, Experiments, Integrity) still have fixed accent colours.

## Deploy

One build. The workflow writes it to the site root; hashed chunks stay in `console/assets`; `console/index.html` is a stub that
forwards `/console/#/...` to the root. Both are written by CI, never by hand.

## Not done

- Me tiles and charts can be a page's metric, not an extra in a comparison.
- On the metric page, a click on a score, an input with no series behind it, a Me item, grip or sweat rate marks its day only:
  none has records the Inspector can show.
- Body check-ins and running form open no metric page: neither has one number to follow.
- Me **rows** open nothing: the phone sends them with a label and a finished value only. Giving them a key is an Android
  change to `me_views`.
- Compare's two views are not unified.
- The web screens are not on the Field Terminal Map (the map covers the Android app).
