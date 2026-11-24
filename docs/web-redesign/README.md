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

## The structure

One app, one shell, one router (`console/src/App.tsx`). Five tabs in the phone's order, and a gear:

| Tab | Route | What |
|---|---|---|
| **Today** | `#/today?day=` | The phone's TODAY for any day: CHARGE and the five rings, the overlays behind them, the day's plan. A ring opens its inputs; an input opens its metric page. |
| **Me** | `#/me?tab=` | The phone's seven tabs, drawn from what the phone worked out (`me_views`). BODY carries the scale analysis below it; EXPOSURES carries the risk analysis. |
| **Lab** | `#/lab/<tab>` | Ask, compare, test, decide. COMPARE, EVIDENCE, EXPERIMENTS, REGIMEN, TESTS, ROUTINE. |
| **Plan** | `#/plan/<tab>` | OVERVIEW, TRAINING (block planner), FOOD (meal prep), SESSIONS, RACES. The phone's tab names. |
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

Eight inputs are read straight from a raw column (`INPUT_SERIES` in `lib/metrics.ts`); their page offers the whole history.
Day keying was checked against the live data: an input and its raw series match on the same day.

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
(`supabase/migrations/20261125000000_plan_edit.sql`, tested on a real Postgres engine in `supabase/tests/plan_edit.pglite.mjs`):

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

Differences from the phone to know about: the phone's fuel settings (meal times, train low, the carbohydrate the gut tolerates) live
only on the phone, so the web uses the defaults; the phone's answers to "what is this?" for calendar entries are read
(`calendar_event_overrides`) but cannot be given here. To regenerate the fixtures after a deliberate rule change:
`FT_PARITY_WRITE=1 ./gradlew :app:testDebugUnitTest --tests '*ParityTest*'` in `android-app`.

**Races** (Plan › Races › FUEL PLAN on an event): the plan for a race or long session, by time and by aid station: carbohydrate, fluid and sodium per hour and in all,
the steps down the course, what to carry between stations, before and after, the weather (Open-Meteo: the forecast within 16 days, else the typical weather of those
dates over ten years), what it assumes and what to watch for. Engine: `heat.ts`, `sweat.ts`, `course.ts`, `raceFuel.ts`; reads: `raceFuelData.ts`, `weather.ts`.
The phone's home place (the weather of an event with no start point) and its carbohydrate tolerance, caffeine and salty-sweat settings are phone-only: no place means
no weather, and the defaults are used.

Not built yet: the intake against the plan, writing `fuel_plans`, long sessions from the day plan (the phone offers them from Today).

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

- Clicking a point on the metric page does not open the Inspector (the days table's RECORDS links cover it).
- Me tiles and charts can be a page's metric, not an extra in a comparison.
- Me **rows** open nothing: the phone sends them with a label and a finished value only. Giving them a key is an Android
  change to `me_views`.
- Numbers outside Today and Me (Tests markers, Lab results, Plan outcomes) do not link to the metric page yet.
- Compare's two views are not unified.
- The web screens are not on the Field Terminal Map (the map covers the Android app).
