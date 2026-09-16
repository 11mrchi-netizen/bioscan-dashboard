package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseLibraryMatch
import com.bioscan.fieldterminal.data.model.MatchExerciseLibraryParams
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// Exercise-name autocomplete against Phase C's exercise_library (876 real
// entries) via the match_exercise_library() Postgres function -- pg_trgm
// similarity ranking done server-side, not re-implemented in Kotlin. A
// read-side convenience for ui/screens/AddEntrySheet.kt's ExerciseEditor
// only; logged names stay free text underneath.
class ExerciseLibraryRepository(private val supabase: SupabaseClient) {

    suspend fun matchExerciseName(query: String, limit: Int = 5): List<ExerciseLibraryMatch> {
        val params = Json.encodeToJsonElement(MatchExerciseLibraryParams.serializer(), MatchExerciseLibraryParams(query, limit)).jsonObject
        return supabase.postgrest.rpc("match_exercise_library", params).decodeList<ExerciseLibraryMatch>()
    }
}
