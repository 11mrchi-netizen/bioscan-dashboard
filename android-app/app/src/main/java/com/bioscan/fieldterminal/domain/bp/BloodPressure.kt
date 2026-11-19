package com.bioscan.fieldterminal.domain.bp

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

// Risk profile R0 (docs/risk-profile/01-plan.md): home blood pressure. Pure; every threshold is a named constant with its
// source.
//
// The home-monitoring protocol is the AHA's (Muntner et al., "Measurement of Blood Pressure in Humans", Hypertension
// 2019;73:e35-e66, and the AHA/AMA home BP guidance): a validated upper-arm cuff, seated after 5 minutes' rest, 2 readings
// 1 minute apart, morning (before medication, food and coffee) and evening (before dinner), for 7 days. The first day is
// dropped (readings are higher while people get used to the cuff); the mean of the rest is the home BP. At least 3 days
// are needed for a usable mean.
//
// Categories: ACC/AHA 2017 guideline (Whelton et al., Hypertension 2018;71:e13-e115), Table 6. The same guideline's
// Table 11 puts home 130/80 level with clinic 130/80, so home and clinic means share the categories.

/** One reading, its time read the app's way (WallClock: the local digits). */
data class BpReading(
    val at: LocalDateTime,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int? = null,
    val manual: Boolean = true,
)

/** Readings close together: one sitting. Its value is the mean of its readings. */
data class BpSitting(val start: LocalDateTime, val readings: List<BpReading>) {
    val systolic: Double get() = readings.map { it.systolic }.average()
    val diastolic: Double get() = readings.map { it.diastolic }.average()
    val date: LocalDate get() = start.toLocalDate()
    val slot: BpSlot get() = slotOf(start)
}

enum class BpSlot { MORNING, EVENING, OTHER }

enum class BpCategory(val label: String) {
    NORMAL("Normal"),
    ELEVATED("Elevated"),
    STAGE_1("Stage 1 hypertension"),
    STAGE_2("Stage 2 hypertension"),
}

/** Readings this close to the previous one belong to the same sitting. */
val SITTING_GAP: Duration = Duration.ofMinutes(15)

/** Morning ends at noon and evening starts at 17:00, local time. */
const val MORNING_END_HOUR = 12
const val EVENING_START_HOUR = 17

const val PROTOCOL_DAYS = 7
const val READINGS_PER_SITTING = 2
/** Days of readings (after the first is dropped) needed for a usable home mean (Muntner 2019: at least 3). */
const val MIN_USABLE_DAYS = 3
/** A series is the run of days with readings; a gap longer than this starts a new series. */
const val SERIES_GAP_DAYS = 3
/** A new 7-day series is due this long after the last usable one (product-defined, labelled as such). */
const val RECHECK_MONTHS = 3L

/** A single reading this high needs repeating and, with symptoms, care at once (ACC/AHA 2017, hypertensive crisis). */
const val CRISIS_SYSTOLIC = 180
const val CRISIS_DIASTOLIC = 120

fun slotOf(t: LocalDateTime): BpSlot = when {
    t.hour < MORNING_END_HOUR -> BpSlot.MORNING
    t.hour >= EVENING_START_HOUR -> BpSlot.EVENING
    else -> BpSlot.OTHER
}

/** ACC/AHA 2017: the higher of the two categories wins. */
fun categorize(systolic: Double, diastolic: Double): BpCategory {
    val s = systolic.roundToInt()
    val d = diastolic.roundToInt()
    return when {
        s >= 140 || d >= 90 -> BpCategory.STAGE_2
        s >= 130 || d >= 80 -> BpCategory.STAGE_1
        s >= 120 -> BpCategory.ELEVATED
        else -> BpCategory.NORMAL
    }
}

fun isCrisis(r: BpReading): Boolean = r.systolic > CRISIS_SYSTOLIC || r.diastolic > CRISIS_DIASTOLIC

/** Readings grouped into sittings, oldest first. */
fun sittings(readings: List<BpReading>): List<BpSitting> {
    val sorted = readings.sortedBy { it.at }
    val out = mutableListOf<MutableList<BpReading>>()
    for (r in sorted) {
        val last = out.lastOrNull()?.last()
        if (last != null && Duration.between(last.at, r.at) <= SITTING_GAP) out.last() += r else out += mutableListOf(r)
    }
    return out.map { BpSitting(it.first().at, it) }
}

/** A run of days with readings: one 7-day home series (or a partial one). */
data class BpSeries(
    val days: List<LocalDate>,
    val sittings: List<BpSitting>,
) {
    val first: LocalDate get() = days.first()
    val last: LocalDate get() = days.last()

    /** The days that count: the first day is dropped when there are more days after it. */
    val countedDays: List<LocalDate> get() = if (days.size > 1) days.drop(1) else days
    private val counted: List<BpSitting> get() = sittings.filter { it.date in countedDays.toSet() }
    private val countedReadings: List<BpReading> get() = counted.flatMap { it.readings }

    /** At least [MIN_USABLE_DAYS] days left after the first is dropped, so 4 days of readings or more. */
    val usable: Boolean get() = days.size > 1 && countedDays.size >= MIN_USABLE_DAYS
    val systolic: Double? get() = countedReadings.takeIf { it.isNotEmpty() }?.map { it.systolic }?.average()
    val diastolic: Double? get() = countedReadings.takeIf { it.isNotEmpty() }?.map { it.diastolic }?.average()
    val readingCount: Int get() = countedReadings.size
    val category: BpCategory? get() = if (usable) categorize(systolic!!, diastolic!!) else null
}

/** Sittings split into series: a gap of more than [SERIES_GAP_DAYS] days without readings ends a series. */
fun series(sittings: List<BpSitting>): List<BpSeries> {
    val byDay = sittings.groupBy { it.date }.toSortedMap()
    val out = mutableListOf<MutableList<LocalDate>>()
    for (d in byDay.keys) {
        val last = out.lastOrNull()?.last()
        if (last != null && !d.isAfter(last.plusDays(SERIES_GAP_DAYS.toLong()))) out.last() += d else out += mutableListOf(d)
    }
    return out.map { days -> BpSeries(days, days.flatMap { byDay[it].orEmpty() }) }
}

/** Where the 7-day protocol stands today. */
data class BpProtocol(
    /** Day of the current series (1-based), or null when no series is running. */
    val day: Int?,
    val morningDone: Boolean,
    val eveningDone: Boolean,
    /** Readings in today's sittings so far, per slot. */
    val morningReadings: Int,
    val eveningReadings: Int,
    val complete: Boolean,
)

fun protocolToday(all: List<BpSeries>, today: LocalDate): BpProtocol {
    val current = all.lastOrNull()?.takeIf { !today.isAfter(it.last.plusDays(SERIES_GAP_DAYS.toLong())) }
    val todays = current?.sittings?.filter { it.date == today }.orEmpty()
    val morning = todays.filter { it.slot == BpSlot.MORNING }.sumOf { it.readings.size }
    val evening = todays.filter { it.slot == BpSlot.EVENING }.sumOf { it.readings.size }
    val day = current?.let { (java.time.temporal.ChronoUnit.DAYS.between(it.first, today) + 1).toInt() }?.takeIf { it in 1..PROTOCOL_DAYS }
    return BpProtocol(
        day = day,
        morningDone = morning >= READINGS_PER_SITTING,
        eveningDone = evening >= READINGS_PER_SITTING,
        morningReadings = morning,
        eveningReadings = evening,
        complete = current != null && current.days.size >= PROTOCOL_DAYS,
    )
}

/** A clinic reading (lab_results 'SBP, Sitting' / 'DBP, Sitting'), on the draw's date. */
data class ClinicBp(val date: LocalDate, val systolic: Double, val diastolic: Double)

enum class ClinicHomePattern(val label: String, val explain: String) {
    CONSISTENT("Clinic and home agree", "Both readings sit on the same side of 130/80."),
    WHITE_COAT(
        "Higher at the clinic",
        "The clinic reading is at or above 130/80 and the home mean below it: a white-coat effect is possible. Home readings are the better guide.",
    ),
    MASKED(
        "Higher at home",
        "The home mean is at or above 130/80 and the clinic reading below it: masked hypertension is possible. Worth raising with a doctor.",
    ),
}

/** Home 130/80 is level with clinic 130/80 (ACC/AHA 2017, Table 11). */
const val HOME_CLINIC_THRESHOLD_SYS = 130.0
const val HOME_CLINIC_THRESHOLD_DIA = 80.0

private fun atOrAbove(s: Double, d: Double) = s >= HOME_CLINIC_THRESHOLD_SYS || d >= HOME_CLINIC_THRESHOLD_DIA

fun clinicHomePattern(clinic: ClinicBp?, home: BpSeries?): ClinicHomePattern? {
    if (clinic == null || home == null || !home.usable) return null
    val c = atOrAbove(clinic.systolic, clinic.diastolic)
    val h = atOrAbove(home.systolic!!, home.diastolic!!)
    return when {
        c && !h -> ClinicHomePattern.WHITE_COAT
        !c && h -> ClinicHomePattern.MASKED
        else -> ClinicHomePattern.CONSISTENT
    }
}

/** What the risk screens use: the latest usable home mean, else the latest clinic reading (labelled), else nothing. */
data class BpForScreens(val systolic: Double, val diastolic: Double, val fromHome: Boolean, val date: LocalDate)

fun bpForScreens(all: List<BpSeries>, clinic: ClinicBp?): BpForScreens? {
    val home = all.lastOrNull { it.usable }
    return when {
        home != null -> BpForScreens(home.systolic!!, home.diastolic!!, fromHome = true, date = home.last)
        clinic != null -> BpForScreens(clinic.systolic, clinic.diastolic, fromHome = false, date = clinic.date)
        else -> null
    }
}

/** Everything the BP card shows. */
data class BpOverview(
    val readings: List<BpReading>,
    val sittings: List<BpSitting>,
    val series: List<BpSeries>,
    val protocol: BpProtocol,
    val latestUsable: BpSeries?,
    val clinic: ClinicBp?,
    val pattern: ClinicHomePattern?,
    val nextSeriesDue: LocalDate?,
    val latestCrisis: BpReading?,
)

fun bpOverview(readings: List<BpReading>, clinic: ClinicBp?, today: LocalDate): BpOverview {
    val s = sittings(readings)
    val all = series(s)
    val usable = all.lastOrNull { it.usable }
    val running = protocolToday(all, today)
    return BpOverview(
        readings = readings.sortedBy { it.at },
        sittings = s,
        series = all,
        protocol = running,
        latestUsable = usable,
        clinic = clinic,
        pattern = clinicHomePattern(clinic, usable),
        nextSeriesDue = usable?.last?.plusMonths(RECHECK_MONTHS),
        latestCrisis = readings.filter { isCrisis(it) && !it.at.toLocalDate().isBefore(today.minusDays(1)) }.maxByOrNull { it.at },
    )
}
