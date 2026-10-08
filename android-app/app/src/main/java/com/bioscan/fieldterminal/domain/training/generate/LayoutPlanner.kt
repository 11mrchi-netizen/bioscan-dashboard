package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import java.time.DayOfWeek

// DAV-345. Suggests how a strength template and a conditioning protocol share the week, and
// says plainly what looks wrong with a layout the lifter has edited. Warnings never block.

private fun DayOfWeek.gapTo(other: DayOfWeek): Int = (other.value - value + 7) % 7

private fun conditioningKinds(p: ConditioningProtocolDef) = setOf("lic", "hic", "wc")

// The suggested week: strength on the template's own days, conditioning from the protocol's
// layout (or spread over the free days when it has none).
fun suggestLayout(strength: StrengthModuleDef?, protocol: ConditioningProtocolDef?, startWeekday: DayOfWeek, conditioningPerWeek: Int? = null): List<DayPlan> {
    fun at(position: Int) = startWeekday.plus((position - 1).toLong())
    val strengthByDay = strength?.let { m -> m.dayPositions.mapIndexed { i, pos -> at(pos) to i }.toMap() }.orEmpty()
    val cond = mutableMapOf<DayOfWeek, CondSlot>()
    if (protocol != null) {
        if (protocol.defaultLayout.isNotEmpty()) {
            protocol.defaultLayout.filter { it.kind in conditioningKinds(protocol) }.forEach { cond.putIfAbsent(at(it.day), CondSlot(it.kind, it.minutes)) }
        } else {
            val b = protocol.budget
            val n = (conditioningPerWeek ?: b?.sessionsPerWeek?.min?.toInt() ?: b?.minConditioningSessionsPerWeek ?: 1).coerceIn(0, 7)
            val free = DayOfWeek.entries.map { startWeekday.plus((it.ordinal).toLong()) }.distinct().sortedBy { startWeekday.gapTo(it) }.filter { it !in strengthByDay }
            val kind = if (protocol.suggested.hic.isNotEmpty() && protocol.suggested.lic.isEmpty()) "hic" else "lic"
            val count = minOf(n, free.size)
            for (i in 0 until count) cond[free[(i * free.size) / count]] = CondSlot(kind)
        }
    }
    // Hard conditioning the day after a deadlift session moves to the next free day (the books say to reposition it).
    if (strength != null) {
        val hinge = hingeSessions(strength)
        val hingeDays = strengthByDay.filterValues { it in hinge }.keys
        for ((day, slot) in cond.toMap()) {
            if (slot.kind != "hic" || day.minus(1) !in hingeDays) continue
            val target = (1..6).map { day.plus(it.toLong()) }.takeWhile { startWeekday.gapTo(it) > startWeekday.gapTo(day) }
                .firstOrNull { it !in cond && it !in strengthByDay && it.minus(1) !in hingeDays }
            if (target != null) { cond.remove(day); cond[target] = slot }
        }
    }
    return (0..6).map { startWeekday.plus(it.toLong()) }.map { d -> DayPlan(d, strengthByDay[d], cond[d]) }
}

private fun hingeSessions(m: StrengthModuleDef): Set<Int> {
    val roles = m.slots.associate { it.id to it.role }
    return m.sessions.mapIndexedNotNull { i, s -> if (s.slots.any { roles[it] == "hinge" }) i else null }.toSet()
}

fun layoutWarnings(layout: List<DayPlan>, strength: StrengthModuleDef?, protocol: ConditioningProtocolDef?): List<String> {
    val out = mutableListOf<String>()
    val days = layout.associateBy { it.weekday }
    fun next(d: DayOfWeek) = days[d.plus(1)]

    if (strength != null) {
        val placed = layout.mapNotNull { it.strengthSession }
        val missing = strength.sessions.indices.filter { it !in placed }
        if (missing.isNotEmpty()) out += "${strength.title}: ${missing.size} of ${strength.sessions.size} strength sessions are not on a day yet"
        if (placed.size != placed.distinct().size) out += "A strength session is on two days"
        // Back-to-back strength days only matter when the template itself spaces its sessions.
        val templateAdjacent = strength.dayPositions.zipWithNext().any { (a, b) -> b - a == 1 }
        if (!templateAdjacent) {
            layout.filter { it.strengthSession != null }.forEach { d ->
                if (next(d.weekday)?.strengthSession != null) out += "Strength on ${d.weekday.short()} and ${d.weekday.plus(1).short()} back to back: ${strength.title} spaces its sessions"
            }
        }
        val hinge = hingeSessions(strength)
        layout.forEach { d ->
            val c = d.conditioning ?: return@forEach
            if (c.kind != "hic") return@forEach
            if (d.strengthSession in hinge) out += "Hard conditioning on ${d.weekday.short()} shares a day with a deadlift session"
            days[d.weekday.minus(1)]?.strengthSession?.let { if (it in hinge) out += "Hard conditioning on ${d.weekday.short()} follows a deadlift day: consider moving one of them" }
        }
    }
    val restDays = layout.count { it.isRest }
    val minRest = protocol?.budget?.minRestDaysPerWeek ?: 1
    if (restDays < minRest) out += "Only $restDays rest day(s) in the week; ${protocol?.title ?: "the plan"} asks for at least $minRest"

    if (protocol != null) {
        val b = protocol.budget
        val lic = layout.count { it.conditioning?.kind == "lic" }
        val hic = layout.count { it.conditioning?.kind == "hic" }
        val all = layout.count { it.conditioning != null }
        if ((b?.lowIntensityMinutesPerWeek?.min ?: 0.0) > 0 && lic == 0) out += "${protocol.title} expects low-intensity work but no day has a LIC session"
        b?.highIntensityPerWeek?.let { r -> if (hic > r.max) out += "$hic hard conditioning days; ${protocol.title} allows at most ${r.max.toInt()} a week" }
        if (b?.highIntensityPerWeek != null && b.highIntensityPerWeek.min > 0 && hic < b.highIntensityPerWeek.min) out += "$hic hard conditioning day(s); ${protocol.title} asks for at least ${b.highIntensityPerWeek.min.toInt()}"
        b?.minConditioningSessionsPerWeek?.let { if (all < it) out += "$all conditioning day(s); ${protocol.title} asks for at least $it" }
        b?.sessionsPerWeek?.let { r -> if (all > r.max) out += "$all conditioning day(s); ${protocol.title} allows at most ${r.max.toInt()}" }
        b?.lowIntensityMinutesPerWeek?.let { r -> if (lic > 0 && b.sessionMinMinutes != null && r.max / lic < b.sessionMinMinutes) out += "$lic LIC days is more than ${r.max.toInt()} minutes a week supports at ${b.sessionMinMinutes} minutes each" }
    }
    return out.distinct()
}

private fun DayOfWeek.short() = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
