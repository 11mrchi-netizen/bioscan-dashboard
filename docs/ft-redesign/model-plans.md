# Field Terminal redesign: plans for the three models without data yet

Source: three read-only feasibility investigations (code reading only; live data not queried). The UI shells for all three
ship in the redesign with a "needs data" state; this file is the plan to fill them.

## 1. Hypnogram (Today › Sleep Phase Breakdown, Health › Sleep)

**Verdict: viable from Zepp, no new capture needed.** Breathing overlay is not available from Zepp.

- Zepp `band_data` already carries timed stages: `slp.stage[]` `{start, stop, mode}` (4 light, 5 deep, 7 awake, 8 REM),
  decoded in `supabase/functions/zepp-extract/index.ts` (`decodeBandDataSummary`) and then reduced to minutes. Raw payloads
  are stored in `zepp_raw_extracts` (metric `band_data`), so every night already pulled can be backfilled.
- Overnight HR: `wearable_daily.hr_timeseries` (`offsetMinutes`, `bpm`, midnight to midnight). A night spans two rows.
- Health Connect parses `SleepStageInterval` per stage (`HealthConnectDailySyncRepository.kt`) and discards it: fallback source.
- Respiratory rate is averaged per night; per-reading series exist only on the Health Connect path and are likely sparse.
- Sleep cycles are not provided; derive them (deep to REM sequence) and label "estimated".

Steps
0. Read one stored `band_data` row: confirm `start/stop` units (epoch seconds vs minute offsets) and that `data_hr` date aligns with `sleep_daily.date`.
1. Migration: `sleep_daily add column stage_intervals jsonb, rr_timeseries jsonb`; format `[[startOffsetMin_from_bedtime, durMin, stageCode]]`.
2. `zepp-extract`: write compact intervals in `decodeBandDataSummary`; unit test beside `redecode.test.ts`.
3. Backfill from `zepp_raw_extracts` via the existing re-decode path (`redecode.ts`).
4. Health Connect sync: serialise stages into the same form, only when the night's source is Health Connect (never overwrite Zepp).
5. App: `SleepAnalysisRow.stageIntervals`; single-night query joining the two `hr_timeseries` rows, sliced to bedtime..wake.
6. `HypnogramCard`: four lanes (awake/REM/light/deep), hh:mm axis, wake marker, HR polyline overlay, RR toggle only when data exists, cycle brackets labelled estimated. Until step 5: falls back to stage-minute bars.

## 2. Glucose-spiking model (Today › Nutrition Today)

**Verdict: feasible only as a coarse per-meal relative glycaemic-load estimate. Never mg/dL, never a curve.**

- Inputs exist: `MealRow`/`MealItemRow` (carbs, sugar, fibre, protein, fat, `is_estimated`, `confidence`, `logged_at`).
- Gaps: no meal-type field (cluster meals within 60 to 90 min); no GI table; fibre/sugar often null (null must not read as 0); no CGM data.
- Evidence limits: individual responses vary widely (Zeevi 2015, Berry 2020); generic GI/GL predicts individuals poorly; modifier effects (fibre, protein, fat, food order, post-meal walk) come from small lab trials and must not be stacked into numbers. Citations are unverified, re-check before shipping copy.

Steps
1. `domain/analysis/MealGlycaemicLoad.kt`: group by time gap; available carbs = carbs - fibre; sugar share; fibre/protein/fat modifiers; output ordinal band (Low/Moderate/High, relative to the person's own meals) plus drivers; Building/Unavailable when carbs are null or mostly estimated.
2. Presentation per `design/ANALYTICAL_PRESENTATION_CONTRACT.md`: provenance model-estimated, confidence Low/Medium, state Neutral/Building only (no clinical threshold), copy "Estimate from carbs, fibre, protein and fat. Not measured glucose."
3. Card: one row per meal (time, band pill with icon, drivers, confidence chip, "incomplete" when fibre/sugar missing).
4. Tests next to `NutritionResolverTest`: carb-heavy vs balanced, null fibre stays null, time-gap grouping, estimated-heavy degrades confidence, empty day builds.
5. Lab fasting glucose in Healthspan stays separate.

## 3. Hydration accounting (Nutrition › Hydration)

**Verdict: feasible, mostly assembly.** Demand model exists for today only; needs history. Show as an intake-vs-demand RANGE band (contract: hydration is a range, not a goal).

- Reuse: `HydrationIntelligence.kt`, `domain/fuel/SweatProfile.kt` (`predictSweatRate`), `HeatStress.kt`, `data/HydrationDemand.kt`, `NutritionGoalsStore.hydrationBoundsMl`, intake aggregation in `NutritionDailyState.kt`, `WeatherRepository.fetchHistoricalAt`.
- Gaps: no per-day demand history; intensity hardcoded to MODERATE; no historical weather persistence; baseline is a generic 2.5 to 3.5 L; no food-water accounting; caffeine/alcohol are annotations, not demand adjustments (Killer 2014); intake vs demand is not a dehydration test.
- Reference: EFSA 2010 total water AI 2.0 L (women) / 2.5 L (men); IOM 2004 2.7 / 3.7 L total. Label "population adequate intake". Open decision: default when sex is unknown (user range wins, else leave unset).

Steps
1. `domain/fuel/HydrationAccounting.kt`: `DayHydration(date, intakeMl?, effectiveMl?, baselineLow/High, exerciseLow/High, demandLow/High, weatherSource, confidence)`; days without logged intake are null, not 0.
2. `data/HydrationAccountingRepository.kt`: 14 to 28 days of intake, workouts (duration, type, HR to intensity), weight, cached weather; refactor `todaySweatRate` to take intensity.
3. Chart in `NutritionHydrationScreen`: shaded demand band per day, intake over it, gaps visible, legend naming source, confidence, "estimate, not a dehydration test".
4. Tests: baseline-only day, workout widens band, hot vs cool, no-intake is null, missing weather widens band, no-workout equals baseline.

Rough size: hypnogram 3 to 4 days (migration + sync + backfill + UI), glucose 2 days, hydration 2 to 3 days.

## Status (built)

- **Hypnogram:** migration `20261105_sleep_stage_intervals.sql` applied to the live database and backfilled (473 nights from `zepp_raw_extracts`). Confirmed on a real night: stage `start`/`stop` are inclusive minutes from the local midnight of the day before the query date, so offsets are taken from the first stage, which equals `bedtime`. The `zepp-extract` change (`sleepStages.ts`) is committed but NOT deployed, so new nights have no stages until it is. The breathing overlay is not built (the band does not record it overnight). Cycles are estimated.
- **Glucose spiking:** `domain/MealGlycaemicLoad.kt` and the Today card. A relative load band per meal, never mg/dL.
- **Hydration accounting:** `domain/fuel/HydrationAccounting.kt`, `data/HydrationAccountingRepository.kt` and the Hydration chart. Demand range = baseline range plus predicted session sweat; the last 28 days (7 at 7D). Session type is assumed to be a run, effort comes from RPE.
- **Migration `20261104_wearable_hr_daily_range.sql`** applied. The view unnests every day's minute series (about 1.4 s for 487 days).
