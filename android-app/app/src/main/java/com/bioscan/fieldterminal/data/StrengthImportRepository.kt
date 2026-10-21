package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.EXERCISES_FROM_APP
import com.bioscan.fieldterminal.domain.EXERCISES_FROM_ZEPP
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

// The person's choices about a strength workout's exercises when they and Zepp disagree (domain/StrengthImport.kt):
// take Zepp's version, keep their own, or pull the workout from Zepp again. Every write merges into the stored details
// JSON, so keys this app does not model are kept.

@Serializable
private data class StrengthDetailsJson(val details: JsonObject = JsonObject(emptyMap()))

@Serializable
private data class StrengthDecodedRow(val decoded: ZeppDecodedSeries? = null)

class StrengthImportRepository(private val supabase: SupabaseClient) {

    suspend fun loadZeppStrengthData(sessionId: Long): ZeppStrengthData? =
        supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.list("decoded")) { filter { eq("exercise_session_id", sessionId) } }
            .decodeSingleOrNull<StrengthDecodedRow>()
            ?.decoded?.strengthData

    private suspend fun mergeDetails(sessionId: Long, changes: Map<String, kotlinx.serialization.json.JsonElement>) {
        val current = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("details")) { filter { eq("id", sessionId) } }
            .decodeSingle<StrengthDetailsJson>()
        supabase.postgrest.from("exercise_sessions")
            .update(StrengthDetailsJson(JsonObject(current.details + changes))) { filter { eq("id", sessionId) } }
    }

    /** Replaces the exercises with Zepp's version; from now on they follow Zepp again. */
    suspend fun useZeppVersion(sessionId: Long, zepp: ZeppStrengthData) {
        val exercises = zepp.exercises ?: return
        val fingerprint = zepp.fingerprint ?: return
        mergeDetails(
            sessionId,
            mapOf(
                "exercises" to exercises,
                "exercises_source" to JsonPrimitive(EXERCISES_FROM_ZEPP),
                "zepp_fingerprint" to JsonPrimitive(fingerprint),
            ),
        )
    }

    /** Keeps the person's exercises and records that they have seen Zepp's current version. */
    suspend fun keepMine(sessionId: Long, fingerprint: String) {
        mergeDetails(
            sessionId,
            mapOf("exercises_source" to JsonPrimitive(EXERCISES_FROM_APP), "zepp_fingerprint" to JsonPrimitive(fingerprint)),
        )
    }

    /**
     * Fetches the workout's days from Zepp again (the sync writes Zepp's current version beside the workout, and replaces
     * the exercises when they were Zepp's) and returns Zepp's version. Throws when the sync fails.
     */
    suspend fun refetchFromZepp(sessionId: Long, date: LocalDate): ZeppStrengthData? {
        when (val result = ZeppRepository(supabase).syncRange(date.minusDays(1), date.plusDays(1), "sport_history")) {
            is ZeppSyncResult.Failed -> throw IllegalStateException(result.message)
            is ZeppSyncResult.Success -> Unit
        }
        return loadZeppStrengthData(sessionId)
    }
}
