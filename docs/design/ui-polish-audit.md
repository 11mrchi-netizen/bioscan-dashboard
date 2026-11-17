# UI/UX consistency and polish: audit and pass (Nov 2026)

Authorities: `design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md`, `design/ANALYTICAL_PRESENTATION_CONTRACT.md`, `design/tokens/`.

## What the audit found

| Area | Finding |
| --- | --- |
| Cards | Three card implementations: `FTCard` (CardTitle, "?" dialog), `Section` (LabelCaps title, ⓘ opening a full-screen page, own paddings), `OverlaySection` (radius 16, own "?"). |
| Chips | `SubTabChip` (40 dp), `DetailKit.Chip` (about 30 dp), several private form chips (about 32-36 dp), one selecting in emerald (`LabelEntrySheet`). Range labels "30 D", "28 D", "4 W", "12 W", "6 M", "1 Y". |
| Icons | Text glyphs as icons: about 25 "›", ‹, ✕, ⚙, ⓘ, ▾ ▸ ▴ ⌄, ✓, ⚠, « », +/−. One Material filled arrow (`TileHeader`). The dock's vector line icons were the only real family. |
| Touch targets | Timeline rows 44 dp; the ME gear 8 dp padding round a glyph; the "?" help button 20 dp; date-picker arrows 44 dp. |
| Colour misuse | Healthspan lab charts in unrelated metric hues (triglycerides = blood pressure, HDL = SpO2, glucose = meal timing, HbA1c = skin temperature, lifts = power / muscle potential). Timeline: sessions in emerald (a status), NOW in emerald. Map pins: encounters / social in critical red. A text link in emerald. |
| Status language | Healthspan status mapped twice (`HealthspanCard.tag`, `HealthspanFactorTrend.pair`), colour-only on the radar vertices, a mono caption in the list. |
| Healthspan radar | Rings all alike, the 25 / 50 lines unlabelled, 10 sp labels, vertex state by colour only. |
| Charts | Two date formats ("14 AUG" and "14 Aug"), two legends, sparse lab series drawn as continuous lines over months, no sparse-data note. |
| Motion | NavHost transitions hand-tuned (150 / 220 ms); ME sub-tabs, overlays and expanders snap; reduced motion honoured only by the score scenes. |
| ME tabs | Six tabs in a plain horizontal scroll: no edge cue, the selected tab could sit off-screen at 360 dp. |

## What changed

Shared primitives (all in `ui/components/`):

- `FTIcons.kt`: one line-icon family drawn in code (24 grid, 1.9 stroke, round caps, as `ic_tab_*.xml`): Back, Close, Chevron right / down / up, double chevrons, Settings, Info, Help, Check, Plus, Minus, Warning. `FTIcon` (three sizes), `FTIconButton` (48 dp, spoken description required), `FTChevron`.
- `FTChips.kt`: `FTChip` (pill, platinum selection, 36 dp pill in a 48 dp target, `selected` semantics), `FTChipRow` (scrolls, edge fades, keeps the selection in view), `FTChoiceChip` (form choice segment), `FTRange` (28D / 90D / 1Y / ALL), `FTRangeSelector`, `RangeMemory` / `rememberRange` (a pick survives leaving and reopening a page).
- `FTMotion.kt`: 120 / 180 / 240 ms with one easing; push, pop, tab, overlay and sub-tab transitions; reduced motion (animator scale 0) snaps everything, including the skeleton pulse, the score scenes, the press feedback and the toast.
- `ChartFraming.kt`: `ChartDates` (one date format), `FTLatestReading`, `FTLegend` (wraps, dashed series marked), `FTChartNote`.
- `FTCard`: three emphasis levels (Hero / Supporting / Compact), one 48 dp header with a trailing note and chevron, one help affordance, one set of paddings. `Section` and `OverlaySection` now delegate to it; `InfoHelpButton` is a 48 dp target with the Help icon.
- `StateWords.kt`: `healthspanStateWord` and `availabilityStateWord` join the central mappers. `FTRadarChart`: marked rings with values, vertex shape by state (dot / ring / triangle), Caption labels, a spoken summary.
- Tokens: lab-marker and lift hues (`--m-triglycerides`, `--m-hdl`, `--m-fasting-glucose`, `--m-hba1c`, `--m-lift-primary`, `--m-lift-secondary`), `--ft-chip-idle`, `--ft-motion-*`; in `tokens.css`, `tokens.json` and `FT` together.

Screens migrated: every `Section` / `OverlaySection` user (about 115 cards), every `SubTabRow` / `Chip` user, `DetailScreen` (back icon, ranges 28D / 90D / 1Y, remembered), `FullScreenOverlay` (icons, fade in), `TileHeader`, ME (gear icon button, animated sub-tabs, narrow tabs), Today's timeline (48 dp rows, category hues, neutral NOW), map pins, Healthspan card, radar and factor pages (own hues, dots for occasional readings, sparse note, standard ranges), Performance hub (28D / 90D / 1Y + block, remembered), Body tab ranges, Records (chevrons, 48 dp rows, animated expansion), date picker arrows, and the ~25 glyph chevrons and toggles across the app.

## Known limitations

- Text links that end in "›" inside a sentence or a button label ("PROGRAM LIBRARY ›", "Session #12 ›") keep the typographic arrow.
- Some form grids (`IntChipRow`, `FocusChipGrid`, `RegimenFields.Chips`, `MealPrepPage.Chips`, `SymptomChips`) keep their own layouts; they are already platinum but not all are 48 dp tall.
- `DetailScreen` pages offer 28D / 90D / 1Y but not ALL: their loaders read a bounded window. The Performance hub's windows are whole weeks (its charts are weekly bars) and offer no ALL.
- `ConcernScreen` (3M / 6M / 1Y) and the Training / Nutrition `TimeRange` selector (7D…ALL) keep their own ranges: they set the chart grain as well as the window.
- Screens not yet framed with `FTLatestReading` keep their own latest-value lines; the date format is shared everywhere through `shortDate` / `ChartDates`.
- 363 direct uses of status colours remain; the ones audited as categories were fixed, the rest read as status.

## Validation

`PolishComponentsTest` (390 × 844 and 360 × 640): 48 dp targets for the gear, the help button, chips and timeline rows; the last ME tab brought into view; the Healthspan states mapped once; range memory; reduced motion. Screenshots are written when `FT_POLISH_SHOTS` names a folder.
