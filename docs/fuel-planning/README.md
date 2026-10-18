# Fuel planning: daily carbohydrate periodisation, race and long-session fuel and hydration plans

Why this exists: the app logged what was eaten and drunk against one fixed daily goal, and in-session fuelling against
completed sessions only. It could not represent a race or a hard day outside a training block, nor plan food and fluid
around the training ahead. This feature plans each day's carbohydrate around the training of the next 72 hours ("fuel
for the work required", Impey 2016/2018), codes each meal LOW / MEDIUM / HIGH with a gram range and a reason, and builds
race and long-session fuel and hydration plans from the course, the expected weather and what was eaten in the days
before. It was modelled on Hexis (hexis.live) and similar apps; the research behind it is below.

Owner decisions: training is read from planned sessions, calendar training events and events, merged; the nutrition goal
sets the week's energy and protein, the plan shapes each day.

## Status

| Phase | What | State |
|---|---|---|
| 1 | Events outside the training plan (`athlete_events`), event form and page, Training › Plan EVENTS card, + EVENT, Status NEXT UP, calendar suggestions, calendar sync wired | Done |
| 2 | Planned-day resolver and session demand | Done |
| 3 | Day fuel engine: bands, the week, meal codes, the days before, safety | Done (engine; shown in phase 6) |
| 4 | Weather (hourly, TYPICAL beyond 16 days), heat stress, sweat profile | Done |
| 5 | Course time estimate; race and long-session fuel and hydration plans | Done (engine; shown in phase 6) |
| 6 | `fuel_plans` snapshots; TODAY'S FUEL, the event FUEL tab | Done |
| 7 | Reminders relative to sessions and race week | Done |
| 8 | After the event: review, sweat rate and gut tolerance learned, periodisation index | |
| 9 | Planner: build toward an event, taper | |

## Phase 1 notes: events

- `supabase/migrations/20261024_athlete_events.sql`: one row per event (kind race / long_session / match / hike_day /
  other_hard_day, sport, priority A/B/C, date, start, status, target time or expected duration, distance, ascent,
  start point, a compact course profile, aid stations, the calendar entry it came from, the session that recorded it).
  RLS own rows; safe to run again.
- `domain/events/AthleteEvent.kt`: validation, `matchEventToSession` (same day, a type the sport is done as, starting
  within two hours of the start, else the longest that day), `looksLikeRace` (conservative: "trail" and "vertical" are
  training words too), `nextUpcomingEvent` / `eventTakesNextUp`, duration parsing.
- `domain/trail/CourseProfile.kt`: a GPX route smoothed with the terrain engine's rules and sampled every 100 m (about a
  thousand points for 100 km), so plans need no GPX file later; `trackpointsFromCourse` rebuilds it for the engine.
- `data/AthleteEventsRepository.kt`; `ui/screens/events/` (EventFormSheet with a GPX picker and an aid-station editor,
  EventScreen at `event/{id}`, EventsCard on Training › Plan). + › TRAINING › EVENT. Status's NEXT UP shows an event
  when it comes first within 24 hours or the calendar has nothing (then up to 14 days ahead).
- Found on the way: `CalendarSyncRepository.sync()` and `PlannedRouteRepository.runPipeline()` had no caller, so
  `calendar_events` and `planned_routes` were empty. `data/CalendarBackgroundSync.kt` runs both when NEXT UP has a Google
  token, at most every 6 hours; calendar race suggestions and calendar-linked GPX courses depend on it.

## Phase 2 notes: the planned day

- `domain/fuel/PlannedDay.kt` (`resolvePlannedDays`): events win; planned sessions; calendar training events (colour 8)
  not already covered (pushed from a planned session, made into an event, or within an hour of another item); recorded
  sessions replace the planned item they are (same type family, starting within two hours) and are the only source for
  a past day. Walks and entries under 10 minutes are left out: the everyday baseline is in the activity factor.
- `domain/fuel/SessionDemand.kt`: the intensity class (EASY / MODERATE / HARD / LONG / RACE) from, in order, the event,
  the training domain, the calendar title, a recording's RPE or heart rate (share of heart-rate reserve), the person's
  usual heart-rate load for the type (median Banister TRIMP per minute; 60 % of reserve is 1.2, 75 % is 2.0). Over
  90 minutes anything but strength is LONG. Energy: the person's median kcal per minute for the type (calories_active
  over duration, 3 sessions or more), else the Compendium MET (Ainsworth 2011) less 1 MET at rest, scaled a little by
  class. Read-only check on live data: runs 11.4 kcal/min, strength 6.2 (last 180 days).
- `data/PlannedDayRepository.kt` reads the four sources, 180 days of history (paged) and today's heart-rate anchors.

## Phase 3 notes: the day engine

- `domain/fuel/DayFuelPlan.kt` (`planFuelDay`, `planFuelDays`). Carbohydrate bands (Thomas 2016): rest or a short easy
  session 3-5 g/kg; up to about 75 min 5-7; 75-240 min 6-10; over 240 min 8-12; easy days sit low in the band, hard
  ones high, races at 90 %. The day before a hard, long or race session that starts before noon steps up one band. The
  two days before an A or B race over 90 minutes load at 10-12 g/kg (low fibre the day before). The goal's calories
  stay the weekly average: day = goal + that day's session energy − the week's mean session energy. Protein stays at the
  goal (1.8 g/kg without one). Fat is the remainder with a 20 % floor; when it binds, carbohydrate gives way (the
  step-up first, then to the low end of the day's own band) and only then does the day's energy rise, with a note.
  Energy availability below 30 kcal/kg fat-free mass is named (IOC REDs 2023); a deficit goal on a key day is noted.
- `domain/fuel/MealCodes.kt` (`codeMeals`): LOW / MEDIUM / HIGH per meal with the reason (HIGH 1-4 h before or 2 h
  after a key session, the evening before a hard morning, all of a loading day; LOW on rest days and, by opt-in only,
  before easy sessions, never on a deficit day). A meal falling inside a session moves before it; a pre-session or
  recovery snack is added when no meal covers the window. In-session carbohydrate is taken off the day first; the rest
  is split by weight (0.5 / 1 / 2) as ±15 % ranges. Protein 0.3-0.4 g/kg at main meals. Logged meals are matched to
  the nearest slot within 2 h and read WITHIN / UNDER / OVER / NOT LOGGED.
- `domain/fuel/SessionFuel.kt` (Jeukendrup 2014): none under 45 min; up to 15-30 g/h or a rinse to 75; 30-60 g/h to
  2.5 h (45-60 for hard sessions and races); 60-90 g/h beyond, capped at the person's tolerated rate (60 by default).
- `domain/fuel/IntakeHistory.kt`: the days before against plan, only on fully logged days (3 meals or more). A loading
  shortfall before a race is carried by today's meals (up to 2 g/kg) and makes the race plan start fuelling early; a
  day not logged is assumed as planned; a morning weight more than 1 % under the usual raises pre-hydration.

## Phase 4 notes: weather, heat and sweat

- `WeatherRepository.fetchHourly`: Open-Meteo (no key), hourly temperature, humidity, dew point, apparent temperature,
  solar radiation, wind and rain. Within 16 days the forecast; further out the TYPICAL weather of those dates
  (`domain/fuel/HeatStress.kt` `climatology`: the same hours within 3 days either side over the last 10 years from the
  archive, median per hour plus the 90th-percentile temperature), always labelled TYPICAL. Cached 30 minutes.
- `HeatStress.kt`: the NWS heat index (Rothfusz), bands COLD / COOL / MILD / WARM / HOT / VERY HOT, strong sun named
  (600 W/m² or more), temperature along a course by the 6.5 °C/km lapse rate. No simplified WBGT (Kong & Huber 2022).
- `SweatProfile.kt`: a sweat test (weight before and after, fluid in, urine out; Sawka 2007) gives a rate; tests move a
  prior (0.5 / 0.8 / 1.0 L/h easy / moderate / hard at 20 °C and 70 kg, by weight, +3 % per °C above 20, −2 % below)
  by the median ratio measured / predicted, and narrow its range (±40 % with none, ±25 % with one or two, ±15 % with
  three). `fluidPlan`: enough that the loss stays within 2 % of body weight, never more than 80 % of the low sweat
  estimate (over-drinking causes hyponatraemia); sodium 500 mg/L, 700 in heat, 1,000 for a salty sweater (ISSN 2019).
- `supabase/migrations/20261025_sweat_tests.sql`, `data/SweatTestsRepository.kt`, the SWEAT TEST sheet (+ › BODY).
- Daily fluid: `data/HydrationDemand.kt` `todaySweatRate` (the profile at today's daytime temperature at the home
  location in Setup › Connected services, 18 °C without one) replaces the fixed 500 ml/h in the Status FUEL preview and
  the Fuel hydration card alike.

## Phase 5 notes: race and long-session plans

- `domain/fuel/CourseTimeEstimate.kt`: the planned duration spread along the course by the Minetti (2002) cost of each
  100 m relative to flat (downhill capped at 0.8, so never faster than 1.25 x flat pace; climbs at 5 x), giving the
  elapsed time at every point and aid station; a run's likely time from the person's recent runs (minutes per
  km-effort, median and interquartile range, Riegel 1.06 for longer efforts). A simple, labelled estimate: the TRAP
  model was not built.
- `domain/fuel/RaceFuelPlan.kt` (`planRaceFuel`): steps every 20 minutes from the 20th (the 15th after a short
  loading) plus aid stations. Carbohydrate at the top of the duration's range for races (the first hour at 75 %),
  under the tolerated rate, as the person's products (a drink mix covers fluid first; generic 25 g gels without a
  catalog); fluid per step from the sweat rate at that hour's temperature and altitude; sodium per litre; caffeine by
  opt-in (3 mg/kg, before the start up to 90 min, else a third at 40 % and the rest at 70 %). What to carry between
  refill points (bottles rounded to 250 ml); cutoffs checked at the planned pace and the slow end of the range; the
  expected body-mass loss with a warning above 3 %; glucose-plus-fructose warning above 60 g/h. Before: loading,
  evening meal, breakfast 2 g/kg (3 after a short loading) 3 h out, 5-7 ml/kg 4 h out, pre-cooling in heat, the last
  15 minutes. After: 1-1.2 g/kg/h for 4 h with 0.3 g/kg protein, 1.25-1.5 x the fluid lost, a weigh-in.
- Each plan lists its assumptions: the duration and its basis, FORECAST / TYPICAL / no weather, the sweat estimate's
  basis, the tolerance cap, generic products.

## Phase 6 notes: on screen

- `data/FuelPlanRepository.kt` puts it together: 17 planned days from three days back, the goal (or the maintenance
  estimate) and whether it is a deficit, weight and fat-free mass, the days before from the Log's daily totals, the
  sweat profile, the home or course weather, the person's products, `FuelSettingsStore` (meal times, training low,
  tolerated g/h, caffeine, salty sweater). `loadToday` codes today and tomorrow, places logged meals in their slots,
  plans long sessions (90 min or more) of the next three days; `loadEvent` builds the event's plan with its duration
  range (target ±5 %, expected ±10 %, or the run estimate) and, the day before or the day itself, the days-before check.
- `supabase/migrations/20261026_fuel_plans.sql`: a compact copy (`domain/fuel/FuelSnapshot.kt`) is written only when a
  plan changes; `describeChanges` says what changed (a session moved, added or dropped; the day's class; carbohydrate
  by 30 g or more; a meal's code; for events the forecast arriving, heat, fluid, carbohydrate, duration, sodium). The
  lines stay until the plan changes again. Append-only (no update policy).
- Fuel › Nutrition leads with TODAY'S FUEL: the day's class chip and g/kg, protein, fat and energy (goal as a weekly
  average, or the maintenance estimate), reasons and notes, the loading countdown, what changed, the meals and sessions
  in time order (code chip, carbohydrate and protein ranges, reason, logged grams and WITHIN / UNDER / OVER; during each
  session the g/h, and for long ones fluid, sodium, heat and the first steps), tomorrow in one line, FUEL SETTINGS.
  Codes are one hue in three steps with the word (the contract keeps red, amber and green for status).
- Today shows a FUEL line (class, g/kg, the key session, the next meal's code) that opens Fuel.
- The event page gets OVERVIEW / FUEL. FUEL: per-hour targets, duration range, weather source and heat, sweat, expected
  loss; what changed; WATCH (cutoffs, heat loss, glucose-fructose); the course profile with fuel stops and aid
  stations; BEFORE, DURING, CARRY, AFTER, ASSUMPTIONS; SHARE (plain text, `domain/fuel/FuelText.kt`). The last plan's
  text is kept on the phone and shown, with its time, when the plan cannot be built (no signal).

## Phase 7 notes: reminders

- `domain/notifications/FuelPrompts.kt`: `fuel_session` 3 h before a hard, long or race session of an hour or more (the
  pre-session meal) and 30 min after its planned end (refuel); `fuel_event` for an A or B race over 90 minutes, 09:00
  two days before (loading starts), 09:00 the day before (low fibre), 19:00 the evening before, 4 h before the start
  (drink) and 3 h before (breakfast). A prompt in the quiet window moves to its end when that is still 30 minutes before
  what it is about, and is dropped otherwise (an early race's morning prompts fall away: set an alarm). Each prompt has
  a dedupe key, so it is shown once.
- The worker (`NotificationDispatchWorker`) reads the next three planned days and shows due prompts within the rules'
  limits and the daily cap; taps open Fuel. `supabase/migrations/20261027_fuel_notification_kinds.sql` widens the kind
  constraint and seeds both rules; Setup › Notifications switches each off.

## Research

## 1. What Hexis is

- **Company.** A UK start-up from Liverpool John Moores University (Prof James Morton, formerly head of nutrition at
  Team Sky/INEOS; Dr Sam Impey). Launched in 2021. About €17–25 a month, or €130 a year.
- **Carb Coding.** Every meal and snack is coded LOW, MEDIUM or HIGH carbohydrate (shown red, amber, green). The code
  depends on how close the meal is to training and how hard that training is, over a 72-hour window.
  - Carbohydrate scales with training. Protein stays roughly constant (reviewers quote 1.8–2 g/kg). Fat fills the rest.
  - Rest days still get 3–4 g/kg of carbohydrate.
  - During sessions it gives g/h targets (60 g/h, rising to 90 g/h on long rides).
  - It has a competition carb-loading protocol.
- **Inputs.** Planned workouts (TrainingPeaks, intervals.icu), completed workouts and wellness data from wearables,
  body data and goal. The plan re-flows when a session moves or runs long.
- **Architecture (Google Cloud case study).**
  - Compute Engine managed instance groups sync device data into multi-region Cloud SQL Postgres.
  - Gemini parses photos and voice notes into structured food data. That output is checked against Hexis's own food
    database (about 5M items) before the Carb Coding engine runs: the model parses input and never sets the numbers.
- **Repeated complaints.**
  - Food logging: missing foods, failed barcode scans.
  - Generic energy targets: few activity categories.
  - A "deficit" reported on a day with nothing logged.
  - Targets too high for someone trying to lose weight.

## 2. The research it rests on, plus race, heat and hydration

| Source | What it says | What we take |
|---|---|---|
| Impey 2016, *Physiol Rep* | "Fuel for the work required": the train-low approaches combined into one rule, matching carbohydrate to the upcoming session | The organising rule |
| Impey 2018, *Sports Med* (glycogen threshold) | A window of muscle glycogen lets the work be done and still triggers adaptation. Signalling improved in 73–78% of studies, performance in only 37% of 11. The authors call for the glycogen cost of usual sessions to be quantified | Low codes only around easy sessions; never compromise a quality session |
| Impey 2020, *MSSE* | Glycogen use differs between session types | A rough per-session carbohydrate cost |
| Thomas, Erdman & Burke 2016 (ACSM/AND/DC) | Daily carbohydrate, g/kg: light 3–5, moderate 5–7, high 6–10, very high 8–12. Before exercise: 1–4 g/kg in the 1–4 h before. Recovery: 1–1.2 g/kg/h for 4 h | Day bands and timing |
| Burke 2011; carb-loading literature | 10–12 g/kg/day for 36–48 h before events over 90 min; low fibre and low residue the day before | The race-loading mode |
| Jeukendrup 2014, *Sports Med* | During exercise: under 45 min, none; 45–75 min, a mouth rinse; 1–2.5 h, 30–60 g/h; over 2.5 h, up to 90 g/h of glucose plus fructose. The gut can be trained | In-session g/h; a tolerance cap per person |
| Tiller 2019, ISSN position stand on ultra-marathon | 150–300 kcal/h under 50 miles, 200–400 kcal/h above; fluid 450–750 mL/h by conditions; sodium 500–700 mg per litre; caffeine in the late stages; gut training and low-FODMAP options | Ultra and trail rules; caffeine timing |
| Sawka 2007, ACSM fluid replacement | Start hydrated: 5–7 mL/kg about 4 h before. Keep the loss under 2% of body mass. Sweat rate is individual: weigh before and after | Fluid targets; the sweat test |
| McCubbin 2020, Sports Dietitians Australia position on heat | Heat raises fluid and sodium needs and can lower gut tolerance. Acclimatise; start euhydrated; pre-cool with cold drinks or ice slurry; let thirst and gut tolerance guide drinking | Heat adjustments |
| Kong & Huber 2022, *Earth's Future* | The simplified WBGT greatly overestimates heat stress in humid conditions | Don't use sWBGT: show temperature, dew point and humidity, plus a heat-index band |
| Bosquet 2007 meta-analysis of tapering | Taper 8–14 days, cut volume 41–60%, keep intensity | Planner support for an event (phase 9) |
| Areta 2013; Moore 2015 | Protein: 0.3–0.4 g/kg per meal, 4–5 meals | Protein spread across meals |
| Rothschild 2022 (preprint) | Carbohydrate Periodization Index: correlation × range ÷ monotony of daily carbohydrate against training | Score how periodised the logged intake is |
| Mountjoy 2023, IOC REDs consensus | Low energy availability is a spectrum; the 30 kcal/kg fat-free-mass cut-off is not firm for men; low carbohydrate adds risk | Safety limits |

## 3. Similar apps

| App | Approach | Worth copying |
|---|---|---|
| Fuelin | A daily plan from TrainingPeaks; traffic-light codes; before, during and after each session; sweat-rate tracker; carb-capacity test | Session windows; a gut-tolerance test |
| Saturday | A session fuel and hydration plan from a sweat level. Users find its fluid targets generous (17 L for 14 h) | A tunable sweat profile; a hard cap on fluid |
| Precision Fuel & Hydration planner | A race plan per hour or checkpoint from sweat testing (±20% error per test) | Plans per aid station and what to carry |
| MAVR | Cheap, AI food logging | Quick logging |
| MacroFactor | Expenditure learned each week from logged intake and the weight trend (equations can be off by more than 500 kcal) | Use the app's existing `evaluateTdee()` once it is confident |

## 4. Design lessons

1. Rules make the numbers; a model, if ever used, only parses input.
2. Codes and ranges, not exact grams.
3. A day with nothing logged reads NOT LOGGED, never as a deficit.
4. Re-plan when sessions, events or the weather change, and say what changed.
5. Low codes only around easy sessions.
6. Every target carries its reason and its source.
7. Fluid is capped by the estimated sweat rate (risk of hyponatraemia).
8. Uncertain inputs (time estimate, sweat rate, a climate-average forecast) widen the range and are labelled.
9. The codes use one hue in three steps plus the words; the contract keeps red, amber and green for status.
10. Close the loop: every race and long run feeds sweat rate and gut tolerance back in.

## Sources

- **Hexis:**
  - [LJMU launch](https://www.ljmu.ac.uk/about-us/news/articles/2021/10/5/researchers-launch-worlds-most-advanced-nutritional-app)
  - [Google Cloud case study](https://cloud.google.com/customers/hexis)
  - [App Store listing](https://apps.apple.com/gb/app/hexis/id1610334327)
  - [220 Triathlon](https://www.220triathlon.com/news/i-used-a-pro-cycling-nutrition-app-for-a-month-and-raised-my-ftp-by-17-watts)
  - [Roadman review](https://roadmancycling.com/blog/hexis-review) (affiliate)
  - [Trustpilot](https://uk.trustpilot.com/review/hexis.live)
  - [TrainerRoad thread](https://www.trainerroad.com/forum/t/anyone-with-experience-using-nutrition-app-hexis/85624)
  - [Ride Revolution](https://www.riderevolution.co.uk/hexis-cycling-nutrition-coach/)
- **Papers:**
  - [Impey 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC5889771)
  - [Impey 2016](https://pmc.ncbi.nlm.nih.gov/articles/PMC4886170)
  - [Impey MSSE](https://researchonline.ljmu.ac.uk/12507/1/ACCEPTED%20R2%20Impey%20et%20al%20MSSE%202019%20%281%29.pdf)
  - [Rothschild CPI](https://www.medrxiv.org/content/10.1101/2022.06.21.22276725v1)
  - [ISSN ultra-marathon 2019](https://jissn.biomedcentral.com/articles/10.1186/s12970-019-0312-9)
  - [ACSM fluid replacement 2007](https://pubmed.ncbi.nlm.nih.gov/17277604/)
  - [SDA heat 2020](https://research.bond.edu.au/en/publications/sports-dietitians-australia-position-statement-nutrition-for-exer/)
  - [Kong & Huber WBGT](https://doaj.org/article/a5fe52ebce2d4f50b76c9ea31ae3b91a)
  - [IOC REDs 2023](https://humanperformancealliance.org/news/2023-international-olympic-committee-consensus-statement-on-reds/)
  - [Burke 2001](https://pubmed.ncbi.nlm.nih.gov/11310548/)
  - Thomas 2016, Jeukendrup 2014 and Bosquet 2007 are cited from the published papers; search did not reach their
    full texts.
- **Other apps:**
  - [Fuelin × TrainingPeaks](https://help.trainingpeaks.com/hc/en-us/articles/9201819207181-How-to-connect-Fuelin-to-TrainingPeaks)
  - [Hexis vs PF&H](https://roadmancycling.com/compare/hexis-vs-precision-fuel-hydration)
  - [Saturday thread](https://www.trainerroad.com/forum/t/saturday-pro-fuel-and-hydration-app-user-inquiries/76366)
  - [MacroFactor](https://macrofactor.com/algorithm-accuracy/)
