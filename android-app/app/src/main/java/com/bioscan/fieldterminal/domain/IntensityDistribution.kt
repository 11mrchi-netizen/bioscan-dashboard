package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// How endurance training time splits between low, moderate and high intensity, week by week (Training › Load). Each
// workout's heart-rate histogram (seconds per bpm, zepp_workout_detail.hr_histogram) is placed in the zones of the chosen
// method with the anchors as they were on its date (HeartRateZones.kt), then grouped in three bands: low = below Z3,
// moderate = Z3, high = Z4 and Z5. Endurance athletes typically spend about 80 % of their sessions or time at low
// intensity (Seiler 2010); the card shows the split and leaves the judgement to the person. Only endurance sessions
// count, since a strength session's heart rate says little about its intensity; one without a Zepp heart-rate record
// counts in the coverage line but not in the bars.

enum class IntensityBand(val label: String) {
    LOW("Low"),
    MODERATE("Moderate"),
    HIGH("High"),
}

fun intensityBand(zone: HeartRateZone): IntensityBand = when (zone) {
    HeartRateZone.BelowZ1, HeartRateZone.Z1, HeartRateZone.Z2 -> IntensityBand.LOW
    HeartRateZone.Z3 -> IntensityBand.MODERATE
    HeartRateZone.Z4, HeartRateZone.Z5 -> IntensityBand.HIGH
}

/** Session types counted. Walks and "other" are mostly auto-detected movement, not training. */
val INTENSITY_SPORTS = setOf("run", "ride", "hike")

/** One session: its date, type and heart-rate histogram (bpm to seconds), null when it has no Zepp heart-rate record. */
data class IntensitySession(val date: LocalDate, val sport: String?, val histogram: Map<Int, Double>?)

data class WeekIntensity(
    val weekStart: LocalDate,
    val seconds: Map<IntensityBand, Double>,
    /** Endurance sessions that week. */
    val sessions: Int,
    /** Of those, the ones placed in the bars. */
    val withHeartRate: Int,
) {
    val totalSeconds: Double get() = seconds.values.sum()
    fun share(band: IntensityBand): Double? = totalSeconds.takeIf { it > 0 }?.let { (seconds[band] ?: 0.0) / it }
}

/** The last [weeks] weeks (Monday to Sunday), oldest first, the current week last. */
fun weeklyIntensity(
    sessions: List<IntensitySession>,
    anchorsFor: (LocalDate) -> ZoneAnchors,
    method: ZoneMethod,
    today: LocalDate,
    weeks: Int = 12,
): List<WeekIntensity> {
    val lastWeek = mondayOf(today)
    val starts = (weeks - 1 downTo 0).map { lastWeek.minusWeeks(it.toLong()) }
    val byWeek = sessions
        .filter { it.sport in INTENSITY_SPORTS && !it.date.isBefore(starts.first()) && !it.date.isAfter(today) }
        .groupBy { mondayOf(it.date) }
    return starts.map { start ->
        val inWeek = byWeek[start].orEmpty()
        val seconds = IntensityBand.entries.associateWith { 0.0 }.toMutableMap()
        var placed = 0
        for (s in inWeek) {
            val histogram = s.histogram?.takeIf { it.isNotEmpty() } ?: continue
            val bounds = zoneBounds(method, anchorsFor(s.date), s.sport) ?: continue
            histogram.forEach { (bpm, secs) ->
                val band = intensityBand(heartRateZoneFor(bpm.toDouble(), bounds))
                seconds[band] = seconds.getValue(band) + secs
            }
            placed++
        }
        WeekIntensity(start, seconds, inWeek.size, placed)
    }
}
