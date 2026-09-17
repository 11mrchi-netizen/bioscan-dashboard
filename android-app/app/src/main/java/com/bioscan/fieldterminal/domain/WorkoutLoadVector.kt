package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseLibraryRow
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto

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

fun strengthSessionLoadVector(
    exercises: List<StrengthExerciseDto>,
    library: List<ExerciseLibraryRow>,
    durationMin: Double?,
    avgHr: Double?,
    maxHr: Double?,
    restingHr: Double?,
    caloriesActive: Double?,
): SessionLoadVector {
    val dims = mutableMapOf<LoadDimension, Double>()

    val cardio = trimp(
        EnduranceSessionInput(durationMin, null, avgHr, maxHr, null, null, null),
        restingHr,
    )
    cardio?.let { dims[LoadDimension.Cardiovascular] = it }

    val volume = sessionVolumeLoad(exercises)
    if (volume > 0) {
        dims[LoadDimension.Muscular] = volume
        dims[LoadDimension.Mechanical] = volume
    }

    exercises.flatMap { ex -> ex.sets.mapNotNull { relativeIntensityPercent(it, ex) } }
        .maxOrNull()?.let { dims[LoadDimension.Neuromuscular] = it }

    caloriesActive?.let { dims[LoadDimension.Metabolic] = it }
    sessionAverageRpe(exercises)?.let { dims[LoadDimension.Perceptual] = it }

    return SessionLoadVector(
        dimensions = dims,
        regional = sessionRegionalLoad(exercises, library),
        competingModels = emptyMap(), // DAV-58's parallel models are an endurance-session concept
    )
}

// Arbitrary, documented placeholder -- not derived from any specific study.
// A 1000m of cumulative gain roughly doubles the perceived "mechanical"
// cost of a given distance in this formula; real per-run elevation data
// only exists on 26% of runs and 19% of rides (see the data inventory), so
// this dimension is already sparse regardless of the coefficient's
// accuracy. Flagged here specifically for revision once real validation
// data exists (DAV-62's backtest) rather than left as an unlabeled magic
// number.
private const val ELEVATION_MECHANICAL_SCALE_M = 1000.0

fun enduranceSessionLoadVector(
    session: EnduranceSessionInput,
    restingHr: Double?,
    caloriesActive: Double?,
    thresholdHr: Double?,
    thresholdPaceMinPerKm: Double?,
    ftpWatts: Double?,
    elevationGainM: Double?,
): SessionLoadVector {
    val dims = mutableMapOf<LoadDimension, Double>()

    trimp(session, restingHr)?.let { dims[LoadDimension.Cardiovascular] = it }
    // Muscular: not computed for endurance -- see the mapping table above.
    elevationGainM?.let { gain ->
        session.distanceKm?.let { distance ->
            if (distance > 0) dims[LoadDimension.Mechanical] = distance * (1.0 + gain / ELEVATION_MECHANICAL_SCALE_M)
        }
    }
    caloriesActive?.let { dims[LoadDimension.Metabolic] = it }
    // Neuromuscular: not computed for endurance -- no power/velocity data available.
    session.rpe?.let { dims[LoadDimension.Perceptual] = it.toDouble() }

    val competing = buildMap {
        trimp(session, restingHr)?.let { put("trimp", it) }
        sRpeLoad(session)?.let { put("srpe", it) }
        thresholdRelativeIntensityPercent(session, thresholdHr, thresholdPaceMinPerKm)?.let { put("threshold_relative_pct", it) }
        powerPercentOfFtp(session, ftpWatts)?.let { put("power_pct_ftp", it) }
    }

    return SessionLoadVector(dimensions = dims, regional = emptyMap(), competingModels = competing)
}
