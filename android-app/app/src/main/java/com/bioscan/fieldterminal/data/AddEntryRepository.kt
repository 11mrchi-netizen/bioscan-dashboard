package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExistingHydrationRow
import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogRunRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.NewArousalRow
import com.bioscan.fieldterminal.data.model.NewEncounterRow
import com.bioscan.fieldterminal.data.model.NewHydrationRow
import com.bioscan.fieldterminal.data.model.NewMealRow
import com.bioscan.fieldterminal.data.model.NewNoteRow
import com.bioscan.fieldterminal.data.model.NewRunRow
import com.bioscan.fieldterminal.data.model.NewStoolRow
import com.bioscan.fieldterminal.domain.LogSource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.time.LocalDate
import java.time.OffsetDateTime

// Step 12 (Phase D): the "+" add-entry flow's writes. Each function is a
// minimal, honest write to the real table Step 11 already reads from -- no
// fabricated fields, no client-side defaults beyond "now"/"today" for tables
// that need a date/timestamp. `runs` allows multiple rows per day (no unique
// constraint), but `hydration_daily` and `arousal_daily` both carry a real
// `unique(user_id, date)` index (confirmed directly against the schema,
// matching the existing quick-log Edge Function's own upsert-on-conflict
// usage for arousal_daily) -- a plain insert() on either would throw a
// duplicate-key error the second time someone logs on the same day, so those
// two go through upsert() instead.
class AddEntryRepository(private val supabase: SupabaseClient) {

    suspend fun addTraining(distanceKm: Double, durationMin: Double, avgHr: Double?) {
        supabase.postgrest.from("runs").insert(
            NewRunRow(date = today(), distanceKm = distanceKm, durationMin = durationMin, avgHr = avgHr)
        )
    }

    suspend fun addFood(description: String, calories: Double?, proteinG: Double?, carbsG: Double?, fatG: Double?) {
        supabase.postgrest.from("meals").insert(
            NewMealRow(
                loggedAt = nowIso(),
                description = description,
                calories = calories,
                proteinG = proteinG,
                carbsG = carbsG,
                fatG = fatG,
            )
        )
    }

    // hydration_daily holds one row per day -- a running daily total, not a
    // per-drink log (confirmed against index.html's own "DAILY TOTAL (ml)"
    // framing of this same table, and its real `unique(user_id, date)`
    // index). Each call accumulates onto today's existing total rather than
    // overwriting it -- matches how someone actually drinks water across a
    // day (several small logs, not one final number). A mis-tap has no
    // dedicated undo here, but doesn't need one: Log tab entry deletion
    // covers correcting it, same as every other entry type.
    suspend fun addDrink(ml: Int) {
        val existing = supabase.postgrest.from("hydration_daily")
            .select(columns = Columns.list("ml")) { filter { eq("date", today()) } }
            .decodeList<ExistingHydrationRow>()
            .firstOrNull()

        val total = ml + (existing?.ml ?: 0)
        supabase.postgrest.from("hydration_daily").upsert(
            NewHydrationRow(date = today(), ml = total)
        ) { onConflict = "user_id,date" }
    }

    suspend fun addEncounter(encounterType: String?, notes: String?) {
        supabase.postgrest.from("encounters").insert(
            NewEncounterRow(date = today(), encounterType = encounterType, notes = notes)
        )
    }

    suspend fun addStool(bristolType: Int, discomfort: Int?) {
        supabase.postgrest.from("stool_log").insert(
            NewStoolRow(occurredAt = nowIso(), bristolType = bristolType, discomfort = discomfort)
        )
    }

    suspend fun addArousal(morningErectionQuality: Int, arousalLevel: Int) {
        supabase.postgrest.from("arousal_daily").upsert(
            NewArousalRow(date = today(), morningErectionQuality = morningErectionQuality, arousalLevel = arousalLevel)
        ) { onConflict = "user_id,date" }
    }

    suspend fun addNote(text: String) {
        supabase.postgrest.from("notes").insert(NewNoteRow(occurredAt = nowIso(), text = text))
    }

    // Edits below reuse the same New*Row payload classes as the add*
    // functions above -- update() only touches the columns present in the
    // payload, so this is a plain overwrite of exactly the editable fields,
    // scoped to one row by id. Each takes the entry's original date/
    // timestamp explicitly (fetched fresh in EditEntrySheet) rather than
    // defaulting to "now"/"today" the way add* does, so editing a past
    // entry doesn't silently move it to today.

    suspend fun updateTraining(id: Long, date: String, distanceKm: Double, durationMin: Double, avgHr: Double?) {
        supabase.postgrest.from("runs").update(
            NewRunRow(date = date, distanceKm = distanceKm, durationMin = durationMin, avgHr = avgHr)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateFood(id: Long, loggedAt: String, description: String, calories: Double?, proteinG: Double?, carbsG: Double?, fatG: Double?) {
        supabase.postgrest.from("meals").update(
            NewMealRow(loggedAt = loggedAt, description = description, calories = calories, proteinG = proteinG, carbsG = carbsG, fatG = fatG)
        ) { filter { eq("id", id) } }
    }

    // Unlike addDrink(), this overwrites the day's total outright rather than
    // accumulating -- editing means "this day's number was wrong," not
    // "another drink happened."
    suspend fun updateDrink(id: Long, date: String, ml: Int) {
        supabase.postgrest.from("hydration_daily").update(
            NewHydrationRow(date = date, ml = ml)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateEncounter(id: Long, date: String, encounterType: String?, notes: String?) {
        supabase.postgrest.from("encounters").update(
            NewEncounterRow(date = date, encounterType = encounterType, notes = notes)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateStool(id: Long, occurredAt: String, bristolType: Int, discomfort: Int?) {
        supabase.postgrest.from("stool_log").update(
            NewStoolRow(occurredAt = occurredAt, bristolType = bristolType, discomfort = discomfort)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateArousal(id: Long, date: String, morningErectionQuality: Int, arousalLevel: Int) {
        supabase.postgrest.from("arousal_daily").update(
            NewArousalRow(date = date, morningErectionQuality = morningErectionQuality, arousalLevel = arousalLevel)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateNote(id: Long, occurredAt: String, text: String) {
        supabase.postgrest.from("notes").update(
            NewNoteRow(occurredAt = occurredAt, text = text)
        ) { filter { eq("id", id) } }
    }

    // Generic delete, usable on every source including Sleep (which has no
    // corresponding add/update -- delete is still a valid "undo" for a bad
    // wearable-synced row).
    suspend fun deleteEntry(source: LogSource, id: Long) {
        supabase.postgrest.from(source.table).delete { filter { eq("id", id) } }
    }

    // Fetches below back EditEntrySheet -- one full row by id, re-read fresh
    // rather than reconstructed from the Log feed's already-formatted
    // headline/detail strings, so the edit form starts from real field
    // values. Reuses the exact Log*Row read models LogRepository already
    // decodes with, just scoped to a single id instead of the whole feed.
    suspend fun fetchMeal(id: Long) = fetchById<LogMealRow>("meals", "id,logged_at,description,calories,protein_g,carbs_g,fat_g", id)
    suspend fun fetchRun(id: Long) = fetchById<LogRunRow>("runs", "id,date,distance_km,duration_min,avg_hr", id)
    suspend fun fetchHydration(id: Long) = fetchById<LogHydrationRow>("hydration_daily", "id,date,ml", id)
    suspend fun fetchEncounter(id: Long) = fetchById<LogEncounterRow>("encounters", "id,date,status,encounter_type,notes,calendar_event_title", id)
    suspend fun fetchStool(id: Long) = fetchById<LogStoolRow>("stool_log", "id,occurred_at,bristol_type,discomfort", id)
    suspend fun fetchArousal(id: Long) = fetchById<LogArousalRow>("arousal_daily", "id,date,morning_erection_quality,arousal_level", id)
    suspend fun fetchNote(id: Long) = fetchById<LogNoteRow>("notes", "id,occurred_at,text", id)

    private suspend inline fun <reified T : Any> fetchById(table: String, columns: String, id: Long): T =
        supabase.postgrest.from(table)
            .select(columns = Columns.list(columns)) { filter { eq("id", id) } }
            .decodeList<T>()
            .first()
}

private fun today(): String = LocalDate.now().toString()
private fun nowIso(): String = OffsetDateTime.now().toString()
