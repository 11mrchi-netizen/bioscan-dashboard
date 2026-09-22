# 09 — The end-to-end planned-route preview pipeline

**Linear:** [DAV-148](https://linear.app/biodashboard/issue/DAV-148) · **Status:** code complete, live verification pending device access

## What runs

`data/PlannedRouteRepository.runPipeline()` is the one entry point: reads every real, upcoming,
non-cancelled event from `calendar_events` (DAV-145's own sync target), and for each one calls
`generatePreview()` — resolve a Drive GPX (DAV-146), download it, hand the raw text to
`domain/trail/PlannedRoutePreview.kt`'s pure `computePlannedRoutePreview()` (parse → smooth → segment
→ course demand + grade distribution, all of Phase 1's own shared engine pieces reused unchanged), and
upsert the result into `planned_routes` (DAV-147).

Matches this milestone's I/O-vs-pure split throughout: everything computable from a GPX string alone
lives in `domain/trail/PlannedRoutePreview.kt` (directly unit-tested, three fixtures covering a real
climb/descent shape, checksum determinism, and the minimal-confidence no-real-route case) — the
repository only downloads, calls that function, and saves.

## Failure handling, and what's deliberately not here yet

A failure on one event (no GPX linked, a bad download, an unparseable file) never stops the rest of
the pipeline — `generatePreview()` catches its own errors and returns a typed result
(`Success`/`NoGpxLinked`/`Failure`) rather than throwing past its boundary, the same convention
`CalendarSyncRepository` already established. A failure best-effort-marks an *existing* `planned_routes`
row's `last_error`, but doesn't attempt DAV-151's fuller freshness policy (retry cadence, first-ever-
failure visibility, checksum-based skip-recompute) — that's explicitly DAV-151's own job, not pulled
forward here just because the column already exists.

## Verification

`./gradlew compileDebugKotlin testDebugUnitTest` green. The pure preview function is fully
unit-tested; the repository's actual download-and-save path needs a live Drive/Calendar token and a
real linked event, the same device access DAV-143/144/145/146 are already waiting on.
