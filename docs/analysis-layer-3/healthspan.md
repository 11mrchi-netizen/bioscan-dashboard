# Healthspan composite score (Phase 8)

A general composite score on a **1-100 scale** that appears on **one page only**: the User profile. It is product-defined and provisional, not clinically validated. The rest of the app keeps showing single-metric states and no cross-metric score; a unit test (`HealthspanTest.onlyTheProfileScreensUseHealthspan`) fails if any screen other than the profile screens uses it.

Code: `domain/aging/Healthspan.kt` (pure, all cut-offs and scoring formulas), `data/HealthspanRepository.kt` (gathers inputs, decides nothing), `ui/screens/HealthspanCard.kt`, `ui/components/FTRadarChart.kt`. Methodology text shown behind the card's help button: registry key `healthspan`.

## Factors & 0-100 Continuous Scoring

Each factor produces a continuous **0-100 score** when assessed (or **unassessed** with what is missing and one action). Score ranges translate to statuses for tags and colors: **Favorable** (≥ 75), **Neutral** (50 to 74), or **Opportunity** (< 50).

| Factor | Input | 100 Score Target | 50 Score Baseline | Scoring Curve / Continuous Function |
| -- | -- | -- | -- | -- |
| Cardio fitness | VO2max age/sex population percentile | Percentile 100 | Percentile 50 | Direct population percentile (1-100) |
| Biological age | PhenoAge acceleration normalized by chronological age | -20% acceleration (-10 y at age 50) | 0% acceleration | Linear relative acceleration scaling (-20% accel = 100, 0% = 70, +20% = 20) |
| Strength | Lift best estimated 1RM vs bodyweight multiples (`domain/StrengthStandards.kt`) | Elite lifter standard | Intermediate lifter standard | Continuous linear interpolation between standards (Untrained=20, Novice=40, Intermediate=60, Advanced=80, Elite=100) |
| Body composition | Waist-to-height ratio (WHtR) | WHtR ≤ 0.45 | WHtR 0.57 | Continuous piecewise linear scaling (≤0.45 = 100, 0.50 = 75, 0.60 = 40, ≥0.65 = 10) |
| Sleep | Duration and regularity index (SRI) | 7-9 h and SRI ≥ 80 | 6 h or SRI 55 | 50% duration score (7-9h = 100, 6h = 60, 5h = 20) + 50% SRI score (SRI 80+ = 100, 50 = 40) |
| Activity | Daily steps median | ≥ 12,500 steps/day | 5,500 steps/day | Continuous piecewise linear scale (≤2,500 = 20, 5,000 = 45, 7,500 = 70, 10,000 = 90, ≥12,500 = 100) |
| Blood lipids | Triglycerides and HDL cholesterol | TG ≤ 100 mg/dL, HDL ≥ 60 mg/dL | TG 160 mg/dL, HDL 40 mg/dL | 50% TG continuous score + 50% HDL continuous score relative to optimal targets |
| Blood glucose | HbA1c if present, else Fasting Glucose | HbA1c ≤ 5.4% / Glucose ≤ 90 mg/dL | HbA1c 6.2% / Glucose 110 mg/dL | Piecewise continuous scaling (HbA1c 5.4% = 100, 5.7% = 75, 6.5% = 35 / Glucose 90 = 100, 100 = 75, 126 = 35) |

## Trust gate

A factor is assessed only if every input is: present; recent (labs and PhenoAge panel within 365 days, VO2max within 90); backed by enough days (14 nights of sleep, 14 days of steps); confidence met (SRI and PhenoAge); in a known unit that matches; free of conflicting values for the same marker; and not assumed. Sleep and steps restart at a confirmed series break. When a factor fails, it says what is missing instead of guessing.

## Overall Composite Score

The overall Healthspan composite score is the **arithmetic average (0-100)** of all assessed factor scores. With at least 5 of the 8 factors assessed, the overall score is displayed (e.g. `82 / 100`); with fewer than 5 assessed factors, it shows "PRELIMINARY".

## Radar Chart Plotting

The radar chart (`FTRadarChart`) plots each assessed factor's vertex directly at its calculated numerical 0-100 score along its spoke axis (with concentric reference rings at 25, 50, 75, 100). Unassessed factors are shown as hollow dashed spokes and excluded from the polygon.
