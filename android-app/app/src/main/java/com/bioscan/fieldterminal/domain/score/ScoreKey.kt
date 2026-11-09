package com.bioscan.fieldterminal.domain.score

import java.security.MessageDigest
import java.time.LocalDate

// TODAY is built once a day and kept (ui/screens/today/TodayStore). It is rebuilt in the background when it may have gone
// stale (a sync landed, an hour passed), but the slow part, the score engine over eight months, only runs again when what
// it reads changed: this key is a digest of everything that decides this morning's scores. A sync that rewrites the same
// rows gives the same key, so the kept scores stand.
//
// Only what is dated on or before the scored morning counts. Today's meals, water, check-in and the stress samples that
// sync through the day are filed under tomorrow morning (ScoreAssembly), and planned sessions lie ahead; none of them
// changes this morning's scores, so they are left out of the digest. Before, every sync of the day changed the key and
// the engine ran again on every open of the app.

fun scoreInputsKey(
    day: LocalDate,
    series: Map<String, Series>,
    plannedDone: Map<LocalDate, Double>,
    caps: List<String>,
    closedYesterday: Boolean,
    afterNoon: Boolean,
    modelVersion: String = SCORE_MODEL_VERSION,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(s: String) = digest.update(s.toByteArray(Charsets.UTF_8))
    add("v=$modelVersion;day=$day;closed=$closedYesterday;noon=$afterNoon;")
    for (key in series.keys.sorted()) {
        add("[$key]")
        for ((d, v) in series.getValue(key).entries.filter { !it.key.isAfter(day) }.sortedBy { it.key }) add("$d=${java.lang.Double.doubleToLongBits(v)},")
    }
    add("[planned]")
    for ((d, done) in plannedDone.entries.filter { !it.key.isAfter(day) }.sortedBy { it.key }) add("$d=${java.lang.Double.doubleToLongBits(done)},")
    add("[caps]")
    caps.sorted().forEach { add("$it,") }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
