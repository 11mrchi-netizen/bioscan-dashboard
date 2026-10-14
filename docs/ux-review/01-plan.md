# Field Terminal UX review: build plan

Status: phases 0–5 built on branch `ccr-b7b17b42-flzlrb` (draft PR #20), one commit per phase; see §7. Awaiting an
Android build and the on-device checks listed in the PR.
Source review: "Field Terminal UX Review" (claude.ai artifact NTRCX87sgYpRFwaPm9NGaG, 14 Oct 2026), findings F1–F14,
proposals P1–P8, contract amendments A1–A7.

This plan applies the review to the Android app as it stands on PR #20, which already changed three of the reviewed
areas. Where PR #20 and the review overlap, PR #20's design is kept and the review item is adapted to it.

## 1. What PR #20 already changed

| Area | PR #20 | Effect on the review |
|---|---|---|
| Log tab | Collapsed day rows with category marks (solid / half / dashed / faint), GAPS ONLY filter, EXPAND ALL, paging by whole days (`domain/LogDays.kt`). | P4's category filter chips are dropped: the marks already show which categories a day has, and GAPS ONLY covers "what is missing". P4 keeps: entry sheet instead of the action menu, a header that counts today, a labelled + LOG button, press feedback. |
| User tab | Healthspan radar, three skill buttons and skill pages; mock domain levels removed. | F6 still applies: the CURRENT TRAINING BLOCK and HISTORY cards are still on User. |
| Training › Load | VO2max and efficiency charts moved to the Running skill page; RUNNING / STRENGTH cards link there. | F10 still applies (readiness above LOAD, three single-line load charts). |
| Training screens | Inline `fontSize` replaced with `FTType` roles in the planner, plan tab, training settings and blocks screens. | F14 drops from 45 raw sizes to 5; the 3 on mounted screens are fixed here. |
| Time picker | Wheel index fix. | No overlap. |

PR #20 also had two failing unit tests (found by running the suite, see §6): `SkillSeriesTest` expects 2500 kg for sets
that add up to 1500 kg, and `WarmUpTest` still expects `press` for "Standing Barbell Press", which `03cf62e`
deliberately maps to `overhead press` (the Zepp function accepts both keys). Both tests are corrected; the code is right.

## 2. Decisions taken

The review left five questions open. The request was to tackle every issue, so the review's recommendations are applied:

1. **Status hero** keeps the HRV-against-baseline band, names its basis ("CONDITION · HRV VS BASELINE"), shows how fresh
   the reading is, and links the fuller picture as TODAY ›.
2. **Frozen IA areas are released** for: Add Entry entry points and grouping, Setup's training section and naming, and the
   Log feed's entry tap. Recorded in `design/FIELD_TERMINAL_IA_CONTRACT.md`.
3. **HEALTH, not Heart**, in the app and in the contract's domain table (rose accent unchanged). The section holds sleep,
   wellbeing and sexual health as well as cardio.
4. **A training cycle and a generated block are the same thing.** Per `docs/training-programming/01-architecture.md`,
   `training_blocks` is the canonical block and `training_cycles` the legacy table kept for the User page and
   `v_training_block_metrics`, linked by `training_blocks.cycle_id` (all 14 cycles were backfilled as imported blocks).
   So the block UI moves to one home: Training › Plan.
5. **Analysis Console**: out of scope here; a separate pass later.

## 3. Findings → phases

| # | Finding | Phase |
|---|---|---|
| F1 | Flag says SEE HEALTH, Next up points to the removed Map tab; neither tappable | 0 |
| F2 | Pushed screens untitled; no dock tab lit on pushed routes; rail scrolls away | 1 |
| F3 | Water / evening supplements take 6 taps | 2 (quick log), 3 (picker) |
| F4 | Status: FUEL / LABS say OPEN, no freshness, hidden hero tap, no scroll | 2 |
| F5 | CONDITION / TODAY'S READINESS / TODAY'S RECOVERY | 2 |
| F6 | Blocks in three places, two tables | 5 |
| F7 | 81 clickables with no press or focus feedback | 1 |
| F8 | One button style for every job; Plan DELETE unconfirmed, errors swallowed | 0 (delete), 1 (button hierarchy) |
| F9 | Equal-weight cards, emerald titles everywhere | 4 |
| F10 | Training › Load out of order, three single-line charts | 4 |
| F11 | Log entry tap opens a menu; header counts all time; bare + | 3 (adapted to PR #20) |
| F12 | Two destinations called USER | 5 |
| F13 | Zepp diagnostics beside SYNC NOW | 0 |
| F14 | Raw font sizes, mono interface labels, duplicate screen-reader labels | 1 |

## 4. Phases

### Phase 0: dead ends (F1, F8 delete, F13)
- Body figure flag: names the region and day (`R KNEE · DAY 4`), taps through to Training › Injury (same requested-tab
  hand-off the supplement notification uses for Labs). `StatusOverview` gains the open injury / illness it already queries.
- Next up: tappable. Found → opens the calendar app at that time; no Calendar access → tap asks for it right there (an
  explicit tap, so the consent dialog is expected); error → tap retries. The Map-tab text is gone.
- Training › Plan: DELETE asks to confirm, failures show, a failed load shows COULDN'T LOAD + RETRY instead of an empty list.
- Setup › Connected services › Zepp: the ten test buttons move under a collapsed DIAGNOSTICS row; the ones that write or
  remove calendar entries confirm first.

### Phase 1: shared primitives (A1, A2, A4, A7; F2, F7, F8, F14)
- `Modifier.ftClickable` (new): press = strong glass fill, instant on, 120 ms fade off; focus = 2 dp emerald-300 ring;
  optional shape and role. Replaces every `indication = null` clickable.
- `FTButton` (renames `AmberButton`) with emphasis Primary / Secondary / Tertiary / Destructive; Inter label
  (`FTType.ButtonLabel`). One primary per form or sheet; destructive actions confirm inline.
- `TileHeader` gains a domain dot and is used with a title and context line on every pushed screen; tile pages pin the
  header and sub-tab rail and scroll only the content.
- Dock: lights the tab whose stack you are in (nearest top-level route in the back stack); icons no longer repeat their
  label to screen readers.
- Remaining raw font sizes on mounted screens → `FTType`.

### Phase 2: Status "today at a glance" and the Today page (P1, P6; F3, F4, F5)
- Hero: "CONDITION · HRV VS BASELINE", the band, state pill, a freshness line (latest reading date, plus the last Health
  Connect sync time when known) and an explicit TODAY › link.
- Body figure kept as the centre, shorter; zone legend unchanged.
- Four tiles become a 2×2 grid with real previews: Training (sessions in 7 days + load state), Fuel (kcal against the saved
  goal, water against the hydration range), Health (HRV, RHR, sleep), Labs (supplements still due today, last draw).
- Quick log row: + WATER, + FOOD, + SUPPS, SAY IT open the add sheet on the right form (SUPPS with the due bundle ticked).
- The hub scrolls; the first 390×844 frame still holds the hero and all four tiles.
- Daily Readiness becomes **Today**: titled, recovery first, then READY FOR, fuel today, patterns and typicality; links to
  Training › Load and Health › Cardio instead of repeating those cards. "TODAY'S READINESS" is renamed "READY FOR".

### Phase 3: logging (P3, P4 adapted to PR #20; F3, F11)
- Add sheet: SAY IT first; a RECENT row (last three types, one tap each); types grouped INTAKE (Food, Drink,
  Supplements, Run fuel), BODY (Wellbeing, Stool, Grip strength), TRAINING (Injury check-in, Injury, Illness),
  INTIMACY (Sexual activity, Encounter), OTHER (Note); Inter labels with category dots. Fuel's sub-picker step is gone
  (the chosen form opens directly; the switcher stays inside the form).
- Drink: 250 / 330 / 500 / 750 ml presets above the amount field.
- Log tab: header counts today and keeps PR #20's gap count; tapping an entry opens an entry sheet (time, kind, values,
  then DETAIL / EDIT / DELETE with the delete confirm kept); "+ LOG" button with a label.

### Phase 4: tile template (P2; F9, F10)
- `FTCard` titles neutral by default; `emphasis = Hero` for the one lead card per tab (strong glass, primary title).
- `FTGlanceStrip` (new): one chip per card below, carrying that card's state; tap scrolls to the card.
- Training › Load: TRAINING LOAD first as the hero (IA contract §5), the timeframe toggle on that card, CTL and ATL in one
  chart with a legend (two Activity steps + dash), TSB keeps its banded gauge and reference strip; READY FOR follows as a
  supporting card.
- Glance strips on Training › Load, Health › Cardio and Health › Recovery.

### Phase 5: one home for blocks, Setup clean-up (P5, P8; F6, F12)
- Training › Plan: CURRENT BLOCK (moved from User, editable), PLAN A NEW BLOCK (primary), UPCOMING, PLANNED BLOCKS,
  HISTORY (top three + VIEW ALL).
- User tab: one compact current-block card that opens Training › Plan.
- Setup: USER → PROFILE & GOALS; TRAINING PROGRAMS → TRAINING SETUP (equipment and session times; the program library and
  import live in the planner already); Connected services MAP tab → MAPS & ROUTES.

## 5. Contract amendments

Written into `design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md` (A1–A5, A7) and `design/FIELD_TERMINAL_IA_CONTRACT.md` (A6):
A1 interaction states, A2 button component, A3 card emphasis and neutral titles, A4 pushed-screen chrome and dock
parent tab, A5 freshness line, A6 released IA areas + HEALTH naming + Calendar consent location, A7 font roles for
compact labels vs buttons.

## 6. How this is verified, and what is not

Google Maven (`dl.google.com`) is blocked by this environment's egress policy, so AGP, AndroidX and Compose for Android
cannot be downloaded and the app cannot be built here. Instead, every phase is checked with a local type-check harness
(not committed): the app's Kotlin compiled with Kotlin 2.4.20 and the Compose compiler plugin against JetBrains' desktop
build of the same `androidx.compose` packages (1.8.2), Robolectric's `android-all` for API 37, and small stubs for the few
Android-only APIs (Health Connect, activity results, auth). The four platform-glue files that cannot be stubbed cheaply
(Health Connect, workers, MainActivity, Session Detail's Health Connect screen) are excluded. All 568 JVM unit tests run
against the result.

- PR #20 baseline: 0 type errors; 566 / 568 tests pass (the two wrong tests above).
- Each phase must keep 0 type errors and all tests passing before it is pushed.
- After phase 5: 0 type errors, 588 / 588 tests pass (4 skipped, as on the baseline); 20 new tests cover the freshness
  line, the supplements-due summary, the grouped add-entry choices and recents, the entry sheet's time line, and the
  injury flag.
- Not covered: Android-only Compose APIs newer than 1.8.2, resource merging, R8, and anything visual. Every phase lists
  on-device checks in the PR description; please run `./gradlew compileDebugKotlin testDebugUnitTest` and the checks.

## 7. Build status

| Phase | Commit | What landed |
|---|---|---|
| 0 | `e3d4f31` | Injury flag names the region and day and opens Training › Injury; tappable Next up (calendar, consent, retry); Plan delete confirms and reports failures; Zepp diagnostics collapsed, writes confirm. |
| 1 | `d7a7e6b` | `ftClickable` on every tap; `FTButton` with emphasis (was `AmberButton`) and `FTConfirmDestructive`; titled `TileHeader` / `PinnedPage`; dock keeps the parent tab lit; HEALTH, PROFILE & GOALS, TODAY naming. |
| 2 | `3bd5166` | Status hero with basis, freshness (`STALE_AFTER_DAYS`) and TODAY ›; scrolling hub, 2×2 tiles with Fuel and Labs previews; quick-log row; Today page (recovery, READY FOR, fuel, links, patterns). |
| 3 | `aabe01a` | Grouped add-entry picker with Fuel's forms first-level, RECENT row, injury/illness entry points, drink presets; Log header counts today; entry sheet; "+ LOG". |
| 4 | `136e9b9` | `FTCard` Hero / Supporting with neutral titles; `FTGlanceStrip` on Training › Load, Health › Cardio and Recovery; Load hero first with the timeframe on it; CTL + ATL on one chart with a legend. |
| 5 | `70fcd91` | Training › Plan holds the current block, planning, planned blocks and history; User keeps a compact card; Setup's TRAINING SETUP keeps equipment and session times; program import moved into the planner; MAPS & ROUTES. |

Where the build differs from the plan or the review:
- **Recents (P3)** repeat the last three *types* (one tap to the form), not past entries with their values; SAY IT stays a
  button that opens its form rather than an inline input.
- **Hero cards (A3)** are set where a tab leads with a card: Training › Load, Training › Plan, Health › Cardio, Health ›
  Recovery and the Today page. Fuel and Labs lead with a headline value, so they have no Hero card; their card titles turn
  neutral like all others.
- **Glance strips** are on the three long tabs named in the plan only.
- **Recording a block run elsewhere** (the legacy `training_cycles` add) stays on CURRENT BLOCK as a tertiary action, so
  nothing that could be recorded before is lost; planning a block is the primary path.
- **Program import** was only in Setup (the planner had the library but not the import); it now sits in the planner's
  first step, and the Setup-only `training_planner/{program}` route is removed.
- **Status figure** height is 30% of the viewport, clamped to 160–280 dp, so the hero and the four tiles fit the first
  390×844 frame at default font size; this is the main thing to confirm on a device.
- **F14**: `MapScreen.kt` (unmounted) keeps its raw font sizes.
