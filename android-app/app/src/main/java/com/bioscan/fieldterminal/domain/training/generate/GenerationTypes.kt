package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.PercentBase
import com.bioscan.fieldterminal.domain.training.ResolvedLoad
import com.bioscan.fieldterminal.domain.training.definition.NumRange
import kotlinx.serialization.json.JsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

// DAV-345. Inputs and outputs of the block generator. Everything here is plain data so a
// generated block can be previewed, frozen into planned_sessions and replayed in tests.

data class GenEquipment(
    val barKg: Double = 20.0,
    val platesKg: List<Double> = listOf(1.25, 2.5, 5.0, 10.0, 15.0, 20.0),
    val addedStepKg: Double = 1.25,
    val weightedBase: PercentBase = PercentBase.AddedLoad,
)

// What is known about one exercise's strength. Looked up by exercise name.
data class MaxEntry(val oneRmKg: Double? = null, val trainingMaxKg: Double? = null, val maxReps: Int? = null)

data class SlotTime(val start: LocalTime, val durationMin: Int)

// The lifter's choices for one module: which variant, how long a block runs before the
// cycle restarts, and which exercise fills each slot (several for a cluster slot).
data class ModuleChoice(
    val variant: String? = null,
    val blockLength: Int? = null,
    val exercises: Map<String, List<String>> = emptyMap(),
)

data class GenChoices(
    val startDate: LocalDate,
    // Day position (1..7) -> weekday. Unlisted positions follow the start date's weekday.
    val weekdays: Map<Int, DayOfWeek> = emptyMap(),
    // slot_in_day (1 or 2) -> time; defaults come from training_settings.
    val slotTimes: Map<Int, SlotTime> = emptyMap(),
    // Template $VARIABLE -> definition key.
    val variables: Map<String, String> = emptyMap(),
    val modules: Map<String, ModuleChoice> = emptyMap(),
    // Composed blocks: conditioning category (lic, hic, wc) -> preferred session keys, rotated weekly.
    val conditioning: Map<String, List<String>> = emptyMap(),
    // Composed blocks: how many calendar weeks to fill, and whether a deload week follows each strength block.
    val weeks: Int? = null,
    val deloadAfterBlock: Boolean = true,
    val bodyweightKg: Double? = null,
    // Composed blocks whose protocol has no layout: conditioning sessions per week.
    val conditioningPerWeek: Int? = null,
    // Later blocks of the same module are generated on projected maxes (forced progression). Off: every block uses today's maxes.
    val projectProgression: Boolean = true,
)

data class GeneratedItem(
    val slot: String,
    val exercise: String,
    val role: String,
    val sets: NumRange?,
    val reps: NumRange?,
    val load: ResolvedLoad,
    val technique: String? = null,
    val optional: Boolean = false,
    val note: String? = null,
    // True when the load rests on a projected (not yet recorded) max.
    val projected: Boolean = false,
)

data class GeneratedConditioning(
    val sessionKey: String?,
    val label: String,
    val category: String?,
    val params: JsonObject? = null,
    val minutes: NumRange? = null,
)

data class GeneratedSession(
    val sequenceNo: Int,
    val weekIndex: Int,
    // Weeks that count toward the block length (deload weeks may not).
    val countsTowardBlock: Boolean,
    val daySlot: Int,
    val slotInDay: Int,
    val date: LocalDate,
    val startTime: LocalTime?,
    val durationMin: Int?,
    val domain: String,
    val workKind: String,
    val moduleRef: String,
    val title: String,
    val items: List<GeneratedItem> = emptyList(),
    val conditioning: GeneratedConditioning? = null,
    val notes: List<String> = emptyList(),
)

data class MaxProgression(val weekIndex: Int, val moduleKey: String, val exercise: String, val fromKg: Double, val toKg: Double)

data class GeneratedBlock(
    val sessions: List<GeneratedSession>,
    val calendarWeeks: Int,
    val countedWeeks: Int,
    val endDate: LocalDate,
    val progressions: List<MaxProgression>,
    val warnings: List<String>,
)
