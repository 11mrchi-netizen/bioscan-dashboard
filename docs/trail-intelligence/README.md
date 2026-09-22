# Trail & route intelligence — data & model contract

Design documents for milestone "07 — Trail & route intelligence" (project
[P-DAV-21](https://linear.app/biodashboard/project/field-terminal-master-roadmap), team Davide). Two
phases share one milestone: a completed-trail-run performance engine (fed by Health Connect's own
recorded route) and a planned-route pre-activity preview (fed by a Calendar-linked Drive GPX). See
doc 01 for why those are two separate data paths rather than one shared GPX pipeline.

| Doc | Linear issue | Answers |
|---|---|---|
| [01-route-data-source-audit.md](01-route-data-source-audit.md) | [DAV-131](https://linear.app/biodashboard/issue/DAV-131) | Where real route/GPX data exists today, and which source feeds which phase |
| [02-trail-metric-conventions.md](02-trail-metric-conventions.md) | [DAV-129](https://linear.app/biodashboard/issue/DAV-129) | How Strava/TrainingPeaks/FFA-ITRA/coaching literature define each trail metric, and which canonical formula this app implements |
| [03-trail-metric-registry.md](03-trail-metric-registry.md) | [DAV-128](https://linear.app/biodashboard/issue/DAV-128) | The canonical name/unit/formula/provenance contract every trail metric maps into |
| [04-terrain-segmentation-rules.md](04-terrain-segmentation-rules.md) | [DAV-130](https://linear.app/biodashboard/issue/DAV-130) | Smoothing, noise threshold, grade bands, stop handling, and climb/descent comparability rules |
| [05-trail-metrics-ui-presentation.md](05-trail-metrics-ui-presentation.md) | (folds into DAV-144) | Where trail metrics render (Session Detail's new TRAIL card, Training tab's weekly rollup) and how they map onto the existing Futuristic Material components |
| [06-trail-metric-validation.md](06-trail-metric-validation.md) | [DAV-143](https://linear.app/biodashboard/issue/DAV-143) | The 40-case hand-verified fixture suite, and the real device backtest identified but pending emulator access |
| [07-planned-route-data-model.md](07-planned-route-data-model.md) | [DAV-147](https://linear.app/biodashboard/issue/DAV-147) | The `planned_routes` table (absorbs DAV-132) — Calendar/Drive identity, cache/versioning, course-demand-only metrics |
| [08-calendar-drive-sync.md](08-calendar-drive-sync.md) | [DAV-145](https://linear.app/biodashboard/issue/DAV-145) · [DAV-146](https://linear.app/biodashboard/issue/DAV-146) | Real Calendar incremental sync (syncToken, token-expiry recovery, dedup) and the Drive attachment-first GPX resolution priority |
| [09-planned-route-pipeline.md](09-planned-route-pipeline.md) | [DAV-148](https://linear.app/biodashboard/issue/DAV-148) | The one-call end-to-end pipeline wiring DAV-145/146/147 and Phase 1's shared engine together |
| [10-route-preview-ui.md](10-route-preview-ui.md) | [DAV-149](https://linear.app/biodashboard/issue/DAV-149) | Surfacing the cached preview in the Map tab's existing per-event sheet, live GPX summary kept as fallback |
| [11-route-preview-caching.md](11-route-preview-caching.md) | [DAV-151](https://linear.app/biodashboard/issue/DAV-151) | Metadata-checksum skip-recompute, freshness confirmation, and the row-cleanup cutoff |

## Prior art this builds on

- `data/SessionDetailRepository.kt` (`checkRouteAvailability`/`toRoutePoints`) already reads a
  completed session's Health Connect `ExerciseRoute` — Phase 1's real data source.
- `data/MapRepository.kt`/`domain/NextSession.kt` (`fetchUpcomingEvents`/`parseGpxLink`/
  `parseGpxPoints`) already read a live, unpersisted Calendar+Drive-GPX preview for the Map tab —
  Phase 2 extends this into a persisted, checksum-cached pipeline rather than rebuilding it.
- `auth/GoogleAuthorizationManager.kt` already holds the `calendar.readonly`+`drive.readonly` OAuth
  grant Phase 2 needs — reused as-is.
- `domain/NutritionResolver.kt` / `domain/analysis/NutritionDailyState.kt`'s pure-function-plus-
  repository-adapter split (milestone 06) is the pattern this milestone's shared terrain/metric engine
  follows, with two adapters (Health Connect route, GPX file) feeding one normalized trackpoint model.
