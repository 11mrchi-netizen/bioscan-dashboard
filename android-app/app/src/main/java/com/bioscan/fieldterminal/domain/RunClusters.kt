package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import java.time.Duration
import java.time.OffsetDateTime

// Ids of the rows that are the same physical run as [r]: every other run row whose start is within five minutes
// (Duration.toMinutes truncates, so up to 5 min 59 s counts) of r's start, followed by r itself. The Zepp sport code hangs
// off whichever duplicate the Health Connect / Zepp race happened to keep, so a "mark as trail" must reach the whole cluster.
fun runClusterIds(sessions: List<ExerciseSessionRow>, r: ExerciseSessionRow): List<Long> =
    (
        sessions.filter { s ->
            s.type == "run" && s.id != r.id &&
                kotlin.math.abs(Duration.between(OffsetDateTime.parse(s.startTime), OffsetDateTime.parse(r.startTime)).toMinutes()) <= 5
        } + r
        ).map { it.id }

// Same answers as calling runClusterIds() for each target (the tests compare them on random data), without comparing every
// target to every session: runs are sorted by start instant once, each target scans only the +-6 minute window around it,
// and the original predicate is then applied to those few candidates, so the result cannot differ. Order is the original
// session order, then the target itself last.
fun runClusterIdsBatch(sessions: List<ExerciseSessionRow>, targets: List<ExerciseSessionRow>): List<List<Long>> {
    class Run(val at: OffsetDateTime, val index: Int, val row: ExerciseSessionRow)
    val runs = sessions.withIndex()
        .filter { it.value.type == "run" }
        .map { Run(OffsetDateTime.parse(it.value.startTime), it.index, it.value) }
        .sortedBy { it.at.toEpochSecond() }
    val epochs = LongArray(runs.size) { runs[it].at.toEpochSecond() }

    return targets.map { r ->
        val rAt = OffsetDateTime.parse(r.startTime)
        val e = rAt.toEpochSecond()
        var lo = 0
        var hi = epochs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (epochs[mid] < e - 361) lo = mid + 1 else hi = mid }
        val hits = mutableListOf<Run>()
        var i = lo
        while (i < runs.size && epochs[i] <= e + 361) {
            val c = runs[i]
            if (c.row.id != r.id && kotlin.math.abs(Duration.between(c.at, rAt).toMinutes()) <= 5) hits += c
            i++
        }
        hits.sortedBy { it.index }.map { it.row.id } + r.id
    }
}
