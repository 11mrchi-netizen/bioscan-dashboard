# App structure (October 2026)

The owner's "Field terminal new structure" document, as built, and the six content changes that came with it. The
code is authoritative: `ui/nav/TopLevelTab.kt` holds every tab set, `ui/nav/FieldTerminalNavHost.kt` the routes and
links.

## Map

| Where | What it holds |
|---|---|
| Status (opening page) | The hero (TODAY ›), NEXT UP, quick log, four tiles: TRAINING · NUTRITION · HEALTH · LAB |
| Today | THIS MORNING · IS TODAY TYPICAL? · TODAY'S RECOVERY · READY FOR · links to Training › Load and Health › Cardio · NUTRITION TODAY. Header: ADD ABOUT LAST EVENING / LAST EVENING ✓ |
| Training | LOAD (training load, intensity) · RUNNING · STRENGTH · SESSIONS (ten at a time, LOAD MORE) · INJURY |
| Nutrition | NUTRITION (calories, macros, averages, goal adherence, 7-day trend, analysis, protein on target) · HYDRATION · PLAN (today and the next two days) |
| Health | CARDIO · SLEEP · BODY · WELLBEING · SEXUAL HEALTH · BLOODWORK |
| Lab | N=1 · SUPPLEMENTS · PLANNING (training block, food plan with meal prep, race fuel) · TESTS |
| User | Healthspan, Aging, the level buttons (Running and Strength open Training's tabs), the block card (opens Lab › Planning) |
| Log, Setup | Unchanged |

Tile route strings did not change (`tile_fuel` is Nutrition, `tile_heart` Health, `tile_labs` Lab), so a back stack
saved before the update still resolves (`NavigationMapTest`). Links that moved: the fuel reminder opens Lab › Planning ›
Food plan; the body check-in reminder opens Lab › Tests with the check-in up; the supplement reminder Lab › Supplements;
the injury flag Training › Injury.

Left for their own plans: a trail-running Running page, Form as trends rather than per-session numbers, injury risk from
past data, and the running form analyser (shown as coming on Lab › Tests).

## The six content changes

1. **THIS MORNING.** Each metric (HRV, resting HR, sleep) is its title, a tappable pill and its band chart. The pill's
   pop-up holds what used to sit beside it: the answer, the expected range, how rare, any run or flag, how many recent
   mornings fell inside their range, and how well the band has held.
2. **Last evening** is a header button on Today, not a card.
3. **One pill per card.** The data-quality chip is a line in the card's "?" (design contract A8).
4. **Today's recovery** weighs its contributors (DynamicRecovery v2): HRV, resting heart rate, the Sleep Index, training
   load and Zepp stress are one vote each (+1 / 0 / −1), the subjective answers one vote together (their mean).
   ABOVE / BELOW EXPECTED at a net lean of ±0.25; MIXED SIGNALS only when each side has at least a third of the votes and
   neither wins. v1 needed every contributor to agree, so in a training block, with load pointing down, a good morning
   read MIXED. Example (synthetic): sleep as expected 0, HRV up +1, resting HR down (good) +1, load −1, answers +1 →
   five votes, net +0.4 → ABOVE EXPECTED (v1: MIXED). Movements are tinted, faint green up and faint red down, always
   with the word (contract A9). READY FOR inherits both.
5. **NUTRITION TODAY** on Today: a bar per nutrient (calories, protein, carbs, fat, water) against today's target, a
   tick at what the hour expects (nothing before 07:00, all by 21:00), ON TRACK / SHORT / OVER, the shortfalls listed,
   caffeine and the meals logged. Targets come from today's fuel plan, else the nutrition goal (`domain/DayProgress.kt`).
6. **Lab › N=1**, graphs first: forest rows for what goes with what, log-scale odds rows for what came before off
   mornings, progress rows for experiments with today's draw on top, difference-from-best-input rows for the scorecards;
   IS TODAY TYPICAL? on Today is its pill and a strip of how far each standing-out signal sits from usual. Sentences are
   behind the "?" or a tap.
