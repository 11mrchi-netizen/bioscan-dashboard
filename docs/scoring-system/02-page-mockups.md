# Page mockups (ASCII), v2

Every page of the rebuilt app, phone width (about 46 characters). The numbers are made up but consistent across pages:
one morning, Tue 3 Nov, CHARGE 112.

v2 changes from v1:
- Scores are **rings**, not bars.
- The first screens show **only the number and its direction**.
- Everything else opens on a **full-screen overlay** when tapped. There are **no collapsed sections**.
- **TODAY'S PLAN** includes the food plan.
- The **quick-log row and SAY IT are gone**. + LOG opens the logging menu directly.

Contents: [0 Conventions](#0-conventions) · [1 TODAY](#1-today) · [2 CHARGE overlay](#2-charge-overlay) ·
[3 Domain overlays](#3-domain-overlays) · [4 Metric overlay](#4-metric-overlay) · [5 PLAN](#5-plan) ·
[6 + LOG](#6--log) · [7 LAB](#7-lab) · [8 ME](#8-me) · [9 Where every current card goes](#9-where-every-current-card-goes)

---

## 0. Conventions

### Three levels, never more

```
 LEVEL 1  page        TODAY · PLAN · LOG · LAB · ME
          shows only headlines: rings, values, direction
            │ tap
 LEVEL 2  overlay     full screen, slides up, ✕ or swipe down to close
          the whole picture of one thing: its drivers and its parts,
          everything expanded
            │ tap a part
 LEVEL 3  overlay     one metric: chart, your normal, what it did to
          its score, what affects it, what it is ("?" text inline)
```

- **No collapsed sections (▸) anywhere.** If something has more to show, it is a tile that opens an overlay.
- **Overlays stack:** level 3 opens over level 2, and ‹ goes back one level.

### The ring

One lap is 0–100. Above 100 a second lap is drawn on top of the first, starting again at 12 o'clock, on the same scale,
so 150 is one and a half laps.

```
          112                         71                    138
     ╭━━━━▓▓▓━━━╮               ╭━━━━━━━━━━╮           ╭━━━━▓▓▓▓▓▓▓╮
     ┃          ┃               ┃          ·           ┃          ▓
     ┃   112    ┃               ┃    71    ·           ┃   138    ▓
     ┃    ▲     ┃               ┃    ▼     ·           ┃    ▲     ▓
     ╰━━━━━━━━━━╯               ╰━━━━━━━━··╯           ╰━━━━━━━━━━╯
  full lap + 12 % of a       71 % of the first lap    full lap + 38 % of
  second lap (▓, brighter)   (· = empty track)        a second lap
```

- **Track:** faint. Small ticks at 40 and 60 mark where LOW and BELOW NORMAL end.
- **Colour by band:**

  | Band | Range | Ring |
  |---|---|---|
  | LOW | < 40 | Red |
  | BELOW NORMAL | 40–60 | Amber |
  | NORMAL | 60–100 | Primary |
  | PRIMED | 100–125 | Second lap, bright |
  | SUPERCHARGED | 125–150 | Second lap, glowing; the treatment is for the design contract |

- **FUEL and LOAD stop at one full lap**, because they have no upside.
- **Direction under the number:** ▲ up, ▼ down or ● about the same, compared with yesterday's score.
- **States on the ring:**
  - PROVISIONAL: a dashed ring.
  - PARTIAL: an empty track with "—".
  - BUILDING: an empty track with "12/28".
  - A cap: a small ⚠ on the ring.

### The headline tile

Used on level 1 and level 2 for anything that isn't a score:

```
│ HRV                      58 ms  ▲          ›
│ └name                    └value └direction vs YOUR normal
```

- The direction is ▲ above normal, ● normal or ▼ below normal. Target-type inputs use ✓ on target, ↓ short or ↑ over.
- No words and no points on the tile. The band words (ABOVE NORMAL…), the points and the explanation appear on the
  level 3 overlay.

### Navigation

```
  TODAY     PLAN     + LOG     LAB     ME
```

---

## 1. TODAY

### Morning (FINAL)

```
│ TUE 3 NOV                        ☁ 24°  ⟳ 07:12
│
│              ╭━━━━━━━▓▓▓▓━━━━━╮
│            ╭━╯                 ╰━╮
│           ┃                       ┃
│           ┃          112          ┃
│           ┃           ▲           ┃
│           ┃                       ┃
│            ╰━╮                 ╭━╯
│              ╰━━━━━━━━━━━━━━━━━╯
│                    CHARGE
│
│  ╭━━▓━╮  ╭━━▓━╮  ╭━━━━╮  ╭━━━━╮  ╭━━▓━╮
│  ┃124 ┃  ┃108 ┃  ┃ 71 ·  ┃ 94 ·  ┃118 ┃
│  ┃ ▲  ┃  ┃ ▲  ┃  ┃ ▼  ·  ┃ ●  ┃  ┃ ▲  ┃
│  ╰━━━━╯  ╰━━━━╯  ╰━━··╯  ╰━━━━╯  ╰━━━━╯
│  SLEEP  RECOVERY  LOAD    FUEL   BALANCE
├──────────────────────────────────────────────
│ TODAY'S PLAN
│ 07:30 ✓ Breakfast · oats, eggs     620 kcal
│ 08:00 ✓ Morning supplements
│ 12:30 ✓ Lunch · rice bowl          780 kcal
│ 16:30 ○ Pre-run snack · banana     150 kcal
│ 18:30 ○ Threshold run · 10 km      READY ✓
│ 20:00 ○ Dinner · salmon, potatoes  850 kcal
│ 21:00 ○ Evening supplements
│ 21:30 ○ Close the day
├──────────────────────────────────────────────
│ SO FAR   1 420 / 2 650 kcal   ✓            ›
└──────────────────────────────────────────────
   TODAY     PLAN     + LOG     LAB     ME
```

**What each part does when tapped:**

| Tap | Opens |
|---|---|
| CHARGE ring | CHARGE overlay (§2) |
| A domain ring | That domain's overlay (§3) |
| A meal row | The meal overlay: planned foods and macros, LOG AS PLANNED or LOG SOMETHING ELSE, swap |
| A session row | The planned session overlay: details, READY FOR against the demand, open in calendar |
| A supplements row | The bundle overlay: tick what was taken |
| Close the day | The CLOSE THE DAY check-in (§6) |
| SO FAR | The SO FAR overlay: kcal, protein, carbs, fat, water and caffeine against today's targets, each a bar with a tick at what the hour expects |

**How the plan rows behave:**
- They come from PLAN › TRAINING, PLAN › FOOD, LAB › SUPPLEMENTS and the calendar, on one timeline.
- ✓ means done or logged. ○ means still to do.
- A row that is late (the time has passed, still ○) dims its time.
- Calendar events that aren't sessions are not listed. TODAY is about body inputs.

### Morning, before yesterday is closed (PROVISIONAL)

```
│              ╭┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅╮      dashed ring
│              ┇       104       ┇
│              ┇        ▲        ┇
│              ╰┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅╯
│                    CHARGE
│  …  ╭┄┄┄┄╮  ╭┄┄┄┄╮
│     ┆ —  ┆  ┆ —  ┆
│     ╰┄┄┄┄╯  ╰┄┄┄┄╯
│      FUEL   BALANCE
├──────────────────────────────────────────────
│ TODAY'S PLAN
│ now   ○ Close Monday                  1 min ›
│ 07:30 ✓ Breakfast …
```

- The closing task moves to the top of the plan, timed "now".
- Why the ring is dashed, and when it becomes final, is explained on the CHARGE overlay.

### With a cap active

```
│                       38
│                       ▼  ⚠
│                    CHARGE
```

The ⚠ is all level 1 shows. The cause (sore throat, day 2, capped at 40) is on the CHARGE overlay.

---

## 2. CHARGE overlay

```
│ ✕                                  TUE 3 NOV
│              ╭━━━━━━━▓▓▓▓━━━━━╮
│              ┃       112      ┃
│              ┃        ▲       ┃
│              ╰━━━━━━━━━━━━━━━━╯
│              PRIMED · FINAL ✓
│ How you should feel after Monday. Better than
│ 93 of your last 100 mornings.
├──────────────────────────────────────────────
│ WHAT MOVED IT          vs your typical day (80)
│ SLEEP     ██████████████▶  +14             ›
│ RECOVERY  ███████████▶     +11             ›
│ BALANCE   ██████████▶      +10             ›
│ FUEL      ███▶              +3             ›
│ LOAD    ◀██████             −6             ›
├──────────────────────────────────────────────
│ LAST 30 DAYS
│ 150 ┤
│ 100 ┤- - - - - - - - - - - - - - - -▄█▄█- -
│  60 ┤ ▅▆▅▃▂▄▆▇▆▅▄▃▅▆▅▄▃▂▁▃▅▆▇▆▅▆▇██▆
│     └ 5 Oct                        3 Nov ┘
│ tap a day: that morning's CHARGE overlay
├──────────────────────────────────────────────
│ CAPS          none
│ TYPICALITY    typical morning
│ BASED ON      5 of 5 domains
│ WEIGHTS       default · personal after 120
│               scored days (you have 84)
│ EARNS ITS PLACE?  ✓ forecasts today's session
│   and tomorrow's energy better than RECOVERY
│   alone (84 days)
├──────────────────────────────────────────────
│ WHAT IS CHARGE?
│ How you should feel this morning, given last
│ night and yesterday. 100 is the top of your
│ normal; above it you are better than 9 in 10
│ of your own days.
```

- Each WHAT MOVED IT row opens that domain's overlay on top.
- **When PROVISIONAL:** the status line reads "PROVISIONAL · final when you close Monday, or at 12:00", with a
  CLOSE MONDAY button.
- **When capped:** CAPS reads "⚠ sore throat · day 2 · capped at 40", and opens ME › HEALTH's concern overlay.

---

## 3. Domain overlays

All five use the same layout:

```
│ ✕  DOMAIN                        DAY / NIGHT
│ ring · band word · one sentence
│ WHAT MOVED IT     driver tiles, sorted by points, each with its points
│ THE FULL PICTURE  tiles for the views that aren't scored (charts, logs)
│ WHAT AFFECTS IT   personal relationships from LAB › N=1
│ WHAT IS IT        two or three lines
```

Every tile opens a level 3 overlay (§4). These overlays replace the domain pages from v1.

### SLEEP

```
│ ✕  SLEEP                            MON NIGHT
│              ╭━━━━━━▓▓▓▓▓▓━━━━╮
│              ┃      124       ┃
│              ┃       ▲        ┃
│              ╰━━━━━━━━━━━━━━━━╯
│                   PRIMED
│ Long night, more deep sleep than usual.
├──────────────────────────────────────────────
│ WHAT MOVED IT                             pts
│ Duration           8h05   ▲          +11   ›
│ Deep + REM         2h41   ▲           +6   ›
│ Sleep debt (7 d)  −0h20   ●           +2   ›
│ Regularity       SRI 86   ●           +1   ›
│ Timing      mid 03:10     ●            0   ›
│ Breathing    14.2 /min    ●            0   ›
│ Awake            18 min   ●           −1   ›
├──────────────────────────────────────────────
│ THE FULL PICTURE
│ Last night        23:05 → 07:10            ›
│ Stages · 30 days  deep 1h22 avg            ›
│ Bed and wake times  steady                 ›
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR SLEEP
│ Alcohol > 20 g        −0h35                ›
│ Training after 20:00  deep −14 min         ›
│ Phone after 23:00     onset +22 min        ›
```

### RECOVERY

```
│ ✕  RECOVERY                       TUE MORNING
│              ╭━━━━━━━━▓━━━━━━━╮
│              ┃      108       ┃
│              ┃       ▲        ┃
│              ╰━━━━━━━━━━━━━━━━╯
│                   PRIMED
│ Body has absorbed the weekend's long run.
├──────────────────────────────────────────────
│ WHAT MOVED IT                             pts
│ HRV                 58 ms  ▲          +7   ›
│ Resting HR         47 bpm  ▲          +4   ›
│ Morning feel     energy 4  ●          +1   ›
│ Night stress           18  ●           0   ›
│ Soreness              2/5  ●           0   ›
├──────────────────────────────────────────────
│ THE FULL PICTURE
│ Is this morning typical?   yes             ›
│ Sexual health    3 this week               ›
│ Open concerns    none                      ›
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR RECOVERY
│ SLEEP < 70          HRV −6 % next day      ›
│ LOAD HIGH 3+ days   RHR +2 bpm             ›
│ Alcohol > 20 g      HRV −9 %               ›
```

### LOAD

```
│ ✕  LOAD                                MONDAY
│              ╭━━━━━━━━━━━━━━━╮
│              ┃       71      ·
│              ┃       ▼       ·
│              ╰━━━━━━━━━━━━··╯
│            NORMAL · HIGH SIDE
│  LOW ◀────────── IN BAND ──────────▶ HIGH
│                                    ▲
│ 4th day without rest; still inside your band.
├──────────────────────────────────────────────
│ WHAT MOVED IT                             pts
│ Days without rest       4  ▲         −12   ›
│ Plan done          4 of 5  ●          −2   ›
│ Fatigue (TSB)         −18  ●           0   ›
│ Monotony              1.6  ●           0   ›
│ Intensity split     78/22  ✓           0   ›
│ Since deload         3 wk  ●           0   ›
├──────────────────────────────────────────────
│ THE FULL PICTURE
│ Yesterday's sessions  2 · load 110         ›
│ Fitness and fatigue   CTL 54 ▲             ›
│ Intensity · 12 weeks  80/20                ›
│ Activity · 52 weeks   5.1 days/wk          ›
│ Regional load         legs high            ›
│ Injury                none open            ›
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR LOAD RESPONSE
│ 5+ days without rest  RECOVERY −14         ›
│ Long run > 25 km      RECOVERY −9          ›
```

LOAD always shows a direction strip (LOW, IN BAND, HIGH), because a lower ring can mean too much or too little. It is
the only domain whose overlay adds a strip to the ring.

### FUEL

```
│ ✕  FUEL                                MONDAY
│              ╭━━━━━━━━━━━━━━━╮
│              ┃       94      ┃
│              ┃       ●       ·
│              ╰━━━━━━━━━━━━━━━╯
│                   NORMAL
│ Fed for the training; gut settled.
│        INTAKE  96 ●        GUT  90 ●
├──────────────────────────────────────────────
│ WHAT MOVED IT · INTAKE                    pts
│ Carbs         5.1 g/kg  ↓             −3   ›
│ Energy        2 610 kcal ✓             0   ›
│ Protein       1.9 g/kg  ✓              0   ›
│ Water           2.9 L   ✓              0   ›
│ Alcohol, caffeine  0 g · 13:30 ✓       0   ›
├──────────────────────────────────────────────
│ WHAT MOVED IT · GUT                       pts
│ Symptoms      comfort 3 ●             −1   ›
│ Timing        08:10     ●              0   ›
│ Frequency         1     ●              0   ›
│ Form      type 4 (3 d)  ●              0   ›
│ In-session    no fuelled session       —   ›
├──────────────────────────────────────────────
│ THE FULL PICTURE
│ Meals yesterday       4 · 2 610 kcal       ›
│ Calories & macros · 7 days                 ›
│ Energy balance    TDEE 2 680 · −0.1 kg/wk  ›
│ Micronutrients    2 short                  ›
│ Glucose spiking   1 high-GL meal           ›
│ Hydration         2.9 / 3.2 L              ›
│ Bowel timing · 14 days   steady            ›
│ Digestive pattern · 14 days   typical      ›
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR GUT
│ Alcohol > 20 g        loose next day       ›
│ Dinner after 21:30    bloating             ›
│ Gel brand X in runs   gut 6/10             ›
```

### BALANCE

```
│ ✕  BALANCE                             MONDAY
│              ╭━━━━━━━▓▓▓▓▓━━━━╮
│              ┃      118       ┃
│              ┃       ▲        ┃
│              ╰━━━━━━━━━━━━━━━━╯
│             PRIMED · ◉ BALANCED
│ Calm day, settled well before bed.
│     ANS 122 ▲    MIND 110 ▲    RHYTHM 104 ●
├──────────────────────────────────────────────
│ WHAT MOVED IT · NERVOUS SYSTEM            pts
│ Stressed time      14 %  ▲            +8   ›
│ Relaxed time       38 %  ▲            +5   ›
│ Wind-down      stress 22 ●             0   ›
│ Night HR dip      −18 %  ●             0   ›
│ HRV stability   CV 4.1 % ●             0   ›
├──────────────────────────────────────────────
│ WHAT MOVED IT · MIND
│ Mood                4/5  ▲            +3   ›
│ Stress (felt)       2/5  ●            +1   ›
├──────────────────────────────────────────────
│ WHAT MOVED IT · RHYTHM
│ Meal timing      steady  ●             0   ›
│ Phone after 23:00 10 min ●             0   ›
├──────────────────────────────────────────────
│ THE FULL PICTURE
│ Stress through the day    peak 15:40       ›
│ ANS map · 14 days         balanced 11 of 14›
│ Phone · 7 days            2h40/day         ›
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR BALANCE
│ Commute by train      stressed time +9 %   ›
│ Workday > 10 h        wind-down +11        ›
```

---

## 4. Metric overlay

All the explanations and confounding factors live here. Every metric in the app uses this one layout. Example: HRV,
opened from RECOVERY.

```
│ ‹ RECOVERY                                  ✕
│ HRV · TUE MORNING
│ 58 ms                         ▲ ABOVE NORMAL
│ your normal 44–52 ms · moved RECOVERY +7
├──────────────────────────────────────────────
│ 60 DAYS                   [7D 30D 60D 1Y]
│  60 ┤                                   ●
│     ┤░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  normal
│  45 ┤ ●  ● ●●  ●● ● ●●● ● ●  ●●● ●● ●●
│  30 ┤
│     └ 4 Sep                         3 Nov ┘
│ tap a dot: that morning
├──────────────────────────────────────────────
│ HOW IT SITS
│ 7-day level      51 ms  ● NORMAL
│ Day-to-day CV    4.1 %  ● STEADY
│ How rare today   1 in 12 mornings
│ Band held        26 of last 30 inside
├──────────────────────────────────────────────
│ WHAT WENT WITH IT
│ Sleep 8h05 (▲) · no alcohol · rest-ish day
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR HRV
│ Alcohol > 20 g        −9 %   (12 nights)
│ Long run > 25 km      −7 %   (6)
│ Sleep < 6 h           −6 %   (18)
├──────────────────────────────────────────────
│ DATA        Zepp via Health Connect · night
│             of 2→3 Nov · RMSSD · 1 reading
├──────────────────────────────────────────────
│ WHAT IS HRV?
│ Variation between heartbeats overnight;
│ higher than your normal usually means your
│ body is recovered. Compared only with you.
```

**Layout for every metric:**
1. Header: value, band word, your normal, and the points it moved.
2. Chart with the normal band.
3. HOW IT SITS.
4. WHAT WENT WITH IT.
5. WHAT AFFECTS IT.
6. DATA (source and quality).
7. WHAT IS IT.

A section with nothing to say is left out.

**Views that aren't scored** use the same overlay with their own chart and no points line. Examples: the hypnogram,
stress through the day, bowel timing, the ANS map. Two of them:

```
│ ‹ FUEL                                      ✕
│ BOWEL TIMING · 14 DAYS              ● STEADY
│ usual window 07:00–08:40 · yesterday inside
│     00   04   08   12   16   20   24
│  M  ·    ·   ●▒▒  ·    ·    ·    ·
│  T  ·    ·  ●▒▒▒  ·    ·    ·    ·
│  W  ·    ·   ▒●▒  ·    ·  ●  ·    ·
│  …                        ▒ = usual window
│ HOW IT SITS   spread 38 min (usual 45) · 1.1/day
│ WHAT AFFECTS IT  late dinner → +1h10 next day
```

```
│ ‹ BALANCE                                   ✕
│ ANS MAP · 14 DAYS                ◉ BALANCED
│              HRV ▲
│     BALANCED  │  UNUSUAL
│   RHR ◀───────┼───────▶ RHR
│     lower  ◉  │   higher       ◉ = today
│   SATURATED   │  SYMPATHETIC
│              HRV ▼
│ 11 of 14 days balanced · 2 sympathetic (Thu,
│ Fri: workdays > 10 h) · 1 saturated (Sun,
│ after the 24 km)
```

---

## 5. PLAN

Sub-tabs: **TRAINING · FOOD · RACE · SESSIONS**. Level 1 shows headline tiles and lists. Every row opens an overlay.

### PLAN › TRAINING

```
│ PLAN   [TRAINING] FOOD  RACE  SESSIONS
├──────────────────────────────────────────────
│ Base 2 · week 3 of 6        ▓▓▓▓▓▓▓░░░░░   ›
├──────────────────────────────────────────────
│ THIS WEEK                          4 of 5 ✓
│ MON ✓ Easy run 8 km · Strength lower       ›
│ TUE ○ Threshold 10 km           READY ✓    ›
│ WED   Rest                                 ›
│ THU ○ Intervals 6×800                      ›
│ SAT ○ Long run 24 km                       ›
├──────────────────────────────────────────────
│ Needs attention   1                        ›
│ Upcoming blocks   1                        ›
│ History           14 blocks                ›
│ [ PLAN A NEW BLOCK ]
```

| Tap | Opens |
|---|---|
| Block row | Block overlay: phases, focus, weekly volume against plan, edit |
| A day | Session overlay: structure, targets, READY FOR against CHARGE, move, swap, delete |
| Needs attention | The flags, e.g. "4 days without rest: keep Wed rest" with a button |

### PLAN › FOOD

```
│ PLAN   TRAINING [FOOD] RACE  SESSIONS
├──────────────────────────────────────────────
│ TODAY · threshold day        2 650 kcal    ›
│ TOMORROW · rest day          2 250 kcal    ›
│ THU · intervals              2 700 kcal    ›
├──────────────────────────────────────────────
│ Meal prep · this week     4 recipes        ›
│ Periodisation · 4 weeks   carbs follow load›
```

| Tap | Opens |
|---|---|
| A day | Day overlay: the same meals TODAY'S PLAN shows, with targets (carbs and protein in g/kg) and per-meal macros; swap or edit a meal |
| Meal prep | The four steps (PICK · RECIPES · WEEK · SHOP & COOK) in one overlay |

### PLAN › RACE

```
│ PLAN   TRAINING  FOOD [RACE] SESSIONS
├──────────────────────────────────────────────
│ Trail 42K · 30 Nov            27 days      ›
│ Race fuel · 75 g carbs/h · 600 ml/h        ›
```

| Tap | Opens |
|---|---|
| Event | Event overlay: course, the day, aid stations |
| Race fuel | Race-fuel overlay: before, during, after, per hour, carry, watch, assumptions, what changed |

### PLAN › SESSIONS

```
│ PLAN   TRAINING  FOOD  RACE [SESSIONS]
├──────────────────────────────────────────────
│ MON 2  Easy run    8.2 km   load 62   ▲    ›
│ MON 2  Strength    lower    load 48   ●    ›
│ SAT 31 Long run   24.1 km   load 188  ●    ›
│ …                              [ LOAD MORE ]
```

The direction mark is the session's overall mark against usual. For runs it is the efficiency factor.

Tapping a session opens the session overlay:

```
│ ✕  EASY RUN · MON 2 NOV
│ 8.2 km · 46:10 · 5:38 /km · HR 141
│ load 62 · fed LOAD for Monday
├──────────────────────────────────────────────
│ Efficiency factor   1.62   ▲               ›
│ HR decoupling       2.1 %  ●               ›
│ Pace                5:38   ●               ›
│ Cadence             172    ●               ›
│ Stride              1.03 m ●               ›
├──────────────────────────────────────────────
│ Heart rate · zones               Z2 81 %   ›
│ Pace · power · vertical ratio              ›
│ Splits                    8 km             ›
│ Route                     map              ›
```

Each row opens a level 3 overlay, with its chart over the run and the mark against usual runs of that kind.

---

## 6. + LOG

Tapping **+ LOG** opens the LOG page **with the logging menu already up**. Close the menu and the log of past entries
is underneath.

```
│ LOG · today 9 entries · 1 gap
│ TUE 3 NOV   ■ food ■ water ◧ supps ┈ check-in
│ MON 2 NOV   ■ ■ ■ ■ ✓ closed 22:14
│ ╭────────────────────────────────────────────
│ │ WHAT ARE YOU LOGGING?                   ✕
│ │ RECENT   [Water] [Food] [Supplements]
│ │
│ │ ● CLOSE THE DAY                (evening)
│ │
│ │ INTAKE    Food · Drink · Supplements ·
│ │           Run fuel
│ │ BODY      Wellbeing · Stool · Grip strength
│ │ TRAINING  Session · Injury check-in ·
│ │           Injury · Illness
│ │ INTIMACY  Sexual activity · Encounter
│ │ OTHER     Note
│ ╰────────────────────────────────────────────
   TODAY     PLAN     + LOG     LAB     ME
```

- SAY IT is gone.
- CLOSE THE DAY sits at the top of the menu from 19:00 until the day is closed.
- Tapping a day in the log opens the day overlay with every entry. Tapping an entry opens it: DETAIL, EDIT, DELETE.
- Late logs are ordinary entries under their day. FINAL scores don't change.

### CLOSE THE DAY

```
│ ✕  CLOSE MONDAY                       1 min
├──────────────────────────────────────────────
│ HOW WAS THE DAY?
│ Stress     ○ ○ ● ○ ○      Mood     ○ ○ ○ ● ○
│ Energy     ○ ○ ○ ● ○      Soreness ○ ● ○ ○ ○
├──────────────────────────────────────────────
│ GUT
│ Comfort    ○ ○ ○ ● ○
│ [bloating] [cramps] [reflux] [urgency]
│ [gas] [nausea]
│ Bowel movements   1 at 08:10       [+ ADD]
├──────────────────────────────────────────────
│ FOOD LOG   2 610 kcal · 96 % of usual  ✓
│            [ ADD MORE ]
├──────────────────────────────────────────────
│ [ CLOSE THE DAY ]
```

---

## 7. LAB

Sub-tabs: **N=1 · SUPPLEMENTS · TESTS · ROUTINE**. Level 1 is headline tiles; each opens an overlay.

```
│ LAB   [N=1] SUPPLEMENTS  TESTS  ROUTINE
├──────────────────────────────────────────────
│ Magnesium at night   day 18/28 · SLEEP +6  ›
│ Late caffeine cut    done · BALANCE +9 ✓   ›
│ [ START AN EXPERIMENT ]
├──────────────────────────────────────────────
│ What goes with what      9 found           ›
│ Before low-CHARGE mornings  3 signals      ›
│ Interactions · protocols                   ›
```

| Tile | Overlay |
|---|---|
| An experiment | Progress chart with today's draw, outcome = a domain score, the protocol |
| What goes with what | Forest rows, one per relationship; each opens the metric overlay of its outcome |
| Before low-CHARGE mornings | Log-odds rows (late dinner, alcohol, < 7 h…) |

```
│ LAB   N=1 [SUPPLEMENTS] TESTS  ROUTINE
├──────────────────────────────────────────────
│ Today          2 of 4 taken                ›
│ Stack          6 · 1 interaction           ›
│ Pantry         2 run out within 14 days    ›
```

```
│ LAB   N=1  SUPPLEMENTS [TESTS] ROUTINE
├──────────────────────────────────────────────
│ Grip strength     48 kg  ●                 ›
│ Body check-in     2 days ago               ›
│ Sweat test        1.1 L/h · 12 Oct         ›
│ Running form      last 20 Oct              ›
│ Movement evaluation                        ›
```

```
│ LAB   N=1  SUPPLEMENTS  TESTS [ROUTINE]
├──────────────────────────────────────────────
│ The week by place    4 places              ›
│ Commute              38 min · train        ›
│ Active travel        4.2 km/day            ›
│ Unlogged visits      2                     ›
```

---

## 8. ME

Sub-tabs: **HEALTHSPAN · PERFORMANCE · BODY · HEALTH · BLOODWORK**, plus ⚙.

```
│ ME [HEALTHSPAN] PERFORMANCE BODY HEALTH BLOODWORK ⚙
├──────────────────────────────────────────────
│           VO2max
│     grip  ╱‾‾‾╲  lipids
│   BP     ⟨  ◆  ⟩   glucose
│     body  ╲___╱  sleep
│           strength
│        6 of 8 favourable                   ›
├──────────────────────────────────────────────
│ Biological age   31.4                      ›
```

The radar is the headline. Tapping it opens the overlay with each factor (it opens the metric overlay) and the history.

```
│ ME  HEALTHSPAN [PERFORMANCE] BODY …
├──────────────────────────────────────────────
│ Running    STRONG     ▲                    ›
│ Strength   SOLID      ●                    ›
│ Fitness    VO2max 53  ●                    ›
```

| Tile | Overlay |
|---|---|
| Running | Volume, ROAD/TRAIL switch, efficiency factor, decoupling, pace (GAP), cadence/stride, ground contact, vertical ratio, records; each chart opens its metric overlay |
| Strength | e1RM, standards, PR timeline, projected max, consistency, mix |
| Fitness | VO2max, LT pace, HR zones |

```
│ ME  … [BODY] HEALTH BLOODWORK
├──────────────────────────────────────────────
│ Weight      72.4 kg   ●                    ›
│ Body fat    13.8 %    ●                    ›
│ Measurements  12 Oct                       ›
```

```
│ ME  … BODY [HEALTH] BLOODWORK
├──────────────────────────────────────────────
│ ⚠ Sore throat        day 2 · caps CHARGE   ›
│ Recurring: L knee    quiet 34 days         ›
│ New, not yet a concern   1                 ›
```

The concern overlay holds: state, episodes, context, patterns, timeline, recurrence, training impact.

```
│ ME  … HEALTH [BLOODWORK]
├──────────────────────────────────────────────
│ Last draw 14 Aug   77 markers · 3 flagged  ›
│ Lipids      ●   Glucose   ●   Iron   ▼     ›
```

Each marker opens the metric overlay: value, range, against the last draw, trend.

⚙ SETTINGS is unchanged: PROFILE & GOALS · NOTIFICATIONS · CONNECTED SERVICES · TRAINING SETUP.

---

## 9. Where every current card goes

"Tile → overlay" means a headline on the page that opens the full card as an overlay.

| Current place | Card | New home | Shown as |
|---|---|---|---|
| Status | Hero (condition, HRV vs baseline) | Retired | CHARGE ring |
| Status | Body figure + injury flag | ⚠ on CHARGE; ME › HEALTH; LOAD › Regional load | Cap mark; tile → overlay |
| Status | NEXT UP | TODAY'S PLAN | Session row |
| Status | Training / Nutrition / Health / Lab tiles | TODAY | Domain rings |
| Status | Quick log | **Removed** | + LOG opens the menu |
| Today | THIS MORNING (HRV, RHR, sleep bands) | RECOVERY, SLEEP overlays | Driver tiles → metric overlay |
| Today | TODAY'S RECOVERY | TODAY | RECOVERY ring |
| Today | READY FOR | TODAY'S PLAN; session overlay | READY mark; demand vs CHARGE in overlay |
| Today | IS TODAY TYPICAL? | RECOVERY › full picture; ⚠ on CHARGE | Tile → overlay |
| Today | NUTRITION TODAY | TODAY › SO FAR | One line → overlay |
| Today | DO THE SUMMARIES EARN THEIR PLACE? | Each score's overlay | One line |
| Today header | ADD ABOUT LAST EVENING | CLOSE THE DAY | Plan row + top of + LOG menu |
| Training › Load | Training load, CTL/ATL, TSB | LOAD overlay | TSB driver; "Fitness and fatigue" tile |
| Training › Load | Intensity & load distribution, intensity 12 wk | LOAD overlay | Driver; tile → overlay |
| Training › Load | Activity heatmap | LOAD overlay | Tile → overlay |
| Training › Load | Fitness (VO2max, LT) | ME › PERFORMANCE › Fitness | Tile → overlay |
| Training › Running | All running cards | ME › PERFORMANCE › Running | Overlay; charts → metric overlays |
| Training › Strength | All strength cards | ME › PERFORMANCE › Strength | Overlay |
| Training › Sessions | Sessions list, session page | PLAN › SESSIONS | Row → session overlay |
| Training › Injury | Injury | ME › HEALTH; LOAD › Injury tile | Concern overlay |
| Nutrition › Nutrition | Calories & macros, macros/kg, protein per meal, nutrient breakdown | FUEL overlay | Drivers; tile → overlay |
| Nutrition › Nutrition | Micronutrients, vitamins & minerals | FUEL overlay | Tile → overlay |
| Nutrition › Nutrition | Glucose spiking | FUEL overlay | Tile → overlay |
| Nutrition › Nutrition | Energy balance | FUEL energy driver + tile; ME › BODY | Driver; tile → overlay |
| Nutrition › Hydration | Hydration intelligence, accounting, AI summary | FUEL water driver + tile | Overlay |
| Nutrition | Caffeine window | FUEL "Alcohol, caffeine" driver | Metric overlay |
| Nutrition › Plan | Next three days | PLAN › FOOD; TODAY'S PLAN | Day rows → overlay; meal rows |
| Health › Cardio | HRV, RHR charts | RECOVERY drivers | Metric overlay |
| Health › Cardio | Physiological stress | BALANCE (day) / RECOVERY (night) | Drivers; "Stress through the day" tile |
| Health › Sleep | Sleep phase breakdown, hypnogram | SLEEP overlay | "Last night", "Stages" tiles |
| Health › Sleep | Circadian alignment | SLEEP timing, BALANCE rhythm drivers | Metric overlay |
| Health › Body | Weight, BF %, measurements | ME › BODY | Tiles → overlay |
| Health › Concerns | All | ME › HEALTH | Concern overlay |
| Health › Wellbeing | Energy, stress, mood, soreness | CLOSE THE DAY → BALANCE, RECOVERY | Drivers |
| Health › Wellbeing | Digestive pattern (Bristol) | FUEL › gut | Form driver; "Digestive pattern" tile |
| Health › Sexual health | Frequency, occurrences | RECOVERY › full picture | Tile → overlay |
| Health › Bloodwork | Labs | ME › BLOODWORK | Theme rows → metric overlay |
| Lab › N=1 | Correlations, what goes with what, off mornings, experiments, interactions | LAB › N=1; "What affects" on each overlay | Tiles → overlay |
| Lab › Supplements | Today, stack, pantry, stock | LAB › SUPPLEMENTS; TODAY'S PLAN rows | Tiles → overlay |
| Lab › Planning | Training block, food plan, meal prep, race fuel | PLAN › TRAINING / FOOD / RACE | Rows → overlay |
| Lab › Tests | Daily tests, grip, sweat, running form, movement | LAB › TESTS | Tiles → overlay |
| Lab › Routine | Week by place, commute, active travel, unlogged visits | LAB › ROUTINE | Tiles → overlay |
| Lab › Routine | Phone · last 7 days | BALANCE rhythm driver + tile | Metric overlay |
| User | Healthspan, aging, levels, block card | ME (block → PLAN) | Radar; tiles → overlay |
| Setup | All | ME › ⚙ | Unchanged |
| Add sheet | SAY IT (log agent) | **Removed** | — |

### New things to build

- **Ring components:** the big CHARGE ring, the small domain rings, and the second lap.
- **One full-screen overlay component** used at both levels 2 and 3.
- **The level 3 metric overlay template.**
- **The TODAY'S PLAN timeline**, merging sessions, food-plan meals, supplements and closing the day.
- **The CLOSE THE DAY check-in.**
- **The gut fields:** comfort and symptom chips, plus a time on stool entries.
- **New charts:** the bowel timing chart and the ANS map.
