package com.bioscan.fieldterminal.domain.stress

import java.time.ZoneId

// BALANCE › Stress through the day, "the pattern through the days": a day's band stress readings placed in Zepp's four
// zones, like a hypnogram's stages. Readings are averaged in [bucketMin] slots so a single noisy sample does not flick the
// line between lanes.

enum class StressZone(val label: String, val range: IntRange) {
    HIGH("HIGH", 80..100),
    MEDIUM("MEDIUM", 60..79),
    NORMAL("NORMAL", 40..59),
    RELAXED("RELAXED", 0..39);

    companion object {
        /** Top to bottom, as drawn. */
        val DRAWN = listOf(HIGH, MEDIUM, NORMAL, RELAXED)

        fun of(value: Double): StressZone = when {
            value >= 80 -> HIGH
            value >= 60 -> MEDIUM
            value >= 40 -> NORMAL
            else -> RELAXED
        }
    }
}

/** One slot of the day: its start (minutes after midnight), mean reading and zone. */
data class StressSlot(val minuteOfDay: Int, val mean: Double, val zone: StressZone)

/** [day]'s readings from [fromHour] to midnight in [bucketMin]-minute slots; slots without a reading are left out. */
fun stressSlots(day: StressDay, zone: ZoneId, bucketMin: Int = 30, fromHour: Int = 6): List<StressSlot> =
    day.samples
        .map { it.timestamp.atZone(zone) to it.value }
        .filter { (t, _) -> t.toLocalDate() == day.date && t.hour >= fromHour }
        .groupBy { (t, _) -> (t.hour * 60 + t.minute) / bucketMin * bucketMin }
        .toSortedMap()
        .map { (m, readings) -> readings.map { it.second }.average().let { StressSlot(m, it, StressZone.of(it)) } }

/** Minutes spent in each zone across [slots] (each slot counts [bucketMin]). */
fun minutesByZone(slots: List<StressSlot>, bucketMin: Int = 30): Map<StressZone, Int> =
    StressZone.entries.associateWith { z -> slots.count { it.zone == z } * bucketMin }
