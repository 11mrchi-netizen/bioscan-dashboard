# 11 — Route-preview caching, freshness and failure handling

**Linear:** [DAV-151](https://linear.app/biodashboard/issue/DAV-151) · **Status:** code complete, live verification pending device access

## Skip-recompute: a metadata call, not a re-download

`generatePreview()` now checks a Drive file's real `md5Checksum` via a **metadata-only** request
(`fields=md5Checksum`, no `alt=media`) before doing anything else. If that checksum matches what's
already stored on the cached `planned_routes` row, **and** both `parser_version` and
`terrain_analysis_version` still match the running app's own `TRACKPOINT_MODEL_VERSION`/
`SEGMENTATION_MODEL_VERSION` constants, nothing is downloaded or recomputed — only `computed_at` is
refreshed and any prior `is_stale`/`last_error` cleared. A version bump alone (no file change at all)
still forces a real recompute, since what changed is the *algorithm*, not the course.

This is cheaper than re-downloading and re-parsing to compare content, and it's why no raw GPX bytes
are cached anywhere (doc 07's `gpx_storage_path` column stays unused) — the metadata checksum already
answers "did anything change" without needing the file's own bytes on hand.

## Failure handling — already mostly true by construction

DAV-148 already wrote `generatePreview()` so a failure updates only `is_stale`/`last_error`, never the
real numeric columns — so "network/offline failures leave the last valid preview available with a
stale indicator" was already satisfied before this ticket added anything. What DAV-151 adds on top:
the fresh-confirmation path (above) actively **clears** a stale flag once a real check succeeds again,
so staleness reflects the *current* state, not a permanent scar from one past failure.

## Partial/incomplete GPX — already true by construction

`computePlannedRoutePreview()`'s `confidenceTier` (DAV-148, reusing `InputCompleteness` from the
Analysis Layer 2 contract) already reads `"minimal"` for a GPX with no usable elevation data. Nothing
new was needed here either — this ticket's acceptance criterion was already met by how DAV-148 was
built.

## Cleanup: rows, not files

Since no GPX bytes are ever cached in Storage, "prevent indefinite storage growth" is a row-cleanup
problem, not a file one: `cleanupPastPreviews()` deletes any `planned_routes` row whose
`event_start_time` is more than 7 days in the past (the same lookback window
`HealthConnectWriteBackRepository` already uses, reused rather than inventing a second number) — a
preview for a run that already happened or was long since skipped has nothing left to preview.

## Verification

`./gradlew compileDebugKotlin testDebugUnitTest` green. The metadata-checksum skip path and the
cleanup query both need a live Drive token and real `planned_routes` rows to verify end to end — the
same device access every other Phase 2 ticket has been waiting on.
