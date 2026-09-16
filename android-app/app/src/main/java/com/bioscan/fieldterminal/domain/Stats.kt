package com.bioscan.fieldterminal.domain

import kotlin.math.sqrt

// Phase A2 (Analysis Layer). This project's own domain layer had no generic
// mean/SD/median helper before this -- domain/Readiness.kt's
// computeHrvReadinessSeries() inlines its own mean/variance math rather than
// calling a shared one. These three are deliberately the first, kept
// minimal (population SD, matching that existing function's own convention
// of dividing by n rather than n-1, for consistency with it) rather than a
// general statistics library.
fun mean(values: List<Double>): Double = values.sum() / values.size

fun populationStdDev(values: List<Double>): Double {
    val m = mean(values)
    val variance = values.sumOf { (it - m) * (it - m) } / values.size
    return sqrt(variance)
}

fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}
