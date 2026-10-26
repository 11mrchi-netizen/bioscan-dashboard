# Field Terminal — Information Architecture Contract

**Status:** Locked navigation/content contract for the UI & Analysis Layer Consolidation project
**Version:** 1.0
**Date:** 2026-09-19
**Linear:** [DAV-93](https://linear.app/biodashboard/issue/DAV-93)

## 1. Purpose and authority

This document is the implementation-ready IA/content contract DAV-93 asks for — it exists so
screen-by-screen build work (DAV-94 through DAV-108) doesn't re-litigate naming or hierarchy while
it's happening. It does not replace Linear as the authority for scope or acceptance criteria, and it
does not replace [FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md](FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md) or
[ANALYTICAL_PRESENTATION_CONTRACT.md](ANALYTICAL_PRESENTATION_CONTRACT.md) as the authority for visual
language or presentation-component behavior. This is the fourth layer: what each screen is *called*
and *contains*, so those two contracts have somewhere fixed to attach to.

Every mapping below was checked against the real current Kotlin tab enums (`HeartTab`, `FuelTab`),
not assumed from the mockup or the project description — see each section's own note.

## 2. Frozen boundaries (unchanged — do not touch in this project)

Login, Add Entry (workflow/IA), broad Setup IA (only sync status relocates here — DAV-94), Log's
calendar-free chronological-feed behavior, and Map's spatial IA. Any issue in this project that
appears to require touching one of these has scope-crept — stop and re-check against DAV-107.

> **Update 2026-10 (UX review, A6; `docs/ux-review/01-plan.md`):** released for that review only:
> - **Add Entry**: new entry points (Status quick log: water, food, supplements, say it), the type picker grouped
>   INTAKE / BODY / TRAINING / INTIMACY / OTHER with Fuel's sub-types as first-level tiles, a RECENT row, Drink presets,
>   and injury / illness entry points (their forms already existed under Training › Injury). The forms and the tables
>   they write are unchanged.
> - **Setup**: USER is renamed PROFILE & GOALS (one USER in the app); TRAINING PROGRAMS becomes TRAINING SETUP
>   (equipment, session times) because blocks move to Training › Plan (§5); Connected services' MAP tab is MAPS & ROUTES.
> - **Log**: tapping an entry opens an entry sheet (values first, then detail / edit / delete) instead of an action menu.
>   The feed stays one chronological, calendar-free stream (PR #20's collapsed days and GAPS ONLY are kept).
> Login and Map stay frozen.

## 3. Top-level navigation

Four tabs: `STATUS | USER | LOG | SETUP`. (DAV-296: USER replaced MAP in the bottom bar; Map's screen stays in the codebase, unmounted.)

> **Update 2026-10 (UX review):** with Map unmounted, Google Calendar access is granted from Status's Next up row (tap it
> when it says Calendar isn't connected); nothing points to a Map tab any more. The rose-accent Status tile and its page
> are called **HEALTH** (the code keeps `TileRoute.Heart` / `HeartTab`); §7's tab mapping is unchanged. On a pushed
> screen the dock lights the tab whose stack you are in.

> **Update 2026-10 (app restructure, the owner's "Field terminal new structure"; supersedes the tab notes below):**
> the Status hero opens **Today**; the four tiles are **Training**, **Nutrition** (was Fuel), **Health** (was Heart) and
> **Lab** (was Labs). Tile route strings are unchanged. Tabs (`ui/nav/TopLevelTab.kt`):
> - Today: THIS MORNING · IS TODAY TYPICAL? · TODAY'S RECOVERY · READY FOR · links to Training › Load and Health › Cardio ·
>   NUTRITION TODAY (fuel line, progress against the day's targets, meals). Last evening's check-in is a header button.
> - `TrainingTab`: LOAD (training load, intensity) · RUNNING · STRENGTH (the former User skill pages) · SESSIONS (ten at a
>   time) · INJURY.
> - `NutritionTab`: NUTRITION (with protein on target) · HYDRATION · PLAN (today and the next two days).
> - `HealthTab`: CARDIO · SLEEP (was Recovery) · BODY (from Fuel) · WELLBEING · SEXUAL HEALTH · BLOODWORK (from Labs).
> - `LabTab`: N=1 (what goes with what, what came before off mornings, experiments, the scorecards, sexual-health
>   correlations) · SUPPLEMENTS · PLANNING (`PlanningSection`: training block, food plan with meal prep, race fuel) ·
>   TESTS (grip, body check-in, sweat test, running form analyser).
> - User keeps Healthspan, Aging, the level buttons (Running and Strength open Training's tabs) and the block card (opens
>   Lab › Planning). Training blocks' one home moved with the planner from Training › Plan to Lab › Planning › Training.

> **Update 2026-10:** the tab sets in §6/§7 below were implemented differently — see `TopLevelTab.kt`: `FuelTab` = Nutrition, Digestion; `HeartTab` = Cardio, Recovery, Wellbeing; `TrainingTab` = Load, Injury; `LabsTab` = Bloodwork, Supplements (Hydration and Body folded into Fuel/Nutrition; Supplements moved under Labs; Injury moved under Training — DAV-207/208/209/216). The code is authoritative where it differs.

## 4. Status IA — Body/Condition as hub

Today: Status renders a flat 2×2 grid of Training/Fuel/Heart/Labs tiles below the existing
`BodyConsole.kt` schematic. Target (DAV-95): the schematic becomes the compositional center; the four
system tiles attach to it as glass instruments with a compact state/metric preview, not an unrelated
grid. Tapping an attached tile still opens that system's existing tile surface — this is a
compositional change, not a new navigation destination.

> **Update 2026-10 (UX review, P1/P6):** the hub reads "today at a glance": hero (HRV-vs-baseline condition with its basis
> and freshness, TODAY › link), a shorter body figure whose injury flag opens Training › Injury, a tappable Next up row,
> the four tiles as a 2×2 grid with real previews (Fuel: kcal vs goal and water vs range; Labs: supplements still due and
> last draw), and a quick-log row. The hub scrolls. The hero's destination is the **Today** page (formerly Daily
> Readiness): recovery, READY FOR, fuel today, patterns; it links to Training › Load and Health › Cardio instead of
> repeating them.
>
> **Update 2026-10 (opening-page clean-up, owner request):** the hero is only this morning's HRV and its state pill (STALE
> when the reading is old); its label, the "as expected for a ..." sentence and the freshness line moved off the hub (all
> on Today, where a tap on the hero goes). The zone legend, SLEEP and FLAGS under the figure are gone; the figure keeps its
> tints and its injury pin. The four tiles show icon, title and one coloured dot, and the dot is the tile's only
> indicator: Training = load, Health = the worst of HRV/RHR, sleep and HRV vs expected, Nutrition = logged calories and
> water against the pace for the time of day, Lab = today's supplements (`domain/HubTileStates.kt`). The dot is announced
> in words to screen readers. `BodyConsole.kt` already exists; DAV-95 should
confirm during its own investigation whether it extends that file or replaces it — not decided here
(see §6).

## 5. Training — hierarchy, not new tabs

Current: `TrainingTileScreen.kt` has no sub-tabs — one flat screen, equal-weight sections. Target
(DAV-98): the same single screen, restructured into an explicit vertical hierarchy:

1. **LOAD** — CTL/ATL/TSB (already computed by `TrainingLoadEvaluation.kt`), current state,
   confidence, cycle tier, rest-cadence context.
2. **PERFORMANCE** — VO2max and other supported performance trends.
3. **SESSIONS** — recent/weekly session history.

No new sub-tab rail — this is section ordering within one screen, matching how the screen already
works today.

> **Update 2026-10 (UX review, P2/P5):** the LOAD tab (now one of LOAD / PLAN / INJURY) opens with TRAINING LOAD as its
> hero card, then READY FOR, then the session rollups (VO2max and efficiency live on the Running skill page since PR #20).
> **Training › Plan is the one home for training blocks**: current block (editable), new block, upcoming sessions,
> planned blocks and history. `training_blocks` is the canonical block and `training_cycles` its legacy mirror
> (`docs/training-programming/01-architecture.md`), so the User tab keeps only a current-block card that opens Plan.

## 6. Fuel — real tab mapping (checked against `FuelTab` enum)

| Current (`FuelTab`) | Target | Change |
|---|---|---|
| `Nutrition` | NUTRITION | Visual/hierarchy only (calories → macros → meals → trend, per DAV-100) |
| `Hydration` | HYDRATION | Visual only |
| `Supplements` | SUPPLEMENTS | Minimal change — retain time-of-day grouping |
| — (new) | **DIGESTION** | New tab. Relocated content: Bristol/stool data currently lives in Heart's `Stool` tab (`BristolEvaluation.kt`/`BristolCard`) — moves here wholesale. No causal food→stool link is implied or computed; it's a contextual section, not a correlation feature. |
| `Weight` | **BODY** | Rename, not rebuild — `WeightTdeeTab` already exists and already has weight/body-fat trend content. DAV-103/104 extend it with the adaptive-TDEE panel and explicit confidence states; the existing trend content is not thrown away. |

**Amendment (body check-in, Oct 2026):** the shipped tabs are NUTRITION (with hydration) and BODY. BODY (the enum entry
was `Digestion` until the monthly body check-in arrived) holds weight, BODY MEASUREMENTS (the check-in's girths,
skinfolds, ratios and labelled body-fat estimates, with START BODY CHECK-IN), body fat, protein on-target, energy
balance, then the Bristol pattern. The monthly reminder lands here with the check-in sheet open (docs/body-checkin/README.md).

## 7. Heart — real tab mapping (checked against `HeartTab` enum: `Heart, Arousal, Wellness, Sleep,
Stool, Injuries, Respiratory`)

| Current (`HeartTab`) | Target | Change |
|---|---|---|
| `Heart` (HRV/RHR) | CARDIO | Merge |
| `Respiratory` | CARDIO | Merge — respiratory-rate anomaly (`evaluateRespiratoryAnomaly`) joins HRV/RHR under one Cardio surface, per DAV-101's own "HRV, resting HR and respiratory state" grouping |
| `Sleep` | RECOVERY | Rename — sleep duration/stages/regularity |
| `Wellness` | WELLBEING | Merge |
| `Arousal` | WELLBEING | Merge — becomes one of Wellbeing's comparable small-multiple dimensions (energy/mood/stress/soreness/arousal), not its own tab |
| `Injuries` | INJURY | Rename |
| `Stool` | *(moved out)* | Relocates to Fuel → DIGESTION (§6). Heart has zero digestion content after this project. |

Net: 7 tabs → 4, with one tab's content leaving Heart entirely rather than being collapsed into it.

## 8. Labs — structurally unchanged

Per DAV-102: retain current thematic marker grouping and latest-vs-earlier comparison. This is a
visualization-pass target (dot plots over line charts for sparse draws), not an IA change — no tab
mapping needed here.

## 9. Session Detail — content contract (locked now, built in M5/DAV-106)

Summary → route/map (when route data exists) → one switchable performance chart
(`[HR] [PACE] [POWER] [CAL]`, only showing signals that exist for that session) → splits/intervals.

> **Update 2026-10:** as built, the switchable chart (`HR / PACE / POWER / CADENCE`) lives inside SUMMARY, the route map was deliberately moved to the bottom (direct user request, 28/9), and CADENCE replaced CAL. The summary leads with one hero value (distance, or duration for strength) with duration and pace as its context line. The code is authoritative.
Locking this now, even though DAV-106 is a milestone-5 issue, so the chart-switching component built
for it doesn't get designed twice.

## 10. Explicitly deferred — not decided by this document

- **BodyConsole.kt extend-vs-rebuild** — DAV-95's own call once it investigates the existing file.
- **Figma component sync** (StatePill, TrendIndicator, etc. per DAV-105) — blocked on Figma
  Starter-plan MCP quota per DAV-105's own note; resume when quota is available, do not let this
  block Android-side IA or screen work.
- **Exact visual treatment of the Cardio/Wellbeing merges** (e.g. whether Respiratory gets its own
  sub-heading inside Cardio, or Arousal its own row inside Wellbeing's small multiples) — a DAV-99/101
  visualization-layer decision, not an IA one; this doc only fixes which screen owns the data.

## Redesign amendment (2026-10): cards, range, page contents

Supersedes earlier rules where they differ. The code is authoritative; this records the intent.

- **Header band**: page title only. The context line / subline of metric names is gone from Training, Nutrition, Health and Today.
- **Global range**: one top-bar selector (7D · 1M · 3M · 6M · 1Y · ALL, `GlobalTimeRange`) sets the window and grain of every graph on Training, Nutrition and Health. Daily values up to 1M, weekly means for 3M and 6M, monthly means for 1Y and ALL. Per-page timeframe toggles are removed. Training charts take whole months (7D/1M read as a month; ALL as a year).
- **Card layouts** (`ui/components/RedesignCards.kt`): Trend, Trend and Baseline, Range Trend (floating low-to-high columns), Hypnogram. Header: latest reading top left with its direction pill below; title right; the "?" at the title's end holds the explanatory notes (no explanatory paragraphs inside cards).
- **Baseline shift band**: baseline at the centre, limits at 10 % and 90 % of the bar, current day a dot without a number, limit values in a quiet font. The baseline is the selected window's mean and the limits one standard deviation either side (five readings minimum).
- **Range columns at week/month grain** span the mean of the daily lows to the mean of the daily highs.
- **Health**: no glance-chip strip. Cardio = HRV, Heart rate (range), RHR, SpO2, Steps, Respiratory rate (blood pressure hidden until a manual log exists). Sleep = Sleep index (score on the right; regularity inside it), Duration, REM / Light / Deep trend cards, Circadian alignment (unchanged, pending refinement). Body = Weight, Body measurements (body map), Body fat (blended), Energy balance. Wellbeing = four trend cards and Physiological stress. Grip strength lives on Training › Strength.
- **Today**: This morning, Is today typical, Recovery and Ready for, Nutrition today, Glucose spiking (placeholder), Intraday heart rate, Sleep phase breakdown (stage minutes until timed stages are stored), Physiological stress (intraday).
- **Log picker**: one Health entry replaces Health check-in, Injury check-in, Injury / pain and Illness / symptom. See `docs/ft-redesign/model-plans.md` for the three models still to build (hypnogram, glucose spiking, hydration accounting).
