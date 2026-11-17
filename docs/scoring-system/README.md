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
- **SLEEP**: did I get the sleep I need? Last night, plus the sleep debt carried into tonight.
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
  - an acute injury (first 7 days) or an acute gut episode (diarrhoea or nausea) caps CHARGE at 40;
  - an open illness caps it by its gravity (`domain/score/IllnessRead.kt`, model 7), from the latest check-in's
    severity, TRAINING answer, temperature and symptoms: mild (above the neck only, up to 3/10, training as usual or
    reduced) 85; moderate (below the neck, 4-6/10, or training modified) 65; severe (a fever of 38 °C, 7/10 or more,
    5/10 or more below the neck, or daily life affected) 40; nothing left (0/10, full training) no ceiling; no check-in
    yet moderate. A grade holds two days, then eases one step every two days. Small things logged through the symptom
    check belong to no illness and cap nothing. With several caps the lowest wins;
  - the body's signals (resting HR up, HRV down, breathing up, z ≥ 2 against your own 60 mornings) are read each
    morning: while ill, a line says whether they agree with the check-in; otherwise two or more together raise a
    heads-up, explained away instead when the evening before logged drinks, a stress of 4-5, or a session ended
    after 20:00 or lasted 90 minutes or more. Neither changes CHARGE;
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
  - after that it is kept (`score_snapshots`, `domain/score/ScoreSnapshots.kt`) and stands. It is rebuilt only when
    the morning gains an input or a cap it did not have (stress that synced late, an illness logged for that day). A
    rewritten value, the monthly re-fit of the CHARGE weights or newer mornings in the ranking history do not move it.
  - **Changing the engine:** a kept morning shows a change to the score code only if `SCORE_MODEL_VERSION` is bumped;
    bump it whenever a change should reach past mornings.
- **Late logs** appear in LOG like any other entry. A late log of a value the morning already had never moves a final
  score; one that fills a missing input does, and no extra note is shown.
- **PARTIAL.** A domain is PARTIAL when the day's food log covers less than about 60 % of your usual intake, or the
  check-in is missing. A PARTIAL domain is left out of CHARGE, never scored low.
- **No score for today during the day.** Today shows progress and what's left to do; scores only describe completed
  days.

## Domain inputs (starting weights)

### SLEEP
- Last night's duration vs your need: 30
- carried sleep debt: 20 (`domain/score/SleepDebt.kt`: each night carries 55 % of the shortfall into the next
  night's target, over the last 14 nights; under 10 min is none, extra sleep builds no credit. Tonight's target and
  a "Bed by" time, from the usual wake of the last 14 mornings less the target and 15 min to fall asleep, are a row
  in TODAY's plan)
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

Rhythm (50/50): meal timing regularity (the spread of the first and last meal times over the week before, against
your own usual), and phone before sleep: minutes on the phone in the hour before that night's sleep onset, measured
by the phone (`phone_usage_daily.bed_window_min`; up to 10 min scores the top of normal, each 20 min more costs one
standard deviation). Without a measurement, an evening check-in "yes" counts as 30 min.

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

### Phase 3 (built): score quality and personal weights

The code is `domain/score/ScoreQuality.kt`, and it is unit-tested.

**Scorecards.** Every score is checked against an outcome. The test is the app's existing rolling-origin one
(`domain/Scorecard.kt`): each morning the score becomes a forecast fitted only on earlier days, it is scored once the
outcome is known, and it is compared with its best single input on the same days.

| Score | Forecasts |
|---|---|
| CHARGE | The next morning's HRV against its recent level; with planned days, also whether the day's planned sessions were done |
| SLEEP | That morning's HRV |
| RECOVERY, FUEL, BALANCE | The next morning's HRV |
| LOAD | The HRV two mornings on |

The verdict (EARNS ITS PLACE · NO BETTER THAN ITS INPUTS · UNCLEAR · NOT YET SCORED) and its sentence show in each
overlay's DOES IT EARN ITS PLACE? section. Unlike the old composites, a NO BETTER score is never hidden; for CHARGE it
feeds the weight fit.

**Personal CHARGE weights.** The fit is a ridge regression of the next morning's HRV residual on each morning's
domain scores, exactly what CHARGE combined (LOAD through its fatigue side; missing domains read as a typical day).
Then:
1. Negative weights are dropped and the rest normalised.
2. They are pulled halfway back to the defaults and floored at 5 %.
3. **Adoption:** they are used only if, on the held-out last third of the mornings, a straight-line forecast from
   CHARGE with them beats the same forecast from the default CHARGE. This is the rule the personal fitness and fatigue
   spans already follow.
4. **Refit:** on all mornings, with a minimum of 120.

The fit uses only mornings before the first of the current month, so the weights change once a month. The CHARGE
overlay's WEIGHTS line shows them, and a sentence says why they are or aren't personal.

**Your data (checked 2 Nov 2026):** HRV and sleep for every night of the last 240 days. About 200 mornings are
available to fit and score, so both run on first install.

**Known bias:** the next-morning HRV favours RECOVERY, whose HRV carries over a day. Halfway shrinkage, the 5 % floor
and the held-out check limit how far it can pull.

**Not yet:** fitting the weights of each domain's own inputs. Those keep their documented starting weights.

### Phase 4 (built): graphs instead of text, and the tour's bugs

The screens were rendered with the screenshot tour (`test/.../tour/TourScreenshots.kt`, opt-in) and reviewed. The
approved changes:

| # | Where | Now |
|---|---|---|
| 1 | TODAY'S PLAN | A time rail with a NOW line. Meals are dots sized by kcal, sessions are bars as long as they last, supplements are pills, closing the day is a diamond; each fills in once done. |
| 2 | SO FAR | One kcal bar against the target, split into carbs, protein and fat kcal, with a tick for "by now" |
| 3 | CHARGE weights | One stacked bar (yours, and the defaults beneath) |
| 4 | DOES IT EARN ITS PLACE? | A skill chart (the score and its best input against no predictor, with the interval) above one sentence |
| 5 | Last 30 days | Bands shaded behind the bars, the axis labelled |
| 6 | Driver rows | Bullet charts: normal band or target zone shaded, target ticked, today a dot coloured by its sub-score |
| 7 | Group scores (FUEL, BALANCE) | Small rings |
| 8 | LOAD | A TSB gauge with the productive band and a 7-day trail; the 30-day strip is a TSB line inside its band |
| 9 | FUEL intake | Bullet charts against the 100 % target (part of 6) |
| 10 | BALANCE | The nervous-system map: HRV against resting HR, 14 mornings, today ringed |
| 11 | THE FULL PICTURE | A thumbnail per tile (sparklines, a mini stool chart, mini bowel timing) |
| 12 | Metric chart | A line through the days, a 7-day mean, the band labelled on the axis (HRV on the log scale, matching its header), date ticks |
| 13 | Metric overlay | Adds HOW IT SITS (7-day level, day-to-day variation, how rare, mornings in your normal or on target), WHAT WENT WITH IT (the morning's other off-normal inputs) and WHAT GOES WITH IT (120-day correlations, labelled as not causes) |
| 14 | Stress through the day | The intraday curve: exercise shaded and left out, the baseline dashed, the peak marked, the evening wind-down shaded |

The pure parts are `domain/score/ScoreCharts.kt` (bullet geometry, ANS points, metric stats, relations, sparklines);
the composables are `ui/screens/today/ScoreGraphs.kt`.

**Bugs fixed:**
- **A:** LOAD and FUEL at 100 read ON TARGET, not PRIMED.
- **B:** scorecard sentences no longer say the score "is shown as its inputs".
- **C:** acronyms keep their case in sentences.
- **D:** a record morning reads "Your best morning of the last 180".
- **E:** values round the way they were judged; 99.6 % of a target reads 99 %.
- **F:** personal weights are adopted only if they cut held-out forecast error by at least 2 %. Before this, they were
  adopted on pure noise.
- **G:** a value beyond the whole history is placed by its robust z, between the old record's score and the rank score,
  so a new best no longer jumps to 150.
- **H:** the bowel-timing headline says "window" rather than repeating "usual".

### Phase 4b (built): time alignment and a kept TODAY

**Timestamps.** The app stores every logged time as the phone's local wall clock labelled with a zero offset. That
covers meals, stool, grip, body check-ins, scale readings, exercise sessions and athlete events. Several readers
converted those as if they were real UTC, moving them by the UTC offset (8 h in Taipei).

What the shift broke:
- Lunch at 13:10 read as 21:10 and was ticked as dinner on TODAY'S PLAN.
- A session at 08:11 read as 16:11 and never matched its 07:00 plan.
- Stool times moved 8 hours, so the bowel timing and gut inputs were wrong.
- LOG THIS and the check-in's + NOW wrote real offsets, breaking the convention.

Checked against the live data, these columns are real instants and are read as such: calendar events, sleep
bedtime / wake (16:01Z is a 00:01 bedtime), and Zepp stress samples.

The fix is one helper, `domain/WallClock.kt`, used by:
- the fuel plan's meal-to-slot matching and meal history;
- food habits;
- recorded sessions for the plan;
- hydration accounting;
- the intensity sessions;
- event-session matching;
- the gut code;
- the stress-curve exercise windows;
- LOG THIS and the check-in.

"Close the day" now sits at the reminder's own 22:25.

**A kept TODAY.** Building TODAY takes seconds: eight months of rows, the engine twice, and every scorecard. It used
to be rebuilt each time the tab opened.
- **Kept in memory:** `ui/screens/today/TodayStore.kt` keeps the finished page for the life of the app process and
  shows it at once.
- **Rebuilt in the background** only when it goes stale: a write to a table it reads (ReadCache now keeps a per-table
  write stamp), a new day, or 15 minutes. It checks once a minute while on screen.
- **Writes that don't count:** the load's own bookkeeping writes (daily_context, fuel_plans) don't mark it stale.
- **Not kept across restarts:** if Android reclaims the app, the next open builds it once.

### Phase 5 (built): the app map's change requests, and the old tabbed pages restyled

Requested on the editable app map (claude.ai artifact "Field Terminal Map"):

- **Each ring's tiles open pages of their own** (`ui/screens/detail`), replacing the Health, Training and Nutrition
  tabbed pages: Sleep, Heart, Sexual health (RECOVERY), Training load (with ALL SESSIONS at the bottom) and Injury (LOAD),
  Nutrition, Hydration and Digestion (FUEL), Wellbeing (BALANCE). Digestion joins the stool hypnogram and bowel timing on
  one page; FUEL's tile keeps the small stool hypnogram as its thumbnail.
- **Restyled** in the overlays' look (`DetailKit.kt`): back arrow, one 30 D · 90 D · 1 Y range per page, glass sections,
  the number first with where it sits against your normal (`domain/MetricReading.kt`, the score engine's robust band),
  then its chart; explanations behind each section's ⓘ. New charts: trend with normal band and target lines, stacked
  columns (stages, macros, weekly load with deloads), day-range bars, multi-line (fitness and fatigue), share bars,
  stat tiles. Metric rows open their own full-screen chart.
- **Last night** adds resting HR and breathing tiles under the hypnogram.
- **Stress through the day** adds the last 7 days as small hypnograms across Zepp's four zones (`domain/stress/StressZones.kt`).
- **ME › PERFORMANCE is a hub of sports** (`domain/SportCatalog.kt`, `ui/screens/performance`): Running, Strength and
  Conditioning first, then every other recorded type (cycling, hiking, walking, yoga...) by recency, each a tile with
  four weeks and twelve weeks of hours. Running, Strength and Conditioning keep their pages (restyled); any other sport
  gets a generic page (volume, effort, records, latest sessions).
- **The supplement reminder opens that block ready to log** (TODAY with the supplement form, the block ticked); TODAY's
  supplement rows do the same.
- **Removed**: the Training, Nutrition and Health tabbed pages, the old Today page (the 22:25 reminder opens TODAY with
  the check-in up) and the unreferenced Status hub.

### Phase 5b (built): TODAY built once a day and kept on disk

Opening the app rebuilt TODAY every time: nothing outlived the app process, and the Health Connect and Zepp syncs that
start on every open wrote rows, which marked the page stale and rebuilt it again (network plus the score engine over
eight months). Now:

- The finished page is kept on disk (`TodayStore`, `today_page.json`: the scores, strips, scorecards, series and the last
  plan), as well as in memory, so a cold open shows it at once.
- It is still checked in the background when it may be stale (a write, a new day, 15 minutes), but silently, and the
  engine only runs again when what it reads changed: `scoreInputsKey` (domain/score/ScoreKey.kt) digests the assembled
  series, planned sessions done, caps, whether yesterday is closed and whether it is past noon. A sync that rewrites the
  same rows gives the same key and the kept scores stand; only the rows the views draw from are refreshed.
- The plan rows and SO FAR line come back from disk too, until the fuel plan has loaded again.

### Phase 5c (built): every page opens from the last data it showed

The read cache (`data/ReadCache.kt`) keeps a disk copy of each read that passes a serializer. When the memory copy is
missing or stale (a new run of the app, a write, 90 seconds), the read is answered at once with the last value, and the
fresh one loads in the background; if it differs, `ReadCache.updates` names the key and the pages on screen load again
from memory (`rememberLoadGuard`, `rememberCacheVersion`). Kept on disk: wearable, sleep, wellbeing, sessions (training
load and every sport), planned sessions, stool, sexual activity, OSTRC, body metrics, lab draws and results, heart-rate
ranges, fitness-fatigue, series breaks, band stress, health concerns, the nutrition overview (keyed on the day) and the
running, strength and conditioning histories. TODAY's own rebuild reads inside `ReadCache.RequireFresh`, so it never
scores from an old copy. Sign-out deletes the disk copies and TODAY's kept page.

### Phase 5d (built): the remaining pages kept on disk too

The rest of the app reads through the same disk-backed cache: PLAN (training plan, blocks and cycles, upcoming sessions,
the food plan's days and meal prep, events and suggestions, every session), LAB (N=1's results and experiments,
supplements, routine), ME (aging, healthspan, profile, body, health concerns, overview and findings, bloodwork) and the
LOG feed. Pages loading outside a LoadGuard redraw on `rememberCacheVersion()`, and keep what is on screen while a
fresher copy loads.

One rule keeps edits honest: a write of the person's own (anything but the watch syncs' tables, `ReadCache.SYNC_TABLES`)
marks the reads it touches dirty, and their next read waits for the network, so a page reloaded after an edit shows the
edit. Sync writes on every open do not: those pages show the last copy and redraw when the synced data lands.

### Verified

- **Unit tests:** the full Android suite passes, 1,107 tests with 0 failures (plus 13 opt-in tour renders). That is 1,039 existing tests plus
  67 new ones (`test/.../domain/score/`, `MealLoggingTest`) and a navigation test for the new dock.
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
