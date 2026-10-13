package com.bioscan.fieldterminal.domain.training

// Warm-up sets written into the Zepp template ahead of the work sets of a lift.
// Each pair is (reps, kg), lightest first; the template adds a rest after every one.
// An empty list means "no warm-up sets": the template then holds work sets only.
//
// The lifter's rule: one set of 10 with the empty bar (bodyweight for a weighted pull-up, where the
// load is the added weight), and when the work load is over 150% of the bar, one set of 3 at 70%.
fun warmupSets(workKg: Double, barKg: Double = 20.0, bodyweight: Boolean = false): List<Pair<Int, Double>> {
    if (workKg <= (if (bodyweight) 0.0 else barKg)) return emptyList()
    val ramp = mutableListOf(10 to (if (bodyweight) 0.0 else barKg))
    if (workKg > 1.5 * barKg) ramp += 3 to roundToStep(0.7 * workKg, 2.5)
    return ramp
}
