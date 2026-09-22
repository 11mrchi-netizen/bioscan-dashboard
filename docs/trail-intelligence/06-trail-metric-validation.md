# 06 — Trail-metric validation fixtures and backtests

**Linear:** [DAV-143](https://linear.app/biodashboard/issue/DAV-143) · **Status:** fixture validation complete, one real backtest pending device access

## Hand-calculated fixture validation (complete)

Every formula built in this milestone (DAV-133 through DAV-142) has its own hand-verified unit test,
matching this project's established convention (`NutritionResolverTest.kt`/`NutritionDailyStateTest.kt`
from milestone 06) — not a framework, not mocked data, real numbers computed by hand and checked
against the implementation's actual output:

| Test file | Covers | Real reference point |
|---|---|---|
| `TrackpointTest.kt` | Haversine distance, trackpoint accumulation | 1° latitude ≈ 111.19km |
| `TerrainSegmentationTest.kt` | Smoothing, hysteresis segmentation, stop handling | Doc 04's own noise-absorption and reversal rules |
| `CourseDemandTest.kt` | Mountain Index, KM-effort | Doc 02's own worked example (50K + 3,000m gain = MI 60) |
| `GradeDistributionTest.kt` | Grade-band classification, climb/descent structure | Hand-computed interval grades |
| `UphillPerformanceTest.kt` | VAM, GAP (Minetti polynomial), climb consistency | Doc 02's 5%-grade worked example |
| `DownhillPerformanceTest.kt` | Descent speed, GAP, descent consistency | Minetti polynomial, both grade signs |
| `LocomotionModeTest.kt` | Run/hike classification | Diedrich & Warren 1995's 2.1 m/s transition speed |
| `GradeEfficiencyTest.kt` | Vertical-speed/HR ratio, hike-dominant filtering | Hand-computed VAM/HR ratios |
| `DurabilityTest.kt` | Comparable-pair matching, VAM/pace degradation, HR decoupling | A full Minetti-polynomial calculation carried by hand |

**40 test cases** across 9 files, `./gradlew testDebugUnitTest` green throughout. This is the
"formula changes can be regression-tested" and "hand-calculated fixture tests cover Mountain Index,
KM-effort, grade, climbs and descents" acceptance criteria, satisfied directly.

## Real-data backtest — target identified, pending device access

DAV-143 also asks to "compare GPX-derived totals with device summaries" and "backtest degradation/
efficiency metrics on repeated or sufficiently long trail activities" against this account's real
history. The real target exists: session `id=11774` (2026-09-20, 20.58km, device-reported
`elevation_gain_m = 1118.0`, `avg_hr ≈ 147.9`, a real `health_connect_record_id`) is this account's
only trail run with actual Health Connect route data available today (the other 4 real trail sessions
in `exercise_sessions` predate Health Connect and carry no route). Doc 01 already covered
"GPS/elevation quality thresholds" and "device summaries" being unverifiable without pulling this
session's real `ExerciseRoute` — that pull needs a live Health Connect consent flow on a real device
or emulator, which isn't available from this environment right now (no `adb`/running emulator in this
session, unlike the milestone-06 session that had one already set up).

**Concrete next step, not yet run**: on-device, open Session Detail for session 11774, grant the
per-session route consent (the existing `RouteAvailability.ConsentRequired` flow), feed the returned
`RoutePoint` list through `trackpointsFromRoutePoints` → `smoothElevation` →
`segmentClimbsAndDescents` → `computeCourseDemand`, and compare the resulting `elevationGainM` against
the device's own `1118.0m`. Doc 04's own 10m hysteresis threshold is deliberately conservative
(matching Strava's non-barometric rule), so some under-counting relative to a barometric watch reading
is expected and would itself be a useful, documented finding — not a bug — consistent with doc 04's
own stated trade-off.

## What's deliberately not here

- GPX-derived backtests (Phase 2 hasn't been built yet — DAV-147 onward).
- Cross-domain state-model validation — DAV-143's own text scopes that to a later milestone (P12),
  not here.
