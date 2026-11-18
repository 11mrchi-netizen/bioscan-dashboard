package com.bioscan.fieldterminal.domain.training.generate

// DAV-345. The week-by-week plan of a block. Defaults follow the books (a block of L weeks, then a
// deload week, repeated); the lifter can change any week afterwards.

// block length L, then one deload week, repeated to fill `weeks`. An optional leading test week
// schedules max tests before the first block. The last block keeps whatever weeks remain.
fun defaultTimeline(blockLength: Int, weeks: Int, startWithTest: Boolean = false, deloads: Boolean = true): List<WeekKind> {
    val len = blockLength.coerceAtLeast(1)
    val out = ArrayList<WeekKind>(weeks)
    if (startWithTest && weeks > 0) out += WeekKind.Test
    var run = 0
    while (out.size < weeks) {
        if (deloads && run == len) { out += WeekKind.Deload; run = 0 } else { out += WeekKind.Normal; run++ }
    }
    return out
}

// Calendar weeks needed for `blocks` blocks of `blockLength` weeks, with a deload week after each
// block (the last one included) or only between blocks.
fun weeksForBlocks(blocks: Int, blockLength: Int, trailingDeload: Boolean = true, startWithTest: Boolean = false): Int {
    val b = blocks.coerceAtLeast(1)
    val deloads = if (trailingDeload) b else b - 1
    return b * blockLength.coerceAtLeast(1) + deloads + if (startWithTest) 1 else 0
}

// Tapping a week cycles normal -> deload -> test -> normal.
fun cycleWeekKind(kind: WeekKind): WeekKind = WeekKind.entries[(kind.ordinal + 1) % WeekKind.entries.size]

fun countedWeeks(timeline: List<WeekKind>): Int = timeline.count { it.countsTowardBlock }

// Grow or shrink the timeline to `weeks`, keeping the lifter's edits at the front and extending
// with the default rhythm.
fun resizeTimeline(current: List<WeekKind>, blockLength: Int, weeks: Int): List<WeekKind> {
    if (weeks <= current.size) return current.take(weeks)
    val out = current.toMutableList()
    var run = 0
    for (k in out.asReversed()) { if (k == WeekKind.Normal) run++ else break }
    while (out.size < weeks) {
        if (run >= blockLength) { out += WeekKind.Deload; run = 0 } else { out += WeekKind.Normal; run++ }
    }
    return out
}

// Building toward a race (Nov 2026): the weeks from the start to the race, worked backwards. The last [taper] weeks are
// light (deload); before them, whole cycles of [blockLength] weeks with a deload between them, the last one ending right
// before the taper so its peak week is the week before it; the weeks that do not make a whole cycle go first, as early
// weeks repeating the cycle's opening weeks (more training before the peak, never an early peak). Too short for one
// cycle: every week before the taper is an early week.
fun raceTimeline(blockLength: Int, weeks: Int, taper: Int, startWithTest: Boolean = false): List<WeekKind> {
    val len = blockLength.coerceAtLeast(1)
    val w = weeks.coerceAtLeast(0)
    val t = taper.coerceIn(0, w)
    val head = if (startWithTest && w - t > 0) 1 else 0
    val avail = w - t - head
    val cycles = (avail + 1) / (len + 1)
    val lead = if (cycles == 0) avail else avail - (cycles * len + cycles - 1)
    val out = ArrayList<WeekKind>(w)
    if (head == 1) out += WeekKind.Test
    repeat(lead) { out += WeekKind.Lead }
    for (c in 0 until cycles) {
        if (c > 0) out += WeekKind.Deload
        repeat(len) { out += WeekKind.Normal }
    }
    repeat(t) { out += WeekKind.Deload }
    return out
}
