# Scoring system: CHARGE and five domains

Status: design, not built. Page mockups: [02-page-mockups.md](02-page-mockups.md).

## Why

The app has many metrics but gives no quick overall picture. This design adds one headline score and five domain
scores, and rebuilds the app around them. It reverses three earlier rules:

| Earlier rule | Where | Change |
|---|---|---|
| No composite score; never aggregate domains into one figure | `domain/CrossDomainStateModels.kt`, `domain/levels/DomainLevel.kt` | Replaced by CHARGE and the five domains |
| Never collapse multidimensional state into an opaque composite score | `design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md` | The scores are allowed because every point is traceable to its drivers |
| A composite whose scorecard reads NO BETTER shows INPUTS ONLY | `design/ANALYTICAL_PRESENTATION_CONTRACT.md` R8 | The scorecard re-fits weights and is reported in the score's "?"; the score is never hidden |
| Healthspan may only appear on the User page (unit test) | `domain/aging/Healthspan.kt` | Healthspan lives on ME; the test is updated to allow ME's sub-pages |

What stays: personal baselines, coverage and confidence, and a scorecard that checks each score against the person's
own history.

## The model

```
   CAUSES (yesterday)                          RESPONSE (last night)
 ┌──────┐ ┌──────┐ ┌─────────┐ ┌───────┐
 │ LOAD │ │ FUEL │ │ BALANCE │ │ SLEEP │ ──►  ┌──────────┐ ──► ┌────────┐
 └──────┘ └──────┘ └─────────┘ └───────┘      │ RECOVERY │     │ CHARGE │
     └────────┴──────────┴── feed into sleep ─►└──────────┘     └────────┘
                                                                    │
     HEALTHSPAN (slow: labs, VO2max, body comp, grip; on ME)        ▼ today's sessions
```

- **CHARGE**: how you should feel after yesterday. Published once each morning.
- **SLEEP**: did I get the sleep I need? Last night, plus 7-day sleep debt.
- **RECOVERY**: has my body absorbed what I've done? Night-time physiology and how I feel in the morning.
- **LOAD**: was yesterday's training dose right? Always shown with a direction: HIGH, IN BAND or LOW.
- **FUEL**: was I fuelled, and did my gut cope? Yesterday's intake and digestion.
- **BALANCE**: how much stress did I carry, in mind and nervous system? Yesterday's daytime.

**Each metric belongs to exactly one domain, so nothing is counted twice.** Physiology is split by time of day:
- **Night** goes to RECOVERY: HRV level, resting HR, overnight stress.
- **Day** goes to BALANCE: the daytime stress pattern and the autonomic (ANS) measures.

## The scale: 0 to 150

Every score (CHARGE and each domain) is placed against **its own last 180 days**:

| Own history | Score | Band |
|---|---|---|
| worst 10 % | < 40 | LOW |
| 10th–25th pct | 40–60 | BELOW NORMAL |
| 25th–90th pct (median day ≈ 80) | 60–100 | NORMAL |
| 90th–97th pct | 100–125 | PRIMED |
| best ~3 % | 125–150 | SUPERCHARGED |

Between those points the score is a straight line on the bell-curve (z) scale, and it stops at 150. One sentence
explains every score: "better than N of your last 100 days".

**How the numbers are built:**
1. **Input to sub-score.** Each input is compared with one of three things:
   - your 60-day baseline (median and robust SD, log scale for HRV, with each metric's better direction applied, and
     a dead zone the size of the smallest worthwhile change);
   - a fixed target (protein in g/kg, at least 7 h of sleep, hydration need);
   - a healthy band (TSB or the acute:chronic load ratio, energy balance).
2. **Sub-scores to raw domain.** A weighted mean over the inputs present; missing weights are shared among the rest.
   A domain can't read more than 25 points above its worst critical input.
3. **Raw domain to domain score.** Ranked against that domain's own history.
4. **Domain scores to raw CHARGE.** A weighted mean, then ranked against CHARGE's own history.

Ranking happens again after combining because averaging narrows the spread. Without it CHARGE would almost never pass
100.

**Which inputs can go above 100.** Only inputs where "more" is genuinely better have an upside:
- HRV above baseline, resting HR below it;
- sleep up to your need plus about 1 h, deep sleep and REM;
- low daytime stress, a good ANS state.

Inputs scored against a target or band stop at 100. So FUEL and LOAD stop at 100, and the room above 100 comes from
SLEEP, RECOVERY and BALANCE.

**Guard on the upside.** When HRV is high but the ANS state reads SATURATED, HRV gives no points above 100. That state
is HRV and resting HR falling together, or the 7-day HRV variation spiking.

## CHARGE

```
CHARGE(D+1) = rank_in_own_history( 0.35·RECOVERY + 0.20·SLEEP + 0.15·LOAD_fatigue + 0.15·BALANCE + 0.15·FUEL )
```

- `LOAD_fatigue` is LOAD's fatigue side only. Too little training makes you fresher, so it doesn't lower CHARGE.
- **Caps:**
  - an open illness, an acute injury, or an acute gut episode (diarrhoea or nausea) caps CHARGE at 40;
  - when the typicality engine flags the day as atypical, a ⚠ is added.
- CHARGE needs RECOVERY or SLEEP. FUEL and BALANCE depend on what you log, and are left out when PARTIAL.
- **Drivers.** Each domain's contribution is its weighted distance from your typical day. The contributions are then
  scaled so they add up exactly to the distance between today's CHARGE and your median. Every screen and the health
  agent's WHY? use these same numbers.
- The weights above are starting guesses. After 120 scored days they are re-fitted on your data and pulled halfway
  back to the defaults. Each fit is versioned, and the "?" says which weights are in use.

## The daily cycle: scores judge yesterday

```
 Day D (live)                 Evening D                   Morning D+1
 log, train, work   ──►  CLOSE THE DAY check-in  ──►  wake + watch sync  ──►  CHARGE published
 progress only, no score  mood, stress, soreness,     sleep, night HRV/RHR     PROVISIONAL → FINAL
                          gut, food log complete?
```

- **Inputs for CHARGE on day D+1:**
  - from the night of D: sleep, plus HRV and resting HR;
  - from day D: load, fuel, gut and balance. Training load already uses yesterday's CTL and ATL.
- **Locking:**
  - **PROVISIONAL** from wake;
  - **FINAL** once day D is closed with the evening check-in, or at 12:00;
  - after that it never changes.
- **Late logs** appear in LOG like any other entry. They never move a final score, and no extra note is shown.
- **PARTIAL.** A domain is PARTIAL when the day's food log covers less than about 60 % of your usual intake, or the
  check-in is missing. A PARTIAL domain is left out of CHARGE, never scored low.
- **No score for today during the day.** Today shows progress and what's left to do; scores only describe completed
  days.

## Domain inputs (starting weights)

### SLEEP
- Last night's duration vs your need: 30
- 7-day sleep debt: 20
- Regularity (SRI): 15
- Timing vs your usual midpoint: 10
- Deep + REM vs baseline: 15
- Breathing stability and time awake: 10

Builds on: the Sleep Index, `CircadianAlignment`, `sleep_stage_intervals`.

### RECOVERY
- ln HRV vs baseline: 35
- Resting HR: 25
- Overnight Zepp stress: 15
- Soreness and body check-in: 15
- Morning energy, morning erection, arousal: 10

An open health concern caps the domain. Builds on: `HrvRhrEvaluation`, `StressRhythm`, `SubjectiveEvaluation`, health
concerns.

### LOAD
- TSB in its band: 40
- Days without rest and weeks since deload: 20
- Monotony and strain: 15
- Plan adherence: 15
- Intensity split vs plan: 10

Fitness trend (CTL slope, VO2max, efficiency factor) is a trend arrow on LOAD. It feeds Healthspan, not the score.
Builds on: `TrainingLoadEvaluation`, `RestCadenceEvaluation`, `IntensityDistribution`, planned sessions.

### FUEL: intake 70 %, gut 30 %

Intake:
- Energy vs need (adaptive TDEE plus the day's sessions): 35
- Protein vs g/kg target: 20
- Carbs vs the day's load: 20
- Hydration vs demand: 15
- Alcohol and late caffeine: 10

Gut (digestion sub-score):

| Input | Weight | Notes |
|---|---|---|
| Symptom burden | 35 | Check-in comfort 0–4 and chips (bloating, cramps, reflux, urgency, gas, nausea); `sym.gi_upset`, `sym.nausea` |
| Bowel timing regularity | 15 | Circular spread of bowel-movement times over 14 days vs your usual spread, plus whether yesterday fell inside your usual window. Needs ≥ 10 logged movements in 14 days |
| Frequency vs your usual | 15 | |
| Form vs your usual type | 15 | Smoothed over 3 days; vs your own typical Bristol type, not vs type 4 |
| In-session gut | 20 | Only on fuelled sessions; the `fuel_plan_reviews` gut score |

Possible triggers (late heavy meal, alcohol grams, fat and fibre before a run, caffeine, new foods) are not scored. Lab
› N=1 uses them to find what upsets your gut.

### BALANCE: ANS 50 %, mind 30 %, rhythm 20 %

ANS (built only from what the watch gives us; no beat-to-beat data, so no LF/HF):
- daytime stress load: % of waking, non-exercise time in medium or high, and the longest stretch;
- % of the day relaxed;
- evening wind-down: stress and HR in the 2 h before bed;
- night-time HR dip: drop from daytime HR to sleeping HR;
- HRV stability: 7-day variation of ln RMSSD.

The **ANS state pill** comes from HRV and resting HR against their baselines:

| HRV | Resting HR | State |
|---|---|---|
| ↑ | ↓ | BALANCED |
| ↓ | ↑ | SYMPATHETIC |
| ↓ | ↓, or HRV variation spike | SATURATED |
| ↑ | ↑ | UNUSUAL |

Mind: subjective stress and mood.

Rhythm: meal and sleep timing regularity, and late phone use.

**Open data question.** Zepp stress history has to reach about 4 weeks back for the ANS baselines. Check whether
`zepp_history_pull` can backfill it; otherwise BALANCE shows BUILDING for its first month.

## How the parts connect

1. **Diagnosis.** When RECOVERY is low, the lowest of yesterday's causes is the likely reason. If RECOVERY is low and
   every cause looks normal, the app shows "unexplained drop: watch for illness" (this ties into typicality and
   health concerns).
2. **Personal sensitivities.** Lagged relationships (`analysis-core/relationship.ts`) learn the size of each arrow,
   for example "1 h less sleep ≈ −X RECOVERY". They are shown on each domain page under "what affects this for you".
3. **The scorecard checks each score's weights.** Each score names the outcome it should forecast:

   | Score | Should forecast |
   |---|---|
   | CHARGE | Today's session execution (efficiency factor and decoupling vs baseline, plan done) and tomorrow's energy |
   | SLEEP | Next-day energy and morning HRV |
   | RECOVERY | Today's session execution |
   | LOAD | RECOVERY 2–3 days later, soreness and injury flags |
   | FUEL, BALANCE | That night's sleep and next-day RECOVERY |

4. **Lab › N=1 experiments** use domain scores as their outcomes.
5. **READY FOR** compares CHARGE with the demand of each planned session.

## Navigation

Five tabs: **TODAY · PLAN · + LOG · LAB · ME**.

- **Reading** (scores and analysis) starts on TODAY and opens the five domain pages.
- **Doing** (planning, logging, testing) lives in PLAN, LOG and LAB.
- **Slow-moving** things (healthspan, performance levels, body, bloodwork, settings) live in ME.
- **Display:** every score is a ring. One full lap is 100; 100–150 is a second lap drawn over the first, so 150 is one
  and a half laps. Each screen shows only the headline (ring or value, plus direction). Explanations, drivers and charts
  open on a full-screen overlay when you tap. Nothing is a collapsed section.
- **+ LOG** opens the log page with the logging menu already open. There are no quick-log shortcuts elsewhere, and
  SAY IT is removed.

The full map, every page and where each current card goes are in [02-page-mockups.md](02-page-mockups.md).

## Decisions taken

| # | Decision |
|---|---|
| 1 | Top band is SUPERCHARGED (125–150); PRIMED is 100–125 |
| 2 | MIND is renamed BALANCE and includes ANS; digestion lives inside FUEL, not as a sixth domain |
| 3 | PROVISIONAL at wake, FINAL when yesterday is closed or at 12:00; late logs are ordinary log entries and never move a final score |
| 4 | Navigation is rebuilt around the scores |
| 5 | Scores are rings (full lap = 100, overlap to 150); first screens show only number and direction |
| 6 | No collapsed sections: a headline tile opens a full-screen overlay with the whole picture |
| 7 | Quick-log row and SAY IT removed; + LOG opens the logging menu directly |
| 8 | Today's plan lists sessions, meals from the food plan, supplements and closing the day, on one timeline |

## Open

- Whether Zepp stress history can be backfilled (BALANCE baseline).
- What the top-band treatment looks like on screen (colour and motion), for the design contract.
- Phased build order: score engine and history, then CLOSE THE DAY, then TODAY, then the domain pages, then moving
  content into PLAN, LAB and ME.

## Build status

### Phase 1 (built): score engine, TODAY, overlays

**Engine** (`android-app/.../domain/score/`, pure Kotlin, unit-tested):

| File | What it does |
|---|---|
| `ScoreScale.kt` | The 0–150 scale: percentile anchors, bands, ranking |
| `ScoreInputs.kt` | One input to a sub-score: baseline, stability, at least, at most, band |
| `ScoreDomains.kt` | The five domains, their inputs and weights, and how they combine (coverage, critical cap, upside) |
| `ScoreEngine.kt` | Ranking against your own last 180 mornings, CHARGE, caps, drivers, ANS state, LOAD direction, PROVISIONAL/FINAL |
| `ScoreAssembly.kt` | Builds the engine's series from rows the app already loads, keyed by the morning they feed |
| `ScorePresentation.kt` | Number formats, sentences and "what is it" text for the screens |

**Screens:**
- **TODAY** (`ui/screens/today/TodayHomeScreen.kt`) is the opening tab, now labelled TODAY. It shows:
  - the CHARGE ring and the five domain rings, number and direction only;
  - TODAY'S PLAN as one timeline: food-plan meals, planned sessions with a READY mark, supplements still due, and
    closing the day;
  - the SO FAR line.
- **Overlays** (`ui/screens/today/ScoreOverlays.kt`):
  - the CHARGE overlay: drivers, 30-day strip, caps, coverage;
  - one overlay per domain: drivers per group, LOAD direction, ANS state;
  - one overlay per metric: value, your normal or target, points, 60-day chart with your normal band;
  - they stack: ‹ goes back a level, ✕ closes them all.
- **Ring** (`ui/components/ScoreRing.kt`): one lap is 100, the second lap overlaps to 150 and glows from 125. Dashed
  when PROVISIONAL, empty when PARTIAL.

**Logging:** tapping **+ LOG** in the dock opens the logging menu directly, and SAY IT is gone from the menu.

**Still reachable from TODAY:** the four old sections (Training, Nutrition, Health, Lab), from an ALL SECTIONS row,
until PLAN, LAB and ME exist. The old Status hub (`StatusScreen`, `BodyConsole`) stays in the code, unreferenced.

### Phase 1b (built): the full picture views

The domain overlays now have a THE FULL PICTURE section. Each tile opens its own overlay:

| Domain | View |
|---|---|
| SLEEP | **Last night**: the existing hypnogram card |
| FUEL | **Digestive pattern · 14 days**: a stool hypnogram, as Throne Health draws it. Lanes from top to bottom are LIQUID (Bristol 7), LOOSE (6), HEALTHY (3–5, the same groups `BristolEvaluation` uses) and HARD (1–2). Each logged movement is a mark at its time, and marks are joined in time order. The healthy lane is shaded. |
| FUEL | **Bowel timing · 14 days**: one row per day with a dot per movement. The usual window for the first movement is shaded: the median of the 60 days before, ± its robust spread, at least 30 minutes. It reads "steady" when the fortnight moved no more than a quarter over the usual spread. |
| BALANCE | **Stress through the day**: the existing stress-rhythm card for the day before, with exercise masked |

The pure logic is in `domain/score/GutPattern.kt` and is unit-tested. The stool hypnogram also heads the existing
Health › Body DIGESTIVE PATTERN (BRISTOL) card.

### Phase 1c (built): gut questions in the evening check-in

- **Database:** `evening_checkins` gains `gut_comfort` (0 rough to 4 comfortable) and `gut_symptoms` (bloating,
  cramps, reflux, urgency, gas, nausea; an empty array means none). The migration is
  `supabase/migrations/20261113_evening_checkin_gut.sql`, applied to the live project on 2 Nov 2026. It is additive and
  nullable.
- **Movement times:** they are not asked. Each stool entry keeps the time it was logged (the stool form's WHEN defaults
  to now).
- **The check-in sheet's GUT section** has the comfort rating, the symptom chips, and the day's logged movements with
  their times and Bristol types. On the day itself, **+ NOW** logs one more at this minute once a Bristol type is
  picked. For an earlier day the sheet points to + LOG.
- **NOTHING ELSE** marks no symptoms, but never invents a comfort rating.
- **FUEL's symptoms input** reads (4 − comfort) + ½ per symptom, or the worst stool entry's discomfort (0–10 scaled to
  0–4), whichever is larger.
- **The gut cap:** an acute gut day caps the next morning's CHARGE at 40 ("Acute gut day"). That means nausea ticked,
  or two or more Bristol 7 stools that day.

### Phase 1d (built): meal pages from TODAY'S PLAN

Tapping a meal row opens its overlay (`ui/screens/today/MealOverlay.kt`). It shows:
- the meal's targets (carbs, protein, fat, kcal) and why it is planned that way;
- what is already logged against it;
- the plan's plate: the prepped breakfast or container when a prep week is set up, otherwise the example plates. Each
  has **LOG THIS**.

**LOG THIS** writes the plate's macros as one meal. Its time is now when now falls in that meal's slot, otherwise the
planned time, so a lunch logged at 16:00 still counts as lunch and not the snack (`domain/fuel/MealLogging.kt`,
unit-tested).

**LOG SOMETHING ELSE** opens the food form.

### Phase 2a (built): the five-tab dock

The dock is **TODAY · PLAN · + LOG · LAB · ME**. The old Status and User routes keep their route strings, so a saved
back stack still resolves.

| Tab | What it holds | Where it came from |
|---|---|---|
| PLAN (`ui/screens/plan/PlanScreen.kt`) | TRAINING (current block, this week, blocks), FOOD (next 10 days, meal prep), RACE (events and their race fuel), SESSIONS (every recorded session) | Lab › Planning and Training › Sessions |
| LAB | N=1, SUPPLEMENTS, TESTS, ROUTINE | The old Lab page, without PLANNING (now in PLAN) |
| ME (`ui/screens/me/MeScreen.kt`) | HEALTHSPAN (the old User page), PERFORMANCE (Running and Strength), BODY, HEALTH (concerns), BLOODWORK; ⚙ opens Setup | The old User page, Training's Running and Strength tabs, and Health's Body, Concerns and Bloodwork tabs |

Top-level pages have no back arrow.

**Where links now land:**

| Link | Lands on |
|---|---|
| Fuel reminder | PLAN › FOOD |
| Supplement and body check-in reminders | LAB |
| Health reminder | ME › HEALTH |
| ME's block card | PLAN › TRAINING |
| A session row on TODAY | PLAN |
| A supplements row on TODAY | LAB |

### Phase 2b (built): every chart reached from its domain; ALL SECTIONS removed

Each domain overlay's THE FULL PICTURE now also links to the existing detail page, pushed over TODAY on the right
tab:

| Domain | Links to |
|---|---|
| SLEEP | Sleep · every chart (Health › Sleep) |
| RECOVERY | Heart · every chart (Health › Cardio); Sexual health |
| LOAD | Training load · every chart (Training › Load); Injury |
| FUEL | Nutrition · every chart; Hydration |
| BALANCE | Wellbeing · every chart (Health › Wellbeing) |

The overlays close when a detail page opens, so back from it returns to TODAY. With that, every old card has a home,
and TODAY's ALL SECTIONS row is gone.

### Verified

- **Unit tests:** the full Android suite passes, 1,089 tests with 0 failures and 4 skipped. That is 1,039 existing
  tests plus 49 new ones (`test/.../domain/score/`, `MealLoggingTest`) and a navigation test for the new dock.
- **Build:** `assembleDebug` builds.
- **Not verified:** the app has not been run on a device or with real data. The TODAY layout, the rings and the
  overlays and the full-picture views have only been compiled, never seen.

### Simplifications in v1

- **Inputs not assembled yet** (they read as missing, and their weight goes to the others):
  - in-session gut (it needs the fuelled-session debrief wired in);
  - the night-time HR dip;
  - meal-timing regularity.
- **Sleep regularity** is the 7-night spread of bed and wake times. The SRI was too slow to recompute for every
  morning of history.
- **FUEL targets:** past days use the saved nutrition goal. The fuel plan's day-by-day targets are not stored for past
  days.
- **Caps:** only the currently open illness (whole episode) or injury (first 7 days) caps CHARGE. Closed past episodes
  are not reconstructed for the history.
- **The READY mark:** CHARGE against a fixed threshold per session intensity (easy 40, moderate 55, hard/long/race 70),
  a documented opinion.
- **Ranking:** SLEEP, RECOVERY, BALANCE and CHARGE are ranked against their own history. LOAD and FUEL are not, because
  "on target" is their top and ranking would turn a perfect day into a middling number.

### Not built yet

- CLOSE THE DAY's mood, energy and soreness questions. The evening check-in now carries the gut questions; the others
  still come from the morning wellbeing entry.
- Rebuilding the old detail pages (Training, Nutrition and Health) in the overlay style. They are reached from the
  domains, but still look like the October pages, with their own tab rails.
- Personal weights (after 120 scored days) and the scorecard for the new scores.
