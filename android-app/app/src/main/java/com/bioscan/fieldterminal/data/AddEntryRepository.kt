package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.EncounterEditRow
import com.bioscan.fieldterminal.data.model.ExerciseDetailsUpdateRow
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetails
import com.bioscan.fieldterminal.data.model.ExistingHydrationRow
import com.bioscan.fieldterminal.data.model.FullExerciseSessionRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogOstrcRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import com.bioscan.fieldterminal.data.model.NewEncounterRow
import com.bioscan.fieldterminal.data.model.NewHydrationRow
import com.bioscan.fieldterminal.data.model.NewSexualActivityRow
import com.bioscan.fieldterminal.data.model.NewMealRow
import com.bioscan.fieldterminal.data.model.SexualActivityInstance
import com.bioscan.fieldterminal.data.model.NewNoteRow
import com.bioscan.fieldterminal.data.model.NewOstrcRow
import com.bioscan.fieldterminal.data.model.LogSleepDetailRow
import com.bioscan.fieldterminal.data.model.LogSupplementTakenRow
import com.bioscan.fieldterminal.data.model.NewStoolRow
import com.bioscan.fieldterminal.data.model.NewSupplementLogRow
import com.bioscan.fieldterminal.data.model.NewWellbeingRow
import com.bioscan.fieldterminal.data.model.NutrientIntakeRow
import com.bioscan.fieldterminal.data.model.SupplementLogEditRow
import com.bioscan.fieldterminal.data.model.SupplementNutrientRow
import com.bioscan.fieldterminal.domain.LogSource
import com.bioscan.fieldterminal.domain.nutrientContribution
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    suspend fun addSexualActivity(date: String, activityType: String, instances: List<SexualActivityInstance>, notes: String?) {
        supabase.postgrest.from("sexual_activity_daily").upsert(
            NewSexualActivityRow(date = date, activityType = activityType, instances = instances, notes = notes)
        ) { onConflict = "user_id,date,activity_type" }
    }

    suspend fun addNote(occurredAt: String, text: String) {
        supabase.postgrest.from("notes").insert(NewNoteRow(occurredAt = occurredAt, text = text))
    }

    suspend fun addWellbeing(date: String, energy: Int?, mood: Int?, stress: Int?, soreness: Int?, morningErectionQuality: Int? = null, arousalLevel: Int? = null) {
        supabase.postgrest.from("wellbeing_daily").upsert(
            NewWellbeingRow(date = date, energy = energy, mood = mood, stress = stress, soreness = soreness, morningErectionQuality = morningErectionQuality, arousalLevel = arousalLevel)
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
    // other source already uses. `items` is (supplement id, name, roster
    // dose string) triples from whichever bundle(s)/individual as-needed
    // toggles were checked -- each gets its own parsed dose default (DAV-156).
    suspend fun addSupplementsTaken(takenAt: String, items: List<Triple<Long, String, String>>) {
        if (items.isEmpty()) return
        val rows = items.map { (id, name, dose) ->
            val (value, unit) = parseDose(dose)
            NewSupplementLogRow(supplementId = id, supplementName = name, takenAt = takenAt, doseValue = value, doseUnit = unit)
        }
        val insertedRows = supabase.postgrest.from("supplement_log")
            .insert(rows) { select(Columns.list("id,supplement_id")) }
            .decodeList<SupplementLogIdRow>()

        try {
            writeSupplementNutrientIntake(takenAt, insertedRows, items)
        } catch (_: Exception) {
            // Best-effort: supplement_log row is the primary record
        }
    }

    // Supplement Intelligence Phase 1 (DAV-328): a roster item linked to a
    // real supplement_products row (supplements.product_id) gets its
    // nutrient_intake rows from that product's real ingredient composition
    // (domain/SupplementComposition.kt's nutrientContribution(), elemental
    // amount preferred, compound amount as an honest fallback -- never a
    // name guess). Anything not yet linked keeps the pre-existing
    // supplement_nutrients-profile-then-name-inference fallback unchanged --
    // zero regression for roster items that haven't been given real
    // ingredient data yet.
    private suspend fun writeSupplementNutrientIntake(
        takenAt: String,
        logRows: List<SupplementLogIdRow>,
        items: List<Triple<Long, String, String>>,
    ) {
        if (logRows.isEmpty()) return
        val supplementIds = logRows.map { it.supplementId }

        val roster = supabase.postgrest.from("supplements")
            .select(columns = Columns.list("id,product_id")) { filter { isIn("id", supplementIds) } }
            .decodeList<SupplementProductLinkRow>()
        val productIdBySupp = roster.filter { it.productId != null }.associate { it.id to it.productId!! }

        val profiles = supabase.postgrest.from("supplement_nutrients")
            .select { filter { isIn("supplement_id", supplementIds) } }
            .decodeList<SupplementNutrientRow>()
        val profilesBySupp = profiles.groupBy { it.supplementId }

        val suppRepo = SupplementsRepository(supabase)
        val nutrientRows = mutableListOf<NutrientIntakeRow>()
        for (logRow in logRows) {
            val productId = productIdBySupp[logRow.supplementId]
            val suppProfile = profilesBySupp[logRow.supplementId]
            if (productId != null) {
                val composition = suppRepo.loadProductComposition(productId)
                for (productIngredient in composition) {
                    val contribution = nutrientContribution(productIngredient, servingCount = 1.0) ?: continue
                    nutrientRows.add(
                        NutrientIntakeRow(
                            loggedAt = takenAt,
                            nutrient = contribution.nutrientKey,
                            amount = contribution.amount,
                            unit = contribution.unit,
                            sourceType = "supplement",
                            sourceId = logRow.id,
                        ),
                    )
                }
            } else if (suppProfile != null) {
                for (sn in suppProfile) {
                    nutrientRows.add(
                        NutrientIntakeRow(
                            loggedAt = takenAt,
                            nutrient = sn.nutrient,
                            amount = sn.amountPerDose,
                            unit = sn.unit,
                            sourceType = "supplement",
                            sourceId = logRow.id,
                        ),
                    )
                }
            } else {
                val item = items.firstOrNull { it.first == logRow.supplementId }
                if (item != null) {
                    val (doseValue, doseUnit) = parseDose(item.third)
                    val inferredNutrient = inferNutrientFromName(item.second)
                    if (inferredNutrient != null && doseValue != null && doseUnit != null) {
                        nutrientRows.add(
                            NutrientIntakeRow(
                                loggedAt = takenAt,
                                nutrient = inferredNutrient,
                                amount = doseValue,
                                unit = doseUnit,
                                sourceType = "supplement",
                                sourceId = logRow.id,
                            ),
                        )
                    }
                }
            }
        }

        if (nutrientRows.isNotEmpty()) {
            supabase.postgrest.from("nutrient_intake").insert(nutrientRows)
        }
    }

    suspend fun updateSupplementTaken(id: Long, takenAt: String, doseValue: Double?, doseUnit: String?) {
        supabase.postgrest.from("supplement_log").update(
            SupplementLogEditRow(takenAt = takenAt, doseValue = doseValue, doseUnit = doseUnit)
        ) { filter { eq("id", id) } }
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

    suspend fun updateSexualActivity(id: Long, date: String, activityType: String, instances: List<SexualActivityInstance>, notes: String?) {
        supabase.postgrest.from("sexual_activity_daily").update(
            NewSexualActivityRow(date = date, activityType = activityType, instances = instances, notes = notes)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateNote(id: Long, occurredAt: String, text: String) {
        supabase.postgrest.from("notes").update(
            NewNoteRow(occurredAt = occurredAt, text = text)
        ) { filter { eq("id", id) } }
    }

    suspend fun updateWellbeing(id: Long, date: String, energy: Int?, mood: Int?, stress: Int?, soreness: Int?, morningErectionQuality: Int? = null, arousalLevel: Int? = null) {
        supabase.postgrest.from("wellbeing_daily").update(
            NewWellbeingRow(date = date, energy = energy, mood = mood, stress = stress, soreness = soreness, morningErectionQuality = morningErectionQuality, arousalLevel = arousalLevel)
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

    // Narrow tag write for the "suspected trail runs" flow: reads the row's
    // current details JSON and merges only route_type, unlike
    // updateExerciseDetails() above which overwrites details/rpe/notes whole.
    suspend fun updateRouteType(id: Long, routeType: String) {
        val current = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("details")) { filter { eq("id", id) } }
            .decodeSingle<DetailsJsonRow>()
        val merged = JsonObject(current.details + ("route_type" to JsonPrimitive(routeType)))
        supabase.postgrest.from("exercise_sessions").update(DetailsJsonRow(merged)) { filter { eq("id", id) } }
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
    suspend fun fetchNote(id: Long) = fetchById<LogNoteRow>("notes", "id,occurred_at,text", id)
    suspend fun fetchWellbeing(id: Long) = fetchById<LogWellbeingRow>("wellbeing_daily", "id,date,energy,mood,stress,soreness,morning_erection_quality,arousal_level", id)
    suspend fun fetchExerciseSession(id: Long) = fetchById<FullExerciseSessionRow>("exercise_sessions", "id,type,rpe,notes,details", id)
    suspend fun fetchOstrc(id: Long) = fetchById<LogOstrcRow>("ostrc_checkins", "id,check_date,body_area,q1,q2,q3,q4,notes", id)
    suspend fun fetchSexualActivity(id: Long) = fetchById<LogSexualActivityRow>("sexual_activity_daily", "id,date,activity_type,instances,notes", id)
    suspend fun fetchSupplementTaken(id: Long) = fetchById<LogSupplementTakenRow>("supplement_log", "id,supplement_name,taken_at,dose_value,dose_unit", id)

    // DAV-160. Read-only -- Sleep has no edit form (see LogSource's own doc
    // comment), this just exposes the richer columns Health Connect sync
    // already writes but the Log feed's own LogSleepRow never selected.
    suspend fun fetchSleepDetail(id: Long) = fetchById<LogSleepDetailRow>(
        "sleep_daily",
        "id,date,hours,score,respiratory_rate,bedtime,wake_time,deep_min,rem_min,light_min,source",
        id,
    )

    private suspend inline fun <reified T : Any> fetchById(table: String, columns: String, id: Long): T? =
        supabase.postgrest.from(table)
            .select(columns = Columns.list(columns)) { filter { eq("id", id) } }
            .decodeList<T>()
            .firstOrNull()
}

// DAV-156. The roster's `dose` column is free text (e.g. "500 mg", "36 mg,
// 3x/week", "1 pill") -- splits off a leading numeric value and keeps
// whatever follows as the unit, rather than trying to validate/normalize
// real-world dose formatting. A dose string with no leading number (none
// currently, but real going forward -- e.g. "as needed") keeps the whole
// string as the unit with a null value, so nothing is silently dropped.
private val LEADING_NUMBER = Regex("""^([0-9.]+)\s*(.*)$""")

internal fun parseDose(dose: String): Pair<Double?, String?> {
    val trimmed = dose.trim()
    val match = LEADING_NUMBER.find(trimmed) ?: return null to trimmed.ifBlank { null }
    val value = match.groupValues[1].toDoubleOrNull() ?: return null to trimmed.ifBlank { null }
    return value to match.groupValues[2].trim().ifBlank { null }
}

@kotlinx.serialization.Serializable
private data class SupplementLogIdRow(
    val id: Long,
    @SerialName("supplement_id") val supplementId: Long,
)

@kotlinx.serialization.Serializable
private data class SupplementProductLinkRow(
    val id: Long,
    @SerialName("product_id") val productId: Long? = null,
)

@kotlinx.serialization.Serializable
private data class DetailsJsonRow(val details: JsonObject = JsonObject(emptyMap()))

private fun inferNutrientFromName(name: String): String? {
    val n = name.lowercase()
    return when {
        n.contains("vitamin d") || n.contains(" d3") -> "vitamin_d"
        n.contains("vitamin c") -> "vitamin_c"
        n.contains("vitamin b12") || n.contains(" b12") -> "vitamin_b12"
        n.contains("vitamin b6") || n.contains(" b6") -> "vitamin_b6"
        n.contains("vitamin a") -> "vitamin_a"
        n.contains("vitamin e") -> "vitamin_e"
        n.contains("vitamin k") -> "vitamin_k"
        n.contains("folate") || n.contains("folic") -> "folate_b9"
        n.contains("thiamin") || (n.contains(" b1") && !n.contains("b12")) -> "thiamin_b1"
        n.contains("riboflavin") -> "riboflavin_b2"
        n.contains("niacin") -> "niacin_b3"
        n.contains("biotin") -> "biotin"
        n.contains("magnesium") -> "magnesium"
        n.contains("zinc") -> "zinc"
        n.contains("iron") -> "iron"
        n.contains("calcium") -> "calcium"
        n.contains("selenium") -> "selenium"
        n.contains("potassium") -> "potassium"
        n.contains("copper") -> "copper"
        n.contains("manganese") -> "manganese"
        n.contains("chromium") -> "chromium"
        n.contains("iodine") -> "iodine"
        n.contains("fish oil") || n.contains("omega-3") || n.contains("omega 3") -> "omega_3"
        n.contains("dha") -> "dha"
        n.contains("epa") -> "epa"
        n.contains("creatine") -> "creatine"
        n.contains("collagen") -> "collagen"
        n.contains("choline") -> "choline"
        n.contains("melatonin") -> "melatonin"
        n.contains("caffeine") -> "caffeine"
        n.contains("ashwagandha") -> "ashwagandha"
        n.contains("coq10") || n.contains("coenzyme q10") -> "coq10"
        n.contains("glucosamine") -> "glucosamine"
        n.contains("curcumin") || n.contains("turmeric") -> "curcumin"
        n.contains("probiotics") || n.contains("probiotic") -> "probiotics"
        else -> null
    }
}
