# Field Terminal — Information Architecture Contract

**Status:** Locked navigation/content contract for the UI & Analysis Layer Consolidation project
**Version:** 1.0
**Date:** 2026-09-19
**Linear:** [DAV-93](https://linear.app/biodashboard/issue/DAV-93)

## 1. Purpose and authority

This document is the implementation-ready IA/content contract DAV-93 asks for — it exists so
screen-by-screen build work (DAV-94 through DAV-108) doesn't re-litigate naming or hierarchy while
it's happening. It does not replace Linear as the authority for scope or acceptance criteria, and it
does not replace [FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md](FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md) or
[ANALYTICAL_PRESENTATION_CONTRACT.md](ANALYTICAL_PRESENTATION_CONTRACT.md) as the authority for visual
language or presentation-component behavior. This is the fourth layer: what each screen is *called*
and *contains*, so those two contracts have somewhere fixed to attach to.

Every mapping below was checked against the real current Kotlin tab enums (`HeartTab`, `FuelTab`),
not assumed from the mockup or the project description — see each section's own note.

## 2. Frozen boundaries (unchanged — do not touch in this project)

Login, Add Entry (workflow/IA), broad Setup IA (only sync status relocates here — DAV-94), Log's
calendar-free chronological-feed behavior, and Map's spatial IA. Any issue in this project that
appears to require touching one of these has scope-crept — stop and re-check against DAV-107.

## 3. Top-level navigation — unchanged

Four tabs: `STATUS | MAP | LOG | SETUP`. No new top-level tab, no removed one.

## 4. Status IA — Body/Condition as hub

Today: Status renders a flat 2×2 grid of Training/Fuel/Heart/Labs tiles below the existing
`BodyConsole.kt` schematic. Target (DAV-95): the schematic becomes the compositional center; the four
system tiles attach to it as glass instruments with a compact state/metric preview, not an unrelated
grid. Tapping an attached tile still opens that system's existing tile surface — this is a
compositional change, not a new navigation destination. `BodyConsole.kt` already exists; DAV-95 should
confirm during its own investigation whether it extends that file or replaces it — not decided here
(see §6).

## 5. Training — hierarchy, not new tabs

Current: `TrainingTileScreen.kt` has no sub-tabs — one flat screen, equal-weight sections. Target
(DAV-98): the same single screen, restructured into an explicit vertical hierarchy:

1. **LOAD** — CTL/ATL/TSB (already computed by `TrainingLoadEvaluation.kt`), current state,
   confidence, cycle tier, rest-cadence context.
2. **PERFORMANCE** — VO2max and other supported performance trends.
3. **SESSIONS** — recent/weekly session history.

No new sub-tab rail — this is section ordering within one screen, matching how the screen already
works today.

## 6. Fuel — real tab mapping (checked against `FuelTab` enum)

| Current (`FuelTab`) | Target | Change |
|---|---|---|
| `Nutrition` | NUTRITION | Visual/hierarchy only (calories → macros → meals → trend, per DAV-100) |
| `Hydration` | HYDRATION | Visual only |
| `Supplements` | SUPPLEMENTS | Minimal change — retain time-of-day grouping |
| — (new) | **DIGESTION** | New tab. Relocated content: Bristol/stool data currently lives in Heart's `Stool` tab (`BristolEvaluation.kt`/`BristolCard`) — moves here wholesale. No causal food→stool link is implied or computed; it's a contextual section, not a correlation feature. |
| `Weight` | **BODY** | Rename, not rebuild — `WeightTdeeTab` already exists and already has weight/body-fat trend content. DAV-103/104 extend it with the adaptive-TDEE panel and explicit confidence states; the existing trend content is not thrown away. |

## 7. Heart — real tab mapping (checked against `HeartTab` enum: `Heart, Arousal, Wellness, Sleep,
Stool, Injuries, Respiratory`)

| Current (`HeartTab`) | Target | Change |
|---|---|---|
| `Heart` (HRV/RHR) | CARDIO | Merge |
| `Respiratory` | CARDIO | Merge — respiratory-rate anomaly (`evaluateRespiratoryAnomaly`) joins HRV/RHR under one Cardio surface, per DAV-101's own "HRV, resting HR and respiratory state" grouping |
| `Sleep` | RECOVERY | Rename — sleep duration/stages/regularity |
| `Wellness` | WELLBEING | Merge |
| `Arousal` | WELLBEING | Merge — becomes one of Wellbeing's comparable small-multiple dimensions (energy/mood/stress/soreness/arousal), not its own tab |
| `Injuries` | INJURY | Rename |
| `Stool` | *(moved out)* | Relocates to Fuel → DIGESTION (§6). Heart has zero digestion content after this project. |

Net: 7 tabs → 4, with one tab's content leaving Heart entirely rather than being collapsed into it.

## 8. Labs — structurally unchanged

Per DAV-102: retain current thematic marker grouping and latest-vs-earlier comparison. This is a
visualization-pass target (dot plots over line charts for sparse draws), not an IA change — no tab
mapping needed here.

## 9. Session Detail — content contract (locked now, built in M5/DAV-106)

Summary → route/map (when route data exists) → one switchable performance chart
(`[HR] [PACE] [POWER] [CAL]`, only showing signals that exist for that session) → splits/intervals.
Locking this now, even though DAV-106 is a milestone-5 issue, so the chart-switching component built
for it doesn't get designed twice.

## 10. Explicitly deferred — not decided by this document

- **BodyConsole.kt extend-vs-rebuild** — DAV-95's own call once it investigates the existing file.
- **Figma component sync** (StatePill, TrendIndicator, etc. per DAV-105) — blocked on Figma
  Starter-plan MCP quota per DAV-105's own note; resume when quota is available, do not let this
  block Android-side IA or screen work.
- **Exact visual treatment of the Cardio/Wellbeing merges** (e.g. whether Respiratory gets its own
  sub-heading inside Cardio, or Arousal its own row inside Wellbeing's small multiples) — a DAV-99/101
  visualization-layer decision, not an IA one; this doc only fixes which screen owns the data.
