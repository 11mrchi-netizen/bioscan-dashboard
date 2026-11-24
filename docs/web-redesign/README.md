# Web redesign: one app, built around analysis

**Date:** 2026-11-24 · **Branch:** `claude/friendly-archimedes-4kbb27` · **Supersedes:** the navigation of
[`analysis-console/IA.md`](../analysis-console/IA.md) §3.1 and §4, and the two-surface split (Dashboard + Console) of the
web transition plan.

## Why

The web had two apps behind two entry pages (a Dashboard for Today and Me; a Console with 23 routes in five groups), with
two shells, two URL schemes and a link between them. Analysis was spread over five rail groups; going from a score to its
detail left one app for the other; comparing two metrics was a separate workspace that did not know about scores. The goal
is analysis first; planning, logging and editing are secondary.

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

## Old routes

Every old route redirects with its query kept as written (`components/layout/Redirect.tsx`, the `MOVED` table in `App.tsx`):
`#/explore` → Lab › Compare › Periods, `#/evidence` → Lab › Evidence, `#/models` → Lab › Evidence › Trust,
`#/experiments|interventions|regimen|routine|tests` → Lab, `#/plan-block|meal-prep|sessions|races` → Plan,
`#/data|integrity|connections` → Setup, `#/body` and `#/risk` → Me, `#/history` → the metric page, `/console/#/...` → the root.
The link builders (`lib/links.ts`) write the new paths directly.

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
