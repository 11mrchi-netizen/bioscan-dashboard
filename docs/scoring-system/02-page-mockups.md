# Page mockups (ASCII)

Every page of the rebuilt app, phone width (about 46 characters). The numbers are made up but consistent across pages:
one morning, Tue 3 Nov, CHARGE 112.

Contents: [0 Conventions](#0-conventions) · [1 TODAY](#1-today) · [2 CHARGE sheet](#2-charge-sheet) ·
[3 SLEEP](#3-sleep) · [4 RECOVERY](#4-recovery) · [5 LOAD](#5-load) · [6 FUEL](#6-fuel) · [7 BALANCE](#7-balance) ·
[8 PLAN](#8-plan) · [9 LOG](#9-log) · [10 LAB](#10-lab) · [11 ME](#11-me) ·
[12 Where every current card goes](#12-where-every-current-card-goes)

---

## 0. Conventions

These apply on every page.

### Navigation

```
  TODAY     PLAN     + LOG     LAB     ME
```

- **Reading** (scores and analysis): TODAY, which opens the five domain pages.
- **Doing**: PLAN, LOG, LAB.
- **Slow-moving**: ME.
- Domain pages are pushed from TODAY's chips, with a back arrow (‹) and the dock lit on TODAY.

### Score band bar

Used for CHARGE and for each domain at the top of its page:

```
 0      40     60              100     125      150
 ├──────┼──────┼────────────────┼───────┼────────┤
 LOW    BELOW       NORMAL       PRIMED  SUPERCHARGED
                                    ▲ 112
```

FUEL and LOAD end at 100, because they have no upside. Their bar is drawn only from 0 to 100.

### Score chip

Used for one domain on TODAY:

```
 SLEEP     124 ▲  8h05, deep above usual       ›
 └name     └score └one reason, from the top driver
```

The arrow shows the change from yesterday's score: ▲ up, ▼ down, ● about the same.

### Metric row

One format for every metric in the app:

```
 HRV          58 ms   ▲ ABOVE NORMAL    +7
 └name        └value  └vs YOUR normal   └points it moved its score (drivers only)
                       (44–52 ms)        tap the row: 60-day chart with your normal band
```

- **Words** against your normal: ▲ ABOVE NORMAL · ● NORMAL · ▼ BELOW NORMAL · ▼▼ UNUSUALLY LOW. Target-type inputs say
  ON TARGET / SHORT / OVER instead.
- **Colour** (from the design contract): a faint green tint only for points that help, a faint red tint only for
  points that hurt. The word is always there too.
- **Detail cards** below the drivers use the same row format without the points column.

### States

| Mark | Meaning |
|---|---|
| `PROVISIONAL` | Published at wake; yesterday not closed yet |
| `FINAL ✓` | Locked; never changes |
| `PARTIAL` | Not enough logged to score; left out of CHARGE, never scored low |
| `BUILDING 12/28` | Not enough history for a baseline yet (days have / days needed) |
| `⚠` | Typicality flag, or a cap is active |
| `?` | Opens the explainer: what it is, coverage, weights in use, scorecard verdict |

---

## 1. TODAY

### Morning, after the evening check-in (FINAL)

```
│ TODAY · TUE 3 NOV                ☁ 24°  ⟳ 07:12
│
│                 C H A R G E
│                     112
│                   ▲ PRIMED
│  0     40   60           100     125       150
│  ├─────┼────┼─────────────┼───────┼─────────┤
│                               ▲
│  Better than 93 of your last 100 mornings.
│  Mostly SLEEP (+14) and RECOVERY (+11).
│  LOAD holds it back (−6).           FINAL ✓  ?
├──────────────────────────────────────────────
│ SLEEP      124 ▲  8h05, deep above usual      ›
│ RECOVERY   108 ▲  HRV 58 ms, RHR 47           ›
│ LOAD        71 ▼  HIGH · 4th day without rest ›
│ FUEL        94 ●  on target · gut settled     ›
│ BALANCE    118 ▲  BALANCED · calm day         ›
├──────────────────────────────────────────────
│ TODAY'S PLAN
│ 18:30  Threshold run · 10 km        READY ✓
│        demand ●●●○  · charge covers it
│ 21:00  Mobility · 20 min            READY ✓
│ Next up in calendar: Team call 10:00
├──────────────────────────────────────────────
│ TODAY SO FAR                 progress, no score
│ kcal    ████████░░░░░  1 420 / 2 650  ON TRACK
│ protein ██████░░░░░░░    78 / 150 g   SHORT
│ carbs   ███████░░░░░░   180 / 340 g   ON TRACK
│ water   █████░░░░░░░░   1.2 / 3.0 L   SHORT
│ caffeine  last dose by 14:00 · 1 so far
│ supps   2 of 4 taken · evening bundle due
├──────────────────────────────────────────────
│ [+ WATER] [+ FOOD] [+ SUPPS] [SAY IT]
└──────────────────────────────────────────────
   TODAY     PLAN     + LOG     LAB     ME
```

### Morning, before yesterday is closed (PROVISIONAL)

```
│                     104
│                   ▲ PRIMED        PROVISIONAL ?
│  Final when you close Monday, or at 12:00.
│  [ CLOSE MONDAY › ]  1 min
├──────────────────────────────────────────────
│ SLEEP      124 ▲  8h05, deep above usual      ›
│ RECOVERY   108 ▲  HRV 58 ms, RHR 47           ›
│ LOAD        71 ▼  HIGH · 4th day without rest ›
│ FUEL        —     PARTIAL · 45 % logged       ›
│ BALANCE     —     PARTIAL · no check-in       ›
```

### Evening (after 19:00)

A banner sits above everything else:

```
│ ┌──────────────────────────────────────────┐
│ │ CLOSE TODAY            stress · gut · log│
│ │ Tomorrow's CHARGE is built from it.    › │
│ └──────────────────────────────────────────┘
```

### With a cap active

```
│                      38
│                   ▼ LOW · CAPPED ⚠
│  Open illness: sore throat, day 2.
│  CHARGE is capped at 40 until it closes.   ?
```

### What happens to today's cards

- The Status hero, the body figure and the four tiles are replaced by this page.
- IS TODAY TYPICAL? becomes the ⚠ on the dial, and its strip moves to RECOVERY.
- NEXT UP becomes the last line of TODAY'S PLAN.
- READY FOR becomes the READY mark on each planned session.

---

## 2. CHARGE sheet

Opens when you tap the dial or its "?".

```
│ CHARGE 112 · PRIMED · TUE 3 NOV · FINAL ✓
│ How you should feel after Monday.
│ Better than 93 of your last 100 mornings.
├──────────────────────────────────────────────
│ WHAT MOVED IT          vs your typical day (80)
│ SLEEP     ██████████████▶  +14
│ RECOVERY  ███████████▶     +11
│ BALANCE   ██████████▶      +10
│ FUEL      ███▶              +3
│ LOAD    ◀██████             −6
│                             ───
│                             +32  → 112
├──────────────────────────────────────────────
│ LAST 30 DAYS
│ 150 ┤
│ 125 ┤                                 ▄
│ 100 ┤- - - - - - - - - - - - - - - -█-█- -
│  60 ┤ ▅▆▅▃▂▄▆▇▆▅▄▃▅▆▅▄▃▂▁▃▅▆▇▆▅▆▇██▆
│  40 ┤
│     └ 5 Oct                        3 Nov ┘
├──────────────────────────────────────────────
│ CAPS          none
│ TYPICALITY    typical morning
│ BASED ON      5 of 5 domains
│ WEIGHTS       default · personal after 120
│               days (you have 84)
│ EARNS ITS PLACE?  forecasts today's session
│   and tomorrow's energy better than RECOVERY
│   alone · 84 days · ✓ yes
├──────────────────────────────────────────────
│ [ ASK WHY › ]                 health agent
```

---

## Domain page template

Sections 3–7 all follow the same layout:

```
│ ‹ DOMAIN                                     ?
│ score · band word · band bar · one sentence
│ 30-DAY STRIP of the score
│ DRIVERS: the scored inputs, metric-row format, sorted by |points|
│ DETAILS: today's existing cards, collapsed by default, same row format
│ WHAT AFFECTS THIS FOR YOU: from N=1 relationships
```

---

## 3. SLEEP

```
│ ‹ SLEEP · MON NIGHT                          ?
│ 124  ▲ PRIMED
│  0     40   60           100     125       150
│  ├─────┼────┼─────────────┼───────┼─────────┤
│                                    ▲
│ Long night, more deep sleep than usual.
│ 30 D ▅▆▅▃▂▄▆▇▆▅▄▃▅▆▅▄▃▂▁▃▅▆▇▆▅▆▇██
├──────────────────────────────────────────────
│ DRIVERS                                   pts
│ Duration    8h05  ▲ ABOVE NEED (7h10)     +11
│ Deep + REM  2h41  ▲ ABOVE NORMAL (2h15)    +6
│ Sleep debt  −0h20 / 7 d  ● NORMAL          +2
│ Regularity  SRI 86  ● STEADY               +1
│ Timing      midpoint 03:10  ● USUAL         0
│ Breathing   14.2 /min  ● STEADY             0
│ Awake       18 min  ● NORMAL               −1
├──────────────────────────────────────────────
│ ▸ LAST NIGHT        hypnogram 23:05 → 07:10
│   ▔▔▁▁▂▃▃▂▁▁▂▃▃▂▂▁▂▃▂▁▁▔
│   awake · REM · light · deep, with minutes
│ ▸ STAGES · 30 DAYS       stacked bars, normal
│ ▸ BED AND WAKE TIMES     dots with usual band
│ ▸ CIRCADIAN TIMING       STEADY / VARIABLE
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR SLEEP
│ Alcohol > 20 g        −0h35   (12 nights)
│ Training after 20:00  deep −14 min (9)
│ Phone after 23:00     onset +22 min (15)
```

---

## 4. RECOVERY

```
│ ‹ RECOVERY · TUE MORNING                     ?
│ 108  ▲ PRIMED
│  ├─────┼────┼─────────────┼───────┼─────────┤
│                                 ▲
│ Body has absorbed the weekend's long run.
│ 30 D ▃▄▅▅▄▃▂▂▃▅▆▆▅▄▃▄▅▆▅▃▂▃▄▅▆▇▆▅▆▇
├──────────────────────────────────────────────
│ DRIVERS                                   pts
│ HRV          58 ms  ▲ ABOVE NORMAL (44–52) +7
│ Resting HR   47 bpm ▲ BELOW USUAL (49–52)  +4
│ Night stress 18     ● NORMAL                0
│ Soreness     2/5    ● NORMAL                0
│ Morning feel energy 4/5 · ● NORMAL         +1
│              (energy, erection, arousal)
├──────────────────────────────────────────────
│ ▸ HRV · 60 DAYS        line inside normal band
│ ▸ RESTING HR · 60 DAYS line inside normal band
│ ▸ IS THIS MORNING TYPICAL?  strip: how far
│   each standing-out signal sits from usual
│ ▸ SEXUAL HEALTH        frequency, occurrences
│ ▸ OPEN CONCERNS        none · see ME › HEALTH
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR RECOVERY
│ SLEEP < 70         next-day HRV −6 % (18 d)
│ LOAD HIGH 3+ days  RHR +2 bpm (11 d)
│ Alcohol > 20 g     HRV −9 % (12 d)
```

When an open illness or injury caps the domain, a banner sits at the top:

```
│ ⚠ CAPPED · Sore throat, day 2 · logged 07:40 ›
```

---

## 5. LOAD

```
│ ‹ LOAD · MONDAY                              ?
│ 71  ▼ NORMAL · HIGH SIDE
│  0     40   60           100
│  ├─────┼────┼─────────────┤
│              ▲
│  LOW ◀────────── IN BAND ──────────▶ HIGH
│                                    ▲
│ 4th day without rest; still inside your band.
│ FITNESS  CTL 54 ▲ +3 in 4 wk · VO2max 53 ●
├──────────────────────────────────────────────
│ DRIVERS                                   pts
│ Days without rest  4  ▼ HIGH (usual ≤ 3)  −12
│ Fatigue (TSB)  −18  ● IN BAND (−25…−5)     0
│ Monotony       1.6  ● NORMAL               0
│ Plan done      4 of 5 this week  ● OK      −2
│ Intensity      78 / 22  ● ON PLAN (80/20)  0
│ Since deload   3 wk  ● OK                  0
├──────────────────────────────────────────────
│ YESTERDAY'S SESSIONS
│ Easy run · 8.2 km · 46 min      load 62    ›
│ Strength · lower · 50 min       load 48    ›
├──────────────────────────────────────────────
│ ▸ FITNESS AND FATIGUE  CTL and ATL, one chart
│ ▸ TSB GAUGE            banded, with reference
│ ▸ INTENSITY · 12 WEEKS stacked by zone
│ ▸ ACTIVITY HEATMAP     52 weeks
│ ▸ REGIONAL LOAD        body figure by region
│ ▸ INJURY               open: none ›
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR LOAD RESPONSE
│ 5+ days without rest   RECOVERY −14 two days on
│ Long run > 25 km       RECOVERY −9 next day
```

- **Score top:** 100 means the dose was right.
- **Direction bar:** always shown. The headline reads NORMAL · HIGH SIDE, LOW SIDE or IN BAND.
- **Fitness line:** a trend, not scored. It links to ME › PERFORMANCE.

---

## 6. FUEL

```
│ ‹ FUEL · MONDAY                              ?
│ 94  ● NORMAL
│  0     40   60           100
│  ├─────┼────┼─────────────┤
│                         ▲
│ Fed for the training; gut settled.
│ INTAKE 96 ●   GUT 90 ●
├──────────────────────────────────────────────
│ INTAKE DRIVERS                            pts
│ Energy    2 610 / 2 700 kcal  ● ON TARGET   0
│ Protein   1.9 g/kg  ● ON TARGET (1.8)       0
│ Carbs     5.1 g/kg  ▼ SHORT for load (6)   −3
│ Water     2.9 / 3.2 L  ● ON TARGET          0
│ Alcohol   0 g  · Caffeine last 13:30  ● OK  0
├──────────────────────────────────────────────
│ GUT DRIVERS                               pts
│ Symptoms  comfort 3/4 · bloating  ● NORMAL −1
│ Timing    08:10  ● IN USUAL WINDOW          0
│           (usual 07:00–08:40 · steady)
│ Frequency 1  ● USUAL (1–2)                  0
│ Form      type 4 (3-day)  ● USUAL           0
│ In-session  no fuelled session yesterday    —
├──────────────────────────────────────────────
│ ▸ BOWEL TIMING · 14 DAYS   steady ●
│     00   04   08   12   16   20   24
│   M  ·    ·   ●▒▒  ·    ·    ·    ·
│   T  ·    ·  ●▒▒▒  ·    ·    ·    ·
│   W  ·    ·   ▒●▒  ·    ·  ●  ·    ·
│   …  ▒ = your usual window
│ ▸ DIGESTIVE PATTERN   Bristol 14-day: firm /
│                       typical / loose shares
│ ▸ CALORIES & MACROS   7 days vs targets
│ ▸ ENERGY BALANCE      adaptive TDEE, weight
│                       trend ›  ME › BODY
│ ▸ PROTEIN PER MEAL    · MACROS PER KG
│ ▸ MICRONUTRIENTS      vitamins, minerals
│ ▸ GLUCOSE SPIKING     meal glycaemic load
│ ▸ HYDRATION           intake vs demand
│ ▸ CAFFEINE WINDOW
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR GUT
│ Alcohol > 20 g    loose next day 3× (7)
│ Dinner after 21:30  bloating 2× (9)
│ Gel brand X in runs gut 6/10 vs 2/10
```

---

## 7. BALANCE

```
│ ‹ BALANCE · MONDAY                           ?
│ 118  ▲ PRIMED            ANS  ◉ BALANCED
│  ├─────┼────┼─────────────┼───────┼─────────┤
│                                  ▲
│ Calm day, settled well before bed.
│ ANS 122 ▲   MIND 110 ▲   RHYTHM 104 ●
├──────────────────────────────────────────────
│ ANS DRIVERS                               pts
│ Stressed time  14 % awake  ▲ LOW (usual 25) +8
│ Relaxed time   38 %  ▲ ABOVE NORMAL         +5
│ Wind-down      stress 22 pre-bed ● NORMAL   0
│ Night HR dip   −18 %  ● NORMAL (−14…−20)    0
│ HRV stability  CV 4.1 %  ● STEADY           0
├──────────────────────────────────────────────
│ MIND DRIVERS
│ Stress (felt)  2/5  ● NORMAL               +1
│ Mood           4/5  ▲ ABOVE NORMAL         +3
├──────────────────────────────────────────────
│ RHYTHM DRIVERS
│ Meal timing    ● STEADY                     0
│ Phone after 23:00  10 min  ● NORMAL         0
├──────────────────────────────────────────────
│ ▸ STRESS THROUGH THE DAY  (yesterday)
│   100┤      ▃                 exercise
│      │ ▂▃▅▃▂ ▂▁▁▂▃▂▁▁▁▂▂▁░░░▁▁▁ masked ░
│     0└ 07    12    17    22
│ ▸ ANS MAP · 14 DAYS
│              HRV ▲
│     BALANCED  │  UNUSUAL
│   RHR ◀───────┼───────▶ RHR
│     lower     │   higher
│   SATURATED   │  SYMPATHETIC
│              HRV ▼
│   (dots, today ringed)
│ ▸ PHONE · LAST 7 DAYS
│ ▸ CIRCADIAN ALIGNMENT
├──────────────────────────────────────────────
│ WHAT AFFECTS YOUR BALANCE
│ Commute by train  stressed time +9 % (14 d)
│ Workday > 10 h    wind-down stress +11 (8 d)
```

---

## 8. PLAN

Sub-tabs: **TRAINING · FOOD · RACE · SESSIONS**.

### PLAN › TRAINING

```
│ PLAN   [TRAINING] FOOD  RACE  SESSIONS
├──────────────────────────────────────────────
│ CURRENT BLOCK · Base 2 · week 3 of 6       ›
│ ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░  focus: aerobic + hills
├──────────────────────────────────────────────
│ THIS WEEK                        4 of 5 done
│ MON ✓ Easy run 8 km · Strength lower
│ TUE ● Threshold 10 km      READY ✓  ●●●○
│ WED   Rest
│ THU   Intervals 6×800      CHARGE known Thu
│ SAT   Long run 24 km
├──────────────────────────────────────────────
│ NEEDS ATTENTION
│ 4 days without rest: Wed rest keeps LOAD in
│ band.                         [ KEEP WED REST ]
├──────────────────────────────────────────────
│ UPCOMING · PLANNED BLOCKS · HISTORY        ›
│ [ PLAN A NEW BLOCK ]
```

- **READY mark:** CHARGE against the session's demand tier: easy ●○○○, threshold ●●●○, long or strength ●●●●.
- **Future days:** show "CHARGE known that morning". There is no forecast in v1.

### PLAN › FOOD

```
│ PLAN   TRAINING [FOOD] RACE  SESSIONS
├──────────────────────────────────────────────
│ TODAY · threshold day        2 650 kcal
│ carbs 6 g/kg · protein 1.8 g/kg
│ breakfast · lunch · pre-run · dinner       ›
│ TOMORROW · rest day          2 250 kcal    ›
│ THU · intervals              2 700 kcal    ›
├──────────────────────────────────────────────
│ MEAL PREP   1 PICK · 2 RECIPES · 3 WEEK ·
│             4 SHOP & COOK                  ›
│ PERIODISATION · LAST 4 WEEKS  carbs vs load ›
```

### PLAN › RACE

```
│ PLAN   TRAINING  FOOD [RACE] SESSIONS
├──────────────────────────────────────────────
│ EVENTS   Trail 42K · 30 Nov · 27 days      ›
│   COURSE · THE DAY · AID STATIONS
│ RACE FUEL   per hour 75 g carbs · 600 ml
│   BEFORE · DURING · AFTER · CARRY · WATCH  ›
│   ASSUMPTIONS · CHANGED SINCE LAST SHOWN
```

### PLAN › SESSIONS and the session page

```
│ PLAN   TRAINING  FOOD  RACE [SESSIONS]
├──────────────────────────────────────────────
│ MON 2  Easy run   8.2 km  46:10  load 62   ›
│        EF ▲ IMPROVED · decoupling ● AS USUAL
│ MON 2  Strength   lower   50 min load 48   ›
│ SAT 31 Long run  24.1 km  2:21   load 188  ›
│ …                              [ LOAD MORE ]
```

```
│ ‹ EASY RUN · MON 2 NOV
│ SUMMARY  8.2 km · 46:10 · 5:38 /km · HR 141
│ load 62 → fed LOAD for Monday
│ MARKS vs your usual easy road runs
│ Efficiency factor  1.62  ▲ IMPROVED
│ HR decoupling      2.1 %  ● AS USUAL
│ Pace     5:38  ● AS USUAL for easy
│ Cadence  172   ● AS USUAL
│ Stride   1.03 m ● USUAL FOR THIS PACE
│ Gut      (fuelled sessions only)
│ ▸ RUN DYNAMICS   heart rate · pace · power ·
│                  cadence · vertical ratio
│ ▸ SPLITS  ▸ ROUTE  ▸ TRAIL (trail runs)
```

The session page keeps today's marks against usual. One new line says which day's LOAD the session fed.

---

## 9. LOG

### LOG tab

```
│ LOG · today 9 entries · 1 gap
│ [ + LOG ]                  GAPS ONLY · EXPAND
├──────────────────────────────────────────────
│ TUE 3 NOV   ■ food ■ water ◧ supps ┈ check-in
│ MON 2 NOV   ■ ■ ■ ■ ✓ closed 22:14
│ SUN 1 NOV   ■ ■ ◧ ■ ✓ closed 21:50
```

- A late log shows as an ordinary entry under its day. FINAL scores don't change.
- `✓ closed` marks a day finished with the evening check-in.

### CLOSE THE DAY

Opens from TODAY's evening banner, the PROVISIONAL button, or a 21:30 notification.

```
│ CLOSE MONDAY                         1 min
├──────────────────────────────────────────────
│ HOW WAS THE DAY?
│ Stress     ○ ○ ● ○ ○      Mood   ○ ○ ○ ● ○
│ Energy     ○ ○ ○ ● ○      Soreness ○ ● ○ ○ ○
├──────────────────────────────────────────────
│ GUT
│ Comfort    ○ ○ ○ ● ○
│ [bloating] [cramps] [reflux] [urgency]
│ [gas] [nausea]
│ Bowel movements today   1 at 08:10  [+ ADD]
├──────────────────────────────────────────────
│ FOOD LOG
│ 2 610 kcal logged · 96 % of your usual
│ ● looks complete      [ ADD MORE ]
├──────────────────────────────────────────────
│ ANYTHING ELSE?    [ SAY IT ]  (free text)
├──────────────────────────────────────────────
│ [ CLOSE THE DAY ]
│ Tomorrow's CHARGE will be final when you wake.
```

### + LOG sheet

Unchanged from the UX review: SAY IT first, a RECENT row, and groups INTAKE · BODY · TRAINING · INTIMACY · OTHER.
The **stool entry** gains the time, so bowel timing works.

---

## 10. LAB

Sub-tabs: **N=1 · SUPPLEMENTS · TESTS · ROUTINE**.

### LAB › N=1

```
│ LAB   [N=1] SUPPLEMENTS  TESTS  ROUTINE
├──────────────────────────────────────────────
│ EXPERIMENTS
│ Magnesium at night · day 18 / 28
│ outcome SLEEP   +6 ± 5 so far  ─────●──┤
│ Late caffeine cut · done · BALANCE +9 ✓   ›
│ [ START AN EXPERIMENT ]  outcome = a domain
├──────────────────────────────────────────────
│ WHAT GOES WITH WHAT          forest rows
│ Alcohol → SLEEP     ──●──┤          −8
│ Long run → RECOVERY ───●─┤          −9
│ Train commute → BALANCE ─●┤         −6
├──────────────────────────────────────────────
│ WHAT CAME BEFORE LOW-CHARGE MORNINGS
│ log-odds rows: late dinner · alcohol · <7 h
├──────────────────────────────────────────────
│ INTERACTIONS · PROTOCOLS                   ›
```

The relationships listed here also feed "what affects this for you" on each domain page. The scorecards have moved to
each score's "?".

### LAB › SUPPLEMENTS

```
│ TODAY        2 of 4 · evening bundle due
│ STACK · EVIDENCE · INTERACTIONS            ›
│ PANTRY · STOCK · RUN-OUT DATES · REORDER   ›
```

Adherence is not scored; it appears in N=1 when it relates to an outcome.

### LAB › TESTS

```
│ DAILY TESTS     grip strength · body check-in
│ SWEAT TEST      last 12 Oct · 1.1 L/h      ›
│ RUNNING FORM    analyse a clip · past      ›
│ MOVEMENT EVALUATION                        ›
```

### LAB › ROUTINE

```
│ THE WEEK BY PLACE · COMMUTE · ACTIVE TRAVEL
│ UNLOGGED VISITS
│ (phone and timing regularity are scored in
│  BALANCE; this page is for exploring)
```

---

## 11. ME

Sub-tabs: **HEALTHSPAN · PERFORMANCE · BODY · HEALTH · BLOODWORK**, with SETTINGS behind ⚙.

### ME › HEALTHSPAN

```
│ ME  [HEALTHSPAN] PERFORMANCE BODY HEALTH BLOODWORK  ⚙
├──────────────────────────────────────────────
│ HEALTHSPAN · 6 of 8 favourable
│           VO2max
│     grip  ╱‾‾‾╲  lipids
│   BP     ⟨  ◆  ⟩   glucose
│     body  ╲___╱  sleep
│           strength
│ (radar; hollow dashed = not assessed)
├──────────────────────────────────────────────
│ BIOLOGICAL AGE   PhenoAge 31.4 · functional ›
│ DIMENSIONS · HISTORY                       ›
```

### ME › PERFORMANCE

Levels and long-term analysis. This was Training › Running and Strength.

```
│ RUNNING   STRONG ▲            [1M 3M 6M 1Y]
│ VOLUME  total · per week · TRAIL/EASY/QUALITY
│ [ROAD | TRAIL]
│ EFFICIENCY FACTOR · HR DECOUPLING · PACE (GAP)
│ CADENCE / STRIDE · GROUND CONTACT · VERT RATIO
│ RECORDS
├──────────────────────────────────────────────
│ STRENGTH  SOLID ●
│ E1RM EVOLUTION · STANDARDS · PR TIMELINE
│ PROJECTED MAX INCREASES · CONSISTENCY · MIX
├──────────────────────────────────────────────
│ FITNESS   VO2max 53 · LT pace 4:32 · HR zones
```

The charts keep today's formats: per-run dots with a median line. Each level opens with one row in the metric-row
format.

### ME › BODY

```
│ WEIGHT   72.4 kg (trend)  ● STABLE −0.1/wk
│ BODY FAT 13.8 %  ● NO CHANGE (> LSC needed)
│ MEASUREMENTS · SCALE READINGS              ›
```

### ME › HEALTH

Health concerns: injuries, recurring pain, symptoms and illness.

```
│ NOW        sore throat · day 2 · caps CHARGE ›
│ NEW, NOT YET A CONCERN · PATTERNS SO FAR
│ TIMELINE · EPISODES · RECURRENCE
│ TRAINING IMPACT · TRAINING AND RECOVERY, SAME DAYS
```

### ME › BLOODWORK

```
│ LAST DRAW 14 Aug · 77 markers · 3 flagged  ›
│ by theme: lipids · glucose · iron · thyroid …
│ each marker: value · range · ▲▼ vs last draw
```

### ⚙ SETTINGS

Unchanged: PROFILE & GOALS · NOTIFICATIONS · CONNECTED SERVICES · TRAINING SETUP.

---

## 12. Where every current card goes

| Current place | Card | New home | Shown as |
|---|---|---|---|
| Status | Hero (condition, HRV vs baseline) | Retired → TODAY | CHARGE dial |
| Status | Body figure + injury flag | TODAY cap banner; ME › HEALTH; LOAD › Regional load | Banner when open |
| Status | NEXT UP | TODAY › TODAY'S PLAN | Last line |
| Status | Training / Nutrition / Health / Lab tiles | TODAY | Domain chips |
| Status | Quick log | TODAY | Same buttons |
| Today | THIS MORNING (HRV, RHR, sleep bands) | RECOVERY, SLEEP | Driver rows; band charts under ▸ |
| Today | TODAY'S RECOVERY | RECOVERY | Domain score |
| Today | READY FOR | TODAY's plan, PLAN › TRAINING | READY mark + demand dots |
| Today | IS TODAY TYPICAL? | TODAY ⚠; RECOVERY ▸ | Flag + strip |
| Today | NUTRITION TODAY | TODAY › TODAY SO FAR | Progress bars, no score |
| Today | DO THE SUMMARIES EARN THEIR PLACE? | Each score's "?" | One line per score |
| Training › Load | TRAINING LOAD, CTL/ATL, TSB | LOAD | TSB driver; charts under ▸ |
| Training › Load | INTENSITY & LOAD DISTRIBUTION, INTENSITY 12 WK | LOAD | Intensity driver; chart ▸ |
| Training › Load | ACTIVITY HEATMAP | LOAD ▸ | Same |
| Training › Load | FITNESS (VO2max, LT) | LOAD fitness line; ME › PERFORMANCE | Trend line + detail |
| Training › Running | All running cards | ME › PERFORMANCE | Unchanged charts |
| Training › Strength | All strength cards | ME › PERFORMANCE | Unchanged charts |
| Training › Sessions | Sessions list, session page | PLAN › SESSIONS | + "fed LOAD for <day>" |
| Training › Injury | Injury | ME › HEALTH; LOAD ▸ | Concern timeline |
| Nutrition › Nutrition | Calories & macros, macros/kg, protein per meal, micronutrients, nutrient breakdown, glucose spiking | FUEL ▸ | Intake drivers + details |
| Nutrition › Nutrition | Energy balance | FUEL energy driver; ME › BODY | Driver + weight trend |
| Nutrition › Hydration | Hydration intelligence, accounting, AI summary | FUEL water driver; ▸ | Driver + detail |
| Nutrition | Caffeine window | FUEL driver (late caffeine); ▸ | Driver |
| Nutrition › Plan | Next three days | PLAN › FOOD | Day cards |
| Health › Cardio | HRV, RHR charts, heart-rate | RECOVERY ▸ | 60-day with normal band |
| Health › Cardio | Physiological stress | BALANCE (day), RECOVERY (night) | Drivers + day chart |
| Health › Sleep | Sleep phase breakdown, hypnogram | SLEEP ▸ | Same |
| Health › Sleep | Circadian alignment | SLEEP timing driver; BALANCE rhythm | Drivers |
| Health › Body | Weight, BF %, measurements | ME › BODY | Rows |
| Health › Concerns | All | ME › HEALTH | Unchanged |
| Health › Wellbeing | Energy, stress, mood, soreness | CLOSE THE DAY input → BALANCE, RECOVERY | Drivers |
| Health › Wellbeing | Digestive pattern (Bristol) | FUEL › gut | Form driver + 14-day ▸ |
| Health › Sexual health | Frequency, occurrences | RECOVERY ▸ | Detail |
| Health › Bloodwork | Labs | ME › BLOODWORK | Rows by theme |
| Lab › N=1 | Correlations, what goes with what, off mornings, experiments, interactions | LAB › N=1 (outcomes are domain scores) | Forest/odds rows |
| Lab › Supplements | Pantry, stock, today | LAB › SUPPLEMENTS; TODAY progress line | Unchanged |
| Lab › Planning | Training block, food plan, meal prep, race fuel | PLAN › TRAINING / FOOD / RACE | Unchanged |
| Lab › Tests | Daily tests, grip, sweat, running form | LAB › TESTS | Unchanged |
| Lab › Routine | Week by place, commute, active travel, unlogged visits | LAB › ROUTINE | Unchanged |
| Lab › Routine | Phone · last 7 days | BALANCE rhythm driver; ▸ | Driver |
| User | Healthspan, aging, levels, block card | ME | Unchanged; block card → PLAN |
| Setup | All | ME › ⚙ | Unchanged |
| Today header | ADD ABOUT LAST EVENING | CLOSE THE DAY | Check-in |

### New things to build

- The CLOSE THE DAY check-in.
- Gut comfort and symptom chips.
- The stool time field.
- The bowel timing chart.
- The ANS state and ANS map.
- The CHARGE sheet.
- The domain page template.
