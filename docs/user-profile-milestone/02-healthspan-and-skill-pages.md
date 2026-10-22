# User tab: Healthspan radar, live skill levels, skill pages

Status: implemented on branch `ccr-b7b17b42-flzlrb` (not compiled in the authoring sandbox; run
`cd android-app && ./gradlew compileDebugKotlin testDebugUnitTest`).

## What changed

- **Healthspan**: the grade, its eight factors and trust gate are `feat/analysis-layer-3`'s (`domain/aging/Healthspan.kt`,
  `docs/analysis-layer-3/healthspan.md`) and are unchanged. `ui/screens/HealthspanCard.kt` now also draws them as an octagon
  (`FTRadarChart`): ring = status, hollow = not assessed, tap a vertex or row for the reading, what is missing and the action.
  The rows are collapsed to one line each (name + status) until tapped.
- **Domain levels were mock data** (`mockUserProfileData()`); removed. Levels now come from `domain/levels/SkillLevel.kt`:
  Running = VO2max percentile from the age- and sex-matched benchmark table (`vo2Placement`, the same placement the Healthspan
  cardio factor uses; fallback: own recent road pace, provisional); Strength = median ladder position over lifts with a standard
  (`domain/StrengthStandards.kt`, shared with Healthspan; 6-month window); Conditioning = last 4 weeks vs the person's own best 4 weeks in 90 days.
- **Skill pages** (`ui/screens/user/`): Running, Strength, Conditioning, opened from three buttons under the Aging card
  (routes `skill_running` / `skill_strength` / `skill_conditioning`). The VO2max / lactate-threshold and efficiency charts moved
  here from Status > Training, which keeps its weekly rollup and an "OPEN RUNNING / OPEN STRENGTH" link.
- Mountain is no longer a skill (`AchievementDomain.MOUNTAIN`, `mountainLevel` removed). Trail metrics in Session Detail are untouched.

## Known gaps (deliberate)

- Strength standards are lifter conventions (bodyweight multiples): not population percentiles, not adjusted for sex or age.
- Running page has no running-only CTL/ATL/TSB yet (needs `type` in `AnalysisRepository.loadExerciseSessionsForTrainingLoad`).
- Strength page has no movement-pattern / regional balance section yet.
- Conditioning has no intervals, power or zone data; the page shows consistency, linked wearable heart rate, block comparisons and template/drill mix.
- The User page's achievement cards and old Domain Levels card are replaced by the three skill buttons and pages (records and
  the PR timeline live on the pages). `setE1rmKg` (Epley, 1-12 reps, shared with `StrengthLoad`) drives the page charts; the
  Healthspan strength factor keeps its own `estimatedOneRepMaxKg`.
