# Aging Profile — Phase 1 audit, formulas and scope

Audit for [DAV-222](https://linear.app/biodashboard/issue/DAV-222), plus the formula sourcing
for [DAV-223](https://linear.app/biodashboard/issue/DAV-223) (PhenoAge) and
[DAV-226](https://linear.app/biodashboard/issue/DAV-226) (Cardio Age), from milestone
"09A — Aging Profile" (DAV-221–233). Written before implementation, per DAV-222's own mandate.

## Data audit

**Lab data** (`lab_draws`/`lab_results`, a free-text `marker_name` EAV table): this account has
exactly **2 real lab draws** (2026-01-14, 2026-04-25). All 9 of PhenoAge's required biomarkers
(Albumin, Creatinine, "Fasting blood sugar", hs-CRP, Lymphocytes %, MCV, RDW, Alkaline
phosphatase, WBC) exist somewhere in the data, but **only the 2026-01-14 draw has all nine
together** — 2026-04-25 is missing alkaline phosphatase, glucose and WBC. Consequence for
Phase 1: exactly one real, complete PhenoAge result exists today, not a trend. The repository
must pick "the most recent draw with every required marker," not naively "the latest draw."

CRP is stored as hs-CRP, not standard CRP — accepted as equivalent (the modern universal CRP
assay), not treated as a gap.

For KDM (deferred, per the BioAge R package's NHANES III-trained panel: albumin, ALP, CRP,
total cholesterol, creatinine, HbA1c, SBP, BUN, uric acid, lymphocyte %, MCV, WBC) this
account is missing only **HbA1c** — worth knowing before that phase starts.

**Demographics**: no chronological age or sex existed anywhere in this app before this
milestone. `domain/comparison/PopulationContext(ageYears, sex, geography)` already existed as
a contract, consumed by `HeartTileScreen.kt`'s population comparisons, but was always
constructed with nulls. A new `user_profile` table (`date_of_birth`, `sex`) now backs it —
account-level, not device-local, since it's consumed by more than just Aging Profile.

**VO₂max**: already synced via Health Connect into `wearable_daily`, already surfaced on the
Training tab (`domain/Training.kt`'s `vo2MaxSeries`/`latestNonNullVo2Max`) — reused directly,
no second query path.

**Existing patterns reused, not reimplemented**:
- RCV logic (`domain/BloodworkEvaluation.kt`, `RCV = 3.1 × CVI`, EFLM Biological Variation
  Database, deliberately two-draws-only) — DAV-231's recalculation-cadence ask for blood-based
  clocks follows this same stance.
- `LabsTileScreen.kt`'s `FTCard` + per-marker row + `DotPlot` (isolated dots, never a
  connecting line) — the idiom Aging Profile's own History section follows.
- No canonical marker-name registry exists anywhere (`BiologicalVariation.kt` and
  `LabMarkerThemes.kt` each keep their own free-text map) — PhenoAge's required-marker set
  (`PHENOAGE_REQUIRED_MARKERS`, `domain/aging/PhenoAge.kt`) follows the same established
  per-concern convention rather than introducing a shared registry.

## PhenoAge formula (DAV-223)

Levine et al. 2018, "An epigenetic biomarker of aging for lifespan and healthspan" (*Aging*
(Albany NY) 10(4)). Linear combination of 9 biomarkers + chronological age, transformed
through a Gompertz mortality-hazard model:

```
xb = -19.907 - 0.0336·albumin(g/L) + 0.0095·creatinine(µmol/L) + 0.1953·glucose(mmol/L)
     + 0.0954·ln(CRP mg/L) - 0.0120·lymphocyte% + 0.0268·MCV + 0.3306·RDW
     + 0.00188·ALP + 0.0554·WBC + 0.0804·age

mortality_score = 1 - exp(-exp(xb) · (exp(120·γ) - 1) / γ),  γ = 0.0076927
PhenoAge = 141.50225 + ln(-0.00553 · ln(1 - mortality_score)) / 0.090165
```

Coefficients cross-checked against multiple independent public PhenoAge calculators during
research (all agreed). Implemented in `domain/aging/PhenoAge.kt`, unit conversions handled
internally so callers pass values in whatever unit `lab_results` actually stores them in.
Fixtures in `PhenoAgeTest.kt` are hand-computed independently (a small Python script, not this
Kotlin implementation) — verifying the code matches the published formula, not itself.

## Cardio Age formula (DAV-226)

FRIEND registry / Cooper Institute Aerobics Center Longitudinal Study 50th-percentile VO₂max
by age decade and sex, as tabulated in *ACSM's Guidelines for Exercise Testing and
Prescription* (11th ed., Table 4.7) — cross-checked against two independent published
transcriptions of the same table (both agreed on all 12 values):

| Age bracket | Men (ml/kg/min) | Women (ml/kg/min) |
|---|---|---|
| 20–29 | 48.0 | 37.6 |
| 30–39 | 42.4 | 30.2 |
| 40–49 | 37.8 | 26.7 |
| 50–59 | 32.6 | 23.4 |
| 60–69 | 28.2 | 20.0 |
| 70–79 | 24.4 | 18.3 |

`domain/aging/FunctionalAge.kt` linearly interpolates between decade midpoints (24.5, 34.5,
…) to invert measured VO₂max into a continuous age-equivalent, sex-specific. Returns
unavailable (never extrapolated) outside the reference population's own measured range, or
when sex isn't set.

## Phase 1 scope decisions

Confirmed with the user 2026-09-29: Phase 1 = contract (DAV-221) + this audit (DAV-222) +
PhenoAge (DAV-223) + Cardio Age (DAV-226, part of DAV-226's own scope) + a User-page-scoped
slice of DAV-227/232/233. **Deferred**: KDM (DAV-224) and homeostatic dysregulation (DAV-225)
both need a published reference-population coefficient table sourced from the literature —
not derivable from this account's 2 lab draws, and separate real research from PhenoAge's
fully-public formula. Molecular-clock adapters (DAV-228), the organ-system framework
(DAV-229), multi-measure aggregation (DAV-230, premature with one real measure), and full
cross-model validation (DAV-231, only meaningful once >1 model exists) are also deferred.

## User page integration

Per the user's decision: a compact "AGING" card on `UserProfileScreen.kt` (chronological
age + headline PhenoAge/Cardio Age + confidence, same treatment as the existing
`DomainLevelRow`), tapping through to `ui/screens/AgingProfileScreen.kt` for the full
Overview/Dimensions/History breakdown — not everything crammed onto the User page directly.
