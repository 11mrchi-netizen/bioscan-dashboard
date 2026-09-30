# Field Terminal — Analysis Console IA & Interaction Contract

**Status:** Phase 1 lock — unblock for implementation  
**Version:** 1.0  
**Date:** 2026-09-30  
**Linear:** DAV-306  
**Visual authority:** [Futuristic Material Visual Contract](../../design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md)  
**Presentation authority:** [Analytical Presentation Contract](../../design/ANALYTICAL_PRESENTATION_CONTRACT.md)

---

## 1. What this is

The Analysis Console is a separate large-screen web client that shares the Field Terminal Supabase account and data model. It is **not** a desktop port of the Android app.

> Mobile Field Terminal = capture, glanceable state, in-the-moment action.  
> Analysis Console = longitudinal investigation, comparison, cross-domain relationships, raw-data inspection.

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

The left rail (desktop) or top bar (tablet) carries:

```
OVERVIEW
TIMELINE
TRAINING
RECOVERY / BODY
NUTRITION
COMPARISON
DATA EXPLORER
RELATIONSHIP LAB
─────────────
[Workspace selector]
[Settings / auth]
```

Workspaces are saved analytical environments, not fixed dashboards. The left rail navigation sets the *workspace type*; the workspace selector lets the user switch between named saved instances of that type.

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

| Workspace | Route | Primary audience use |
|---|---|---|
| Overview | `/console/overview` | Quick cross-domain status snapshot, comparison deltas |
| Timeline | `/console/timeline` | Chronological event stream, "what happened on day X" |
| Training | `/console/training` | Load curve, performance trends, session drill-down |
| Recovery / Body | `/console/recovery` | Sleep, HRV, RHR, wellbeing, weight, injury context |
| Nutrition | `/console/nutrition` | Macros, fluid, caffeine, meals table, TDEE overlay |
| Comparison | `/console/comparison` | Personal-historical + population benchmarks |
| Data Explorer | `/console/data-explorer` | Raw + derived canonical datasets, virtualized table |
| Relationship Lab | `/console/relationship` | Two-metric scatter/lag with explicit caveats |

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
- **Metric selection**: clicking a metric label in Overview navigates to its home workspace with the same date context.

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

Timezone is always explicit. Daily aggregations are bounded by the user's local midnight (IANA timezone stored in Supabase profile, defaulting to `Asia/Taipei` for this account).

---

## 6. Reference-pattern audit

### 6.1 Patterns adopted

| Pattern | Source | Adoption |
|---|---|---|
| Shared crosshair/cursor sync across charts | Intervals.icu | Full — `AnalysisContext.selectedDate` drives it |
| Brushable load/performance chart with session list below | TrainingPeaks / Intervals.icu | Full — Training workspace |
| Small-multiples KPI wall with comparison deltas | Oura / WHOOP | Full — Overview workspace |
| Virtualized raw-data table with provenance column | Heads Up Health | Full — Data Explorer |
| Two-metric scatter with explicit n/missingness caveats | GoldenCheetah | Full — Relationship Lab |
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

## 7. v1 boundary

### In scope for v1

- All 8 workspaces (Overview, Timeline, Training, Recovery/Body, Nutrition, Comparison, Data Explorer, Relationship Lab)
- Synchronized `AnalysisContext` across every workspace
- Inspector with full provenance/lineage chain
- Workspace save/load/share (Supabase-backed)
- AI interpretation layer (Gemini over structured results only)
- Performance validation on real longitudinal data

### Out of scope for v1

- Editing or creating records (mobile owns capture)
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

## 10. Design decisions not decided here

The following are deferred to implementation issues:

- Exact chart library sub-configuration (ECharts option shape) — DAV-309.
- Metric query adapter TypeScript interface — DAV-309.
- `AnalysisContext` React provider and subscription model — DAV-308.
- Workspace schema in Supabase — workspace persistence issue.
- Session detail internal layout beyond the contract in FIELD_TERMINAL_IA_CONTRACT.md §9.

---

## 11. Acceptance checklist

- [ ] IA and navigation map documented (this document).
- [ ] Core interaction contract documented with concrete examples (§5).
- [ ] Reference-pattern audit with adoption/rejection rationale (§6).
- [ ] Desktop/tablet breakpoints and density rules documented (§8).
- [ ] v1/out-of-scope boundary explicit (§7).
- [ ] No new metric calculations invented in this document.
- [ ] Scaffolding decision recorded (§2).
