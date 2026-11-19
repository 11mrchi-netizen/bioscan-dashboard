# Risk profile: environmental and behavioural exposures

Status: R0 and R1 built (19 Nov 2026, not yet deployed; see [README.md](README.md)); R2-R5 planned. Owner decisions so far (2026-11-19): build our own version rather than port
[healthrating](https://github.com/alanrooster/healthrating) (AGPL-3.0, so no code is copied; its methodology page is used
only as a reading list); take home blood pressure readings; replace life expectancy with the AHA PREVENT equations; the
tool **assesses exposures and risks and shows ways to improve, rather than giving one precise number**.

## Why not healthrating as it is

Its author lists the limits themselves (README, methodology page). Each one has an answer here:

| healthrating's own caveat | Here |
|---|---|
| Module weights are "this tool's own judgement" | No composite. Every domain stands alone; CHARGE and Healthspan stay the only headline scores |
| Biological age = life-expectancy adjustment × 0.5, "a modelling assumption" | Not built. PhenoAge from labs already exists (`docs/aging-profile/`) |
| Risk screens "can only under-count": FINDRISC 19 of 26 points, Life's Essential 8 5 of 8, the dementia profile without BP / LDL / diabetes / head injury | Full instruments: the skipped questions are asked; labs, BP, sleep, activity and body measurements are measured |
| Peer bands are an assumption ("no population sample has taken the questionnaire") | Only published cut-offs and norms, plus the person's own history |
| Life expectancy: gains capped by a saturating curve, work / water / home years "small modelling assumptions", percentile SD "a rough approximation" | Replaced by PREVENT (10- and 30-year cardiovascular risk), a validated absolute-risk model |
| City PM2.5 and country water scores entered by hand ("a few µg/m³ off", "20 points or more") | Measured: home sensors indoors, a modelled hourly air-quality feed outdoors, weighted by where the person actually was |
| Functional fitness from self-reported proxies | Grip, VO2max and lifts, already measured (Healthspan) |
| Relative risks multiplied as if independent, capped at "4× or more" | Factors are listed present / absent with their source; no multiplied figure |

Not on their list but just as important: it is a one-off snapshot and cannot tell whether a change helped. Here each
instrument repeats on its own schedule, and every improvement can be started as a Lab experiment.

## Principles

1. **Exposure, not a verdict.** Each domain shows the exposure or state against a cited threshold, its trend, what drives
   it and what would lower it. A number is shown only where a validated instrument produces one (PREVENT, FINDRISC,
   Life's Essential 8, PHQ-9, GAD-7, WHO-5, the Copenhagen Burnout Inventory, UCLA-3).
2. **Measured beats answered.** Every input row says its source (measured, answered, missing) and its age. A screen that
   lacks inputs says what is missing and how to get it; it never fills the gap silently.
3. **Ways to improve are ranked, not prescribed.** Each suggestion carries its evidence (source, effect size where one
   exists), the effort, and which exposure it lowers. Any of them can be started as an Intervention or Experiment in Lab,
   which then reads whether it worked.
4. **Design contract.** Ok / Warn / Bad mark the state against the threshold only; each exposure metric gets its own hue
   in `design/tokens/` and `FT.Metric`; every metric that looks tappable opens its history.
5. **Dates.** BP readings are logged times (wall clock, `occurred_at`, read with `WallClock` / `wallClock`). Questionnaire
   answers are real instants (`answered_at`), with the local date taken from the zone. Outdoor air samples are real
   instants.

## Domains

### 1. Air (outdoor and indoor)

- **Outdoor**: hourly PM2.5, NO₂, O₃ and UV index from the Open-Meteo Air Quality API (CAMS model; the phone already uses
  Open-Meteo for weather, no key). Fetched server-side for the grid cells the person was in, from `place_visits` /
  `location_fixes`.
- **Indoor**: `home_env_readings` (PM2.5, CO₂, temperature, humidity) from Home Terminal.
- **Personal exposure** = time-weighted mean over the day: indoor readings while at home, the outdoor cell elsewhere,
  outdoor during outdoor sessions (`exercise_sessions` with GPS). Coverage is shown (hours with a source / 24).
- **Thresholds**: WHO Air Quality Guidelines 2021 (PM2.5 15 µg/m³ 24-h, 5 annual; NO₂ 25 24-h, 10 annual; O₃ 100 8-h).
  CO₂ as a ventilation marker (about 800 ppm good, above 1,400 poor; a convention, labelled as one).
- **Highlights**: days over the 24-h guideline, the share of exposure from home vs outside, hard sessions on poor-air
  hours, bedroom CO₂ overnight.
- **Ways to improve**: purifier / filter in the room with the most hours, ventilation when CO₂ is high, moving outdoor
  sessions to cleaner hours or routes (the forecast is available), extraction while cooking.

### 2. Heat and sun

- UV index × outdoor time (sessions, place visits outdoors); heat from `daily_context.temp_max_c` and humidity on
  training days. Thresholds: WHO UV index bands; heat stress on the Open-Meteo apparent temperature.
- Ways to improve: session timing, sun protection on high-UV outdoor days.

### 3. Substances

- **Alcohol**: grams a week from logged drinks (beverage category `alcohol`, nutrient `alcohol`), against the low-risk
  limits (UK CMO 14 units; WHO "no safe level" stated alongside). Drinks not logged cannot be counted; the row says so.
- **Caffeine**: daily mg and the last dose before bed (sleep link already in Routine; reuse, do not duplicate).
- **Nicotine and other drugs**: answered (never / former + years since / current).

### 4. Mental health

- **Instruments**: PHQ-9 and GAD-7 (free to use, no permission needed), WHO-5 (free), every 4 weeks with a reminder.
  Severity bands as published.
- **Passive context**: `wellbeing_daily` mood and stress, daytime stress (BALANCE), night-time phone use
  (`phone_usage_daily`). Shown next to the scores; never folded into them.
- **Safety**: a positive PHQ-9 item 9 (thoughts of self-harm) shows a short, plain line at once, whatever the total,
  suggesting talking to someone or a doctor. Owner decision: no specific crisis line and no notifications to anyone.

### 5. Social connection

- UCLA 3-item loneliness scale every 4 weeks; contact from `encounters` (time with people a week, distinct people a
  month). Source for the risk: Holt-Lunstad 2015 (social isolation and mortality); 2024 Lancet dementia commission.

### 6. Work

- Copenhagen Burnout Inventory, work-related scale (7 items, free use), every 8 weeks; hours a week from the calendar or
  answered, against 55 h (WHO/ILO 2021: stroke and heart disease).

### 7. Cardiometabolic

- **Blood pressure.** What exists today: clinic readings only, as lab markers (`lab_results`: `SBP, Sitting`,
  `DBP, Sitting`, `Pulse Rate`; grouped under Cardiovascular in `LabMarkerThemes.kt`, which also lists them under Kidney
  by mistake, the later entry winning) and a metric hue (`FT.Metric.BloodPressure`, `bloodPressure` in tokens). No home
  readings, no table for them, no Health Connect import (`BloodPressureRecord` is not among the records the phone reads).
  - **Home readings**: imported from Health Connect (`BloodPressureRecord`; Omron Connect and most cuff apps write there),
    with manual entry in the add sheet as the fallback. Both land in `bp_readings`.
  - **Protocol**: the AHA one (validated upper-arm cuff; 2 readings 1 min apart, morning and evening, 7 days; seated,
    5 min rest). The 7-day mean, dropping day 1, is the value used. Then a 7-day series every 3 months, or as advised.
    Categories: AHA/ACC 2017.
  - **Clinic vs home**: both kept and shown side by side. A clinic reading well above the home mean suggests a white-coat
    effect; the reverse, masked hypertension. Home means win as the input to PREVENT and Life's Essential 8; the latest
    clinic reading is the fallback, labelled as such.
- **PREVENT** (Khan et al., Circulation 2024): 10- and 30-year risk of total CVD, ASCVD and heart failure from age, sex,
  total and HDL cholesterol, systolic BP, BP treatment, diabetes, smoking, eGFR (CKD-EPI 2021 from creatinine) and BMI;
  the HbA1c add-on when available. Valid for ages 30-79; outside that range it is not shown, and the row says why. Tests
  use the paper's worked examples and the AHA calculator's outputs.
- **Life's Essential 8** (Lloyd-Jones 2022), all eight: diet (answered, MEPA-style short form), activity (measured),
  nicotine (answered), sleep (measured), BMI (scale), lipids, glucose / HbA1c (labs), BP (readings).
- **FINDRISC**, all items including BP medication and past high glucose (answered once).
- Ways to improve come from the components that score lowest, each linked to the metric it would move.

### 8. Brain (modifiable dementia factors)

- The 14 factors of the 2024 Lancet Commission listed present / absent / unknown, each with its source and where its
  value comes from (hearing and vision answered; LDL from labs; BP from readings; activity measured; PM2.5 from domain 1;
  isolation from domain 5; depression from PHQ-9). No multiplied relative risk.

### 9. Home and water

- Checklists, not scores, since no validated instrument exists: gas cooking, non-stick cookware past its life, heating
  food in plastic, smoking indoors, radon tested (and the result), mould, water source and filter. Each item carries its
  source (IARC, WHO indoor air, EPA) and its fix. Answered once; asked again after 12 months.

### 10. Preventive care

- Screenings, vaccines and check-ups due for age and sex, each with its interval, last date and next date. Due items go
  to Plan as reminders.
- **Which schedule** (owner decision 2026-11-19: the best evidence, not one country's). No single country's guidance is
  best on its own, so the schedule is built in layers, and each item names the layer it comes from:
  1. **Core: USPSTF grade A and B.** The most rigorously graded and most often updated screening reviews. They are
     deliberately conservative, recommending only what trials show helps people without symptoms.
  2. **Cardiovascular prevention: ESC / EAS.** Europe is ahead on prevention here: Lipoprotein(a) measured once in
     adult life (EAS 2022 consensus, ESC 2019), ApoB as a target, and the European Code Against Cancer (5th ed., 2025)
     for cancer exposures.
  3. **Regional prevalence.** Where the person lives changes a few items, for example hepatitis B screening (high
     prevalence in East and Southeast Asia) and gastric cancer (endoscopy programmes in Korea and Japan). Driven by the
     country of residence in `user_profile`.
  4. **Optional, discuss with a doctor.** Items that are useful for someone tracking closely but are not a population
     recommendation: USPSTF grade C and I, a coronary calcium score when PREVENT sits in the borderline band
     (ACC/AHA 2019), skin checks.
  Each item shows its layer and source, so it is clear what is a guideline and what is optional.

## Data

| Table | Holds | Notes |
|---|---|---|
| `bp_readings` | `occurred_at` (logged time), systolic, diastolic, pulse, arm, position, `on_bp_meds`, note (sittings are derived: readings within 15 minutes), source (`health_connect` with its record id, or `manual`) | Own-row RLS; the Health Connect record id is unique, so a re-import adds nothing |
| `instrument_responses` | instrument, version, `answered_at`, item answers (jsonb), score, band | Append-only; a correction supersedes a row, as in health concerns |
| `exposure_answers` | checklist item, value, `answered_at` | Latest per item wins; history kept |
| `preventive_items` | item, last done, interval, source | Feeds Plan |
| `outdoor_air_hourly` | grid cell, hour (real instant), PM2.5, NO₂, O₃, UV | Shared cache, written server-side only |

Scorers live in `analysis-core` (`riskAir.ts`, `riskCardio.ts` with PREVENT / LE8 / FINDRISC, `riskInstruments.ts`,
`riskBrain.ts`), each with tests built from published worked examples. `analysis-run` publishes the results nightly and
on demand; new registry entries go in `metric-registry.json`.

## Where it shows

- **Phone** (logging stays on the phone): BP entry in the add sheet with the 7-day protocol as a guided series;
  questionnaire reminders; a new Me tab, **EXPOSURES**, published to `me_views` like the other tabs.
- **Console**: a **Risk** workspace for answering the longer instruments and checklists on a large screen and for digging
  into each domain (exposure over time, drivers, coverage), in the ANALYSIS rail group.
- **Plan**: preventive care due dates. **Lab**: "start as experiment" from any improvement.
- Healthspan is unchanged and stays on its own; nothing here feeds it.
- The app map gets the new screens (EXPOSURES tab, BP entry, questionnaire sheet) with `status: "new"` when they are
  built.

## Phases

| Phase | Builds | Why first |
|---|---|---|
| R0 | `bp_readings`, Health Connect import, manual entry and the 7-day protocol | Readings need a week before they can be used; start collecting at once |
| R1 | Air: outdoor cache, personal exposure, indoor join, EXPOSURES tab skeleton | Uses data already collected; the owner's first ask |
| R2 | Mental health, social, work instruments; reminders; safety handling | Second ask; small and self-contained |
| R3 | Cardiometabolic: PREVENT, Life's Essential 8, FINDRISC; brain factors | Needs R0's readings |
| R4 | Substances, heat and sun, home and water checklists, preventive care to Plan | Mostly questionnaires and joins |
| R5 | Ways to improve → Lab experiments; Console Risk workspace in full | Needs the domains to exist |

Each phase: tests, `assembleDebug`, console `tsc`, then the map update.

## Decisions (2026-11-19)

1. Preventive care: the layered schedule above (USPSTF A/B, ESC / EAS cardiovascular prevention, regional prevalence,
   optional items), not one country's.
2. PHQ-9 item 9: a plain line only; no crisis line and no notifications.
3. Questionnaires: short ones on the phone, longer ones on the phone or in the Console.
4. The Console Risk workspace goes in the ANALYSIS rail group.

## Still open

- Country of residence for the regional layer (stored in `user_profile`, asked once in the app).
- Outdoor air: the Open-Meteo Air Quality host may need allowing for the edge function's network.
