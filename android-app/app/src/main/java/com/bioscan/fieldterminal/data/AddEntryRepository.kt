package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.EncounterEditRow
import com.bioscan.fieldterminal.data.model.ExerciseDetailsUpdateRow
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetails
import com.bioscan.fieldterminal.data.model.ExistingHydrationRow
import com.bioscan.fieldterminal.data.model.FullExerciseSessionRow
import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMasturbationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogOstrcRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import com.bioscan.fieldterminal.data.model.NewArousalRow
import com.bioscan.fieldterminal.data.model.NewEncounterRow
import com.bioscan.fieldterminal.data.model.NewHydrationRow
import com.bioscan.fieldterminal.data.model.NewMasturbationRow
import com.bioscan.fieldterminal.data.model.NewMealRow
import com.bioscan.fieldterminal.data.model.NewNoteRow
import com.bioscan.fieldterminal.data.model.NewOstrcRow
import com.bioscan.fieldterminal.data.model.NewStoolRow
import com.bioscan.fieldterminal.data.model.NewSupplementLogRow
import com.bioscan.fieldterminal.data.model.NewWellbeingRow
import com.bioscan.fieldterminal.domain.LogSource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns

// Step 12 (Phase D) + the 2026-09-15 follow-up pass: the "+" add-entry
// flow's writes. Every add*/update* now takes its date/timestamp as an
// explicit parameter (added alongside the date picker on every form) rather
// than defaulting to "now" internally -- the UI layer owns "when," this
// layer just writes what it's given. `runs` allowed multiple rows per day
// (moot now: Training was removed from the add-entry flow entirely, see
// domain/AddEntry.kt), but `hydration_daily`, `arousal_daily` and
// `wellbeing_daily` all carry a real `unique(user_id, date)` index
// (confirmed directly against the schema, matching the existing quick-log
// Edge Function's own upsert-on-conflict usage for arousal_daily) -- a plain
// insert() on any of them would throw a duplicate-key error the second time
// someone logs on the same day, so those three go through upsert() instead.
class AddEntryRepository(private val supabase: SupabaseClient) {

    suspend fun addFood(
        loggedAt: String,
        description: String,
        calories: Double?,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
        fiberG: Double?,
        sugarG: Double?,
        sodiumMg: Double?,
    ) {
        supabase.postgrest.from("meals").insert(
            NewMealRow(
                loggedAt = loggedAt,
                description = description,
                calories = calories,
                proteinG = proteinG,
                carbsG = carbsG,
                fatG = fatG,
                fiberG = fiberG,
                sugarG = sugarG,
                sodiumMg = sodiumMg,
            )
        )
    }

    // hydration_daily holds one row per day -- a running daily total, not a
    // per-drink log (confirmed against index.html's own "DAILY TOTAL (ml)"
    // framing of this same table, and its real `unique(user_id, date)`
    // index). Each call accumulates onto that day's existing total rather
    // than overwriting it -- matches how someone actually drinks water
    // across a day (several small logs, not one final number). A mis-tap
    // has no dedicated undo here, but doesn't need one: Log tab entry
    // deletion covers correcting it, same as every other entry type.
    suspend fun addDrink(date: String, ml: Int) {
        val existing = supabase.postgrest.from("hydration_daily")
            .select(columns = Columns.list("ml")) { filter { eq("date", date) } }
            .decodeList<ExistingHydrationRow>()
            .firstOrNull()

        val total = ml + (existing?.ml ?: 0)
        supabase.postgrest.from("hydration_daily").upsert(
            NewHydrationRow(date = date, ml = total)
        ) { onConflict = "user_id,date" }
    }

    // personId/calendarEventTitle (Phase M3) are only ever passed by the Map
    // tab's "LOG ENCOUNTER" action -- defaulted null so that call site
    // (date/encounterType/notes only) is unchanged. occurredAt/locationType/
    // durationMin/activities/myRating (DAV-158/159) default null too, for
    // the same reason -- Map's minimal flow stays exactly as it was.
    suspend fun addEncounter(
        date: String,
        encounterType: String?,
        notes: String?,
        personId: Long? = null,
        calendarEventTitle: String? = null,
        occurredAt: String? = null,
        locationType: String? = null,
        durationMin: Int? = null,
        activities: List<String>? = null,
        myRating: Int? = null,
    ) {
        supabase.postgrest.from("encounters").insert(
            NewEncounterRow(
                date = date,
                occurredAt = occurredAt,
                encounterType = encounterType,
                locationType = locationType,
                durationMin = durationMin,
                activities = activities,
                myRating = myRating,
                notes = notes,
                personId = personId,
                calendarEventTitle = calendarEventTitle,
            )
        )
    }

    suspend fun addStool(occurredAt: String, bristolType: Int, discomfort: Int?) {
        supabase.postgrest.from("stool_log").insert(
            NewStoolRow(occurredAt = occurredAt, bristolType = bristolType, discomfort = discomfort)
        )
    }

    suspend fun addMasturbation(occurredAt: String, watchedPorn: Boolean, loadSize: Int?, orgasmIntensity: Int?, notes: String?) {
        supabase.postgrest.from("masturbation_log").insert(
            NewMasturbationRow(occurredAt = occurredAt, watchedPorn = watchedPorn, loadSize = loadSize, orgasmIntensity = orgasmIntensity, notes = notes)
        )
    }

    suspend fun addArousal(date: String, morningErectionQuality: Int, arousalLevel: Int) {
        supabase.postgrest.from("arousal_daily").upsert(
            NewArousalRow(date = date, morningErectionQuality = morningErectionQuality, arousalLevel = arousalLevel)
        ) { onConflict = "user_id,date" }
    }

    suspend fun addNote(occurredAt: String, text: String) {
        supabase.postgrest.from("notes").insert(NewNoteRow(occurredAt = occurredAt, text = text))
    }

    suspend fun addWellbeing(date: String, energy: Int?, mood: Int?, stress: Int?, soreness: Int?) {
        supabase.postgrest.from("wellbeing_daily").upsert(
            NewWellbeingRow(date = date, energy = energy, mood = mood, stress = stress, soreness = soreness)
        ) { onConflict = "user_id,date" }
    }

    // Phase A4 (Category 8). severity_score is a stored generated column --
    // never written here, matching NewOstrcRow's own shape (q1-q4 + notes
    // only).
    suspend fun addOstrc(checkDate: String, bodyArea: String, q1: Int, q2: Int, q3: Int, q4: Int, notes: String?) {
        supabase.postgrest.from("ostrc_checkins").insert(
            NewOstrcRow(checkDate = checkDate, bodyArea = bodyArea, q1 = q1, q2 = q2, q3 = q3, q4 = q4, notes = notes)
        )
    }

    // One row per supplement taken (not one combined row per session) --
    // this is what makes "add or remove single items" free: each is just a
    // normal Log entry, deletable with the same generic deleteEntry() every
    // other source already uses. `items` is (supplement id, name) pairs from
    // whichever bundle(s)/individual as-needed toggles were checked.
    suspend fun addSupplementsTaken(takenAt: String, items: List<Pair<Long, String>>) {
        if (items.isEmpty()) return
        val rows = items.map { (id, name) -> NewSupplementLogRow(supplementId = id, supplementName = name, takenAt = takenAt) }
        supabase.postgrest.from("supplement_log").insert(rows)
    }

    // Edits below reuse the same New*Row payload classes as the add*
    // functions above -- update() only touches the columns present in the
    // payload, so this is a plain overwrite of exactly the editable fields,
    // scoped to one row by id. Each takes the entry's original date/
    // timestamp explicitly (fetched fresh in EditEntrySheet) rather than
    // defaulting to "now"/"today", so editing a past entry doesn't silently
    // move it to today.

    suspend fun updateFood(
        id: Long,
        loggedAt: String,
        description: String,
        calories: Double?,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
        fiberG: Double?,
        sugarG: Double?,
        sodiumMg: Double?,
    ) {
        supabase.postgrest.from("meals").update(
            NewMealRow(
                loggedAt = loggedAt,
                description = description,
                calories = calories,
                proteinG = proteinG,
                carbsG = carbsG,
                fatG = fatG,
                fiberG = fiberG,
                sugarG = sugarG,
                sodiumMg = sodiumMg,
            )
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

    // Uses EncounterEditRow, not NewEncounterRow -- a real bug found live:
    // NewEncounterRow carries personId/calendarEventTitle (defaulted null
    // here), and Postgrest writes every field a DTO carries, so this used to
    // silently null out an already-linked encounter's person_id/
    // calendar_event_title on every edit. EncounterEditRow structurally
    // can't touch those two columns.
    suspend fun updateEncounter(
        id: Long,
        date: String,
        occurredAt: String?,
        encounterType: String?,
        locationType: String?,
        durationMin: Int?,
        activities: List<String>?,
        myRating: Int?,
        notes: String?,
    ) {
        supabase.postgrest.from("encounters").update(
            EncounterEditRow(
                date = date,
                occurredAt = occurredAt,
                encounterType = encounterType,
                locationType = locationType,
                durationMin = durationMin,
                activities = activities,
                myRating = myRating,
                notes = notes,
            )
        ) { filter { eq("id", id) } }
    }

    suspend fun updateStool(id: Long, occurredAt: String, bristolType: Int, discomfort: Int?) {
        supabase.postgrest.from("stool_log").update(
            NewStoolRow(occurredAt = occurredAt, bristolType = bristolType, discomfort = discomfort)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateMasturbation(id: Long, occurredAt: String, watchedPorn: Boolean, loadSize: Int?, orgasmIntensity: Int?, notes: String?) {
        supabase.postgrest.from("masturbation_log").update(
            NewMasturbationRow(occurredAt = occurredAt, watchedPorn = watchedPorn, loadSize = loadSize, orgasmIntensity = orgasmIntensity, notes = notes)
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

    suspend fun updateWellbeing(id: Long, date: String, energy: Int?, mood: Int?, stress: Int?, soreness: Int?) {
        supabase.postgrest.from("wellbeing_daily").update(
            NewWellbeingRow(date = date, energy = energy, mood = mood, stress = stress, soreness = soreness)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateOstrc(id: Long, checkDate: String, bodyArea: String, q1: Int, q2: Int, q3: Int, q4: Int, notes: String?) {
        supabase.postgrest.from("ostrc_checkins").update(
            NewOstrcRow(checkDate = checkDate, bodyArea = bodyArea, q1 = q1, q2 = q2, q3 = q3, q4 = q4, notes = notes)
        ) { filter { eq("id", id) } }
    }

    // Phase G3 + Phase B follow-up: the only editable fields on a
    // Health-Connect-sourced exercise session -- times/distance/HR/etc. all
    // come from Health Connect and are never written here. `details` is
    // written in full (not merged server-side) -- the caller (AddEntrySheet's
    // ExerciseDetailsForm) always starts from the row's existing details and
    // only changes the fields its own type's UI exposes, so this is never a
    // blind overwrite in practice.
    suspend fun updateExerciseDetails(id: Long, rpe: Int?, notes: String?, details: ExerciseSessionDetails) {
        supabase.postgrest.from("exercise_sessions").update(
            ExerciseDetailsUpdateRow(rpe = rpe, notes = notes, details = details)
        ) { filter { eq("id", id) } }
    }

    // Generic delete, usable on every source including Sleep and Supplement
    // (neither has a corresponding add/update form) -- delete is still a
    // valid "undo" for a bad wearable-synced row or a mistakenly-checked
    // supplement.
    suspend fun deleteEntry(source: LogSource, id: Long) {
        supabase.postgrest.from(source.table).delete { filter { eq("id", id) } }
    }

    // Fetches below back EditEntrySheet -- one full row by id, re-read fresh
    // rather than reconstructed from the Log feed's already-formatted
    // headline/detail strings, so the edit form starts from real field
    // values. Reuses the exact Log*Row read models LogRepository already
    // decodes with, just scoped to a single id instead of the whole feed.
    // No fetchSupplement -- that source isn't editable (see LogSource's own
    // doc comment). fetchExerciseSession only selects the editable fields
    // (id, type, rpe, notes) -- everything else is Health-Connect-sourced
    // and read-only in this app.
    suspend fun fetchMeal(id: Long) = fetchById<LogMealRow>("meals", "id,logged_at,description,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg", id)
    suspend fun fetchHydration(id: Long) = fetchById<LogHydrationRow>("hydration_daily", "id,date,ml", id)
    suspend fun fetchEncounter(id: Long) = fetchById<LogEncounterRow>(
        "encounters",
        "id,date,status,occurred_at,encounter_type,location_type,duration_min,activities,my_rating,notes,calendar_event_title,person_id",
        id,
    )
    suspend fun fetchStool(id: Long) = fetchById<LogStoolRow>("stool_log", "id,occurred_at,bristol_type,discomfort", id)
    suspend fun fetchArousal(id: Long) = fetchById<LogArousalRow>("arousal_daily", "id,date,morning_erection_quality,arousal_level", id)
    suspend fun fetchNote(id: Long) = fetchById<LogNoteRow>("notes", "id,occurred_at,text", id)
    suspend fun fetchWellbeing(id: Long) = fetchById<LogWellbeingRow>("wellbeing_daily", "id,date,energy,mood,stress,soreness", id)
    suspend fun fetchExerciseSession(id: Long) = fetchById<FullExerciseSessionRow>("exercise_sessions", "id,type,rpe,notes,details", id)
    suspend fun fetchOstrc(id: Long) = fetchById<LogOstrcRow>("ostrc_checkins", "id,check_date,body_area,q1,q2,q3,q4,notes", id)
    suspend fun fetchMasturbation(id: Long) = fetchById<LogMasturbationRow>("masturbation_log", "id,occurred_at,watched_porn,load_size,orgasm_intensity,notes", id)

    private suspend inline fun <reified T : Any> fetchById(table: String, columns: String, id: Long): T =
        supabase.postgrest.from(table)
            .select(columns = Columns.list(columns)) { filter { eq("id", id) } }
            .decodeList<T>()
            .first()
}
