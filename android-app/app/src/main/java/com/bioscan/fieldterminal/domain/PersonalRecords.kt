@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class)

package com.bioscan.fieldterminal.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

// Personal records (docs/performance/records.md). The database keeps each run's best efforts (run_best_efforts, read
// once per run) and lists every time a record was set (v_personal_records); this file names the records, formats them
// and reads their progress. Pure.

enum class RecordKind { TIME, KM, METRES }

enum class RecordKey(val key: String, val label: String, val kind: RecordKind, val distanceM: Int? = null) {
    K1("fastest_1000", "1K", RecordKind.TIME, 1000),
    K5("fastest_5000", "5K", RecordKind.TIME, 5000),
    K10("fastest_10000", "10K", RecordKind.TIME, 10000),
    HALF("fastest_21097", "Half marathon", RecordKind.TIME, 21097),
    MARATHON("fastest_42195", "Marathon", RecordKind.TIME, 42195),
    K50("fastest_50000", "50K", RecordKind.TIME, 50000),
    K60("fastest_60000", "60K", RecordKind.TIME, 60000),
    K75("fastest_75000", "75K", RecordKind.TIME, 75000),
    K100("fastest_100000", "100K", RecordKind.TIME, 100000),
    LONGEST("longest_run", "Longest run", RecordKind.KM),
    LONGEST_TRAIL("longest_trail_run", "Longest trail run", RecordKind.KM),
    CLIMB("biggest_climb", "Biggest climb", RecordKind.METRES);

    val higherBetter: Boolean get() = kind != RecordKind.TIME

    companion object {
        fun of(key: String): RecordKey? = entries.firstOrNull { it.key == key }
    }
}

/** One time a record was set; [previousValue] is the record it beat (null for the first). */
@Serializable
data class RecordSet(
    val key: String,
    val value: Double,
    val date: LocalDate,
    val sessionId: Long?,
    val previousValue: Double? = null,
    val previousDate: LocalDate? = null,
)

/** A record's history, oldest first; [current] its best now. */
data class RecordHistory(val key: RecordKey, val sets: List<RecordSet>) {
    val current: RecordSet? get() = sets.lastOrNull()
    val first: RecordSet? get() = sets.firstOrNull()

    /** Improvements (every set after the first), each with how much it gained and the days since the one it beat. */
    val improvements: List<Triple<RecordSet, Double, Long>>
        get() = sets.drop(1).mapNotNull { s ->
            val p = s.previousValue ?: return@mapNotNull null
            val days = s.previousDate?.let { ChronoUnit.DAYS.between(it, s.date) } ?: 0
            Triple(s, if (key.higherBetter) s.value - p else p - s.value, days)
        }

    /** Days since the record last moved, as of [today]. */
    fun daysSinceLast(today: LocalDate): Long? = current?.let { ChronoUnit.DAYS.between(it.date, today) }
}

/** Every record, in [RecordKey] order, with what was set (records never set yet have no sets). */
fun recordHistories(sets: List<RecordSet>): List<RecordHistory> {
    val byKey = sets.groupBy { it.key }
    return RecordKey.entries.map { k -> RecordHistory(k, byKey[k.key].orEmpty().sortedBy { it.date }) }
}

/** 1:05:42 or 24:06. */
fun formatRecordTime(seconds: Double): String {
    val s = seconds.roundToLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatRecordValue(key: RecordKey, value: Double): String = when (key.kind) {
    RecordKind.TIME -> formatRecordTime(value)
    RecordKind.KM -> "%.1f km".format(value)
    RecordKind.METRES -> "%,d m".format(value.roundToLong())
}

/** The pace a time record works out to, as "4:49 /km". */
fun recordPace(key: RecordKey, value: Double): String? = key.distanceM?.let { formatRecordTime(value / (it / 1000.0)) + " /km" }

/** What an improvement gained, in words: "1:12 faster", "+2.4 km", "+273 m". */
fun gainWords(key: RecordKey, gain: Double): String = when (key.kind) {
    RecordKind.TIME -> formatRecordTime(gain) + " faster"
    RecordKind.KM -> "+%.1f km".format(gain)
    RecordKind.METRES -> "+%,d m".format(gain.roundToLong())
}
