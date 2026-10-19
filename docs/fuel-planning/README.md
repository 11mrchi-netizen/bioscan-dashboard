# Fuel planning: daily carbohydrate periodisation, race and long-session fuel and hydration plans

> **App restructure (Oct 2026):** the screens named below moved. Fuel › PLAN and PREP are **Lab › Planning › Food plan**
> (NEXT 10 DAYS and MEAL PREP); **Nutrition › PLAN** shows today and the next two days with a link there; the fuel
> reminder opens Lab › Planning › Food plan; Today's fuel line heads the NUTRITION TODAY card and opens Nutrition › PLAN;
> race fuel plans are reached from Lab › Planning › Race fuel (each event's FUEL tab). See docs/app-structure/README.md.

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
| 8 | After the event: review, sweat rate and gut tolerance learned, periodisation index | Done (index computed on the phone; see notes) |
| 9 | Planner: build toward an event, taper | Done |
| 10 | Its own page (Fuel › PLAN), the next ten days, per-meal protein and fat, example plates by fixed rules | Done |
| 11 | Calendar training read from its text; WHAT IS THIS? for unreadable long entries; Sunday meal prep (Fuel › PREP) and the fixed breakfast | Done |

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
  class (checked read-only against the owner's history; values not kept here).
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

## Phase 8 notes: after the event

- REVIEW FUELLING on a past event (`ui/screens/fuel/FuelReviewSheet.kt`, `data/FuelReviewRepository.kt`): the kept
  plan's steps marked TAKEN / HALF / SKIPPED (or totals when no plan was kept), gut 0-10, energy 0-10, weigh-ins,
  notes. The steps go into `fueling_intake_log` against the recorded session (source `fuel_plan`, keyed so a second
  save does not duplicate; consumed and partial rows reach the day's nutrition totals through the existing trigger),
  the review into `fuel_plan_reviews` (`supabase/migrations/20261028_fuel_plan_reviews.sql`, one per event; the app's
  first gut-symptom records), and with both weights the race into `sweat_tests`.
- `domain/fuel/Periodization.kt` `suggestTolerance`: a session within 5 g/h of the ceiling with gut 2 or less suggests
  10 g/h more (90 at most); gut 6 or more suggests 10 less (30 at least). Shown after saving, applied only on SET.
- Carbohydrate Periodization Index (Rothschild 2022) and code adherence: Fuel › Nutrition's PERIODISATION card, over the
  fully logged days of the last 28 (index; daily carbohydrate per kg against the day's training energy) and 14 (HIGH /
  MEDIUM / LOW meals within range). Computed on the phone, not nightly on the server as first planned: the server's
  analysis frame carries no daily carbohydrate, and the phone already holds both inputs, so this needs no deploy. The
  console does not show it.
- Maintenance: once `evaluateTdee` (logged intake against the weight trend) is confident, its expenditure replaces
  Mifflin-St Jeor times the activity factor, and the card says MAINTENANCE, FROM YOUR INTAKE AND WEIGHT.
- Not built: adjusting the course time estimate from finished races (the review keeps the actual duration, which a
  later change can use).

## Phase 9 notes: the planner and events

- `domain/events/EventTaper.kt`: a block built toward an event ends in its week; the last weeks taper: two for an A
  race or anything over 90 minutes, one for a B race, none for a C race (Bosquet 2007: 8-14 days, volume down 41-60 %,
  intensity kept). The planner's lighter weeks are its deload weeks, which also lower intensity, so the step says to
  keep a few short efforts at race pace. Only open A and B events at least 3 weeks after the block's start are offered.
- Training › Plan › PLAN A NEW BLOCK › LENGTH AND DELOADS: BUILD TOWARD AN EVENT sets the length and the taper weeks
  (and names the block when unnamed). The UPCOMING list marks a planned session on an event's day (EVENT DAY: move or
  drop this session).
- Metric registry: `fuel_day_plan`, `sweat_rate`, `carb_periodization_index` (also in doc 02), shown behind the "?" of
  TODAY'S FUEL and PERIODISATION.

## Phase 10 notes: the plan page, ten days, and what it looks like on the plate

Owner requests: the plan on its own page under Fuel, at least the next ten days, and what each day's and meal's
carbohydrate, protein and fat look like as food, with no language model in that layer. Owner decisions: suggestions
from the person's usual foods first, then staples; two example plates per meal and a one-line summary per day.

- **Page.** `FuelTab.Plan` is Fuel's first tab and where it opens (Today's FUEL line and the fuel reminders open Fuel, so
  they land there). `ui/screens/fuel/FuelPlanPage.kt`: TODAY'S FUEL (as before, plus per-meal protein, fat and kcal and
  the plates), NEXT 10 DAYS (a strip of carbohydrate g/kg per day against the day's band, one hue, labelled axis, tap or
  drag to read a day; one row per day with class, main session, C / P / F / kcal and the day in everyday terms, opening
  to where the plan comes from, reasons, meals and plates), and PERIODISATION. One load feeds the page; open days are
  kept by the tile, so they survive a tab switch. Nutrition keeps what was logged.
- **Ten days.** `loadToday` returns `horizon`: today and the next ten days, each a `HorizonDay` (planned day, plan,
  coded meals, plates, summary). The read reaches 20 days (three back, the horizon, and what the last day's loading
  check needs). The goal now holds as the average of each block of seven days from today (`planInWeekBlocks`); one
  shared window gave days 7-10 the first week's mean session energy. Today's side effects (the `fuel_plans` snapshot,
  the intake catch-up, long-session weather) stay on today only. The result is cached for a minute (key: the date, the
  local settings and goal) and dropped by writes to the tables it reads, so the page and Today's line share one load.
- **Per-meal targets.** `codeMeals` splits the day's protein (weight 1 for main meals and recovery, 0.5 for snacks; at
  least 0.3 g/kg at each main meal when the day allows, Areta 2013, Moore 2015) and fat (weight 1 for main meals, 0.5
  for snacks and for HIGH meals in the 4 h before a key session; at most 10 g pre-session and 15 g at recovery, low fat
  before training per ACSM 2016, what a cap leaves going to the other meals). The meals now add up to the day.
- **Plates, deterministic** (`domain/fuel/MealSuggestions.kt`). A template per meal (lunch and dinner: a weighed starch,
  a protein, an optional fat and vegetables; breakfast: carbohydrate, protein, optional fat; snack: carbohydrate and an
  optional protein; pre-session: low-fat, low-fibre carbohydrate and an optional lean protein; recovery: carbohydrate
  and protein). Up to six candidates per role, the person's foods first. Grams by bounded least squares on carbohydrate,
  protein and fat (fat weighted 0.6) with a pull towards the usual portion, then rounded to real servings (halves of a
  banana, whole eggs and slices, 5-10 g steps). The score: distance outside each target's band, a penalty for fat or
  fibre before a key session, a soft cap of 15 g fibre a meal, a small cost for a second starch and for repeating the
  meal before, a small credit for the person's foods; ties by food id, so the same inputs give the same plates. Savoury
  and sweet foods are not put on one plate. Two plates with different carbohydrate sources; a plate more than 25 % off a
  target is not offered, and the meal says so. The day line puts three quarters of the day's carbohydrate in the
  person's most eaten starch and a quarter in bananas.
- **Foods.** `domain/fuel/StapleFoods.kt`: 50 staples, values per 100 g rounded from USDA FoodData Central SR Legacy
  (whey: a typical label), each with role, taste, meals it suits, usual portion and bounds, and the names it goes by in
  English and Chinese. `data/FoodHabitsRepository.kt` reads the last 120 days of meals: each meal's description and
  item descriptions are matched against those names (longest first, whole words; one count per meal), so the staples
  the person eats rank first for that meal slot; catalogue foods (`meal_items.food_id`) logged three times or more become
  the person's own foods with their median portion and `food_nutrients` values. An item an AI estimate produced counts
  only through its catalogue food or its description, never through the estimate's numbers.
- **Found on the way.** Few logged items resolve to a catalogue food (most meals are dishes described in words), so the
  description match is what carries "your usual foods" today; the catalogue path takes over as foods are picked from
  search or a barcode. The plan reads the next days from planned sessions, calendar training and events: with none of
  them, every day reads as a rest day.
- **Verification.** Tests: meal splits add up and caps hold; plates hit targets, are deterministic, stay in bounds and on
  servings, are low in fat and fibre before key sessions, put the person's foods first, report an unreachable target;
  name matching; each seven-day block averages to the goal; ten days of plates in about 0.25 s on a desktop JVM (run off
  the main thread on the phone).

## Phase 11 notes: the calendar's text, and Sunday meal prep

Owner requests: tailor each day from the training in the calendar; a meal-prep feature for cooking on Sunday (up to five
proteins, vegetables and carbohydrates; four meals, lunch A-B-A-B-A and dinner C-D-C-D-C; some meals skipped; a fixed
breakfast whose amounts follow the day; snacks at discretion; how much to buy and prepare). Owner decisions: Monday to
Friday with skippable meals; stored in Supabase; unreadable long calendar entries are asked about on the plan.

- **The calendar's text** (`domain/fuel/CalendarWorkout.kt`). Training entries are often one-hour placeholders whose
  session is in the title and description. Fixed rules read the distance ("25.7km", the title's before a route's), the
  climb ("+868m"), interval rounds and repetition length ("800m Repeats — 5-8 rounds" → 6.5 × 800 m; "6x400"), strength
  sets, reps and load ("4x4 @47.5kg", "3-5x3 @85%", "(85%)"), deload, easy (LSS, Z2), long and back-to-back, and the GPX
  link. Runs are timed by `estimateRunTime` on the person's runs of the last 120 days with a distance (easy runs at the
  slower half of their paces), intervals as warm-up + rounds × (repetition at the faster quarter of their paces less 15 % +
  4 min) + cool-down, strength as 10 min + 3 min a set (HARD at 85 % or heavy sets of five or fewer), a deload as 30 easy
  minutes. The reason names what was read. The calendar read now includes the description; recorded sessions carry
  distance and climb.
- **WHAT IS THIS?** A training-colour entry over four hours with nothing the rules can read is left out of the day and
  listed on Fuel › Plan: MAKE IT AN EVENT (the event form, prefilled), A SESSION (hours and class) or IGNORE; answers in
  `calendar_event_overrides`, applied by the resolver.
- **Meal prep** (`domain/fuel/MealPrep.kt`). Recipes: one protein, carbohydrate and vegetable each from the picks;
  proteins rotate so a day's lunch and dinner differ, carbohydrates on another pattern so two of each still make four
  pairings; a fifth pick joins a recipe. Containers: each weekday lunch and dinner not marked OUT, portioned for that
  day's coded meal (`solvePortions`: 150 g vegetables fixed; starch, protein and oil solved and rounded), so the dinner
  before a long run carries more starch. Breakfast: the person's usual foods and amounts, each day between 0.5 and 1.75
  times them. Once cooked (MARK AS COOKED) the containers' grams are stored and fixed; breakfast and then the snacks take
  up what they miss (`prepDay`). The shopping list adds the containers and a week of breakfasts, raw weight by USDA
  cooking yields (rice about 0.37-0.40 dry per cooked, pasta 0.45, meat 1.35 raw per cooked, fish 1.25, greens 1.2),
  rounded up to 50 g, whole items or tablespoons; the cook sheet gives each recipe's raw and cooked amounts and its split
  across containers; SHARE sends all of it as text.
- **Screens.** Fuel › PREP: THIS WEEK / NEXT WEEK, 1 PICK, 2 RECIPES (tap to swap), 3 WEEK (tap a meal for OUT), 4 SHOP &
  COOK, BREAKFAST. Fuel › Plan: on a prepped day, CONTAINER A-D (or OUT with what to aim for), YOUR BREAKFAST at the day's
  amounts, LEFT FOR SNACKS TODAY with the plates as ideas, and the containers on each day row.
- **Storage.** `supabase/migrations/20261029_meal_prep.sql`: `meal_prep_weeks` (one per person and Monday; picks,
  recipes, meals out, containers once cooked, status), `breakfast_templates`, `calendar_event_overrides`; owner-only RLS
  and shape checks. Only choices and cooked grams are stored; the numbers are worked out each time.
- **Verification.** Parser tests on synthetic titles shaped like real ones (long runs, routes with climb, LSS, repeats,
  strength waves, deload, plain text); the resolver uses the parsed session, asks about an unreadable 17-hour entry and
  applies IGNORE / A SESSION. Prep tests: rotation and four distinct pairings; A-B-A-B-A / C-D-C-D-C and skipped meals;
  every container within a quarter of its targets and the pre-long-run dinner larger than the deload one; the shopping
  list equals containers plus breakfasts in raw weight; breakfast within its bounds; after cooking, a changed day still
  adds up through breakfast and snacks. The migration was applied twice on a local Postgres 16 (policies, checks,
  uniqueness, other users) and then live.

## Read-only check on live data

Run once through the real engine on the owner's data (values reported in the PR conversation, not kept in the repository,
which is public): the periodisation index, the run-time estimate against finished trail runs, and a long-session plan
without a sweat test. It led to one change: without a sweat test the plan caps drinking at the low end of a wide sweat
estimate, and the warning now says that this is caution for lack of a test (drink to thirst on top, do a sweat test),
not heat. The run-time estimate can fall well short on long, steep courses, so the target or expected time stays the
main input.

## Deploy order

1. `20261024_athlete_events.sql`, `20261025_sweat_tests.sql`, `20261026_fuel_plans.sql`, `20261028_fuel_plan_reviews.sql`
   (new tables, RLS on; nothing else depends on them).
2. `20261027_fuel_notification_kinds.sql` before the app build: the app seeds missing notification rules on start, and
   the seeding fails while the kind constraint does not yet allow the two new kinds.
3. The app build. No edge function changes. (Phase 10 is app-only: no migration.)
4. Phase 11: `20261029_meal_prep.sql` (new tables only); the app reads them quietly as empty until it is applied.

Steps 1 and 2 were applied to the live project on 18 Oct 2026, in that order. The four tables were sent without their
`drop … if exists` lines (they do nothing on a first apply); the files here keep them so they stay safe to re-run.
Checked afterwards: RLS on all four with 4, 4, 3 and 4 policies; the kind constraint lists nine kinds; the two new
rules are seeded for existing users; the security advisors list nothing for the new tables. Step 4 was applied live on
19 Oct 2026 the same way (without the `drop … if exists` lines): RLS on all three tables with 4 policies each, no new
advisor findings.

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
