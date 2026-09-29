# KDM investigation and milestone wrap-up

Follow-up to [01-phase-1-audit-and-formulas.md](01-phase-1-audit-and-formulas.md). Records why
KDM and homeostatic dysregulation weren't pursued further this pass, and how the rest of the
milestone was closed out.

## KDM (DAV-224) — investigation findings

Unlike PhenoAge, KDM is not one fixed published formula — it's a method that must be *trained*
on a reference population. Investigated two paths to a reproducible implementation:

**1. Port the `BioAge` R package's algorithm (Kwon & Belsky 2021, GeroScience) to Python.**
Downloaded its bundled NHANES III dataset (public US government survey data) and replicated
`kdm_calc()`'s exact per-biomarker regression + combination formula in Python. Validation:

- The package's own `phenoage0` column (their precomputed PhenoAge on the same NHANES III
  data) matched a from-scratch Python re-implementation to the coefficient's full precision
  once the exact production coefficients were found in `data-raw/nhanes_all.R` (more precise
  than the public-calculator-sourced values used in Phase 1 — e.g. RDW's coefficient is
  `0.3306156`, not the rounded `0.3306` Phase 1 shipped; worth a follow-up decimal-precision
  update to `PhenoAge.kt`, low priority since the difference is negligible in practice).
- KDM itself: per-biomarker regression fits matched almost exactly (age-acceleration
  correlation of 0.9999999999 against the package's own precomputed `kdm0` column), but the
  final combined KDM values were consistently **2.1749× larger in magnitude** than the
  reference — an exact scalar factor, not noise. This points to the final age-weighting term
  (`s_ba2`, how much weight chronological age gets versus the biomarker signal in the
  combination formula) being computed slightly differently between the Python port and the
  original R, and the cause wasn't found — R isn't available in this environment to step
  through intermediate values directly, and further numerical guessing didn't converge.

**2. Look for an external library or service.** No hosted "biological age" API exists.
`biolearn` (the aging-research community's actively-maintained Python library) doesn't
implement KDM at all — only epigenetic/DNA-methylation clocks, plus one blood-based PhenoAge
function whose own RDW coefficient (`0.3356`) has a small transcription error relative to the
authoritative source (`0.3306`) — a useful confirmation that even established libraries in
this space carry their own small errors, not a path to more confidence than manual
verification already gave.

**Decision** (confirmed with the user): don't ship an unresolved 2.17× calibration gap in a
number presented as a biological age. KDM is set aside, not built. Homeostatic dysregulation
(DAV-225) has the identical structural problem — a reference mean vector and covariance
matrix that must come from a published population, not from this account's 2 lab draws — and
is set aside for the same reason without a separate investigation.

## Milestone wrap-up

With KDM and homeostatic dysregulation set aside, and the remaining issues (DAV-227–233)
re-reviewed against what PhenoAge + Cardio Age alone actually support:

- **DAV-227, 230, 232, 233** — closed. Their acceptance criteria are met for the two models
  that exist today (state/acceleration/pace kept distinct; parallel measures presented without
  averaging; UI answers DAV-232's explainability checklist per metric, including population
  applicability and recalculation timing, added in this pass). What's left in each — cross-model
  agreement, a benchmark artifact for biological age, organ-system/molecular sections — is
  genuinely contingent on more models existing, not something more effort against today's data
  could close.
- **DAV-224, 225, 228, 229, 231** — cancelled, consolidated into one forward-looking issue
  (link added to each). DAV-228 (molecular-clock adapters) and DAV-229 (organ-system framework)
  were reconsidered on their own merits, not just as KDM/HD dependents: both are real,
  buildable data models, but with no import pipeline and only one populated domain
  (cardiovascular, from Cardio Age) respectively, building them now would be scaffolding
  ahead of any real data to put in it — deferred rather than spec'd out further.
