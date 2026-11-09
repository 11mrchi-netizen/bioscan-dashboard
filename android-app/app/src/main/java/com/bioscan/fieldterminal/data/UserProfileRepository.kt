package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.UserProfileRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// DAV-222/09A Aging Profile. One row per account (user_id defaults to
// auth.uid(), same convention as exercise_sessions/training_cycles/lab_draws)
// -- read/write is a single-row upsert, not a list, since there's exactly one
// profile per user.
// No defaults on purpose: every column is always sent, so clearing the height or turning athlete mode off is saved
// (a default-valued field is not encoded unless the client's Json says so, which would leave the old value in place).
@Serializable
private data class UserProfileUpsert(
    @SerialName("date_of_birth") val dateOfBirth: String?,
    val sex: String?,
    @SerialName("height_cm") val heightCm: Double?,
    @SerialName("athlete_mode") val athleteMode: Boolean,
)

class UserProfileRepository(private val supabase: SupabaseClient) {

    suspend fun loadProfile(): UserProfileRow? =
        supabase.postgrest.from("user_profile")
            .select(columns = Columns.list("date_of_birth,sex,height_cm,athlete_mode"))
            .decodeSingleOrNull<UserProfileRow>()

    suspend fun saveProfile(dateOfBirth: LocalDate?, sex: String?, heightCm: Double?, athleteMode: Boolean) {
        supabase.postgrest.from("user_profile")
            .upsert(UserProfileUpsert(dateOfBirth?.toString(), sex, heightCm, athleteMode)) { onConflict = "user_id" }
    }
}
