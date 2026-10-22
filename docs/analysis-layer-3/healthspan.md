# Healthspan score (Phase 8)

A general score from 1 to 100 that appears on **one page only**: the User profile. It is product-defined and provisional, not clinically validated. The rest of the app keeps showing single-metric states and no cross-metric score; a unit test (`HealthspanTest.onlyTheProfileScreensUseHealthspan`) fails if any screen other than the profile screens uses it.

Code: `domain/aging/Healthspan.kt` (pure, every anchor a named constant), `data/HealthspanRepository.kt` (gathers inputs, decides nothing), `ui/screens/HealthspanCard.kt`, `ui/components/FTRadarChart.kt`. Methodology text shown behind the card's help button: registry key `healthspan` (methodology 2.0.0).

## The scale

Every factor is a whole number from 1 to 100, or **not assessed** (with what is missing and one action). One scale for all of them:

- **favorable** from 50;
- **neutral** from 25 to 49;
- an **opportunity** below 25.

What the number is depends on the factor, and the row says which:

- **Percentile for your age and sex** where a population table exists: cardio fitness, and handgrip once its table is installed.
- **Against clinical targets** for the rest: each cited cut-off is placed exactly on the 50 or 25 line, the optimum at 100, straight lines between.
- **Lifter standards** for barbell lifts.
- **Product-defined targets** for biological age.

A value exactly on a cut-off stays on the side its source puts it. A triglyceride of 150 mg/dL is "150 or more", so it scores 49 (neutral), not 50. Each metric keeps its cited comparison to choose its band, and the number is held inside that band. So the status of a single-input factor is the same as it was under the old grade.

## Factors and anchors

Anchors are value → score. The 50 and 25 points are the cited cut-offs; the points between and beyond them are product-defined unless a source is named.

| Factor | Input | 100 | 50 (favorable line) | 25 (opportunity line) | 1 | Source |
| -- | -- | -- | -- | -- | -- | -- |
| Cardio fitness | Latest VO2max (90 days), placed against the age- and sex-matched table in `benchmark_artifacts` | top | P50 | P25 | bottom | ACSM 11th ed. table; Mandsager 2018 groups by percentile |
| Biological age | PhenoAge acceleration (biological minus calendar age) | -10 y | -2 y | +2 y | +10 y | Liu, Levine et al. 2018 (PhenoAge); all points product-defined |
| Strength, lifts | Best estimated 1RM per lift over 183 days against bodyweight multiples (`domain/StrengthStandards.kt`); 3 lifts with a standard and a body weight from 90 days | elite | intermediate | novice | 0 | Lifter standards, `ladderFraction` (`domain/levels/StrengthStandards.kt`); the lower middle lift counts |
| Body composition | Waist-to-height ratio (latest of lab waist and check-in tape) | 0.40 | 0.50 | 0.60 | 0.70 | Ashwell 2012 (0.5); Ashwell & Gibson 2016 (0.4 / 0.5 / 0.6). Under 0.40 falls back to 50 at 0.30, never below the line |
| Sleep, hours | Mean duration, last 30 days | 8.0 h | 7.0 h | 6.0 h | 4.5 h | Watson 2015 (7 h); long sleep not penalised |
| Sleep, regularity | Sleep regularity index (SRI) | 90 | 80 | 60 | 40 | Windred 2024 shows regularity matters; all points product-defined |
| Activity | Median daily steps, last 30 days (0-step days dropped) | 12,500 | 10,000 | 7,500 | 2,500 | Tudor-Locke and Bassett 2004 bands; the band in `benchmark_artifacts` decides the status |
| Triglycerides | Lab | 100 | 150 | 200 | 500 | Miller 2011, AHA (under 100 optimal); Alberti 2009 (150); ATP III 2002 (200 high, 500 very high) |
| HDL cholesterol | Lab; needs sex | 60 | 40 men / 50 women | 30 / 40 | 20 / 30 | ATP III (60 protective); Alberti 2009 (40 / 50); lower points product-defined |
| HbA1c | Lab (wins over glucose when valid) | 5.4 % | 5.7 % | 6.5 % | 9.0 % | ADA 2025 (5.7, 6.5); Selvin 2010 (lowest risk 5.0-5.4) |
| Fasting glucose | Lab | 70-90 | 100 | 126 | 200 | ADA 2025 (100, 126); 70-90 and 200 product-defined; under 70 falls back to the line |

Two-part factors:

- **Lipids** (triglycerides, HDL) and **sleep** (hours, regularity) take the **weaker** part. When both parts are below 50 the factor is at most 24 (an opportunity), as two flags were under the old rule. Favorable still needs both parts fine; an average would let one good part hide the other. The row shows both parts' scores.
  - The only change from the old flag count: a single flag far past its 25 line is now an opportunity, not neutral. For example: triglycerides 200 or more with HDL fine; under 6 h of sleep with regularity 80 or more.
- **Strength** averages the lift score and the grip percentile when both pass their gates. Either one alone assesses the factor (grip alone is a percentile; lifts alone work as before). A grip reading counts from the same 183 days as the lifts.

### Past the table

`interpolatePercentile` never goes past a table's lowest and highest published point (DAV-196), and `comparePopulation` keeps that rule. For this score only, `extendedPercentile` (`domain/comparison/PopulationComparison.kt`) carries a value past the 10th or 90th percentile with a split normal:

- the spread below the median comes from P10, the spread above from P90;
- it is continuous at both ends and rises with the value;
- it is capped to 1..99.

Fitted to the live ACSM VO2max tables, it reproduces the published P25 and P75 within about 4 points in all ten rows. The women's rows are right-skewed (spread below the median about two-thirds of the spread above), which is why the spread is split. The row says "past the table's published range: estimated".

## Trust gate

A factor is assessed only if every input is:

- present;
- recent: labs and the PhenoAge panel within 365 days, VO2max within 90;
- backed by enough days: 14 nights of sleep, 14 days of steps;
- confidence met (SRI and PhenoAge);
- in a known unit that matches: no unit, or a different unit, is never assumed;
- free of conflicting values for the same marker on its latest date;
- not assumed: no sex or date of birth means no sex-specific placement.

Sleep and steps restart at a confirmed series break. When a factor fails, it says what is missing instead of guessing. A population table is used only if it covers the person's age and sex (`strictPlacement`); it never falls back to the nearest table.

## Score

The headline is the **mean of the assessed factors' scores**, rounded, shown as "NN / 100" when at least 5 of the 8 are assessed. With fewer it shows "PRELIMINARY". "N of M assessed factors favorable" stays under it.

On the radar each assessed factor sits at its score, so the 50 and 25 rings are the favorable and opportunity lines. A factor that could not be assessed is a dashed spoke labelled "—", with no marker (anywhere on the spoke would read as a score).

The floor, the equal weighting and the lines between the cited cut-offs are provisional. There are no outcome data to calibrate them on and only a couple of lab draws to look at, so treat them as a starting point and expect to revise them.

## Deviations from the plan

- **TyG replaced.** The plan listed a triglyceride-glucose index. There is no widely agreed cut-off to cite for it, so the lipid factor uses the guideline thresholds for triglycerides and HDL, and glucose uses the ADA categories. TyG can be added if a defensible threshold is chosen.
- **HbA1c** is accepted but the account has none today; fasting glucose is used.
- **Strength** uses lifter standards for lifts; no population benchmark exists for barbell lifts. Handgrip is logged (GRIP in the add-entry picker, one row per hand, best of the attempts in kg, table `grip_strength_log`) and scored as a percentile once the grip table (`grip_strength` in `benchmark_artifacts`) is installed; until then it is shown and not scored. Alternative names of one lift (for example "standing military press" and "standing barbell press") are merged so a lift counts once.
- **Mock data removed.** The profile screen used to render sample domain levels and sample achievements while real ones loaded. That is gone: the running level comes from the real VO2max placement, the other three domains read "Not enough data yet" until benchmarks exist, and achievements show only real ones.
- **Letter grade replaced** (methodology 2.0.0). The A-D letter (share of assessed factors favorable) became the 1-100 mean of factor scores, so two people both "favorable" at the 51st and 95th percentile no longer look the same.

## What it cannot say

- Blood pressure, family history, smoking, diet, medication and much else are not inputs.
- Long sleep, very high HDL and a low HbA1c are not penalised, although long sleep and low HbA1c go with higher mortality in some cohorts.
- Whether a triglyceride was fasting is not recorded.
- A reading counts as current for up to a year.
- A clinical-target score is a position between cut-offs, not a percentile.
- A favorable score is not low risk, and an unfavorable factor is a prompt to look, not a diagnosis.

## Changing it

Edit the named anchors at the top of `Healthspan.kt` and the matching tests in `HealthspanTest.kt`. Keep the methodology text in `metric-registry.json` and this page in step with them.
