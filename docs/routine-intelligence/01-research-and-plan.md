# Routine intelligence: places, commutes, time use and phone use (research and plan)

Status: research and plan only. Nothing here is built. Written 27 Oct 2026 against `main` at `b31b313`.
Scope: a feature that passively records where the person spends time, how they move between places, and how they use
the phone, then reads those habits against the data the app already holds (sleep, HRV, training, nutrition, check-ins)
so the person can spot what to change and try alternatives. It is a personal routine mirror, not a productivity score
and not surveillance: raw traces stay on the phone, and every finding speaks the evidence vocabulary the app already
uses (OBSERVED / ASSOCIATED / TESTED, no cause verbs).

Contents: 1 verdict · 2 what the app already has · 3 feasibility per signal · 4 metrics catalogue · 5 what it enriches
and what enriches it · 6 existing products · 7 evidence for utility · 8 evaluation: value, impact, risks · 9 data model ·
10 action plan · 11 open decisions · sources.

---

## 1. Verdict

**Feasible, and a good fit — build it in two halves, phone use first.**

| Question | Answer |
|---|---|
| Can an Android app do this passively? | Yes. Phone use needs one special-access toggle (`PACKAGE_USAGE_STATS`) and no location. Places and commutes need "Allow all the time" location plus the Activity Recognition permission. The app is personal and sideloaded, so the Play Store's background-location declaration and review do not apply. |
| Is it reliable? | Phone use: yes, it is the operating system's own event log. Places: good for "where did I spend 20 minutes or more", fair for short stops, and dependent on keeping the phone's battery optimiser from killing the collector. Commute mode: good for walk/run/cycle/vehicle, weak for telling bus from MRT from car without extra rules. |
| Battery cost | Phone use: negligible (one read a day). Places: aim under 3 % a day with transition-driven sampling (section 3.1); continuous GPS would cost far more and is ruled out. |
| Does it add value to *this* app? | Yes, more than in a generic tracker, because the hard part (an honest association and experiment engine with weekday adjustment, explicit unknowns and a ledger) already exists and is starved of exposures. Section 5 lists 14 concrete hooks. |
| Biggest risk | Privacy. Location is the most identifying data the app would hold, and this database already holds sexual-health and encounter records. Section 8.3 sets the rule: coordinates never leave the phone. |
| Second risk | Insight that is only obvious ("you spend more time at the office on weekdays") or only nagging. The design answers with the existing analysis contracts and a strict cap on nudges. |
| Effort | Roughly 10–14 sessions over six phases (section 10). Phase R1 (phone use, measured instead of self-reported) ships value in 2 sessions on its own. |

---

## 2. What the app already has (inspected 27 Oct 2026)

| Asset | Where | Why it matters here |
|---|---|---|
| `daily_context` with nullable `temp_max_c`, `humidity_mean_pct`, `daylight_hours` | `migrations/20261015_evening_checkins.sql` | Built for this. The analysis review lists "Passive weather and daylight — not built: the app has no location source, and adding one needs a permission decision" (`docs/analysis-review/README.md`). This feature is that permission decision. |
| `evening_checkins.late_screen` (boolean, self-reported) | same migration, `EveningCheckinRepository.kt` | Becomes a measured value. The self-report stays for one month as a check of the measurement, then is dropped from the sheet. |
| Home location, entered by hand | `MapSettingsStore.kt`, Setup › Connected services | Seeds the first place ("Home") with no learning needed. |
| Calendar events with free-text locations, Nominatim geocoding | `GeocodingRepository.kt`, `MapScreen.kt` | Planned vs actual: did the person go where the calendar said, and when. |
| Health Connect: steps, sleep sessions, exercise sessions and routes, heart rate | `healthconnect/`, manifest | Sleep onset and wake give the bed window for "phone in bed"; steps and HR confirm active commute legs; exercise routes are already GPS. |
| Zepp workouts with a `location` geohash | `docs/zepp-integration/03-workout-detail-field-decode.md` | Workout start places without new permissions; trains the place model (gym, trailheads). |
| WorkManager background sync, notification rules engine with `skip_if_logged`, `max_per_day` | `NotificationDispatchWorker.kt`, `notification_rules` | The collector and the nudges reuse both; no new background infrastructure. |
| Analysis core: fixed hypothesis catalogue, weekday adjustment, explicit unknown vs zero, case-crossover, expected bands, typicality, randomised experiments, interrupted time series, evidence ledger | `supabase/functions/_shared/analysis-core/` | The engine that turns routine data into honest findings. It already refuses a weekly-schedule cause as "not testable", which matters a lot here (section 8.4). |
| Interventions and experiments tables | `interventions`, `experiments` | "Phone out of the bedroom for two weeks" is an experiment the app can already run once its metric list is widened. |
| Device context | Android 14+, one user, sideloaded, Taipei (UTC+8, no DST), Amazfit watch via Zepp | No Play Store constraints; one OEM to tune battery handling for; Taiwan's open transit data is available for commute alternatives. |

---

## 3. Feasibility per signal

### 3.1 Location: visits and trips

**Platform pieces** (all Google Play services or framework, no paid SDK):

| Piece | What it gives | Notes |
|---|---|---|
| Activity Recognition **Transition API** | Events when the person starts or stops STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE | Google built it so apps stop polling sensors themselves; Life360 reported better latency and battery after switching. The trigger for everything else. Needs `ACTIVITY_RECOGNITION`. |
| **Fused Location Provider** | Fixes at a chosen priority (balanced ≈ Wi-Fi/cell, high accuracy = GPS) | Used in bursts, never continuously. |
| **Geofencing** | Enter/exit/dwell events for up to 100 circles | Watches the current stay and the top places cheaply, so the app knows when a visit ends without sampling. |
| `ACCESS_BACKGROUND_LOCATION` | Location while the app is not on screen | Granted in Settings as "Allow all the time". On the Play Store this requires a declaration and review; for this sideloaded app it is only a user grant. |
| Foreground service type `location` (Android 14) | An alternative to background location during an active trip | Optional: a quiet ongoing notification while a trip is being recorded makes the OS far less likely to kill sampling mid-commute. |

**Recording strategy (transition-driven):**

1. STILL → arm a geofence (about 150 m) around the current fix; take no more fixes.
2. Geofence exit or a WALKING / ON_BICYCLE / IN_VEHICLE transition → a trip starts: balanced-power fixes every 30–60 s
   (high accuracy only for walk/run/cycle, where the route matters for active minutes).
3. STILL for 5 minutes, or geofence dwell → the trip ends; the stay begins.
4. A WorkManager job every 6 hours checks the collector is alive and records gaps as **unknown**, never as "at home".

**From fixes to meaning** (standard stay-point method, on the phone):

- A **visit** is a stay of at least 10 minutes within 150 m (both tunable; the research literature mostly uses
  100–200 m and 10–30 min).
- Visits cluster into **places** (density clustering on visit centroids). Each place gets a label the person chooses
  (Home, Work, Gym, Parents, Café…) and a category (home, work, exercise, food, social, shop, health, transit, other).
  Suggestions come from the existing home location, Zepp workout geohashes, calendar event locations and a reverse
  lookup against OpenStreetMap tags through the Nominatim client the app already has.
- A **trip** is the gap between two visits: start and end place, departure and arrival, distance, and legs by mode
  (from the transitions; vehicle legs split into rail/road later by matching to MRT lines, phase R5).

**History backfill:** Google Maps Timeline moved to on-device storage in 2025 and has no API; the web viewer is gone and
Takeout no longer carries it for migrated accounts. Its in-app export (Maps › Your Timeline › ⋮ › Export) writes a JSON
file that an import screen could read once to seed places and a baseline. Optional, and only covers that device.

**Known weak spots:** OEM battery optimisers (must ask for the battery-optimisation exemption and show collector health
in Setup); indoor and underground positioning (MRT trips will look like a jump between stations, which is fine for
duration and mode, not for route); short errands under 10 minutes merge into trips.

### 3.2 Phone use

| Piece | What it gives | Notes |
|---|---|---|
| `UsageStatsManager.queryEvents` | Every app moving to and from the foreground, with timestamps | Needs `PACKAGE_USAGE_STATS`, granted by the person under Settings › Special app access › Usage access. No runtime dialog. |
| `UsageEvents.Event.SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE` (API 28+) | Screen on/off | Screen time that does not depend on which app was on screen. |
| `KEYGUARD_HIDDEN` (API 28+) | Each unlock | Pickups, first unlock of the day, unlocks during the night. |
| `ApplicationInfo.category` (API 26+) | The app's own category: social, video, game, audio, news, productivity, maps… | Groups without a hand-made list; the person can override per app. |

The system keeps the detailed event stream for a limited time only, so a daily WorkManager job reads yesterday and
stores aggregates; the raw events are discarded. Android's Digital Wellbeing dashboard has no public API, but its daily
totals are the reference to validate against (gate in phase R1).

**Derived per day:** screen time, pickups, first unlock (against wake time from sleep), last use before sleep onset,
minutes of use in the bed window (sleep onset − 60 min to onset, plus any use between onset and wake), night unlocks,
time per category, longest phone-free stretch between 08:00 and 22:00, and use while at each place category and while
on each trip mode (once 3.1 exists).

### 3.3 Other signals considered

| Signal | Value | Cost / concern | Decision |
|---|---|---|---|
| Weather and daylight at the actual location | Fills `daily_context`; heat and humidity are real confounders for HRV and runs in Taipei | None once location exists (Open-Meteo is already used) | **In** (R3) |
| Estimated time outdoors in daylight | One of the better-evidenced exposures for mood and sleep (section 7) | No light sensor on the watch path; estimate from "on a walk/run/cycle leg, or at a place categorised outdoor, between sunrise and sunset" | **In**, labelled as an estimate |
| Charging events | Phone-on-nightstand proxy, cheap | Ambiguous | Later, as a helper for "phone in bedroom" |
| Notification counts (`NotificationListenerService`) | Interruptions are a habit lever | Reads every notification's content; very invasive | **Out** for v1 |
| Calls / SMS logs | Social contact proxy in research | Sensitive, other people's data | **Out** |
| Microphone / ambient noise, raw accelerometer | Sleep environment, sedentariness | Battery, invasive, duplicates the watch | **Out** |
| Wi-Fi network name | Helps place detection indoors | Needs location permission anyway; small gain | Later, optional |
| Spending (bank) | Routine cost; "alternatives" with money attached | No open banking path in Taiwan for a personal app | **Out** |

---

## 4. Metrics catalogue

All daily unless noted. Each enters the metric registry (`_shared/metric-registry/metric-registry.json`) with method,
limits and reference before it is shown.

**Time budget**

| Metric | Definition |
|---|---|
| Time by place category | Minutes at home, work, exercise, food, social, other, in transit, unknown |
| Work span | First arrival at a work place to last departure |
| Places visited, new places | Count of distinct places; places first seen in the last 7 days |
| Day type | Workday / home day / mixed / travel (rule on time at work and distance from home); used as a covariate |

**Mobility (the digital-phenotyping set, section 7)**

| Metric | Definition |
|---|---|
| Location entropy (normalised) | How evenly time is spread across places |
| Radius of gyration | Typical distance from the day's centre of movement, km |
| Circadian movement | How consistently the same places are occupied at the same hours, across 14 days |
| Routine similarity | Similarity of today's hour-by-place vector to the same weekday's usual one; feeds IS TODAY TYPICAL? |
| Time from home | Minutes away from home |

**Commute** (a trip between home and work, either direction, or a place the person marks)

| Metric | Definition |
|---|---|
| Door-to-door time, departure time | Per trip, with the 28-day median and spread by direction |
| Mode split, active minutes | Minutes walking/cycling inside the commute; steps from Health Connect for the same span |
| Variability | Spread of door-to-door time; the unpredictable commute is a stressor of its own |
| Calendar slack | Arrival vs the first calendar event at that place |

**Phone** — as in 3.2.

**Regularity**

| Metric | Definition |
|---|---|
| Sleep Regularity Index | Already flagged as a needed schema addition (`feasibility-assessment.md` via ROADMAP P8); bedtime/wake are now in `sleep_daily` |
| First-unlock vs wake | Minutes from wake to first unlock |
| Social jet lag | Mid-sleep on free days vs work days, with day type from places instead of the weekday |

---

## 5. What it enriches, and what enriches it

### 5.1 New exposures for the analysis core (hypothesis catalogue bump)

Each is one fixed, pre-registered question, tested once with the existing regression (weekday, trend, prior outcome,
Newey-West intervals), so adding them widens the family and the Benjamini-Hochberg count honestly.

| Question | Cause series | Outcome | Lag |
|---|---|---|---|
| Phone use in the bed window and that night's sleep | `screen_bed_min` (continuous, per 15 min) | sleep hours, sleep latency | 1 |
| Phone use in the bed window and next-morning HRV | `screen_bed_min` | HRV | 1 |
| Night unlocks and next-morning energy | `night_unlocks` | energy | 1 |
| Social/video minutes after 21:00 and sleep | `screen_pm_social` | sleep | 1 |
| Commute over its usual time and evening stress | `commute_over_median` (binary) | `stress` (evening check-in) | 0 |
| An active commute and next-morning HRV | `active_commute` | HRV | 1 |
| Daylight outdoors and that night's sleep | `outdoor_daylight_min` | sleep | 1 |
| A home-bound day and next-day energy | `home_day` | energy | 1 |
| Travel day (far from home or new time zone) and HRV | `travel_day` | HRV, RHR | 1 |

### 5.2 Other hooks in the existing app

| Area | Hook |
|---|---|
| Capture (the app's main gap) | Leaving the gym geofence after 40 + minutes with no strength session logged → one LOG SESSION? notification (existing `skip_if_logged`). Arriving home after a long trip with no meal logged since noon → existing fuel reminder fires earlier. |
| Training load | Walk and cycle commute legs are real load that is never logged; show them as NEAT next to CTL/ATL (shown adjacent, not merged into the TRIMP model without a decision). |
| Expected bands / typicality | Day type as a covariate: a home day or a travel day is often why a morning sits outside its band. |
| Health concerns | Reduced mobility (time at home up, radius down) is an early illness signal; offered as context on an episode, never as a diagnosis. |
| Nutrition / fuel planning | Meals by place (home-cooked vs out); the meal-prep plan can count real at-home evenings. |
| Weather | `daily_context` filled from the actual place, not Taipei's centre; the gear checklist uses the session's real start place. |
| Calendar | Planned vs actual: late arrivals, skipped sessions (calendar said gym, location said home). |
| Wardrobe (P6) | Days at work vs home vs gym is the main input that table was waiting on. |
| Evening check-in | `late_screen` measured, so the sheet asks one question fewer. |
| Experiments | "Phone charges outside the bedroom" or "walk the last MRT stop" as randomised experiments; adherence is measured passively, which removes the usual weakest link of N=1 trials. |

### 5.3 What enriches routine data

Sleep (bed window), steps and HR (confirm active legs), calendar (labels and intent), Zepp geohashes (exercise places),
weather (outdoor plausibility), evening check-in (stress, mood as outcomes), and the person's own labels.

---

## 6. Existing products and approaches

| Product | What it does | What works | What to learn / avoid |
|---|---|---|---|
| **Google Maps Timeline** | Passive visits and trips with mode, now stored on the device | Excellent place matching from Google's place database; zero effort | No API, no analysis, moved off the cloud in 2025 partly over privacy. The bar for "it just works" on place labels. |
| **Moves** (2013–2018) | Automatic walk/cycle/transport diary | Proved people want a passive daily story | Bought by Facebook and shut down; users lost their history. Own the data. |
| **Arc Timeline** (iOS) | Visits, trips and 15 + transport modes, learns per user; GPX/JSON export | The closest reference design: an on-device learning classifier that improves with the person's corrections | Corrections must be cheap (one tap to fix a mode or a place). Exports are a feature. |
| **Life360** | Family location sharing, drive detection | Battery-efficient detection with the Transition API | Sold precise location to about a dozen data brokers (The Markup, 2021). The reason the "coordinates stay on the phone" rule exists. |
| **Gyroscope** | All-in-one life dashboard incl. travel, productivity | Beautiful daily/annual reports | Wide but shallow; few honest statistics. |
| **Exist** | Pulls many services, finds correlations | Correlations are what users value most | Naive correlation over many pairs finds noise; this app deliberately retired that approach (`docs/analysis-console/IA.md`). |
| **Bearable** | Symptoms vs factors | Clear "factor → symptom" framing | Manual logging fatigue. |
| **Android Digital Wellbeing / Apple Screen Time** | Screen time, unlocks, per-app limits | Accurate, built in | Numbers without context; no link to sleep or mood. No public API on Android. |
| **one sec** | A pause before opening chosen apps | Field experiment (PNAS 2023, n = 280, 6 weeks): 36 % of opening attempts dismissed, opening attempts down 37 %; the option to cancel mattered most | Friction works better than reports. A candidate nudge in phase R5, not a feature to copy wholesale. |
| **Apple Journaling Suggestions** | On-device "moments" from significant locations, workouts, media | On-device by design; the person chooses what leaves | The privacy pattern to copy. |
| **Beiwe, AWARE, RADAR-base, mindLAMP** | Research digital-phenotyping platforms | Published feature definitions (entropy, circadian movement, home time), missing-data handling | Use their feature definitions and their honesty about missingness; do not adopt the platforms (multi-user servers, overkill for one person). |

**Patterns that recur in what works:** passive capture, one-tap correction, a daily narrative before charts, data the
person owns and can export, and a small number of actionable comparisons. **What fails:** dashboards without a
question, correlation fishing, constant nudges, and opaque data use.

---

## 7. Evidence for utility

| Claim | Evidence | Strength |
|---|---|---|
| Mobility patterns reflect wellbeing | Saeb et al. 2015 (JMIR, n = 28): normalised entropy, circadian movement and location variance correlated r ≈ −0.58 to −0.63 with depressive symptoms; replication 2016 (PeerJ, n = 48) r ≈ −0.43 to −0.46, stronger on weekends | Small samples, between-person; supports tracking the features, not judging a person by them |
| Sleep regularity matters beyond duration | Windred et al. 2024 (SLEEP, UK Biobank, ~61,000 with accelerometry): most regular quintile 20–48 % lower all-cause mortality; regularity a better fit than duration | Large, observational |
| Daylight is a modifiable exposure | Burns et al. 2021 (J Affect Disord, UK Biobank): more outdoor daytime light with fewer depressive symptoms and better sleep; 2023 Nature Mental Health (~87,000 with light sensors): more day light, less night light, lower risk of several disorders | Large, observational |
| Commute length and mode matter | Clark et al. 2019 (Transportation; Understanding Society, 26,000 English workers over 5 years): every extra minute lowers job and leisure satisfaction and worsens strain; walkers and cyclists did not show the leisure penalty | Large panel, observational |
| Phone use *in bed* is the harmful part | 2022 study (n = 75, wearable ring): use in bed worsened sleep latency, wake time, HR and HRV; use outside bed did not. Adolescent 21-day study: post-bedtime use, not pre-bedtime | Small to moderate; consistent on timing |
| Self-report of phone use is unreliable | Andrews, Ellis et al. 2015 (PLoS ONE): estimates adequate for totals only; Davidson & Ellis: "addiction" scales barely predict measured use; other studies find people both under- and overestimate | Consistent |
| Passive beats manual for sustained tracking | Epstein et al. 2015/2016: lapses come from forgetting and upkeep; incomplete records lead to abandonment | Qualitative, consistent |
| Friction can change phone habits | one sec field experiment, above | One good field study |

**What this means for the design:** the strongest, most actionable levers are *timing* ones (phone in the bed window,
daylight before noon, departure time, sleep regularity), not totals. Lead with those.

---

## 8. Evaluation: value, impact, risks

### 8.1 Value per component

| Component | Value to the person | Value to the rest of the app | Cost | Rank |
|---|---|---|---|---|
| Phone use, measured | High: the bed-window and night-unlock numbers are rarely seen and directly actionable | High: replaces a self-report; four new hypotheses with solid priors | Low | **1** |
| Places and time budget | Medium-high: the week at a glance | High: day type covariate, capture prompts, weather at place | Medium-high | **2** |
| Commute analysis | Medium: depends on how much choice the person has | Medium: NEAT, stress | Medium (needs places) | **3** |
| Alternatives engine (routes, timing) | Medium, situational | Low | Medium (external API) | **4** |
| Mobility phenotype metrics | Low on their own; useful as context | Medium: illness and typicality context | Low once places exist | **5** |

### 8.2 Expected impact

- **Capture**: gym-exit and late-arrival prompts address the app's documented weakest link (RPE on 17 of 6,680
  sessions; strength sessions often logged late). Measured `late_screen` removes an unanswered field from 100 % of
  evenings.
- **Statistics**: the evidence board today runs 13 catalogue questions on a handful of exposures, several of which are
  "not testable" because they follow the weekly schedule. Phone-use exposures vary day to day independent of the
  weekday, so they are among the most testable exposures the app could add.
- **Behaviour**: realistic first wins are the bed-window phone use and departure-time/active-commute choices, both of
  which the person controls and the app can test with its own randomised experiments.
- **Not expected**: a "routine score". It would be a composite with no outcome to calibrate against; the healthspan
  grade's trust gate shows how much work an honest composite needs.

### 8.3 Privacy and security (non-negotiable rules)

Four time-stamped locations are enough to single out 95 % of people in a mobility dataset (de Montjoye et al., 2013,
Scientific Reports). The same database holds `encounters`, `people`, `masturbation_log` and `arousal_daily`; joined
with places and times, it would describe the person's private life in detail. So:

1. **Raw fixes, raw usage events and place coordinates stay on the phone** (an encrypted Room database, excluded from
   Android auto-backup: `allowBackup` is currently `true`, so add a backup rule excluding it).
2. Supabase receives **daily aggregates, place ids with labels and categories, and visit/trip times** — no
   latitude/longitude, no app package names (categories only, unless the person opts a specific app in).
3. Raw fixes are kept 30 days on the phone (enough to re-cluster and correct), then deleted; visits and trips stay.
4. One switch per stream in Setup (Phone use, Places, Commute), each with PAUSE FOR TODAY and DELETE ALL. A place can be
   marked **private**: visits there are recorded as "private" with no label and excluded from every analysis.
5. No routine data goes to Gemini (`log-agent`) or any third party. Reverse lookups to Nominatim send a rounded point
   (about 100 m) once per new place, never a trace.
6. RLS on every new table, as on every existing one; nothing in the web dashboard (`index.html` is public on GitHub
   Pages and only RLS protects it).

### 8.4 Analytical risks

| Risk | Mitigation (mostly already in the core) |
|---|---|
| Routine is weekly by nature, so place-based causes confound with weekday | The core already removes weekday means and reports weekly-scheduled causes as **not testable**. Expect several place questions to land there; that is the honest answer. |
| Gaps read as behaviour (collector killed = "stayed home") | Unknown is stored as unknown; days under 80 % coverage are excluded from mobility metrics; coverage is shown as the DATA chip. |
| Reverse causation (a bad night → more phone in bed) | Lags are fixed per question; bed-window use is measured *before* sleep onset for the sleep questions; the experiment path settles direction. |
| Obvious findings ("more time at work on weekdays") | Never shown as findings; the time budget is a view, the evidence board only shows catalogue questions. |
| Nudge fatigue | At most one routine notification a day across all kinds, each switchable; the gym prompt only when nothing was logged. |

### 8.5 Technical risks

| Risk | Mitigation |
|---|---|
| OEM battery killer stops the collector | Battery-optimisation exemption request; collector heartbeat and a COLLECTOR STOPPED line in Setup; optional foreground service during trips. |
| Battery drain | Transition-driven sampling, gate in R2 (under 3 %/day measured with Android's battery usage over 7 days). |
| Mode errors (bus vs car vs MRT) | Store the raw activity mode; finer modes only from rules with a confidence; one-tap correction that the classifier learns from. |
| Permission revoked by the OS (unused-app auto-reset) | Already relevant for Health Connect; check grants on every app start and say what stopped. |

---

## 9. Data model (sketch)

**On the phone (Room, encrypted, not backed up):** `location_fix` (ts, lat, lon, accuracy_m, source, activity),
`activity_transition` (ts, activity, enter/exit), `place_geo` (place_id, centroid, radius_m), `usage_day_raw`
(transient; aggregates written then cleared).

**Supabase (RLS, same `(select auth.uid())` pattern, provenance column `source`):**

| Table | Key columns |
|---|---|
| `places` | `id`, `label`, `category`, `is_private`, `created_at`, `source` (learned / home_setting / calendar / zepp) |
| `place_visits` | `place_id`, `start_at`, `end_at`, `confidence` |
| `trips` | `start_at`, `end_at`, `from_place_id`, `to_place_id`, `distance_km`, `legs` jsonb (mode, minutes, distance), `is_commute`, `corrected` |
| `mobility_daily` | `date`, `coverage_pct`, minutes by category, `n_places`, `new_places`, `entropy_norm`, `radius_gyration_km`, `routine_similarity`, `outdoor_daylight_min`, `day_type` |
| `phone_usage_daily` | `date`, `coverage_pct`, `screen_min`, `unlocks`, `first_unlock_at`, `last_use_before_sleep_at`, `bed_window_min`, `night_unlocks`, `by_category` jsonb, `longest_free_min`, `by_place_category` jsonb |
| `daily_context` (existing) | fill `temp_max_c`, `humidity_mean_pct`, `daylight_hours` from the day's main place |

Findings use the existing `analysis_results` + `evidence_ledger`; no new findings infrastructure.

---

## 10. Action plan

Each phase ends with a gate, as in the analysis review. Estimates are sessions in the roadmap's sense (directional).

| Phase | What | Gate | Est. |
|---|---|---|---|
| **R0** Decisions | Settle section 11; write the permission and privacy copy shown in Setup; add backup exclusion rule | Owner sign-off on 11.1–11.5 | 0.5 |
| **R1** Phone use | `UsageStatsCollector` (daily WorkManager job, yesterday's events → aggregates; screen on/off, unlocks, categories; bed window from `sleep_daily`); `phone_usage_daily` migration; Setup › Data capture row with grant status; Health › SLEEP gains PHONE IN BED; Today's LAST EVENING shows the measured value; keep `late_screen` self-report one month for comparison | Daily rows on ≥ 95 % of days over 14 days; screen time within 5 % of Digital Wellbeing on 10 spot-checked days; measured bed-window use agrees with self-reported `late_screen` on ≥ 80 % of answered evenings (or the disagreement is explained) | 2 |
| **R2** Location capture | Transition API + geofence + burst sampling; on-device Room store; collector heartbeat; Setup switch, pause, delete; stay-point detection to visits | 7-day test: battery under 3 %/day; ≥ 90 % of visits ≥ 20 min found against a hand-kept diary; no visit invented at home during collector gaps | 3 |
| **R3** Places, trips, commute | Clustering to places; labelling and correction on the Map tab (pins for places, one-tap fix of a trip mode); suggestions from home, calendar, Zepp, OSM; `places`, `place_visits`, `trips`, `mobility_daily`; commute detection; weather and daylight into `daily_context`; optional Maps Timeline import | ≥ 90 % of the week's time attributed to a labelled place or a trip; commute door-to-door within 3 min of the diary; `daily_context` weather filled on ≥ 90 % of days | 3 |
| **R4** Analysis integration | New series in `runRelationships.ts`; catalogue bump with the section 5.1 questions; day type covariate in expected bands and typicality; mobility context on health-concern episodes; NEAT beside load; metric-registry entries | Existing null-data gates still hold with the larger family (≤ 5 % "clear" on null data); simulation with synthetic routines shows weekly-scheduled place causes read "not testable" | 2 |
| **R5** UX and nudges | Today: ROUTINE line (yesterday's time budget, commute vs usual, phone in bed) behind one pill; Lab › ROUTINE: week strip by place category, commute card (median, spread, active minutes, alternatives), phone card; gym-exit capture prompt; bedtime wind-down reminder (off by default); commute alternatives from Taiwan's TDX transit data or a routing API (walk-an-extra-stop, YouBike leg, departure shift) shown as time vs active minutes | Every new card passes the presentation contract (R1–R8); ≤ 1 routine notification/day in a 14-day log | 2–3 |
| **R6** Experiments | Widen `experiments.primary_metric` to phone and mobility outcomes and adherence; ready-made designs: phone outside the bedroom, no social/video after 21:30, walk to the MRT, morning daylight walk | One experiment run end to end with passively measured adherence | 1 |

**Recommended order and first step:** R0 → R1 ships independently and is useful in two weeks of data. Start R2 only
after R1's gate, since R2 carries the permission, battery and privacy weight. R4 can start on R1 data alone (the four
phone questions).

---

## 11. Open decisions for the owner

1. **Location at all?** R1 stands alone; R2–R5 need "Allow all the time" location. Recommended: yes, with the section
   8.3 rules.
2. **Coordinates in the cloud?** Recommended: never (8.3.1). The cost: the web dashboard and console cannot draw a map
   of visits, only labels and times.
3. **Per-app names or categories only in Supabase?** Recommended: categories, with an opt-in per app.
4. **Notifications:** gym-exit prompt on by default, wind-down reminder off by default?
5. **Commute alternatives source:** Taiwan TDX (free, registration) vs a paid routing API vs none in v1.
6. **Private places:** which, if any, to mark before collection starts (recommended: decide before R2 runs, not after).
7. **Maps Timeline import:** worth one session for history, or start fresh?

---

## Sources

Platform
- [Android Developers Blog: Activity Recognition's new Transition API (2018)](https://android-developers.googleblog.com/2018/03/activity-recognitions-new-transition.html)
- [Google Play services location package reference](https://developers.google.com/android/reference/com/google/android/gms/location/package-summary)
- [(Deprecated) Android Sleep API codelab](https://developer.android.com/codelabs/android-sleep-api)
- [Play Console help: background location permission declaration](https://support.google.com/googleplay/android-developer/answer/9799150)
- [Background location usage best practices](https://developers.google.com/maps/documentation/navigation/android-sdk/background-location-usage)
- [HERE SDK: enabling background updates (Android 14 foreground service types)](https://docs.here.com/here-sdk/docs/android-get-locations-enable-background-updates)
- [UsageStatsManager reference](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [UsageEvents.Event reference (SCREEN_INTERACTIVE, KEYGUARD_HIDDEN)](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
- [Google Maps Timeline changes (MileageWise)](https://mileagewise.com/?p=46670), [Tom's Guide](https://www.tomsguide.com/computing/mobile-apps/google-maps-is-going-to-delete-your-location-history-very-soon-heres-how-to-stop-it), [Google Maps Help thread on local export](https://support.google.com/maps/thread/334584618/exporting-local-google-maps-timeline-fails?hl=en)
- [Apple: Journaling Suggestions and privacy](https://apple.com/au/legal/privacy/data/en/journaling-suggestions)

Products
- [Arc Timeline on the App Store](https://apps.apple.com/us/app/arc-timeline-trips-places/id1063151918)
- [The Markup: Life360 selling precise location data (2021)](https://themarkup.org/privacy/2021/12/06/the-popular-family-safety-app-life360-is-selling-precise-location-data-on-its-tens-of-millions-of-user)
- [Gyroscope on the App Store](https://apps.apple.com/app/id1104085053), [Exist on the App Store](https://apps.apple.com/zw/app/exist/id986201088), [Bearable: what are factors](https://bearable.app/support/tips/what-are-factors/)
- [one sec research page](https://one-sec.app/research/); [Grüning, Riedel & Lorenz-Spreen 2023, PNAS](https://pmc.ncbi.nlm.nih.gov/articles/PMC9974409)
- [Mobile phone based sensing software (overview of Beiwe, AWARE, RADAR-base…)](https://en.wikipedia.org/wiki/Mobile_phone_based_sensing_software), [mindLAMP](https://www.digitalpsych.org/mindlamp)

Evidence
- [Saeb et al. 2015, JMIR](https://doaj.org/article/6ebd66deba1a43b5849e2225b32f6636); [Saeb et al. 2016, PeerJ](https://peerj.com/articles/2537)
- [Windred et al. 2024, SLEEP: sleep regularity and mortality](https://pmc.ncbi.nlm.nih.gov/articles/PMC10109261/)
- [UK Biobank: time in outdoor light and mood, sleep, circadian outcomes (Burns et al.)](https://www.ukbiobank.ac.uk/publications/time-spent-in-outdoor-light-is-associated-with-mood-sleep-and-circadian-rhythm-related-outcomes-a-cross-sectional-and-longitudinal-study-in-over-400000-uk-biobank-participants/); [PsyPost on the 2023 Nature Mental Health study](https://www.psypost.org/2023/12/individuals-spending-more-time-in-daylight-have-lower-risk-of-mental-health-disorders-214967)
- [UWE: commuting and employee wellbeing (Clark et al.)](https://blogs.uwe.ac.uk/research-external-engagement/commuting-has-multiple-impacts-on-employee-wellbeing/)
- ["Leave your smartphone out of bed" (2022)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9643910/)
- [Andrews, Ellis et al. 2015, PLoS ONE](https://doaj.org/article/7c5339108e43423b83e68af1bf225ca6); [Bath: smartphone addiction evidence (Davidson & Ellis)](https://www.bath.ac.uk/announcements/why-fears-over-smartphone-addiction-are-based-on-flawed-evidence/)
- [Epstein et al. 2016: beyond abandonment](https://homes.cs.washington.edu/~jfogarty/publications/ubicomp2016.pdf); [Epstein et al. 2015: lived informatics](https://my.eng.utah.edu/~cs5540/au16/readings/PersonalInformatics-Epstein2015.pdf)
- de Montjoye et al. 2013, "Unique in the Crowd: the privacy bounds of human mobility", Scientific Reports 3:1376 (from memory, not re-fetched for this doc)

To verify before building: the exact retention window of `UsageEvents` on the target phone; Activity Recognition
behaviour on the target OEM's battery manager; TDX API terms for personal use.
