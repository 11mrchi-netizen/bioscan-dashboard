# 12 — What the first real on-phone check found

**Linear:** [DAV-184](https://linear.app/biodashboard/issue/DAV-184) · [DAV-185](https://linear.app/biodashboard/issue/DAV-185) · [DAV-186](https://linear.app/biodashboard/issue/DAV-186) · [DAV-187](https://linear.app/biodashboard/issue/DAV-187) · **Status:** code complete, live device re-check pending

Phase 1 (docs 01-06) was verified with hand-fixtures and pre-tagged rows. This is what turned up
the first time it was checked against a real, just-completed trail run and the Training tab on a
physical phone.

## Distance triple-counting in the live splits/PACE series

`SessionDetailRepository.loadTimeSeries()` summed every `DistanceRecord` Health Connect returns for
the session window across every source app, with no dedup — SPLITS showed splits up to 66km on a
real 21km run (~3.1x). This is the exact bug `HealthConnectExerciseSyncRepository.buildRow()`
already found and fixed for the stored `distance_km` aggregate (see that file's own comment,
referencing DAV-79/153: real HC-sourced runs came out 1.6-3.2x true distance whenever more than one
app/device reported distance for the same workout) — it just never reached this second, independent
on-demand read path built for the live per-km chart. Fixed the same way: group by
`metadata.dataOrigin.packageName`, take the single largest-total source, build the cumulative curve
from only that source's records.

## The TRAIL card needs a manual tag first, and never said so

`details.route_type` defaults to null and is only ever set by the manual road/trail/mixed/track chip
picker in the add/edit sheet — Health Connect has no distinguishing signal to auto-classify by (the
exercise just comes through as plain "Running," confirmed with the user). Tagging a run `trail` by
hand is the intended flow, not a gap. What was a real bug: even after tagging, the TRAIL card
rendered nothing at all whenever route points weren't ready yet (no recorded route, or Health
Connect's per-session route consent still pending) — indistinguishable from broken. Fixed by
reusing the ROUTE card's own `ConsentRequired`/`NoRoute` messaging pattern for the TRAIL card's own
not-ready states.

## No route-shape visualization existed

`RouteMiniMap` is a flat 2D polyline on a map; `TrailCard` was pure text stat lines. Doc 05 (folded
into DAV-144) only ever planned the text card — an elevation profile was never built or scheduled.
Added `domain/trail/TrailElevationProfile.kt`'s `elevationProfile()`, reusing DAV-134's existing
smoothing pass, rendered through the existing `LineChart` composable (already axis-agnostic —
`PerformanceChartCard` already feeds it elapsed seconds; this feeds it cumulative distance instead).
Grade-band coloring deferred as a real future enhancement, not needed for a first pass.

## No combined session list on the Training tab

`TrainingScreen.kt` already fetched all 200 recent `exercise_sessions` rows in one query for its
weekly rollups and threw the raw list away. Reused that fetch: added a `recentSessions` list to
`TrainingOverview`, rendered as a simple tappable list at the bottom of the Training tab, wired to
the same `session_detail/{id}` route the Log tab already opens.

## Verification

`./gradlew compileDebugKotlin testDebugUnitTest` green. No new unit test for the distance-dedup fix
— this class of Health-Connect-record-shaped logic isn't unit-tested anywhere else in this codebase
either (`buildRow()`'s own identical fix has none), verified live on-device instead, consistent with
that existing convention. Live re-check pending: the phone disconnected mid-session before the
updated build could be pushed.
