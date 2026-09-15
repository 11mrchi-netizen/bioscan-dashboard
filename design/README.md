# Handoff: Bioscan mobile app — "Field Terminal" direction

## Overview
A personal-biometrics mobile app (working name Bioscan, Pip-Boy-inspired). It gives the user a
one-glance read on their current physical condition and lets them log food, water, supplements,
training, labs and injuries. This handoff covers the **Field Terminal** visual direction only —
the dark gunmetal/signal-amber theme. A second direction ("Armour Plate", pale ceramic + lime)
was explored and is NOT part of this handoff.

Scope in this bundle:
- **3 candidate opening/launch screens** (`3b`, `3c`, `3d`) — one still to be chosen.
- **10 production screens** (`2a`) — the rest of the app.

## About the Design Files
The files in this bundle are **design references created in HTML** — prototypes that communicate
intended layout, colour, typography and behaviour. They are *not* production code to copy.

The task is to **recreate these designs in the target codebase's existing environment**
(React Native, SwiftUI, Flutter, React web, etc.) using its established component patterns,
navigation library and styling approach. If no codebase exists yet, choose the framework best
suited to the project (these are phone screens at 390 × 844 logical px, so a native or
React Native target is the natural fit) and implement there.

The HTML uses a small in-house component runtime (`support.js`, `<x-dc>`, `{{ }}` holes,
`dc-import`). **Ignore that machinery** — it exists only so the mockups render in a browser.
What matters is the markup inside each phone frame and the exact style values on it.

### How to open the mockups
Open `Field Terminal Mockups.dc.html` in a browser (all three files must sit in the same folder).
It renders a board: newest section at the top holds the three launch candidates, the section below
holds the ten production screens. Each phone frame has a caption under it and each option has a
visible id badge (`3b`, `3c`, `3d`, `2a`).

Reading the source directly is also fine and often faster: every screen is a self-contained
`<div class="ph">` block with all styling inline. Search for the caption text (e.g.
`Status · Supplements`) to jump to a screen.

## Fidelity
**High fidelity.** Colours, type sizes, weights, letter-spacing, borders and spacing are final and
were contrast-checked (all text meets WCAG AA 4.5:1 against its background; the muted ink values in
the token list below are the checked ones — do not dim them further). Recreate pixel-for-pixel
using the codebase's own primitives. The only deliberately unfinished areas are the map render
(a placeholder graphic) and chart data (representative, not real).

What is *not* final: which launch screen wins, and real interaction wiring (the mockups are static).

## Screens / Views

Frame geometry for every screen: **390 × 844** logical px, `display:flex; flex-direction:column`,
`overflow:hidden`.
Vertical stack is always: status bar (44px) → screen header → scrollable content (`flex:1`) →
bottom tab bar (78px, fixed).

### Global chrome

**Status bar** — 44px tall, background `#0e1013`, horizontal padding 22px, space-between.
Left: time `9:41`. Right: `LTE` (11px) + a 22 × 11px battery outline (1.5px border
`rgba(233,237,242,.6)`, radius 2px) with an amber fill inset 2px and `right:7px`.
Type: JetBrains Mono 600 12px, letter-spacing .06em, colour `rgba(233,237,242,.75)`.
In a real app this is the OS status bar — recreate only the dark background behind it.

**Screen header** — background `#0e1013`, padding `18px 22px 14px`, bottom border `2px solid #ffb02e`.
Left column: screen name in JetBrains Mono 700 11px, letter-spacing .26em, colour `#ffb02e`
(e.g. `STATUS`, `MAP`, `LOG`, `SETUP`); under it a context line in JetBrains Mono 500 13px,
letter-spacing .04em, colour `rgba(233,237,242,.75)` (date, time, or record count).
Right: a sync pill — padding `6px 10px`, 1px border `rgba(233,237,242,.2)`, background `#1a1d22`,
a 7px square dot `#7ef2a8` with `box-shadow:0 0 8px #7ef2a8`, label `SYNCED` in JetBrains Mono
600 10px / .12em / `rgba(233,237,242,.78)`. Squared corners everywhere — no radius.

**Bottom tab bar** (`PipNavA.dc.html`) — 78px tall (14px of that is bottom safe-area padding),
background `#0e1013`, top border `2px solid rgba(233,237,242,.12)`, four equal grid columns.
Each tab: centred column, 5px gap, a 21px 24-viewBox stroke icon (stroke-width 1.9, round caps)
above a JetBrains Mono 600 9.5px / .14em label.
- Tabs in order: `STATUS` (pulse line), `MAP` (pin), `LOG` (three lines), `SETUP` (gear).
- Inactive: stroke and label `rgba(233,237,242,.7)`.
- Active: stroke and label `#ffb02e`, plus the tab cell gets
  `border-top:3px solid #ffb02e; margin-top:-2px; background:rgba(255,176,46,.08)`.
- Tab ownership: Status sub-tabs → `STATUS`; Map → `MAP`; Log, the add-entry picker and the entry
  form → `LOG`; Settings → `SETUP`.

**Status sub-tab rail** — on Status screens, directly under the header: a horizontally scrollable
row of segmented chips (`TRAINING`, `FUEL`, `SUPPLEMENTS`, `LABS`, `HEALTH`), JetBrains Mono 600
10px / .14em. Active chip: amber text on `rgba(255,176,46,.1)` with a 1px amber border.
Inactive: `rgba(233,237,242,.78)` on transparent with a 1px `rgba(233,237,242,.16)` border.

### Launch-screen candidates (pick one)

These are three answers to the same question — "how am I doing right now, in one look". All three
sit at the top of the STATUS tab, above the sub-tab rail, and fit on one screen with no scrolling.
All three show the same underlying data: readiness 82, sleep 7 h 20, HRV 64 ms, RHR 49,
fuel 2140/2750 kcal, water 1.9/3.0 L, supplements 2/4, training 3/5 sessions this week,
one open injury flag (right knee, day 4 of 7), next session tempo run 17:30.

#### `3b` — System grid
A 2-column, auto-row grid (12px gap, padding `16px 18px`) of six subsystem tiles, then a
full-width "next up" bar spanning both columns.
- Header right side replaces the sync pill with a readiness pill: `82 READY`, JetBrains Mono
  700 11px / .1em `#ffb02e`, on `rgba(255,176,46,.12)` with a 1px `rgba(255,176,46,.5)` border.
- Tile shell: 1px border `rgba(233,237,242,.14)`, background `#1a1d22`, padding `13px 13px 12px`,
  flex column, `justify-content:space-between`.
- Tile title: JetBrains Mono 700 10px / .2em, coloured per system (see tokens).
- Big values: Saira Condensed 700 30px, line-height 1; units in JetBrains Mono 500 11.5px
  `rgba(233,237,242,.78)`.
- FUEL tile: `2140 kcal`, a 6px progress bar (78% amber on `rgba(233,237,242,.12)`),
  footnote `610 LEFT · P 148 G`.
- WATER tile: `1.9 / 3.0 L`, six 14px segment blocks (4 solid `#6fd8ff`, 1 at 45% opacity,
  1 empty with a `rgba(233,237,242,.22)` border), footnote `LAST 09:05`.
- TRAIN tile: `Tempo run` (Saira 600 15px), `TODAY 17:30 · 8 KM`, and a 7-bar 26px-tall mini
  histogram of the week (today at 100% solid `#7ef2a8`, past days at 50% alpha, rest days a 1px
  top rule).
- SUPP tile: `2 / 4 taken`, `Due: magnesium, omega-3`, footnote `EVENING DOSE`.
- LABS tile: `All in range`, `DRAWN 08 SEP`, `T 21.4 ▲ · CRP 0.6 ▼` in `#7ef2a8`.
- HEALTH tile (flagged state): border `rgba(255,107,74,.5)`, background `rgba(255,107,74,.1)`,
  `Right knee`, `DAY 4 · FLAGGED` in `#ff6b4a`, `Auto-clears in 3 days`.
- Next-up bar: spans 2 columns, background `#0e1013`, 1px `rgba(233,237,242,.16)` border,
  `NEXT UP` label + `17:30 · Riverside loop, 4:45 /km`, amber `›` chevron at 22px.
- Each tile is a tap target that navigates to its Status sub-tab (LABS → Labs, HEALTH → Injuries).

#### `3c` — Day line
A four-cell vitals strip inside the header, then today's events as a single ordered list.
- Vitals strip: 4-column grid with 1px gaps showing through a `rgba(233,237,242,.14)` background
  (that's how the hairlines are made), outer 1px border of the same colour, cells `#14161a`,
  padding `9px 8px`. Cell label JetBrains Mono 600 9.5px / .14em `rgba(233,237,242,.8)`; value
  Saira Condensed 700 21px. `READY 82` amber, `SLEEP 7:20` default ink, `HRV 64` green,
  `RHR 49` default ink.
- Section rule: `TODAY` in amber mono 700 10px / .2em, a 1px flexible divider, then
  `3 DONE · 4 DUE` right-aligned in `rgba(233,237,242,.8)`.
- Event rows: 12px gap, 1px bottom border `rgba(233,237,242,.08)`. Time column 44px wide,
  JetBrains Mono 11.5px. Then a 12px square status box: **done** = filled `#7ef2a8` with a 1.5px
  border of the same; **due** = 1.5px amber border, no fill; **later** = 1.5px
  `rgba(233,237,242,.4)` border. Label Saira 500 14px (done rows drop to
  `rgba(233,237,242,.8)`); due rows are Saira 600 14.5px with a 12px sub-line and get a
  `rgba(255,176,46,.07)` row tint.
- Rows, in order: 07:20 Breakfast 620 kcal (done) · 07:40 AM stack (done) · 09:05 Water 250 ml
  (done) · 13:00 Lunch 900 kcal budget, "60 g protein to stay on pace" (due, tinted) ·
  17:30 Tempo run 8 km, "Riverside loop · 4:45 /km target" (due, chevron) · 21:30 PM stack (later).
- Bottom flag panel, pushed down with `margin-top:auto`: `border-left:3px solid #ff6b4a`,
  background `rgba(255,107,74,.1)`, padding `12px 14px`, `OPEN FLAG · DAY 4` +
  "Right knee — keep tempo pace conservative. Auto-clears in 3 days."

#### `3d` — Body console (most visual)
A schematic human figure carries condition; three dial gauges carry the numbers.
- Figure field: `flex:1`, background `#0e1013` with a 26 × 26px amber grid
  (`linear-gradient(rgba(255,176,46,.07) 1px, transparent 1px)` in both axes), centred content,
  1px bottom border.
- Top-left overlay `CONDITION` (mono 700 10px / .2em). Top-right: `82` in Saira Condensed 700 34px
  amber + `READY` in mono 600 10px / .14em.
- The figure is an inline SVG, 330 × 254 rendered, `viewBox="0 0 260 200"`: head circle, neck,
  torso polygon, two arms, two legs, all stroked `#ffb02e` at 2.4px with round caps/joins at
  0.9 opacity, plus three faint `rgba(126,242,168,.55)` 1.2px rib lines across the torso.
- Condition pins are part of the same SVG so leader lines always terminate on their labels:
  - **Right knee (flagged)**: 13r circle, fill `rgba(255,107,74,.18)`, 2px `#ff6b4a` stroke, with a
    4.4r solid dot inside; a 1.4px leader runs right to three stacked labels `R KNEE` (amber-red,
    mono 700 9px), `DAY 4`, `EASE OFF` (mono 500 9.5px, default ink).
  - **Cardiac / "engine" (nominal)**: 9r circle, fill `rgba(126,242,168,.14)`, 1.8px `#7ef2a8`
    stroke; leader runs left to `ENGINE` (green), `HRV 64`, `RHR 49` — three lines, right-aligned.
- Bottom overlay row: `SLEEP 7:20` left, a 1px divider, `1 FLAG · 5 SYSTEMS OK` right in `#7ef2a8`.
- Dial row: 3-column grid, 1px gaps showing a `rgba(233,237,242,.14)` background as hairlines,
  cells `#14161a`, padding `14px 10px`. Each dial is a 66px circle drawn with
  `conic-gradient(<accent> 0 <pct>%, rgba(233,237,242,.14) <pct>% 100%)` and a 50px `#14161a`
  inner disc holding a Saira Condensed 700 17px value over a mono 500 8.5px unit; the system label
  sits under the dial in mono 700 9.5px / .16em.
  Values: FUEL `2140 / KCAL` at 78% amber · WATER `1.9 / LITRES` at 63% `#6fd8ff` ·
  SUPP `2/4 / TAKEN` at 50% `#7ef2a8`.
- Next-up bar: background `#1a1d22`, padding `14px 18px`, `NEXT UP · 17:30` +
  "Tempo run · 8 km, 4:45 /km", amber `›`.

**Note on 3d:** the figure is the only place injuries appear on the launch screen, and it scales —
it can later shade muscle groups by training load. If the codebase can't render inline SVG easily,
the figure is the one element worth exporting as a vector asset with anchor points for the pins.

### Production screens (`2a`)

All ten use the global chrome described above.

1. **Status · Training** — merged strength + endurance feed with explicit discipline labels on
   every row (so a user never has to guess which log a session came from). Week volume summary at
   top, then session rows with date, type label, headline metric and a chevron.
2. **Status · Nutrition & hydration** — calorie ring/bar against target, macro split
   (protein / carbs / fat) with per-macro bars, water total with segment blocks, then the day's
   meal entries in time order.
3. **Status · Supplements** — today's stack grouped by dose window (AM / PM), each item a row with
   a square check control, name, dose, and taken/due state; adherence count in the header line.
4. **Status · Labs** — latest panel with draw date, each marker as a row: name, value + unit,
   reference range, and an in/out-of-range indicator (green `▲`/`▼` for direction, amber-red for
   out of range). Historical panels listed below.
5. **Status · Injuries & illness** — open flags first, then resolved history. Each flag carries the
   body region, day count, and the **7-day auto-clear rule**: a flag raised and not re-reported
   clears itself on day 7, shown as "Auto-clears in N days". Resolved entries are dimmed.
6. **Map · next session** — deliberately shows **only the next session's route**, never a history of
   routes. Route graphic fills the content area; an overlay card gives session name, distance,
   target pace and start time.
7. **Log · unified feed** — one reverse-chronological stream of every entry type (food, water,
   supplement, training, lab, flag), day-grouped with a mono date rule. Each row: time in the 44px
   mono column, a type label chip, the entry headline in Saira 500 14px, and the key metric
   right-aligned in Saira Condensed. The persistent `+` sits bottom-right above the tab bar.
   This is where a saved entry lands, at the top of today's group.
8. **Log · add-entry type picker** — reached from the persistent `+`. A grid of entry types
   (food, water, supplement, training, lab result, injury/symptom), each a bordered tile with an
   icon and a mono label. Tapping one opens the type-specific form.
9. **Log · type-specific form** (food shown) — field rows with mono labels above squared inputs;
   amber focus border; a primary amber `SAVE` action and a ghost `CANCEL`. Numeric fields use
   Saira Condensed for the value so entered numbers match display type elsewhere.
10. **Settings** — grouped rows (profile, units, targets, integrations, data export), each a
   label + current value + chevron, with section headers in mono 700 10px / .2em amber.

## Interactions & Behavior

- **Bottom tabs** — switch top-level section; state persists per tab (a returning user lands back
  on the sub-tab they left).
- **Status sub-tab rail** — horizontal scroll, tap to switch; the rail keeps its scroll position.
  The chosen launch screen sits above the rail as the default Status view.
- **Launch-screen tiles / rows** — every tile in `3b`, every due row in `3c`, and every dial and
  pin in `3d` is a tap target that deep-links to the matching Status sub-tab (or Map, for the
  next-up bar).
- **Persistent `+`** — opens the type picker as a sheet or pushed screen; picking a type pushes the
  form; save returns to Log with the new entry at the top of the feed.
- **Injury auto-clear** — a flag with no new report for 7 days transitions to resolved
  automatically; the countdown ("Auto-clears in 3 days") must be computed from the last report
  date, not stored.
- **Supplement check** — tapping the square control toggles taken/due immediately (optimistic),
  and updates the adherence count in the header and the SUPP figures on the launch screen.
- **States to design for beyond these mocks**: empty (no entries yet today), loading (the sync pill
  becomes a spinner/`SYNCING` state), offline (`SYNCED` → `OFFLINE`, dot goes amber), and save
  failure on the form (inline error under the offending field, mono 10px, `#ff6b4a`).
- **Motion** — the design is deliberately mechanical: 120–160ms linear or ease-out for state
  changes, no spring, no scale bounce. Tab changes are instant cross-fades at most.
- **Responsive** — single-column phone layout only. Content areas scroll; the header and tab bar
  are fixed. Respect bottom safe-area inset (the tab bar's 14px bottom padding stands in for it).

## State Management
- `activeTab`: `status | map | log | setup`
- `activeStatusSubTab`: `training | fuel | supplements | labs | health`
- `today`: readiness score, sleep, HRV, RHR, fuel totals + target, water total + target,
  supplement doses (id, name, window, taken flag), training sessions this week
- `nextSession`: name, discipline, start time, distance, target pace, route
- `flags[]`: region, first reported, last reported, severity, note (auto-clear derived)
- `labPanels[]`: draw date, markers (name, value, unit, range)
- `logEntries[]`: type, timestamp, payload — one feed, filtered per view
- `addEntry`: picker open, selected type, form values, validation errors, saving flag
- `sync`: `synced | syncing | offline`, last sync timestamp

## Design Tokens

Colours
| Token | Hex | Use |
|---|---|---|
| Ground | `#14161a` | screen background |
| Panel / chrome | `#0e1013` | header, tab bar, figure field, inset panels |
| Raised surface | `#1a1d22` | cards, tiles, next-up bar |
| Ink | `#e9edf2` | primary values and body copy |
| Ink muted | `rgba(233,237,242,.78)` – `rgba(233,237,242,.82)` | labels, units, secondary copy (AA-checked — do not go dimmer for text) |
| Hairline | `rgba(233,237,242,.14)` | borders, grid gaps |
| Hairline faint | `rgba(233,237,242,.08)` | list row dividers |
| Track | `rgba(233,237,242,.12)` | progress-bar background |
| Signal amber | `#ffb02e` | primary accent, active state, fuel |
| Green | `#7ef2a8` | nominal / done / food, drink, supplements |
| Cyan | `#6fd8ff` | labs (blood work) only — water moved to Green 2026-09-15 |
| Alert | `#ff6b4a` | flags, out-of-range, errors — not reused for Red below |
| Deep blue | `#4a5fd9` | log category: sleep |
| Orange | `#ff8040` | log category: activity (runs, strength, ...) |
| Sand | `#c9a876` | log category: stool |
| Azure | `#3fa9e8` | log category: wellness |
| Red | `#e5484d` | log category: encounter, arousal |
| Magenta | `#ff4cd6` | Map tab GPX route end-marker only — not a log category |

The five "log category" rows (added 2026-09-15, direct user request) exist so Log-feed entry
chips are tellable apart by hue, not just by their text label — each is a distinct color from its
warm/cool neighbors above.

Typography
- **JetBrains Mono** — all labels, times, units, tab labels, small data. Weights 500/600/700.
  Sizes 10 / 11 / 11.5 / 12 / 12.5 / 13 / 13.5 / 14.5px. Letter-spacing .1em–.26em on uppercase
  labels. (Bumped up one step from the original mockup scale on 2026-09-15 per direct user
  request — "bigger font, only text" — since real-device legibility mattered more than matching
  the mockup's literal pixel values; large numerics were left alone, see below.)
- **Saira** — body copy and item names. Weights 400/500/600. Sizes 13.5 / 14 / 14.5 / 15.5 / 16 /
  16.5 / 17 / 17.5px. Same 2026-09-15 bump as JetBrains Mono, same reasoning.
- **Saira Condensed** — large numerics only. Weight 700. Sizes 17 / 21 / 30 / 34 / 58px,
  line-height .9–1. Deliberately NOT bumped — the request was for text specifically, "numbers are
  big enough" already at this scale.
- Minimum text size anywhere is 10px (unit labels inside dials only); nothing interactive is
  labelled below 11px.

Spacing — 4px base. Used steps: 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 22px.
Screen horizontal padding is 22px (18px on grid-based screens).

Radius — **0 everywhere**, except the 2px battery outline and the circular dials. This is a
deliberate machined-instrument look; do not let a component library's default radius creep in.

Shadows — none. The only glow is `box-shadow:0 0 8px #7ef2a8` on the sync dot and the same
treatment on any live indicator.

Bar/segment geometry — progress bars 6–8px tall, square ends, no radius. Segment blocks are equal
flex children with a 3–4px gap.

## Assets
No external image assets. Every icon is an inline SVG drawn in the mockups (tab icons: pulse line,
map pin, list, gear; plus the body schematic in `3d`). Fonts are Google Fonts —
JetBrains Mono, Saira, Saira Condensed. Substitute the codebase's licensed equivalents if it
already ships a mono and a condensed grotesque; otherwise bundle these three.

The map screen uses a placeholder route graphic — replace with the real map provider
(the design assumes a dark tile style with an amber route stroke).

## Files
- `Field Terminal Mockups.dc.html` — all mockups: launch candidates `3b`/`3c`/`3d`, then the
  ten production screens under `2a`.
- `PipNavA.dc.html` — the bottom tab bar component, including its active-state logic.
- `support.js` — the runtime that lets the two files above render in a browser. Not part of the
  design; do not port it.

Open the first file in a browser with all three in the same folder.
