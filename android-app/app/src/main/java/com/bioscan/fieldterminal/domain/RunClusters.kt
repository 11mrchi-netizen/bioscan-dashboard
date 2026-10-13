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
