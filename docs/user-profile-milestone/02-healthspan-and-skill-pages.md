# User tab: Healthspan heptagon, live skill levels, skill pages

Status: implemented on branch `ccr-b7b17b42-flzlrb` (not compiled in the authoring sandbox; run
`cd android-app && ./gradlew compileDebugKotlin testDebugUnitTest`).

## What changed

- **Healthspan** (`domain/healthspan/`): seven axes, each 0-100 from an existing evaluator, one equal-weight headline
  over the axes that resolved. `FTRadarChart` draws the heptagon; a contribution list shows each axis's share of the
  headline and its headroom. See the "One transparent composite" amendment in
  `design/ANALYTICAL_PRESENTATION_CONTRACT.md`.
- **Domain levels were mock data** (`mockUserProfileData()`); removed. Levels now come from `domain/levels/SkillLevel.kt`:
  Running = VO2max vs the FRIEND/ACSM age-and-sex norm (fallback: own recent road pace, provisional); Strength = mean
  ladder position over lifts with a standard (`StrengthStandards.kt`, extracted from the old private UI table, resolved
  through `movementKey`); Conditioning = last 4 weeks vs the person's own best 4 weeks in 90 days.
- **Skill pages** (`ui/screens/user/`): Running, Strength, Conditioning, opened from three buttons under the Aging card
  (routes `skill_running` / `skill_strength` / `skill_conditioning`). The VO2max / lactate-threshold and efficiency charts moved
  here from Status > Training, which keeps its weekly rollup and an "OPEN RUNNING / OPEN STRENGTH" link.
- Mountain is no longer a skill (`AchievementDomain.MOUNTAIN`, `mountainLevel` removed). Trail metrics in Session Detail are untouched.

## Known gaps (deliberate)

- Strength standards are the app's own bodyweight multiples: unsourced and not sex-adjusted. Labelled "app reference".
- Nutrition axis = protein inside the AMDR band + (if set) calorie-target adherence. Hydration is not part of it: no
  daily hydration series is loaded centrally.
- Running page has no running-only CTL/ATL/TSB yet (needs `type` in `AnalysisRepository.loadExerciseSessionsForTrainingLoad`).
- Strength page has no movement-pattern / regional balance section yet.
- Conditioning has no intervals, power or zone data; the page shows consistency, linked wearable heart rate, block comparisons and template/drill mix.
- `AchievementsRepository` (best-set PR cards) is no longer used by the User page; the e1RM definition is now the single
  `setE1rmKg` (Epley, 1-12 reps) shared with `StrengthLoad`.
