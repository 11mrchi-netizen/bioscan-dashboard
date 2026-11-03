package com.bioscan.fieldterminal.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.pow

// Amount in the body, estimated (docs/regimen/README.md, phase 4): each logged dose decays by half every half-life (the
// FDA label's figure on the item's reference card), and the doses add up. It assumes the whole dose is in at once, so it
// ignores absorption time and a depot injection's slow release, and it is an amount, not a blood level. An outline of how
// doses stack and wash out, labelled as such wherever it is shown.

data class LoggedDose(val at: Instant, val amount: Double)

/** The estimated amount still in the body at [at] from [doses] (doses after [at] are not counted). */
fun amountInBody(doses: List<LoggedDose>, halfLifeHours: Double, at: Instant): Double {
    if (halfLifeHours <= 0) return 0.0
    return doses.sumOf { d ->
        val h = Duration.between(d.at, at).toMinutes() / 60.0
        if (h < 0) 0.0 else d.amount * 0.5.pow(h / halfLifeHours)
    }
}

/** The curve from [from] to [to], one point every [stepHours]; each dose is drawn at its own time so peaks are not lost. */
fun amountCurve(doses: List<LoggedDose>, halfLifeHours: Double, from: Instant, to: Instant, stepHours: Long = 2): List<Pair<Instant, Double>> {
    if (!from.isBefore(to)) return emptyList()
    val times = (generateSequence(from) { it.plus(Duration.ofHours(stepHours)) }.takeWhile { !it.isAfter(to) }.toList() +
        doses.map { it.at }.filter { !it.isBefore(from) && !it.isAfter(to) } + to).distinct().sorted()
    return times.map { it to amountInBody(doses, halfLifeHours, it) }
}

/** How long the window should be: five half-lives (when an even schedule has about reached its steady state), 3 to 60 days. */
fun curveWindowDays(halfLifeHours: Double): Long = (halfLifeHours * 5 / 24).toLong().coerceIn(3, 60)
