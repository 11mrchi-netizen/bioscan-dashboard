# Healthspan grade (Phase 8)

A general grade that appears on **one page only**: the User profile. It is product-defined and provisional, not clinically validated. The rest of the app keeps showing single-metric states and no cross-metric score; a unit test (`HealthspanTest.onlyTheProfileScreensUseHealthspan`) fails if any screen other than the profile screens uses it.

Code: `domain/aging/Healthspan.kt` (pure, all cut-offs as named constants), `data/HealthspanRepository.kt` (gathers inputs, decides nothing), `ui/screens/HealthspanCard.kt`. Methodology text shown behind the card's help button: registry key `healthspan`.

## Factors

Each factor ends as **favorable**, **neutral**, **opportunity**, or **not assessed** (with what is missing and one action).

| Factor | Input | Favorable | Neutral | Opportunity | Basis |
| -- | -- | -- | -- | -- | -- |
| Cardio fitness | Latest VO2max, placed against the age- and sex-matched table in `benchmark_artifacts` | percentile 50 or above | 25 to under 50 | under 25 | Mandsager 2018 groups fitness by age/sex percentile; the cut points are product-defined |
| Biological age | PhenoAge acceleration (biological minus calendar age) | 2 years younger or more | between | 2 years older or more | Levine 2018; the plus and minus 2 years are product-defined |
| Strength | Needs a strength benchmark | not assessed until one is installed | | | none exists in `benchmark_artifacts` |
| Body composition | Waist-to-height ratio from lab entries | under 0.50 | 0.50 to under 0.60 | 0.60 or more | Ashwell 2012 (0.5 boundary); 0.6 is product-defined |
| Sleep | Mean duration, last 30 days, and the regularity index (SRI) | at least 7 h and SRI 80 or more | one of the two | neither | Watson 2015 (7 h); Windred 2024 shows regularity matters; SRI 80 is product-defined |
| Activity | Median daily steps, last 30 days (0-step days dropped as watch-off) | "active" or "highly active" | "somewhat active" | "low active" or "sedentary" | Tudor-Locke and Bassett 2004 categories, the artifact already in the database |
| Blood lipids | Triglycerides and HDL cholesterol | no flag | one flag | both flagged | Alberti 2009: triglycerides 150 mg/dL or more; HDL under 40 (men) or 50 (women) |
| Blood glucose | HbA1c if present and valid, else fasting glucose | HbA1c under 5.7 % / glucose under 100 mg/dL | 5.7 to under 6.5 / 100 to under 126 | 6.5 or more / 126 or more | American Diabetes Association Standards of Care 2025 |

## Trust gate

A factor is assessed only if every input is: present; recent (labs and PhenoAge panel within 365 days, VO2max within 90); backed by enough days (14 nights of sleep, 14 days of steps); confidence met (SRI and PhenoAge); in a known unit that matches (no unit, or a different unit, is never assumed); free of conflicting values for the same marker on its latest date; and not assumed (no sex or date of birth means no sex-specific placement). Sleep and steps restart at a confirmed series break. When a factor fails, it says what is missing instead of guessing. The VO2max placement uses a table only if it covers the person's age and sex; it never falls back to the nearest table.

## Grade

Share of **assessed** factors that are favorable. With at least 5 of the 8 assessed it shows a letter (A at 80 % or more, B at 60 %, C at 40 %, otherwise D); with fewer it shows "PRELIMINARY" and no letter. The floor and the bands are provisional: there are no outcome data to calibrate them on and only a couple of lab draws to look at, so treat them as a starting point and expect to revise them.

## Deviations from the plan

- **TyG replaced.** The plan listed a triglyceride-glucose index. There is no widely agreed cut-off to cite for it, so the lipid factor uses the harmonised guideline thresholds for triglycerides and HDL, and glucose uses the ADA categories. TyG can be added if a defensible threshold is chosen.
- **HbA1c** is accepted but the account has none today; fasting glucose is used.
- **Strength** is always "not assessed" because no strength benchmark is installed.
- **Mock data removed.** The profile screen used to render sample domain levels and sample achievements while real ones loaded. That is gone: the running level now comes from the real VO2max placement, the other three domains read "Not enough data yet" until benchmarks exist, and achievements show only real ones.

## What it cannot say

Blood pressure, family history, smoking, diet, medication and much else are not inputs. Long sleep is not penalised. A reading counts as current for up to a year. A favorable grade is not low risk, and an unfavorable factor is a prompt to look, not a diagnosis.

## Changing it

Edit the named constants at the top of `Healthspan.kt` and the matching test in `HealthspanTest.kt`; keep the methodology text in `metric-registry.json` and this page in step with them.
