package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.domain.achievement.Achievement
import com.bioscan.fieldterminal.domain.achievement.AchievementDomain
import com.bioscan.fieldterminal.domain.analysis.Provenance
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.OffsetDateTime

class AchievementsRepository(private val supabase: SupabaseClient) {

    // Returns (strengthPRs, runningPRs).
    suspend fun loadAchievements(): Pair<List<Achievement>, List<Achievement>> =
        Pair(strengthPRs(), runningPRs())

    private suspend fun strengthPRs(): List<Achievement> {
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,source,details")) {
                filter { eq("type", "strength") }
                order("start_time", Order.ASCENDING)
                limit(1000)
            }
            .decodeList<ExerciseSessionRow>()

        data class BestSet(val e1rm: Double, val weightKg: Double, val reps: Int, val sessionId: Long, val startTime: String, val source: String?)

        val bests = mutableMapOf<String, BestSet>()
        val counts = mutableMapOf<String, Int>()
        for (session in sessions) {
            session.details.exercises?.forEach { exercise ->
                counts[exercise.name] = (counts[exercise.name] ?: 0) + 1
                exercise.sets.forEach { set ->
                    if (set.weightKg > 0 && set.reps > 0) {
                        // ponytail: direct 1-rep measure skips Epley; reps > 30 would invert the formula so cap there
                        val e1rm = if (set.reps >= 30) set.weightKg else set.weightKg * (1.0 + set.reps / 30.0)
                        val cur = bests[exercise.name]
                        if (cur == null || e1rm > cur.e1rm) {
                            bests[exercise.name] = BestSet(e1rm, set.weightKg, set.reps, session.id, session.startTime, session.source)
                        }
                    }
                }
            }
        }

        return bests.entries
            .sortedByDescending { (name, _) -> counts[name] ?: 0 }
            .take(6)
            .map { (name, best) ->
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trimEnd('_')
                Achievement(
                    domain = AchievementDomain.STRENGTH,
                    metric = "best_1rm_$slug",
                    activityRef = best.sessionId.toString(),
                    value = best.e1rm,
                    unit = "kg",
                    occurredAt = OffsetDateTime.parse(best.startTime),
                    provenance = Provenance(best.source ?: "unknown", "epley_1rm", "1"),
                    confidence = if (best.reps == 1) null else 0.85,
                    comparisonContext = if (best.reps == 1) null else "Epley: ${best.weightKg}kg × ${best.reps}",
                )
            }
    }

    private suspend fun runningPRs(): List<Achievement> {
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,distance_km,duration_min,source")) {
                filter { eq("type", "run") }
                order("start_time", Order.ASCENDING)
                limit(500)
            }
            .decodeList<ExerciseSessionRow>()

        val result = mutableListOf<Achievement>()

        sessions.maxByOrNull { it.distanceKm ?: 0.0 }
            ?.takeIf { (it.distanceKm ?: 0.0) > 0 }
            ?.let {
                result += Achievement(
                    domain = AchievementDomain.RUNNING,
                    metric = "longest_run_km",
                    activityRef = it.id.toString(),
                    value = it.distanceKm!!,
                    unit = "km",
                    occurredAt = OffsetDateTime.parse(it.startTime),
                    provenance = Provenance(it.source ?: "unknown", null, null),
                )
            }

        // Best avg pace over any run ≥ 5km (min/km, stored as total seconds for the display formatter)
        sessions.filter { (it.distanceKm ?: 0.0) >= 5.0 && (it.durationMin ?: 0.0) > 0 }
            .minByOrNull { it.durationMin!! / it.distanceKm!! }
            ?.let { best ->
                val paceSecPerKm = (best.durationMin!! / best.distanceKm!! * 60).toLong()
                result += Achievement(
                    domain = AchievementDomain.RUNNING,
                    metric = "fastest_pace_sec",
                    activityRef = best.id.toString(),
                    value = paceSecPerKm.toDouble(),
                    unit = "sec",
                    occurredAt = OffsetDateTime.parse(best.startTime),
                    provenance = Provenance(best.source ?: "unknown", null, null),
                    comparisonContext = "Best avg pace over ≥5km",
                )
            }

        return result
    }
}
