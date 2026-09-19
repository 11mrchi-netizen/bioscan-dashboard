# Field Terminal — Futuristic Material Visual Contract

**Status:** Design authority for new UI  
**Version:** 1.0  
**Date:** 2026-09-18  
**Figma reference:** `Futuristic Material Status`  
**Figma contract page:** `DESIGN CONTRACT`

## 1. Purpose

This document defines the visual language for Field Terminal and the rules for extending it into a reusable design system.

The system is based on the Futuristic Material direction: a near-black instrument surface, translucent glass layers, restrained emerald signal color, soft geometry, strong metric hierarchy, and a deliberate split between interface typography and telemetry typography.

> **Field Terminal should feel like an instrument reading the user, not a database displaying the user's data.**

This is a visual and interaction contract, not an information-architecture replacement. Linear remains the authority for product IA, screen scope, and data behavior.

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
Use the stack **base surface → atmospheric field → glass surface → border → content**. Depth comes from opacity, blur, shadow, and spacing.

### Clinical + futuristic + premium
The character should feel closer to a high-end biometric/medical instrument than cyberpunk. Avoid neon overload, gaming UI tropes, excessive monospace text, and dense sci-fi ornament.

## 4. Color system

### Neutral / material foundation

| Token | Hex | Role |
|---|---|---|
| `color/neutral/base` | `#0A0C0E` | App background / instrument field |
| `color/neutral/surface` | `#11161A` | Primary surface |
| `color/neutral/elevated` | `#171E23` | Elevated solid surface |
| `color/neutral/text-primary` | `#E2E8F0` | Primary text |
| `color/neutral/text-secondary` | `#A4AFBA` | Supporting text |
| `color/neutral/text-muted` | `#66717C` | Metadata / low-emphasis content |
| `color/neutral/white` | `#FFFFFF` | Overlay source for glass/borders |

The neutral family should stay cool and slightly desaturated.

### Primary signal / Emerald

| Token | Hex | Role |
|---|---|---|
| `color/emerald/300` | `#6EE7B7` | Highlighted text / fine signal |
| `color/emerald/500` | `#10B981` | Primary active/healthy signal |
| `color/emerald/700` | `#047857` | Pressed / strong state |
| `color/emerald/900` | `#064E3B` | Atmospheric dark wash |

`#10B981` is the default active/healthy signal. It is not a universal decoration color.

### Semantic state palette

| Token | Hex | Meaning |
|---|---|---|
| `color/state/info` | `#60A5FA` | Informational state |
| `color/state/warning` | `#F59E0B` | Attention required |
| `color/state/critical` | `#F87171` | Error / critical condition |
| `color/state/analysis` | `#A78BFA` | Model/analysis layer |

State colors must not be used interchangeably with domain accents.

### Domain accents

| Domain | Accent |
|---|---|
| Training | Emerald `#10B981` |
| Fuel | Cyan `#22D3EE` |
| Heart | Rose `#F43F5E` |
| Labs | Violet `#A78BFA` |
| Map | Blue `#60A5FA` |
| Log | Amber `#F59E0B` |

Domain accents identify sections or navigation; they do not encode health/warning/error.

### Glass / overlay system

- Glass fill: white at roughly 5–8%.
- Strong glass fill: white at roughly 8–12%.
- Border: white at roughly 8–12%.
- Active/focus border: semantic accent at roughly 40–70% only when state/action requires it.
- Emerald atmospheric wash: emerald 500/900 at roughly 8–20%, large-area only.

Opacity is part of the token, not a random component-specific adjustment.

### Adding a new color

Do not add an isolated hex value. Add a color only when a new semantic role is required and an existing role cannot represent it.

Create a `500` anchor plus `300`, `700`, and `900` support stops. Give it a semantic name, define whether it is a state or domain color, check contrast on base and glass, ensure it does not visually dominate emerald, and document the token before using it.

Never introduce a color only because two adjacent cards need more variety.

## 5. Typography system

### Font roles

**Inter = interface language**: navigation, screen titles, descriptions, buttons, explanatory copy, and user-facing labels.

**Roboto Mono = telemetry language**: measurements, percentages, timestamps, statuses, units, data-source labels, provenance, and compact system metadata.

Conceptually: **Inter = interface · Roboto Mono = telemetry**. Do not make whole screens monospace.

### Type scale

| Role | Font | Weight | Size |
|---|---|---:|---:|
| Display metric | Roboto Mono | Bold | 36–40 |
| Page title | Inter | Bold | 24–30 |
| Section title | Inter | Bold/Semi Bold | 18–22 |
| Body | Inter | Regular | 15–16 |
| Telemetry | Roboto Mono | Bold | 13–14 |
| Label | Roboto Mono | Bold | 11–12 |
| Micro | Roboto Mono | Regular | 10 |

Primary metrics should be visually dominant. Preferred pattern:

`RECOVERY`
`94%`
`HRV 58 ms · RHR 49 bpm · SLEEP 7.7 h`

## 6. Spacing and geometry

### Spacing
Use a 4 px base unit: **4 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 48**.

### Radius
Use **0 · 8 · 12 · 16 · 24 · 28 · FULL**.

- 8: compact controls
- 12–16: buttons, chips, small modules
- 24–28: primary cards / glass surfaces
- FULL: pills and circular controls

### Borders
Default: 1 px, white at roughly 8–12% opacity. Stronger/colored borders are reserved for active, focused, warning, or critical states.

## 7. Material and elevation

### Surface levels

- **Level 0** — `#0A0C0E` instrument field.
- **Level 1** — `#11161A` solid surface.
- **Level 2** — translucent glass, approximately 55–75% neutral fill plus blur.
- **Level 3** — glass plus soft shadow.
- **Level 4** — focused/modal layer for transient UI only.

### Blur
Reference values: ambient 100–120 px; normal glass 12 px; larger floating surfaces 24 px.

### Shadow
Reference: approximately 8 px y-offset, 32 px blur, low-opacity black. Floating navigation may use a larger softer shadow.

### Glow
Emerald glow is an atmospheric effect, not a component default. Use it for biometric focal visualization, active system states, and selected/engaged focal elements. Never use it behind every card or icon.

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

## 9. Component design-system contract

Build the system in this order:

1. **Foundations:** color, spacing, radius, typography, opacity, blur/elevation, icon sizing, motion.
2. **Atoms:** icons, telemetry labels, status pills, provenance labels, metric values, indicators, selectors.
3. **Molecules:** metric headers, analytical-card headers, metric rows, confidence blocks, range/baseline indicators, chart headers, nav items.
4. **Organisms:** analytical cards, body/system tiles, chart modules, biometric focal visualizations, session summaries.
5. **Screens:** compose from the above primitives.

### Naming
Use deterministic semantic names such as `Color/Signal/Emerald500`, `Color/State/Warning`, `Surface/Glass/Default`, `Radius/Card`, `Spacing/MD`, `Typography/Metric/Display`, and `Component/AnalyticalCard`.

Avoid names such as `Green2`, `CardNew`, or `CoolBox`.

### Variants
Variants represent behavior or semantic state, not arbitrary color options.

Good: `State=Default | Active | Warning | Critical | Disabled`.

Bad: `Color=Green | Blue | Red | Purple | Orange` when those colors do not represent different meanings.

Use instance swap for icons instead of icon-specific variant matrices.

## 10. Data visualization contract

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

## 12. Interaction and motion

Prefer soft 120–240 ms transitions, short fades, subtle elevation changes, chart selection/reveal transitions, and smooth state changes.

Avoid bounce-heavy motion, decorative parallax, long transitions, or motion that delays access to data.

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
- Use one dominant primary metric.
- Use emerald as the primary system signal.
- Use glass selectively.
- Use soft 24–28 px primary-card geometry.
- Use Inter for interface language and Roboto Mono for telemetry.
- Expose confidence and provenance.
- Select chart types according to metric semantics.
- Preserve generous negative space.
- Keep domain accents subordinate to the primary signal.

### DON'T
- Make every component glow.
- Turn every value into a chart.
- Use domain colors as health states.
- Use monospace for all copy.
- Use thick borders as the main visual hierarchy.
- Create arbitrary one-off colors.
- Imply precision that the data do not support.
- Make all cards visually equal.
- Collapse multidimensional health/training state into an opaque composite score.

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