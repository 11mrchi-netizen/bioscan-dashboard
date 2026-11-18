package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import java.time.LocalDate

// Ending a block early (Nov 2026): the planned sessions after the last day are kept but marked superseded, the block ends
// on that day, and the maxes stay where they are unless the lifter asks to step them up (the books step maxes up only
// after a completed cycle). Pure; the repository writes it. Ported to supabase/functions/_shared/training-core/blockEnd.ts.

/** A planned session as ending a block needs it: its day, status, and the heaviest % of 1RM in its prescription. */
data class PlannedLite(val id: Long, val date: LocalDate, val weekIndex: Int, val status: String, val topPct: Double?)

data class DroppedWeek(val weekIndex: Int, val sessions: Int, val topPct: Double?)

data class EndEarlyPreview(val kept: Int, val dropped: Int, val droppedWeeks: List<DroppedWeek>, val heaviestDroppedPct: Double?)

/** What ending on [lastDay] keeps and drops: only sessions still planned after it are dropped (done ones stay). */
fun endEarlyPreview(sessions: List<PlannedLite>, lastDay: LocalDate): EndEarlyPreview {
    val dropped = sessions.filter { it.date.isAfter(lastDay) && it.status == "planned" }
    val weeks = dropped.groupBy { it.weekIndex }.toSortedMap().map { (w, list) -> DroppedWeek(w, list.size, list.mapNotNull { it.topPct }.maxOrNull()) }
    return EndEarlyPreview(sessions.size - dropped.size, dropped.size, weeks, dropped.mapNotNull { it.topPct }.maxOrNull())
}

data class MaxStep(val exercise: String, val fromKg: Double, val toKg: Double)

/**
 * The maxes one block's progression would step up: each exercise of [module] (the lifter's choice, else the slot's
 * standard) by the module's upper or lower increment on the plate step. Empty without a progression. The same rule the
 * generator applies between blocks (BlockGenerator.kt, Gen.progress).
 */
fun stepUpMaxes(module: StrengthModuleDef, choice: ModuleChoice, oneRm: (String) -> Double?, stepKg: Double): List<MaxStep> {
    val prog = module.progression ?: return emptyList()
    val out = mutableListOf<MaxStep>()
    for (slot in module.slots) {
        val names = (if (isCluster(module, slot)) choice.exercises[slot.id].orEmpty() else choice.exercises[slot.id].orEmpty().take(1))
            .ifEmpty { if (slot.pick == null) listOf(slot.standard) else emptyList() }
        val lower = slot.role == "squat" || slot.role == "hinge"
        for (name in names) {
            if (out.any { it.exercise == name }) continue
            val from = oneRm(name) ?: continue
            out += MaxStep(name, from, from + progressionIncrementKg(if (lower) prog.lower else prog.upper, prog.unit, stepKg))
        }
    }
    return out
}
