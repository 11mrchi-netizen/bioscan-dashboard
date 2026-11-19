# Risk profile: implementation status

The plan and the owner's decisions: [01-plan.md](01-plan.md). This file says what is built, where it lives, how to deploy
it and what is not done. Built so far: **R0** (home blood pressure) and **R1** (air exposure), 19 Nov 2026.

## R0: home blood pressure

| Part | Where | Tested by |
|---|---|---|
| `bp_readings`: one row per reading, logged (wall-clock) time, own-row RLS, unique Health Connect record id | `supabase/migrations/20261119090000_bp_readings.sql` | applied on Postgres 16 with a Supabase stub: RLS, idempotent re-import, source/record-id check |
| Sittings (readings within 15 min), series (gap over 3 days), the AHA 7-day protocol (first day dropped, 3 days needed), ACC/AHA 2017 categories, clinic vs home (white-coat / masked at 130/80), crisis flag above 180/120, next series in 3 months | `android-app/.../domain/bp/BloodPressure.kt` | `BloodPressureTest` (10) |
| Health Connect import, optional permission (`OPTIONAL_PERMISSIONS`: the sync and "connected" state do not depend on it), its own 400-day window, failures never hold back the sync's watermark | `healthconnect/HealthConnectManager.kt`, `data/BloodPressureRepository.kt`, `data/HealthConnectSyncCoordinator.kt`, Setup › Connected services (ALLOW BLOOD PRESSURE) | compiled; on-device check pending |
| Manual entry: a sitting of 1 or 2 readings, 1 minute apart; editing one reading from the Log | add sheet › Other › Blood pressure, and Lab › TESTS (`ui/screens/EntryBpForm.kt`; in OTHER because a tenth BODY tile no longer fits the one-page menu) | `AddEntryChoiceTest`, `FlowFitTest` |
| Day log: readings as BP entries (read softly: a missing table does not cost the feed) | `domain/Log.kt`, `data/LogRepository.kt` | compiled |
| Lab › TESTS › BLOOD PRESSURE: today's protocol, the home mean and category, the clinic reading from lab draws, the pattern, the next series | `ui/screens/status/BloodPressureCard.kt` | compiled |
| Console: the same series rules | `console/src/lib/bp.ts` | `bp.test.ts` (same cases as the Kotlin test) |

Clinic readings stay where they were (`lab_results`: `SBP, Sitting`, `DBP, Sitting`). Still open, small:
`LabMarkerThemes.kt` lists those markers under Kidney and Cardiovascular (the later wins); not changed here.

## R1: air exposure

| Part | Where | Tested by |
|---|---|---|
| `outdoor_air_hourly` (shared cache, service role only) and `air_exposure_daily` (own-row reads); `private.run_analysis` accepts `exposures`; cron job `analysis-run-exposures` at 20:45 UTC | `supabase/migrations/20261119100000_air_exposure.sql` | applied on Postgres 16 with stubs: own rows read, cache invisible, user writes refused |
| Placing each hour (visit, else a fix within 90 min), indoor time-weighting (sensor values held 20 min), sources (indoor / session / outdoor at home / outdoor away), daily means, highest 8-h ozone, night CO2, dose shares, the 30-day summary and the ranked ways to improve | `supabase/functions/_shared/analysis-core/airExposure.ts` | `airExposure.test.ts` |
| The run: soft reads, outdoor cache filled only for missing hours from the Open-Meteo Air Quality API (CAMS), days and the `air_exposure` summary published | `analysis-core/runAir.ts`; `analysis-run` group `exposures` and action `air` | `airExposure.test.ts` (fake admin), `runGroups.test.ts`; live API checked from this container; `deno check` |
| Phone: Me › EXPOSURES, every metric opening its history; published to `me_views` (and by the background worker) | `ui/screens/me/ExposuresPage.kt`, `data/ExposuresRepository.kt`, `domain/meview/ExposuresMeView.kt` | `ExposuresMeViewTest` (the summary decoded from a fixture the TS engine wrote), `NavigationMapTest` |
| Console: ANALYSIS › Risk (air summary, ways to improve, PM2.5 / NO2 / ozone / night CO2 by day, any day hour by hour, blood pressure); EXPOSURES on the Dashboard's Me tabs | `console/src/workspaces/Risk.tsx`, `dashboard/meView.ts` | `tsc`, build, `e2e/risk.mts` (the page rendered from the real engine's output) |
| Metric hues: PM2.5, NO2, ozone, UV, CO2 | `design/tokens/tokens.{json,css}`, `FT.Metric` | — |

Without an indoor PM2.5 sensor (the ALPSTUGA is not bought yet) home hours use the outdoor level at home, and every view
says so; the first suggestion is then to measure indoors.

## R2: mental health, connection, work

| Part | Where | Tested by |
|---|---|---|
| The questionnaires as published: PHQ-9, GAD-7, WHO-5, UCLA-3, the Copenhagen Burnout Inventory's work scale, working hours; items, options, scoring (sum / mean / multiplier / reversed items), bands with their state, flags, intervals (4 or 8 weeks), sources, and worked cases | `supabase/functions/_shared/instruments/instruments.json` (an Android asset too) | the cases, by both scorers below |
| `instrument_responses`: one row per completed questionnaire (answers, score, band, flags, the definitions version), append-only (no update policy: a mistake is deleted and answered again); `answered_at` real instant, `local_date` the local calendar date. `questionnaire_due` reminder, Sunday 10:00, sent only when one is due | `supabase/migrations/20261119120000_instrument_responses.sql` | applied on Postgres 16 with stubs: RLS, the update refused, the rule seeded |
| Scorer, due dates, summaries (latest, state, previous, history) | `android-app/.../domain/risk/Instruments.kt`; `console/src/lib/instruments.ts` | `InstrumentsTest` (6), `instruments.test.ts` (6, including the local date in UTC+8) |
| Phone: Me › EXPOSURES › MIND, CONNECTION, WORK (each score in its hue, band, the score before, due; a row opens its history; TAKE opens the questionnaire), the one-page questionnaire flow with its result in place; the reminder (Setup › Notifications › QUESTIONNAIRES) opens the tab; published to `me_views` | `ui/screens/me/QuestionnaireSheet.kt`, `QuestionnaireCards.kt`, `ExposuresPage.kt`, `data/InstrumentsRepository.kt`, notification files | `ExposuresMeViewTest`, `NavigationMapTest`, routing in `InstrumentsTest` |
| Console: ANALYSIS › Risk › MIND, CONNECTION, WORK (results over time, answer any questionnaire there) | `console/src/workspaces/RiskQuestionnaires.tsx` | `e2e/risk.mts` (a PHQ-9 answered in the browser: scored 9, Mild, the flag saved and shown, a local date) |
| Metric hues: PHQ-9, GAD-7, WHO-5, loneliness, burnout, working hours | `design/tokens`, `FT.Metric` | — |

PHQ-9 item 9 (thoughts of being better off dead or of self-harm) shows a plain line at any total; no crisis line and no
notification to anyone (owner decision). Passive context (encounters, phone use at night) is not folded in: encounters
became the partnered record in `main`'s sex-tracking work, so they no longer measure social contact.

## R3: heart, sugar and brain

| Part | Where | Tested by |
|---|---|---|
| AHA PREVENT, base model: 10-year (30-79) and 30-year (30-59) risk of total CVD, ASCVD, heart failure, CHD and stroke; validity ranges; the AHA categories; CKD-EPI 2021 eGFR from creatinine when no eGFR is reported | `android-app/.../domain/risk/Prevent.kt`; coefficients in `supabase/functions/_shared/risk/prevent-base.json` (an asset), extracted programmatically from the preventr R package's data (MIT), each row with preventr's label | `PreventTest`: preventr's published reference results, 20 values (both sexes, 10 and 30 years, five outcomes), and its eGFR references |
| Life's Essential 8 (the advisory's point tables; diet left as "not assessed" until R4 brings a diet questionnaire, the score shown over the measures assessed), FINDRISC (all eight items, with the most it could still be when some are unanswered), the 14 Lancet 2024 dementia factors (present / absent / unknown with the source and what is needed; no combined number) | `domain/risk/Screens.kt` | `ScreensTest` (each point table, partial answers, the factors) |
| `health_background`: one row per person (smoking, quit year, second-hand smoke, diabetes, BP and cholesterol medicine, high glucose history, family diabetes, vegetables daily, hearing, vision, head injury, education), own-row RLS, edited in place | `supabase/migrations/20261119140000_health_background.sql` | applied on Postgres 16 with stubs: partial answers merge, others see nothing, bad values refused |
| Gathering: labs (total, HDL, LDL cholesterol, fasting glucose, HbA1c, eGFR or creatinine, waist), BMI (latest weight, profile height), BP (home mean, else the clinic reading), activity (logged sessions, last 4 weeks), sleep, alcohol (logged drinks), PHQ-9 and UCLA-3, PM2.5; all read softly | `data/RiskScreensRepository.kt` | `ScreensTest` (PREVENT on gathered inputs, or what is missing) |
| Phone: Me › EXPOSURES › HEALTH BACKGROUND (ANSWER / EDIT opens the one-page form), HEART · PREVENT, HEART · LIFE'S ESSENTIAL 8, SUGAR · FINDRISC, BRAIN · DEMENTIA FACTORS; published to `me_views` | `ui/screens/me/HealthBackgroundSheet.kt`, `RiskScreenCards.kt`, `domain/meview/ExposuresMeView.kt` | `ExposuresMeViewTest` |

Assumptions, labelled where they show: logged sessions count as moderate-to-vigorous activity; FINDRISC's "30 minutes a day"
is 210 minutes a week of logged sessions; alcohol counts only logged drinks; "high blood pressure" for the dementia factors
is a systolic of 130 or more (the Commission's midlife threshold) or BP medicine.

## R4: substances, sun and heat, home and water, preventive care

| Part | Where | Tested by |
|---|---|---|
| Home and water checklist: 7 items (gas cooking without extraction, incense or candles, heating food in plastic, overheated non-stick pans, damp and mould, radon, private well water), each flagged answer with why, the fix and its source (WHO indoor air quality guidelines, WHO radon handbook, EFSA, WHO drinking-water guidelines); no score; asked again after 12 months | `supabase/functions/_shared/risk/home-checklist.json` (asset) | `PreventionTest` |
| Preventive care: the layered schedule (owner decision): USPSTF A/B screenings and CDC adult vaccines; ESC/EAS (Lp(a) once); regional (hepatitis B for those from high-prevalence regions); optional (coronary calcium when PREVENT is borderline or intermediate, dental, eye, skin). By age, sex and condition (BMI, smoking, PREVENT); intervals by method (a colonoscopy every 10 years, a stool test every year); last done from records or the person's data (BP readings, lab draw dates); "check if it applies" where the app cannot tell (20 pack-years) | `supabase/functions/_shared/risk/preventive.json` (asset), `domain/risk/Prevention.kt` | `PreventionTest` |
| `home_checklist_answers`, `preventive_records` (done on a local date, by a method, or not applicable): append-only, own rows | `supabase/migrations/20261119160000_home_checklist_preventive.sql` | applied on Postgres 16 with stubs |
| Substances (alcohol and caffeine from logged drinks, late caffeine by the wall clock, smoking from the background), sun and heat (sessions on days of 32 °C or more, outdoor sessions at UV 6 or more) | `data/PreventionRepository.kt` | compiled |
| Phone: Me › EXPOSURES › PREVENTIVE CARE (RECORD opens the record form), SUBSTANCES, SUN AND HEAT, HOME AND WATER (ANSWER / EDIT opens the checklist); published to `me_views` | `ui/screens/me/PreventionCards.kt`, `PreventionSheets.kt` | `ExposuresMeViewTest` |

Not done in R4: **the diet questionnaire for Life's Essential 8.** LE8 names the MEPA screener for individuals, and its
16 items could not be read from a source here (the paper's open copy refused automated access, and the advisory's
tables are not in its PubMed Central text). It was not written from memory; diet stays "not assessed". Preventive
items are not yet in Plan or the Sunday reminder: they show on Me › EXPOSURES.

## The health assistant's scope

`agent-core/scope.ts` classifies every table (a test fails on one that is not). `bp_readings` is `shared`, like
`grip_strength_log` and `lab_results`; its free-text note is dropped unless notes are shared. `air_exposure_daily` and
`outdoor_air_hourly` are `never`: they are built from where the person was, hour by hour, the same rule as
`place_visits` and `home_env_readings`. The `air_exposure` summary in `analysis_results` is withheld too
(`resultAllowed`), since it carries the share of the dose breathed at home. `instrument_responses` (R2) is `never`:
mental health answers, including the self-harm item, stay closed until the owner opens them as an opt-in group of their own. `health_background`
(R3: smoking, medicines, diagnoses) is `never` too. So are R4's `home_checklist_answers` and `preventive_records`. These take effect in the assistant when
`health-agent` is redeployed; the live one (v3) would still serve an `air_exposure` row once `analysis-run` writes one.

## Deploy, in this order

Status (19 Nov 2026): step 1 done on live (both migrations applied and checked: tables, RLS, `run_analysis` accepting
`exposures`, job `analysis-run-exposures` at 20:45 UTC). R2's `20261119120000_instrument_responses.sql`, R3's `20261119140000_health_background.sql` and R4's
`20261119160000_home_checklist_preventive.sql` are applied on live too.
Step 2 is not done: the live `analysis-run` is still v32 (the deploy command is refused by this environment's auto mode). It
was diffed against `main` first: v32 equals `main` except the symptom-check change to `healthSeries.ts` and vocabulary
1.1.0, which `main` has and the database already expects, so deploying this branch ships `main` plus the air engine.
Until it is deployed, the 20:45 UTC `exposures` job gets a 400 (unknown group) from v32; nothing else is affected.
The bundle (53 files, 571 KB) is too large for the connector's inline deploy; use the CLI from the repo root:
`supabase functions deploy analysis-run --project-ref ugfrglbcoivkprjqvjzz --use-api`.

1. Apply `20261119090000_bp_readings.sql` and `20261119100000_air_exposure.sql`. Both only add; the second replaces
   `private.run_analysis` with the same body plus the `exposures` group.
2. Deploy `analysis-run` (it bundles `_shared/analysis-core`). Then call it once with `{"action":"air"}` as the user to
   backfill 35 days, instead of waiting for the night.
3. Build the app in Android Studio and install it. In Setup › Connected services, tap ALLOW BLOOD PRESSURE.
4. The console deploys itself from `main` (`deploy-console.yml`).

The edge function needs outbound access to `air-quality-api.open-meteo.com` (Supabase edge functions have it by default).

## Not done (next phases)

R2 (mental health, social, work questionnaires), R3 (PREVENT, Life's Essential 8, FINDRISC, brain factors), R4
(substances, heat and sun, home and water checklists, preventive care in Plan), R5 (ways to improve as Lab experiments).
