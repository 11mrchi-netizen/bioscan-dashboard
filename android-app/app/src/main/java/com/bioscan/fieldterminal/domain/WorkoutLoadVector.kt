package com.bioscan.fieldterminal.domain

// DAV-56 (Analysis Layer 2, milestone 2) -- the capstone of the multimodal
// load engine, combining DAV-57 (strength) and DAV-58 (endurance) into one
// per-session structure across cardiovascular/muscular/mechanical/
// metabolic/neuromuscular/perceptual dimensions, per DAV-53's "avoid
// collapsing dimensions prematurely" principle. `regional` (DAV-65) and
// `competingModels` (DAV-58's parallel endurance models) stay separate
// fields rather than dimensions, matching the DAV-67 output-contract sketch.
//
// Deliberately a lighter-weight version of DAV-67's full sketch: each
// dimension here is a plain `Double?` (present = computed, absent = a real
// gap per DAV-66's missingness rule) rather than a `DimensionLoad` carrying
// its own `Confidence`. A single session's dimension value is a point
// measurement with no natural "n of N" depth to report -- Confidence's
// have/need shape represents something real for a multi-day rolling window
// (DAV-59) but not for one session's own cardiovascular load. Revisit this
// once DAV-59 actually needs to propagate confidence through these values.

enum class LoadDimension { Cardiovascular, Muscular, Mechanical, Metabolic, Neuromuscular, Perceptual }

data class SessionLoadVector(
    val dimensions: Map<LoadDimension, Double>,
    val regional: Map<BodyRegion, Double>,
    val competingModels: Map<String, Double>,
)

// Mapping confirmed with the user before implementing (real ambiguity: how
// abstract exercise-science dimensions map onto this account's actual
// available fields, not something with one correct answer):
//
//               | strength                          | endurance
// Cardiovascular| TRIMP (avg_hr/max_hr present on   | TRIMP (DAV-58)
//               | ~98% of real HC strength sessions)|
// Muscular      | volume load (reps x weight)       | not computed -- no
//               |                                    | established analog
// Mechanical    | volume load again (cumulative     | elevation-adjusted
//               | tissue/tendon stress)              | distance, when available
// Metabolic     | calories_active / calories_total   | same
// Neuromuscular | peak relative intensity (max %1RM | not computed -- needs
//               | in session -- heavy singles stress| power/velocity data
//               | the CNS differently than volume)  | this account doesn't have
// Perceptual    | session-average RPE                | session RPE
// Regional      | DAV-65 anatomical vector           | not applicable -- no
//               |                                    | exercise identity to map

