# Field Terminal — Analysis Console IA & Interaction Contract

**Status:** Revised for the analysis hub (Phase 0, see [PLAN.md](PLAN.md)). **The navigation (§3.1) and the workspace catalogue (§4) are superseded by [the web redesign](../web-redesign/README.md)** (2026-11-24): one app with five tabs and a Setup gear. The shell zones, header, interaction loop and boundary below still hold.  
**Version:** 3.0 (navigation and catalogue); 2.0 otherwise  
**Date:** 2026-11-24 (v2.0: 2026-10-27, v1.0: 2026-09-30)  
**Linear:** DAV-306  
**Visual authority:** [Futuristic Material Visual Contract](../../design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md)  
**Presentation authority:** [Analytical Presentation Contract](../../design/ANALYTICAL_PRESENTATION_CONTRACT.md)

---

## 1. What this is

The Analysis Console is a separate large-screen web client that shares the Field Terminal Supabase account and data model. It is **not** a desktop port of the Android app.

> Mobile Field Terminal = capture, glanceable state, in-the-moment action.  
> Analysis Console = longitudinal investigation, comparison, cross-domain relationships, raw-data inspection.

**v2.0 (2026-10-27):** the console becomes the hub for in-depth analysis, organised around three coordinated workstreams
rather than one dashboard per domain:

- **Data integrity**: is the data arriving, is it complete enough to trust, do derived metrics agree with their records?
- **Decision layer**: question, then evidence, then experiment, then a recorded decision that keeps its evidence.
- **Longitudinal engine**: one aggregation and time contract, adaptive resolution, detail on demand.

The per-domain dashboards of v1 (Overview, Timeline, Training, Recovery / Body, Nutrition, Comparison) repeated what the
Android app shows and were removed in Phase 0. Their charting role passes to Explore.

---

## 2. Scaffolding decision (Phase 0)

**Option chosen: `/console/` subdirectory in the same repo.**

- Vite + React + TypeScript builds into `bioscan-dashboard/console/dist/`.
- GitHub Pages serves the console at `https://11mrchi-netizen.github.io/bioscan-dashboard/console/`.
- `index.html` (the 3D BIOSCAN mobile view) is unchanged and continues to serve the root.
- Same Supabase project (`ugfrglbcoivkprjqvjzz`), same auth, same RLS.
- One repo, one deployment pipeline.

---

## 3. Information architecture

### 3.1 Top-level navigation

The left rail (desktop) or top bar (tablet) carries the phone's five tabs and a Setup gear:

```
TODAY     the phone's TODAY for any day; rings and inputs open the metric page
ME        the phone's tabs; BODY and EXPOSURES carry the deeper analysis
LAB       COMPARE · EVIDENCE · EXPERIMENTS · REGIMEN · TESTS · ROUTINE
PLAN      OVERVIEW · TRAINING · FOOD · SESSIONS · RACES
LOG       the day's log and fixes
─────────────
⚙ SETUP   INTEGRITY · DATA · CONNECTIONS
```

Every tappable number opens the metric page (`#/m/<ref>`), where metrics can be added to compare. The Inspector is global; the
header range shows only on the pages that read it (Integrity and Compare's PERIODS view). Every issue, evidence point and
decision links to the record it rests on through a Data URL (`#/setup/data?table=…&…`). See
[the web redesign](../web-redesign/README.md) for the routes, the compare, and the old-route redirects.

### 3.2 Shell zones (≥ 1280 px)

```
┌──────────────────────────────────────────────────────────────────┐
│  HEADER: date range · comparison period · save workspace · export │
├────────┬─────────────────────────────────────┬────────────────────┤
│        │                                     │                    │
│  LEFT  │        CENTRAL CANVAS               │   RIGHT INSPECTOR  │
│  RAIL  │  (charts, tables, event stream)     │   (detail, lineage,│
│  nav + │                                     │    provenance)     │
│  ws    │                                     │                    │
│        │                                     │                    │
└────────┴─────────────────────────────────────┴────────────────────┘
```

- Left rail: collapsible, 240 px default.
- Central canvas: flex-fills remaining width minus inspector.
- Right inspector: 360 px default, collapsible, slide-over on tablet.
- Panels within the canvas are resizable; drag handles on card borders.

### 3.3 Tablet behavior (768–1279 px)

- Left rail collapses to icon-only; tap to expand as overlay.
- Inspector becomes a bottom-sheet drawer; activates on selection.
- Central canvas takes full width when both panels are collapsed.

### 3.4 Header controls

| Control | Behavior |
|---|---|
| Date range picker | Primary window: rolling (7d/14d/28d/90d/1y) or custom. Broadcasts to `AnalysisContext`. |
| Comparison period | Optional secondary window: prior period / prior year / custom. Visible as a parallel band on every chart. |
| Save workspace | Persists layout + context snapshot to Supabase. |
| Export | Downloads the current central-canvas view as CSV/PNG. |

---

## 4. Workspace catalogue

Superseded by the tabs above. Where each v2.0 workspace went (hash routes; the old routes redirect):

| v2.0 workspace | Now |
|---|---|
| Integrity (`#/integrity`) | Setup › INTEGRITY (`#/setup/integrity`) |
| Evidence (`#/evidence`) | Lab › EVIDENCE (`#/lab/evidence`) |
| Explore (`#/explore`) | Lab › COMPARE › PERIODS (`#/lab/compare?view=periods`); the metric page's compare is the default view |
| Data (`#/data`) | Setup › DATA (`#/setup/data`) |
| Models (`#/models`) | Lab › EVIDENCE › TRUST (`#/lab/evidence?view=trust`) |

The corrected discovery screen (Evidence > DISCOVERY, phase C2) scans the registrable questions on the older two-thirds of
the history with a false-discovery correction and registers a promising pair as a question tested on new days only.

---

## 5. Primary interaction loop

```
Select date/range/event/metric
         │
         ▼
  AnalysisContext updated
         │
         ▼
  All subscribed components re-query via metric adapter
         │
         ▼
  Charts/tables update synchronously (same context window)
         │
         ▼
  User clicks a point / row / event
         │
         ▼
  Inspector opens with value → metric definition → aggregation → source records → provenance
         │
         ▼
  Optional: drill-down to session detail / Data Explorer (filtered)
```

Every step is reversible — back navigation restores prior context state.

### 5.1 Selection semantics

- **Day cursor**: clicking a point on any time series broadcasts that date to all components in the canvas; every compatible chart highlights that day.
- **Range brush**: dragging on any chart zooms all linked charts to that range.
- **Event selection**: clicking a Timeline event or session row opens the Inspector; chart annotations for that event appear across all panels.
- **Metric selection**: clicking a metric (an Integrity row, an evidence item's outcome) opens it in Explore with the same date context.

### 5.2 Context dimensions (`AnalysisContext`)

```typescript
interface AnalysisContext {
  primaryRange: DateRange;          // start/end
  comparisonRange: DateRange | null;
  selectedDate: LocalDate | null;   // day cursor
  selectedEvent: EventRef | null;   // session/event/entity
  selectedMetrics: MetricId[];
  domainFilters: Domain[];
  sourceFilters: Source[];
  activityFilters: ActivityType[];
  rawVsDerived: 'raw' | 'derived' | 'both';
  benchmarkRef: BenchmarkRef | null;
  activeWorkspaceId: string | null;
  drillDownRoute: string | null;
  timezone: string;                 // IANA, explicit always
  dataQualityWarnings: QualityWarning[];
}
```

Timezone is always explicit. Daily aggregations are bounded by the user's local midnight. How each stored column carries local time (a calendar date, local wall time labelled UTC, or a true UTC instant with an offset, defaulting to +480 for this account) is declared per series in the series contract (`metric-series.json`, `analysis-core/seriesContract.ts`; PLAN.md, phase A).

---

## 6. Reference-pattern audit

### 6.1 Patterns adopted

| Pattern | Source | Adoption |
|---|---|---|
| Shared crosshair/cursor sync across charts | Intervals.icu | Full — `AnalysisContext.selectedDate` drives it |
| Brushable load/performance chart with session list below | TrainingPeaks / Intervals.icu | Explore (the Training workspace was removed in v2.0) |
| Small-multiples KPI wall with comparison deltas | Oura / WHOOP | Dropped in v2.0: the Integrity health overview replaces a metrics wall |
| Virtualized raw-data table with provenance column | Heads Up Health | Full — Data Explorer |
| Two-metric scatter with explicit n/missingness caveats | GoldenCheetah | Retired 2026-10 with the exploratory scan; returns as the corrected discovery screen |
| Saved workspaces / configurable dashboards | RUNALYZE / MacroFactor | Full — workspace persistence |
| Session detail: route + switchable perf chart + splits | Strava / RUNALYZE | Full — session drill-down |
| Inspector panel with lineage chain | Heads Up Health / Welltory | Full — Inspector |

### 6.2 Patterns explicitly rejected

| Pattern | Source | Reason |
|---|---|---|
| Single composite score (Recovery Score, Readiness, etc.) | WHOOP / Oura | Hides constituent signals; contracts require contributors to remain distinguishable |
| Generic SQL user scripting console | GoldenCheetah | Out of scope v1; adds surface area without clear benefit given the metric-adapter abstraction |
| Chat-first / AI-primary navigation | Multiple modern apps | AI is an interpretation layer, not the navigation model |
| Real-time streaming widgets | General BI tools | Not needed; data is daily-aggregated or session-based |
| Multi-user / team analytics | Intervals.icu, TrainingPeaks | Out of scope; single-user RLS model |
| Proprietary universal score | Every wearable app | Contracts prohibit inventing thresholds not supplied by analysis layer |

---

## 7. Boundary

### In scope

- The five workspaces of §4, the shared `AnalysisContext` and the global Inspector with its provenance chain
- One aggregation and local-time contract used by every workspace (PLAN.md, phase A)
- Workspace save/load (Supabase-backed)
- Performance validated on the real history (7D, 1Y and all-time, cold and warm)

### What the console writes

The console is an investigation and decision environment, not a second data-entry app. It writes only:

- decision records (append-only `decisions`, phase D)
- series-break verdicts (`series_break_decisions`)
- question registrations (`registered_questions`)

### Out of scope

- Editing or creating records (mobile owns capture and record correction)
- Multi-user / team features
- Generic SQL or JavaScript user scripting
- Arbitrary real-time streaming
- Clinical/EHR workflows
- Proprietary composite scores not in the analysis contracts
- DuckDB-WASM local analysis mode (after server-backed analysis proven)

---

## 8. Desktop breakpoints and density rules

| Breakpoint | Layout |
|---|---|
| < 768 px | Not supported; redirect to mobile app |
| 768–1279 px | Tablet: collapsed rails, bottom-sheet inspector |
| ≥ 1280 px | Desktop: three-zone layout, full rail, side inspector |
| ≥ 1600 px | Wide: canvas gets more columns; inspector stays 360 px |

### Density rules

- Default: comfortable (16 px row height minimum in tables, 4 px chart line weight).
- Compact: 12 px row height, 2 px line weight. User-toggled per workspace.
- No animation on data transitions — state changes should feel immediate and precise.
- Microinteractions (panel resize, expand/collapse) max 150 ms ease-out.

---

## 9. Keyboard and navigation expectations

- Tab / Shift-Tab: focus between interactive elements within a workspace.
- Arrow keys: advance day cursor on focused time-series chart.
- `Cmd/Ctrl + K`: command palette (workspace-switch, metric-search, date jump).
- `Escape`: close inspector, collapse overlay rail.
- `[` / `]`: step comparison window backward/forward.
- Every chart interaction (brush, click, crosshair) has a keyboard-accessible alternative via the date picker / metric selector.

---

## 10. Implementation plan

Phases, gates and the owner's decisions of 2026-10-27 are in [PLAN.md](PLAN.md).
