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
