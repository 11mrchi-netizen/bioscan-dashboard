# Data underuse: what was collected and not used (26 Nov 2026)

The audit is the doc "Data underuse audit (26 Nov 2026)" in the project; this page is what was built from it and what is
left. Catalogue version 2.5.0, engine `CORE_VERSION` unchanged.

## Built

| Step | What | Where |
|---|---|---|
| 1 | The watch's all-day stress (Zepp `all_day_stress`, 508 days from Feb 2025) as a daily series: mean and share of high-band readings per local day, recomputed from the raw extracts as the phone does. Written to `derived_daily` (`watch_stress_mean`, `watch_stress_high_share`) by engine `derived`; the relationship engine reads the same raw rows. Seven questions: sleep, SLEEP score, HRV, resting HR, RECOVERY, next-day energy, digestion. Metric pages `series:watch_stress`, `series:watch_stress_high_share`. | `analysis-core/watchStress.ts`, `runDerived.ts`, `runRelationships.ts`, `hypotheses.ts` |
| 2 | `stress_high` was documented as "a 4 or 5 in the evening check-in or the wellbeing log" but read only the check-in (never above 3), so every stress question had 0 days. It now reads both. A question whose exposure never (or always) happened in the days measured says so ("it never happened in the N days measured ... needs a deliberate experiment") instead of "not yet". | `runRelationships.ts` (`buildFrame`), `relationship.ts` |
| 3 | The relationship engine reads 1,500 days (about 4 years) instead of 365: sleep from Mar 2023, sessions, steps, resting HR. HRV and the meal and phone exposures start later, so their questions are unchanged (a day without both ends of a pair is never used). The other engines keep the 365-day frame their calibrations were set on. The model already has a weekday term and a spline trend every 60 days. A null-world test on 1,500 days (10 seeds) calls nothing clear; one catalogue run takes about 1.4 s. | `RELATIONSHIP_LONG_LOOKBACK_DAYS`, `analysis-run/index.ts` |
| 4 | New sleep outcomes: night heart-rate dip (`hr_dip_nightly`), deep-sleep share, bedtime clock minutes, SpO2 (series only). Questions against caffeine, watch stress, food before bed and training minutes, filed under SLEEP on both boards. Metric page `series:hr_dip`. | `sleepExposures.ts` |
| 5 | Caffeine on board at bedtime (mg, 5 h half-life, from logged items): a dose exposure for sleep, SLEEP score, HRV, deep share, HR dip. | `sleepExposures.ts` |
| 7 | Weather for days before the phone filled it: `backfillWeather` (engine `derived`, only when the request carries `weather_days`) writes `daily_context` rows from Open-Meteo's archive at the Home place, coordinates rounded to 0.1 degree, never overwriting a row. Three heat questions (sleep, HRV, resting HR against the day's high). | `weatherBackfill.ts`, `runDerived.ts` |
| 8 | Water logged against next-morning resting HR, HRV and next-day energy; a logged energy balance per day (eaten minus the watch's total, only on days with at least 1,200 kcal logged) as `derived_daily.energy_balance`, with a metric page. | `runDerived.ts`, `hypotheses.ts` |
| 9 | Web metric pages for night HR dip, screen time, phone use at night and outdoor daylight time (series contracts and hues only: they open from Explore and any chart that names them). | `metric-series.json`, `console/src/lib/schema.ts`, `metrics.ts` |
| 10 | The weekly and monthly reports suggest a repeat blood test when the last draw is 12 weeks old and a regimen item is followed. The only draw is 11 Sep 2026, so it fires from 4 Dec. | `periodReport.ts` |
| 12 | Whole-day phone and movement questions (screen time, unlocks, spread of the day, new places) against sleep and next-day energy. They read "not yet" until about 60 days exist (phone data starts 25 Oct, about 25 Dec). | `routineExposures.ts`, `hypotheses.ts` |

## Not built, and why

- **Step 6 (intimacy against next-morning outcomes)** already exists on the web Intimacy page (Effects view, aggregates only,
  discreet by default) and the phone page; it is deliberately not an engine question, so it never appears on the general
  evidence boards.
- **Step 11 (capture repair)**: the yes/no `evening_checkins.late_screen` was filled on 0 of 33 days. The owner chose to
  replace it with the phone's measured bed-window (`phone_usage_daily.bed_window_min`). The web Log now shows
  "phone before sleep N min" and no longer reads `late_screen`. The Android score already prefers the measured value
  (`phoneBedMin`) and falls back to the old field only when the phone has no measure; that Android code is left as is
  because it cannot be built in the cloud. Recovery sessions (stopped 9 Sep) and breathwork nudges are Android work.
- **Calendar load against stress and sleep**: the calendar has 35 events in the last five weeks, mostly sessions; it needs a
  classification of what counts as a busy day first. Revisit about 20 Dec.
- **Awake minutes from `stage_intervals`**, **SpO2 as a question** (its night-to-night spread is tiny), **phone surfaces for the
  new series** (the phone already shows stress rhythm, and has its own metric pages).

## To go live (needs the owner's OK)

No migration is needed. Deploy `analysis-run` (it bundles `_shared/analysis-core`), then fill the history once, for example
from the scheduler: `{ "action": "run", "group": "reports", "stress_days": 800, "weather_days": 1500 }` (group `reports`
runs `derived`), then the nightly `core` run publishes catalogue 2.5.0.
