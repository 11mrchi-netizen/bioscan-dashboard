# App structure (October 2026)

The owner's "Field terminal new structure" document, as built, and the six content changes that came with it. The
code is authoritative: `ui/nav/TopLevelTab.kt` holds every tab set, `ui/nav/FieldTerminalNavHost.kt` the routes and
links.

## Map

| Where | What it holds |
|---|---|
| Status (opening page) | The hero (TODAY ›), NEXT UP, quick log, four tiles: TRAINING · NUTRITION · HEALTH · LAB |
| Today | THIS MORNING · IS TODAY TYPICAL? · TODAY'S RECOVERY · READY FOR · links to Training › Load and Health › Cardio · NUTRITION TODAY. Header: ADD ABOUT LAST EVENING / LAST EVENING ✓ |
| Training | LOAD (training load, fitness, intensity) · RUNNING (see below) · STRENGTH · SESSIONS (ten at a time, LOAD MORE) · INJURY |
| Nutrition | NUTRITION (calories, macros, averages, goal adherence, 7-day trend, analysis, protein on target) · HYDRATION · PLAN (today and the next two days) |
| Health | CARDIO · SLEEP · BODY · WELLBEING · SEXUAL HEALTH · BLOODWORK |
| Lab | N=1 · SUPPLEMENTS · PLANNING (training block, food plan with meal prep, race fuel) · TESTS |
| User | Healthspan, Aging, the level buttons (Running and Strength open Training's tabs), the block card (opens Lab › Planning) |
| Log, Setup | Unchanged |

Tile route strings did not change (`tile_fuel` is Nutrition, `tile_heart` Health, `tile_labs` Lab), so a back stack
saved before the update still resolves (`NavigationMapTest`). Links that moved: the fuel reminder opens Lab › Planning ›
Food plan; the body check-in reminder opens Lab › Tests with the check-in up; the supplement reminder Lab › Supplements;
the injury flag Training › Injury.

Left for its own plan: injury risk from past data. The running form analyser opens from Lab › Tests
(`docs/running-form/README.md`).

## Training › RUNNING (owner's structure, Oct 2026)

- **LEVEL**, then the 1M · 3M · 6M · 1Y switch for everything below.
- **VOLUME**: TOTAL VOLUME and AVG PER WEEK tiles, weekly bars stacked TRAIL · EASY · QUALITY by kilometres, a
  legend with each group's share. The groups (`domain/RunCategory.kt`):
  - TRAIL: tagged trail, or Zepp's trail sport.
  - A run type decides the rest: tempo, threshold, intervals, hills and races are QUALITY; easy, recovery and long
    are EASY.
  - An untyped road run climbing 25 m/km or more is a hill session (QUALITY).
  - Otherwise an untyped road run is QUALITY when 20 % or more of its heart-rate time was in zone 4 or above (its
    average heart rate without a histogram), in the person's zones as of that date.
  - Checked read-only against the 14 runs tagged when this was built: all 11 tagged road runs land in their own
    group. Heart rate alone missed both hill sessions, which is why climb comes first.
- **ROAD | TRAIL**, one switch for every card below it:
  - EFFICIENCY FACTOR, HR DECOUPLING, PACE (with grade-adjusted pace as a median line), CADENCE over STRIDE
    LENGTH (two panels on one date span), GROUND CONTACT and VERTICAL RATIO.
  - Each is per run with its median line (`RunBaseline` in `domain/SkillSeries.kt`):
    - road: the 28-day median, from 3 runs (the efficiency factor keeps its 6);
    - trail: the median of the last 5 trail runs within 60 days, from 3, because trail runs come about once a week.
  - Ground contact and vertical ratio are hidden on TRAIL: the watch reports neither on trail runs.
- **RECORDS**, by the same switch. Road: longest, fastest of 5 km or more, biggest week. Trail: longest, biggest
  climb, fastest grade-adjusted pace over 10 km or more, biggest week.
- FITNESS (VO2max, lactate threshold) is on Training › LOAD. Trail runs need no confirming: the Zepp sync tags them
  (`docs/zepp-integration/04-data-model-and-provenance.md`).

## Run session page: RUN DYNAMICS

- Sections HEART RATE, PACE, POWER, CADENCE, VERTICAL RATIO. Each opens with its tiles followed by its chart over the run.
  A tap folds a section; the fold is kept for every run, on the phone only (`RunPageSettingsStore`).
  - Power over time comes from Health Connect on the phone; Zepp stores none. An open section without a series says so.
- Average pace is distance over moving time (`sessionAvgSpeedKmh`): the shorter of the stored duration and the Zepp
  workout's elapsed time less its pauses. The stored `avg_speed_kmh` is only the fallback without a distance, because
  neither writer makes it a pace:
  - the Health Connect sync stores a plain mean of the speed samples;
  - Zepp's `avg_pace` is seconds per metre. Until 21 Oct 2026 the sync read it as seconds per km, and the 106 rows that
    stored a 1000-times speed were divided back. Even read right it can differ from distance over time: on interval
    and hill sessions in October 2026 it was up to a third slower.
- Marks against usual (`domain/RunMarks.kt`) under the tiles: arrow, word and the usual value.
  - Usual is the median of earlier runs (`RunBaseline`): road the last 28 days, trail the last 5 trail runs within 60
    days, from 3 runs; the efficiency factor on road keeps its 6.
  - AS USUAL inside the normal run-to-run spread: the robust SD of those runs, with a floor (efficiency factor, pace and power 3 %,
    decoupling 2 points, cadence 3 spm, vertical ratio 0.3 points).
  - IMPROVED / REGRESSED only where a direction is better: efficiency factor (higher), HR decoupling and vertical
    ratio (lower).
  - Pace and power compare with runs of the same kind (easy, quality or trail) and only say FASTER / SLOWER or
    HIGHER / LOWER. Cadence says HIGHER / LOWER against the same surface.
  - Stride length is read for the pace: a straight line through the same surface's runs over 90 days (stride against
    speed, from 6 runs). LONG FOR THIS PACE at 8 % or more over the line is the watch's hint of overstriding; the form
    analyser is what shows where the foot lands. SHORT FOR THIS PACE is a quicker step, not a risk by itself. Outside
    the paces of those runs there is no verdict.
  - Replayed read-only on the road runs since July 2026: 38 of 46 read usual for the pace, 4 long, 2 short, 2 outside
    the range; the line's run-to-run spread is about 5 %, so 8 % is roughly 1.7 times it.

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
