# Field Terminal — Visual Contract (v1 "new look")

**Status:** Design authority for all UI
**Version:** 2.0 (supersedes 1.0, the emerald Futuristic Material direction)
**Date:** 2026-11-06
**Design handoff:** `design/tokens/` (`README.md`, `tokens.css`, `tokens.json`, `backgrounds.css`, `components.css`, `gauge.html`)
**Canvas boards:** 14 · Components, 17 · Metric colors (v1), 18–22 · the five domain detail pages, 23 · Design tokens

## 1. Purpose

This document defines the visual language for Field Terminal and the rules for extending it.

v1 is a dark, glassmorphic health instrument. **One colour hierarchy drives everything:**

- **Background = domain.** A soft wash of the domain hue behind the page (Sleep violet, Cardio aqua, Load amber, Fuel lime,
  Balance rose).
- **Surface = neutral.** Faint-platinum frosted glass, never tinted by a domain or metric.
- **Ink = metric.** Every metric keeps one fixed hue app-wide (heart red, HRV pink, SpO₂ blue, effort zones blue→red …).

A page says *which domain* by its wash and *which metric* by its chart colour; the glass stays out of the way.

> **Field Terminal should feel like an instrument reading the user, not a database displaying the user's data.**

This is a visual and interaction contract, not an information-architecture replacement.

## 2. Source-of-truth hierarchy

1. **Product/data truth** — what the app actually has and what a metric means.
2. **Linear roadmap** — screen ownership, information architecture, acceptance criteria, and scope boundaries.
3. **This contract** — visual language, component behavior, tokens, presentation rules, and accessibility.
4. **Existing implementation** — current behavior and compatibility constraints.
5. **Legacy visual styling** — only where needed during migration.

Never invent a metric, imply unsupported precision, or change the meaning of a value to make a visual look better.

## 3. Visual principles

### Instrument, not dashboard
Every visible element should answer at least one: What is the current state? What changed? Is it within the expected/personal range? How trustworthy is the value? Where did it come from? What should the user inspect next?

### Signal over decoration
Color, glow, rings, borders, and motion are communication tools. Do not add decorative gradients, meaningless charts, glow without a semantic reason, or color only to differentiate cards.

### Hierarchy before density
Default analytical reading order: **TITLE → CONTEXT → PRIMARY STATE/METRIC → SUPPORTING SIGNALS → DETAIL**.

### Material creates depth
Use the stack **ground → domain wash → neutral glass → hairline → metric ink**. Depth comes from the glass gradient, the
hairline and its inset top highlight, and spacing; never from tinting a card.

### Clinical + futuristic + premium
The character should feel closer to a high-end biometric/medical instrument than cyberpunk. The holographic spectrum is the
one exuberant element, and it is reserved for CHARGE.

## 4. Color system

The token source of truth is `design/tokens/tokens.css` (and the same values in `tokens.json`). Android mirrors it one to
one in `ui/theme/FuturisticMaterialTokens.kt`: surfaces at the top level, then `Domain`, `Metric`, `Zone` and the
semantic `Ok` / `Warn` / `Bad`.

### Surfaces

| Token | Android | Hex | Role |
|---|---|---|---|
| `--ft-bg` | `Base` | `#070A0D` | Page ground |
| `--ft-fg` | `TextPrimary` | `#EAF0F2` | Primary text, values |
| `--ft-muted` | `TextSecondary` | `#8793A2` | Labels, supporting text |
| — | `TextMuted` | `#6C7786` | Low-emphasis text (the lightest step that still reads on the ground) |
| `--ft-dim` | `Dim` | `#55606E` | Non-text marks only (off ticks, idle tracks): below 4.5:1, never for text |
| `--ft-line` | `Line` | white 10% | Hairlines, control borders |
| `--ft-glass-border` | `GlassBorder` | white 12% | Card and tile borders |
| — | `Surface`, `Elevated` | `#0E1317`, `#151B21` | Solid fallbacks for sheets and dialogs |

### Domain framing hues (`FT.Domain`)

Background wash and hero gauge only. Never a metric, never a card.

| Domain | Hex | Pages |
|---|---|---|
| Sleep | `#8B7CFF` | SLEEP score and Sleep page |
| Cardio (recovery) | `#2FD6C3` | RECOVERY score, Heart, Sexual health |
| Load | `#FF8A5B` | LOAD score, Training load, Injury, Performance and sport pages |
| Fuel | `#8CE06B` | FUEL score, Nutrition, Hydration, Digestion, Nutrient breakdown |
| Balance | `#FF6EC7` | BALANCE score, Wellbeing |
| CHARGE | `#6D5EFC → #19C7E0 → #5AF0C8 → #C6F06A → #FF6EC7` | The holographic composite spectrum: CHARGE only |

Each domain gauge sweeps between two stops (`FT.Domain.stops`): Sleep `#6D5EFC→#B48CFF`, Recovery `#19C7E0→#5AF0C8`,
Load `#FF6B4D→#FFB15A`, Fuel `#4FD67A→#C6F06A`, Balance `#FF6EC7→#C08BFF`.

### Metric colours (`FT.Metric`)

Every metric has one fixed hue, the same on every chart, row dot, legend and gauge that shows it. The full dictionary is
`--m-*` in `tokens.css` (about 60 entries: cardio, sleep and stages, body, balance, load and training, fuel, digestion,
routine). Examples: heart rate `#FF4D5E`, resting HR `#FF5D8A`, HRV `#FF6EC7`, SpO₂ `#3A8DFF`, breathing `#2FD0D0`,
steps `#46C8A0`, sleep duration `#4A7DFF`, stages awake / REM / light / deep `#FFB04D` / `#9B6CFF` / `#4A7DFF` / `#2342C9`,
stress `#FF6B5B`, calories `#8CE06B`, protein `#FF4D6A`, carbs `#FF9A3D`, fat `#FFD23A`, hydration `#3AA8FF`.

Intentional shares: arousal = HRV, sodium = weight, sleeping HR = heart rate. Lab markers keep their own per-marker themes
(`domain/LabMarkerThemes.kt`). A chart that names no metric draws in the neutral `Metric.WeightEma` (`#C7D0E0`), never in
a status colour.

### Ordered ramps (`FT.Zone`)

- Heart-rate zones 1–5: `#6F86B0`, `#3AA0FF`, `#35C77A`, `#FFB03A`, `#FF4D4D`.
- Stress: calm `#36D07A`, moderate `#FFB03A`, high `#FF4D4D`.

### Semantic (status only)

| Token | Hex | Meaning |
|---|---|---|
| `--ok` (`Ok`) | `#58E39A` | Good / done / in range |
| `--warn` (`Warn`) | `#FFC24B` | Attention |
| `--bad` (`Bad`) | `#FF5D6C` | Error / out of range / destructive |

Status colours say how something is; they never identify a metric or a domain, and are always paired with a word, glyph
or shape. The v1.0 names (`Emerald`, `Warning`, `Critical`, `Info`, `DomainFuel`, `Category.*` …) remain in code as aliases
onto the v1 values so the whole app moved at once; new code uses `Domain`, `Metric`, `Zone`, `Ok` / `Warn` / `Bad`.

### Platinum (primary action surface)

`--ft-platinum`: a vertical gradient `#FFFFFF → #D6DDE8` with dark ink `#0B0F14` (`FT.Platinum`, `FT.PlatinumInk`;
`FT.PlatinumSolid` where an API takes one colour). It marks the primary action and the selected state of chips, segments
and switches. Selection is platinum app-wide, never a domain or metric tint. A selected row that holds body text uses
`SelectedGlass` (white 10%) with a `SelectedBorder` hairline instead, like the dock's selected tab.

### Glass

- `.ft-card` / `FTCard`, `Section`: gradient white 8.5% → 2.8% at 60%, 1 dp border white 12%, inset 1 dp top highlight
  white 15%, radius 18 (hero card 24).
- `.ft-tile` / tiles and link rows: the same recipe at radius 16.
- Holographic hairline (`ftHoloHairline`): CHARGE and premium surfaces only.
- Backdrop blur is specified at 18 px (cards) and 14 px (tiles). Compose cannot blur what is behind a surface, so the
  Android build draws the gradient, hairline and highlight without it; over the near-black ground they carry the look.

### Domain wash

`.ft-wash` / `Modifier.ftDomainWash(hue)`: three elliptical radial glows of the domain hue behind the page: 540×440 at
top centre (34%), 440×400 off the right edge (17%), 480×440 off the bottom left (20%). TODAY and the CHARGE overlay use
`ftChargeWash()` (deep teal from the top, violet from the right). Pages that belong to no domain (PLAN, LOG, LAB, ME,
Setup, sheets) keep the plain ground.

### Adding a new color

Do not add an isolated hex value. A new metric gets one `--m-*` entry in `tokens.css` / `tokens.json` and the matching
`FT.Metric` value; reuse an existing hue only as a documented intentional share. Never introduce a colour because two
adjacent cards need variety, and never tint glass to tell cards apart.

## 5. Typography system

### Font roles

- **Nunito** = numbers and headline metrics: hero values, gauge centres, stat tiles, row values (`FTType.DisplayMetric`,
  `MetricMedium`, `StatValue`, `Value`, `Telemetry`, `HeroHeading`).
- **Inter** = interface language: titles, body copy, buttons, row titles.
- **IBM Plex Mono** = micro-labels: units, timestamps, chips, segments, dock tabs, badges, card eyebrows (`Label`,
  `LabelCaps`, `MonoCaption`, `Micro`).

Do not make whole screens monospace. Fonts are bundled in `res/font/` with their OFL licences in
`android-app/licenses/fonts/`.

### Implemented roles (`FTType`)

Screens use these named roles; hand-written `TextStyle(...)` is not used. A new size means a new role here and in
`FTType.kt`, not a one-off.

| Role | Font | Weight | Size | Use |
|---|---|---|---:|---|
| `DisplayMetric` | Nunito | ExtraBold | 40 | The one hero value (gauge centres scale it to the gauge) |
| `MetricMedium` | Nunito | ExtraBold | 26 | Supporting levels beside a hero, mid-size gauges |
| `StatValue` | Nunito | ExtraBold | 30 | Stat tile value (`.ft-stat__v`) |
| `Value` | Nunito | Bold | 15 | Row values |
| `Telemetry` | Nunito | ExtraBold | 14 | Emphasised data, small gauge centres |
| `HeroHeading` | Nunito | Black | 18 | The state word under a hero gauge (PRIMED) |
| `PageTitle` | Inter | Bold | 24 | Page titles |
| `SectionTitle` | Inter | SemiBold | 20 | Section / sheet / back-header titles |
| `CardTitle` | Inter | Bold | 13 | `FTCard` title bar |
| `RowTitle` | Inter | SemiBold | 15 | List-row titles |
| `Body` / `BodySmall` | Inter | Regular | 15 / 13 | Copy |
| `Caption` / `CaptionStrong` | Inter | Regular / SemiBold | 12 | Secondary notes |
| `ButtonLabel` | Inter | SemiBold (Bold on platinum) | 13 | Button labels, add-entry type tiles |
| `Label` / `LabelCaps` | IBM Plex Mono | Medium | 11 | Labels; `LabelCaps` adds 0.14em tracking for chips, segments, dock, badges, eyebrows |
| `MonoCaption` | IBM Plex Mono | Regular | 11 | Timestamps, metadata |
| `Micro` | IBM Plex Mono | Regular | 10 | Smallest allowed; never for a primary metric |

The reference sets micro-labels at 9–10 px; the app keeps 10–11 sp, the owner's earlier request for larger text on device.

### Date and time entry

Date / time fields open a themed bottom sheet (`DateField` / `DateTimeField` in `DatePickers.kt`): a month calendar with month and year navigation, and a 12-hour time panel with an AM/PM toggle. No platform dialogs, no 24-hour clock or inner ring; nothing is written back until SET.

Primary metrics should be visually dominant. Preferred pattern:

`RECOVERY`
`94%`
`HRV 58 ms · RHR 49 bpm · SLEEP 7.7 h`

## 6. Spacing and geometry

### Spacing
Use a 4 px base unit: **4 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 48**.

### Radius

| Radius | Use |
|---|---|
| 9 | A selected segment inside `.ft-seg` |
| 12 | Buttons, the segmented track, non-pill selectors (`RadiusSmall`) |
| 16 | Tiles, link rows, dock tabs (`RadiusModule`) |
| 18 | Cards and sections (`RadiusCard`) |
| 24 | Hero cards, sheet tops (`RadiusCardLarge`) |
| 26 | The dock (`RadiusDock`) |
| FULL | Chips, badges, switches, search pill, dots |

### Borders
Default: 1 dp `Line` (white 10%) on controls, `GlassBorder` (white 12%) on glass. Coloured borders only for status (ok /
bad badges, destructive buttons).

## 7. Material and elevation

- **Ground**: `#070A0D` with the page's domain wash.
- **Glass**: the card / tile recipe above. The hero card uses a stronger glass (white 14% → 5%), not a colour.
- **Floating**: the dock, `rgba(20,25,30,.72)` with a hairline (blur 20 px in the reference).
- **Modal**: sheets and dialogs on the solid `Surface`.

### Glow
Glow is a reading, not decoration: the gauge's radar sweep and its cap, and the metric dot (`.ft-row__dot`). Nothing else
glows.

## 8. Layout and composition

### Mobile reference
The primary QA frame is **390 × 844**. Review key screens at this size before generalizing.

### Default analytical structure
1. Compact screen header.
2. Dominant primary state or visualization.
3. One or more analytical cards.
4. Supporting details.
5. Floating navigation where applicable.

### Cards as instruments
Default anatomy: **TITLE → CONTEXT → STATE/METRIC → VISUALIZATION → SUPPORTING METADATA**.

Examples:

`TRAINING LOAD` → `CUMULATIVE INTENSITY · 7 DAY TREND` → `OPTIMAL` → trend

`FUEL` → `TODAY · TARGET VS ACTUAL` → macro hierarchy

`LAB DIAGNOSTICS` → `LATEST PANEL · 14 MARKERS` → sparse/distribution-aware view

### Card emphasis (A3, 2026-10; v1)
`FTCard` has two emphasis levels. **Hero**: the one lead card of a tab, stronger glass and radius 24, title in primary
text. **Supporting**: every other card, default glass, title in text-secondary. Card titles are never coloured. A long
tab opens with a **glance strip** (`FTGlanceStrip`): one chip per card below, carrying that card's state pill or
headline, tapping scrolls to the card.

### One pill per card (A8, 2026-10, app restructure)
A card shows one state pill: the direction or verdict. How much data backs it (the DATA: GOOD / PARTIAL / THIN chip under
the pill) is a line in the card's "?" (`FTCard(dataQuality = …)`), not a second pill. Where the pill's reading needs
explaining (THIS MORNING's ranges, how many mornings fell inside, how well the band held; IS TODAY TYPICAL?'s drivers),
the pill is tappable (`FTStatePill(onClick = …)`, marked with "›") and opens the shared `InfoDialog`; the sentences do
not sit beside it.

### Movements (A9, 2026-10, app restructure)
A contributor's or a composite's **movement** may be tinted by its direction: a faint green fill (`color/primary` at low
alpha) when it supports recovery or readiness, a faint red fill (`color/state/critical` at low alpha) when it holds it
back, no tint when neutral (`FTMovementChip`). This amends the rule that red, amber and green are status only: a
movement counts as status here. The arrow and the word always say it too (↑ SUPPORTS, ↓ HOLDS BACK, = NEUTRAL; ABOVE /
BELOW / AS EXPECTED), so colour is never the only signal. Used on TODAY'S RECOVERY and READY FOR.

### Pushed screens (A4, 2026-10)
- Every pushed screen shows a title and a context line (`TileHeader`), with the section's domain dot when it belongs to one.
- On screens with a sub-tab rail, the header and the rail stay pinned and only the content scrolls.
- The dock keeps the tab you came from lit while you are on a pushed screen (the nearest top-level route in the back
  stack), so the dock always says where you are.
- The Status hub may scroll, provided the first 390×844 frame still holds the hero and all four system tiles.

## 9. Component design-system contract

Build the system in this order:

1. **Foundations:** color, spacing, radius, typography, opacity, blur/elevation, icon sizing, motion.
2. **Atoms:** icons, telemetry labels, status pills, provenance labels, metric values, indicators, selectors.
3. **Molecules:** metric headers, analytical-card headers, metric rows, confidence blocks, range/baseline indicators, chart headers, nav items.
4. **Organisms:** analytical cards, body/system tiles, chart modules, biometric focal visualizations, session summaries.
5. **Screens:** compose from the above primitives.

### Naming
Use deterministic semantic names such as `Color/Metric/HeartRate`, `Color/Domain/Sleep`, `Color/State/Warn`, `Surface/Glass/Default`, `Radius/Card`, `Spacing/MD`, `Typography/Metric/Display`, and `Component/AnalyticalCard`.

Avoid names such as `Green2`, `CardNew`, or `CoolBox`.

### Variants
Variants represent behavior or semantic state, not arbitrary color options.

Good: `State=Default | Active | Warning | Critical | Disabled`.

Bad: `Color=Green | Blue | Red | Purple | Orange` when those colors do not represent different meanings.

Use instance swap for icons instead of icon-specific variant matrices.

### Buttons (A2, 2026-10; v1)
`FTButton`: `Emphasis = Primary | Secondary | Tertiary | Destructive`, `State = Default | Pressed | Focused | Disabled | Busy`.

| Emphasis | Treatment (`components.css`) | Use |
|---|---|---|
| Primary | `.ft-btn--primary`: platinum fill, dark ink, bold | The one main action of a screen or sheet (SAVE). At most one per screen or sheet. |
| Secondary | `.ft-btn`: white 5% fill, `Line` hairline, light ink | Alternatives and navigation-like actions. The default. |
| Tertiary | `.ft-btn--ghost`: label only, `#E4E9F1` | Low-emphasis inline actions. |
| Destructive | Bad outline and label | Deleting or overwriting. Always followed by an inline confirm (CANCEL / filled bad CONFIRM). |

Labels use `ButtonLabel` (Inter). Radius 12, minimum 48 dp touch target. Disabled = muted border and label.

### Controls (v1)

| Component | Android | Spec |
|---|---|---|
| Segmented control | `SegmentedToggle`, `PeriodToggle` | `.ft-seg`: white 5% track, hairline, 3 dp padding; selected segment platinum (radius 9) with dark ink. `accentColor` is ignored. |
| Chips | `SubTabChip`, `SubTabRow`, `DetailKit.Chip` | `.ft-chip`: mono pill, white 3% fill, hairline; selected = platinum, dark ink, bold. |
| Switch | `FTSwitch` | `.ft-switch`: 48×27 pill; off = white 6% with hairline; on = platinum with the white knob to the right. |
| Badge | `FTBadge` | `.ft-badge`: mono pill. `Primed` = holographic CHARGE state; `Ok` / `Bad` = status ink with a 40% border. |
| Dock | `FieldBottomBar` | `.ft-dock`: floating pill radius 26, dark translucent fill, hairline; selected tab = light ink on white 10%, others muted. No per-tab accent. |
| Metric dot | `FTMetricDot` | `.ft-row__dot`: 9 dp dot in the metric hue with its glow. |
| Gauge | `FTGauge`, `ScoreRing` | See section 10. |

Local chip variants in screens follow the same rule: selected = platinum (`PlatinumSolid` + `PlatinumInk`), or
`SelectedGlass` + `SelectedBorder` for a row holding body text.

## 10. Data visualization contract

### The radial gauge (v1)
Every score is a tick gauge (`FTGauge`, the `ftGauge` logic of `design/tokens/gauge.html`): N ticks around the ring (96 on
a hero gauge, 56 on a ring, 40 on a small one), the lit ones coloured along the palette from first to last stop, a
glowing cap on the last lit tick, and a blurred radar sweep inside that brightens towards the value. Off ticks are white 8%.

- One lap is 0–100, for CHARGE and every domain alike. Above 100 the outer lap stays fully lit and a second lap of ticks
  runs just inside it (radius 28–34 of the 100 viewBox) from 12 o'clock on the same scale, brighter: 150 is one and a half
  laps, SUPERCHARGED (125) a quarter of the second lap. The cap sits at the end of the second lap.
- CHARGE sweeps the holographic spectrum with a `#3FE0D0` radar; a domain sweeps its own two stops with its domain hue.
- The centre holds the number (Nunito), an optional mono label (CHARGE, RECOVERY) and the direction glyph.
- The band is said in words under the hero gauge (`GaugeStateWord`): PRIMED and SUPERCHARGED in CHARGE's holographic
  ink, LOW in bad, BELOW NORMAL in warn. The sweep is never recoloured by the band.
- A provisional or partial reading draws its lit ticks at 55%; no reading draws the bare track.
- The radar blur needs Android 12; older versions show the unblurred, masked sweep.

### Chart ink
- A chart is drawn in its metric's hue (`FT.Metric`); bars use the hue solid at the top fading to 25% at the base, area
  sparklines fade top-down, the end point is a dot.
- Sleep stages use `StageAwake/Rem/Light/Deep`; HR zones `Zone.Hr`; stress lanes `Zone.Stress*`; macros and water the
  fuel metric hues (`NutrientColor`).
- Multi-series charts carry a legend; colour is never the only signal.

### Chart forms

- **Temporal data** → line/area trends when enough observations exist.
- **Bounded / target-vs-actual** → rings or gauges where useful for glance reading.
- **Sparse measurements** → dot plots or distribution-oriented visuals.
- **Comparable dimensions** → small multiples rather than a single composite score.
- **Single observations** → large metric plus context; no meaningless trend chart.
- **Route/spatial data** → map for spatial representation.

Every chart needs explicit units, a clear time window when temporal, sensible scale, appropriate missing-data behavior, and no fabricated precision.

## 11. Analytical state, confidence, provenance

Represent values as **Measured**, **Derived**, **Estimated**, **Sparse / Building**, or **Unavailable** where relevant.

Expose provenance where useful: Health Connect, wearable, manual input, imported activity, or model-estimated.

Confidence/state treatment must remain subordinate to the metric and must never make a low-confidence model output appear authoritative.

### Freshness (A5, 2026-10)
A hero or header that shows synced data carries a freshness line in `Micro`: when the reading is from (TODAY, YESTERDAY,
or a date) and its source, plus the last sync time when the app knows it, e.g. `TODAY'S READING · WEARABLE · SYNCED 07:42`.
When the reading is older than the sync layer's stale threshold (`STALE_AFTER_DAYS` in `data/StatusRepository.kt`,
documented there), the line turns Warning and starts with STALE. The UI never invents the threshold; the wording lives in
`domain/Freshness.kt`. This replaces the always-"SYNCED" pill removed in DAV-94 with a real signal.

## 12. Interaction and motion

Prefer soft 120–240 ms transitions, short fades, subtle elevation changes, chart selection/reveal transitions, and smooth state changes.

Avoid bounce-heavy motion, decorative parallax, long transitions, or motion that delays access to data.

### Interaction states (A1, 2026-10)
Every tappable element uses `Modifier.ftClickable` (never `indication = null`):
- **Pressed**: strong glass fill (`GlassStrongFill`) drawn instantly on press, faded out over 120 ms on release, clipped to
  the element's shape. No ripple, no scale.
- **Focused** (keyboard, switch access): a 2 dp ring, independent of the pressed state.
- **Selected**: platinum (chips, segments, switches) or the selected glass (rows, dock tabs); pressed draws on top.
- **Disabled**: muted text and border, not clickable.

## 13. Accessibility

- Check text and essential controls against both base and glass surfaces.
- Do not rely on color alone; pair state color with text, icon, shape, border, or label.
- Maintain platform-appropriate minimum touch targets even when the visual icon is small.
- Do not put important metrics in micro type.
- Focused controls need a visible focus state independent of hover/pressed styling.

## 14. Android implementation rules

Centralize the design tokens in the Android UI layer. Do not scatter raw hex values, corner radii, spacing constants, or shadow values across individual screens.

Maintain a single mapping for colors, semantic states, typography, spacing, radii, material surfaces, elevation, and chart styling.

Prefer semantic references such as `colorSurfaceGlass` over raw constants inside components.

Figma token names and Android token names should map one-to-one whenever practical.

### Migration strategy

**New UI:** follows this contract.

**Existing UI:** when touched, migrate its visual primitives toward this system rather than expanding the legacy language.

**Frozen surfaces:** may retain legacy styling until the roadmap calls for a visual pass.

The visual contract does not authorize IA or workflow changes by itself.

## 15. Do / Don't

### DO
- Put the domain in the page's wash and the metric in the chart's ink.
- Keep every card neutral glass.
- Use one dominant primary metric per page, in Nunito.
- Use platinum for the primary action and for selection.
- Use each metric's fixed hue everywhere that metric appears.
- Expose confidence and provenance.
- Select chart types according to metric semantics.

### DON'T
- Tint a card with a domain or metric hue.
- Colour a metric with a domain hue, or a domain with a metric hue.
- Use the holographic spectrum outside CHARGE and premium moments.
- Use status colours (ok / warn / bad) to tell series or sections apart.
- Make every component glow.
- Turn every value into a chart, or imply precision the data do not support.
- Use monospace for copy.
- Create one-off colours outside `tokens.css`.

## 16. Figma system roadmap

1. Variables — color, spacing, radius, typography, opacity, blur/elevation.
2. Styles — typography and effects.
3. Primitive components — telemetry, status, selectors, indicators.
4. Analytical components — metric cards, chart headers, confidence/provenance blocks.
5. Navigation components — floating dock, screen header, system tiles.
6. Screen templates — Status, Training, Fuel, Heart, Labs, Session Detail.
7. QA page — 390×844 frames covering long text, missing data, critical state, and low-confidence state.

Before publishing a component: verify token bindings, every state, typography, contrast, 390×844 composition, real-data behavior, and sparse/missing-data states.

## 17. Current roadmap migration

The existing roadmap described Field Terminal as dark gunmetal + amber + technical/squared geometry. The Futuristic Material contract supersedes that as the preferred visual direction for new work.

The most affected areas are the global shell/header, Status/body hub, analytical category surfaces, chart/ring/dot-plot presentation, shared confidence/provenance components, Session Detail, and final cross-screen visual QA.

Functional boundaries remain unchanged unless explicitly changed by Linear.

## 18. Contract acceptance test

A new surface is visually compliant when:

- the first reading is obvious within roughly 2 seconds;
- the primary metric/state dominates;
- color has a documented semantic purpose;
- surfaces use the material hierarchy consistently;
- typography separates interface from telemetry;
- chart form matches metric semantics;
- uncertainty/provenance is visible when relevant;
- no new hard-coded design values are introduced without a token decision;
- the surface works at 390×844; and
- accessibility does not depend on color alone.

The contract succeeds when a new screen can be designed without inventing a new visual language.