package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.ResolvedLoad
import com.bioscan.fieldterminal.domain.training.definition.NumRange

// DAV-345. One-line text for a generated item and session, shared by the setup preview and the
// day view. Numbers only as the engine produced them: no rounding of prescriptions here.

private fun fmt(x: Double): String = if (x == Math.floor(x)) x.toInt().toString() else "%.2f".format(x).trimEnd('0').trimEnd('.')

fun NumRange.text(): String = if (isFixed) fmt(min) else "${fmt(min)}-${fmt(max)}"

fun ResolvedLoad.text(): String = when (this) {
    is ResolvedLoad.Barbell -> "${fmt(loadable.totalKg)} kg" + (if (loadable.platesPerSideKg.isEmpty()) " (bar)" else " (${loadable.platesPerSideKg.joinToString("+") { fmt(it) }} per side)")
    is ResolvedLoad.Added -> "+${fmt(addedKg)} kg"
    is ResolvedLoad.MaxRepsFraction -> "$reps reps (${fmt(pct)}% of max)"
    is ResolvedLoad.WorkUp -> "work up to a ${minRm}-${maxRm}RM"
    is ResolvedLoad.FixedKg -> "${fmt(loadable.totalKg)} kg"
    is ResolvedLoad.Rpe -> "RPE ${fmt(rpe)}"
    ResolvedLoad.Bodyweight -> "bodyweight"
    ResolvedLoad.NoLoad -> ""
    is ResolvedLoad.Unresolved -> "needs a max"
}

fun GeneratedItem.text(): String = buildList {
    add(exercise)
    val scheme = listOfNotNull(sets?.text(), reps?.text()).joinToString(" x ")
    if (scheme.isNotEmpty()) add(scheme)
    val l = load.text()
    if (l.isNotEmpty()) add("@ $l")
    technique?.let { add("[$it]") }
    if (projected) add("(projected)")
}.joinToString(" ")

fun GeneratedSession.summaryLines(): List<String> =
    if (conditioning != null) listOf(buildString {
        append(conditioning.label)
        conditioning.minutes?.let { append(" · ${it.text()} min") }
    }) else items.map { it.text() }
