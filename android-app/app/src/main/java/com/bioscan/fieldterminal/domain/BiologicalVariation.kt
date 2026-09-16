package com.bioscan.fieldterminal.domain

// Evaluation Method Spec, Category 9: within-subject (CVI) and between-
// subject (CVG) biological variation percentages, per the spec's own named
// authoritative source (the EFLM Biological Variation Database, built from
// the Ricos et al. desirable-BV dataset). Real research, not estimated --
// fetched 2026-09-16 from a published mirror of that same table for every
// marker this account's real lab_results actually contains more than once
// (see ROADMAP.md's Phase A4 Category 9 entry for where each number came
// from).
//
// Two real, honest gaps, deliberately NOT worked around:
// - `eGFR` is a calculated/derived value (from creatinine via an equation
//   like CKD-EPI), not its own primary analyte in the standard BV database
//   -- no defensible CVI citation found for it. Creatinine itself IS
//   covered below, so its own RCV is real.
// - The white-cell differential *percentages* (Basophils/Eosinophils/
//   Lymphocytes/Monocytes/Neutrophils %) -- the standard database only
//   publishes CVI/CVG for these as absolute counts (10^9/L), a genuinely
//   different measurement (a ratio vs. a concentration, with its own
//   variance behavior). Applying the count-based CVI to a percentage value
//   would be a real citation mismatch, unlike the hs-CRP<->CRP case below.
//
// Any marker not present in this map (including DXA/body-composition
// measures that share this project's `lab_results` table but aren't blood
// chemistry at all -- e.g. "Body Fat Percentage (DXA)") gets value-vs-range
// display only, no RCV claim -- the spec's own "no CVA available" default,
// extended one step further to "no CVI available."
data class BiologicalVariation(val cvi: Double, val cvg: Double)

// hs-CRP shares its entry with plain CRP: both measure the identical
// biological analyte, differing only in assay sensitivity/reporting range
// -- biological variation is a property of the analyte in the body, not
// the assay, so the same CVI/CVG applies. Stated here, not assumed
// silently.
val BIOLOGICAL_VARIATION: Map<String, BiologicalVariation> = mapOf(
    "Albumin" to BiologicalVariation(cvi = 3.2, cvg = 4.75),
    "Creatinine" to BiologicalVariation(cvi = 5.95, cvg = 14.7),
    "Hematocrit" to BiologicalVariation(cvi = 2.7, cvg = 6.41),
    "Hemoglobin" to BiologicalVariation(cvi = 2.85, cvg = 6.8),
    "hs-CRP" to BiologicalVariation(cvi = 42.2, cvg = 76.3),
    "MCH" to BiologicalVariation(cvi = 1.4, cvg = 5.2),
    "MCHC" to BiologicalVariation(cvi = 1.06, cvg = 1.2),
    "MCV" to BiologicalVariation(cvi = 1.4, cvg = 4.85),
    "Platelets" to BiologicalVariation(cvi = 9.1, cvg = 21.9),
    "RBC" to BiologicalVariation(cvi = 3.2, cvg = 6.3),
    "RDW" to BiologicalVariation(cvi = 3.5, cvg = 5.7),
)
