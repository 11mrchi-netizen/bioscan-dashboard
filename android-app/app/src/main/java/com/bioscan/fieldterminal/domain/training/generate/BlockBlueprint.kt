package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.definition.NumRange
import java.time.DayOfWeek

// DAV-345. A block the lifter assembles from a strength template and a conditioning
// protocol: how the two share the week (the layout) and which weeks do what (the timeline).
// Plain data, so the setup screen edits it, the generator expands it and the block keeps it
// in training_blocks.choices.

// One conditioning session in the week. kind: lic | hic | wc. minutes overrides the protocol's budget share.
data class CondSlot(val kind: String, val minutes: NumRange? = null)

// What happens on one weekday: at most one strength session (an index into the module's
// sessions) and at most one conditioning slot. Both on one day is a two-a-day.
data class DayPlan(val weekday: DayOfWeek, val strengthSession: Int? = null, val conditioning: CondSlot? = null) {
    val isRest: Boolean get() = strengthSession == null && conditioning == null
}

// What a calendar week is. Deload weeks are invisible: they do not count toward the block's
// length and pause the strength cycle (Tactical Barbell III). A test week schedules max tests.
enum class WeekKind(val key: String, val label: String) {
    Normal("normal", "NORMAL"),
    Deload("deload", "DELOAD"),
    Test("test", "TEST"),
    ;

    val countsTowardBlock: Boolean get() = this != Deload

    companion object {
        fun fromKey(key: String): WeekKind = entries.firstOrNull { it.key == key } ?: Normal
    }
}

data class BlockBlueprint(
    val strengthKey: String?,
    val protocolKey: String?,
    val layout: List<DayPlan>,
    val timeline: List<WeekKind>,
)
