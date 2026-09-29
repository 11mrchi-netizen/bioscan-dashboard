package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.UserProfileRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.time.LocalDate

// DAV-222/09A Aging Profile. One row per account (user_id defaults to
// auth.uid(), same convention as exercise_sessions/training_cycles/lab_draws)
// -- read/write is a single-row upsert, not a list, since there's exactly one
// profile per user.
class UserProfileRepository(private val supabase: SupabaseClient) {

    suspend fun loadProfile(): UserProfileRow? =
        supabase.postgrest.from("user_profile")
            .select(columns = Columns.list("date_of_birth,sex"))
            .decodeSingleOrNull<UserProfileRow>()

    suspend fun saveProfile(dateOfBirth: LocalDate?, sex: String?) {
        supabase.postgrest.from("user_profile")
            .upsert(UserProfileRow(dateOfBirth = dateOfBirth?.toString(), sex = sex)) { onConflict = "user_id" }
    }
}
