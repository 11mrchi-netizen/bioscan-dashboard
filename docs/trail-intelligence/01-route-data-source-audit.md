# 01 — Route/GPX data source audit

**Linear:** [DAV-131](https://linear.app/biodashboard/issue/DAV-131) · **Status:** audited, real source identified

## What was asked

DAV-131 asks where GPX/track data exists today between imported events, the Android app, Health
Connect, external files and backend storage — "Supabase currently has no GPX column/reference in
`exercise_sessions` and no GPX objects in storage, so this issue establishes the actual current path
rather than assuming one," and to select the recommended v1 ingestion path.

## What actually exists

Confirmed directly against the schema and the real Android source, not assumed:

- `exercise_sessions` has no GPX/route column and no reference into any Storage bucket
  (`information_schema.columns` has no such field; no `route_artifacts`-style table exists). The
  ticket's own premise is correct.
- `exercise_sessions.details` (jsonb) already carries a real `route_type` classification for runs —
  `'trail'` vs `'road'` — populated by earlier work (DAV-48/49). 5 real rows today have
  `type = 'run' AND details->>'route_type' = 'trail'`; 1 of those 5 has a `health_connect_record_id`.
- **`data/SessionDetailRepository.kt`'s `checkRouteAvailability()`/`toRoutePoints()` already read a
  completed session's real GPS track from Health Connect** — `ExerciseSessionRecord.exerciseRouteResult`
  (Health Connect's own per-session route API), with real three-state handling
  (`RouteAvailability.Available` / `ConsentRequired` / `NoRoute`) and a normalized
  `RoutePoint(offsetSeconds, lat, lon, elevationM)` shape. This is already wired into
  `ui/screens/SessionDetailScreen.kt` via `ui/components/RouteMiniMap.kt` for on-screen route display.
- Separately, `data/MapRepository.kt`/`domain/NextSession.kt` already read a **planned** route from a
  Google Drive GPX file linked in a Calendar event's description (`parseGpxLink`/`parseGpxPoints`,
  `GpxPoint(lat, lon, elevationM)`), for the Map tab's live pre-activity preview. This is a completely
  separate real code path from Health Connect's route API, feeding a different concern (a route that
  hasn't happened yet, vs. one that has).

## Recommended v1 path — and the milestone-07 planning correction it drove

Two real route sources exist, for two genuinely different questions, and neither should feed the
other:

1. **Completed trail-run performance** (course demand + effort metrics, DAV-135 onward) reads Health
   Connect's `ExerciseRoute` for the actual recorded session — already available via
   `SessionDetailRepository`, no GPX file or new storage involved. This is the source for every
   `type='run' AND details.route_type='trail'` session.
2. **Pre-activity route preview** (Phase 2 of the milestone-07 plan) reads a calendar-linked Drive GPX
   file — extending the Map tab's existing `parseGpxLink`/`parseGpxPoints`/`GpxPoint` path, not
   Health Connect.

This is the opposite of the milestone's initial (later-corrected) sequencing draft, which assumed a
single shared GPX-file pipeline fed both concerns. It doesn't: the completed-run engine needs no GPX
storage at all. What both paths *do* share is a common normalized trackpoint shape they each adapt
into (DAV-133) and the terrain-segmentation/course-demand calculation built on top of it (DAV-130/134/
135/136) — see `docs/trail-intelligence/README.md` for how the two phases divide the milestone's
remaining tickets.

## Acceptance criteria, addressed

- **Existing route/GPX source(s) identified**: two, described above, serving different purposes.
- **Relationship between an imported event and any external route file documented**: the Map tab's
  existing Calendar-description-URL path, above; DAV-146 upgrades this to the Calendar API's real
  `attachments` field as the primary path, per the milestone plan.
- **Health Connect limitations versus future Amazfit/Zepp direct data recorded**: Health Connect's
  `ExerciseRoute` requires a per-session consent flow (`ConsentRequired`) rather than being covered by
  this app's bulk grant — already handled by `SessionDetailScreen`'s existing consent UI, reused as-is
  for trail-metric computation. No Zepp-specific route API exists yet (P7 is still in its own
  investigation milestone); nothing here blocks on it.
- **Recommended v1 ingestion path selected**: Health Connect `ExerciseRoute` for completed trail runs,
  Drive-GPX-via-Calendar for planned-route previews — both above.
