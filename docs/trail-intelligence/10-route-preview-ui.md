# 10 — Exposing the route preview in the app

**Linear:** [DAV-149](https://linear.app/biodashboard/issue/DAV-149) · **Status:** code complete, live verification pending device access

## Where, and why the Map tab's existing sheet

`ui/screens/MapScreen.kt`'s `PinDetailSheet` already has a real "Training" branch for a selected
upcoming session — planned date/time and route name/source are already shown above it (the event's
own title/`formatEventMeta`), and it already renders a one-line GPX summary
(`routeSummary()`, naive haversine distance + unsmoothed elevation delta) fetched live via
`MapRepository.fetchGpxPoints()`. This is the real, existing "pre-activity preview" surface — DAV-149
doesn't need a new screen, just a richer figure in the same place.

`PinDetailSheet` now takes a `plannedPreview: PlannedRouteRow?` (loaded read-only via
`PlannedRouteRepository.loadPreview()`, DAV-148's cached output) and prefers it over the live GPX
fallback whenever one exists for the selected event:

- **Distance, elevation gain, Mountain Index, KM-effort** — `plannedRouteSummary()`, only the
  figures that are actually non-null render, matching this file's own `routeSummary()` pattern.
- **Confidence and freshness** — the `confidence_tier` DAV-148 computed, plus a visible
  "MAY BE STALE" caption (`FT.Warning`) when `is_stale` is set — never hidden, per this app's
  established confidence-surfacing convention (DAV-168's "NEEDS CONFIRMATION" badge, DAV-180's
  "LIMITED DATA" caption).
- **The live fallback stays** — an event the pipeline hasn't reached yet (not yet synced, no GPX
  linked, or a fresh calendar entry) still shows the old naive one-line summary rather than nothing,
  satisfying this ticket's own "design so future metrics can be added without changing the
  route-preview contract" — the contract here being "the Training branch always shows *something*
  useful," which neither path breaks.

No new UI primitives, no new screen, no calculation logic duplicated in the UI (`plannedRouteSummary()`
only formats already-computed numbers).

## Verification

`./gradlew compileDebugKotlin testDebugUnitTest` green. Real confirmation needs a live Calendar
event with a linked GPX, a completed DAV-148 pipeline run, and eyes on the actual sheet — the same
device access every other Phase 2 ticket is waiting on.
