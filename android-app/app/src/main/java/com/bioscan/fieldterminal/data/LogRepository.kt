package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogRunRow
import com.bioscan.fieldterminal.data.model.LogSleepRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.LogSupplementTakenRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import com.bioscan.fieldterminal.domain.LogEntry
import com.bioscan.fieldterminal.domain.buildLogEntries
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

// Pagination decision for Step 11's flagged open question: fetch a generous
// window from every source table up front (60 rows each, well past this
// account's current real volume), merge + sort once, then reveal the merged
// pool 20 entries at a time via "LOAD OLDER" -- client-side windowing over
// one real fetch, not unbounded, and not a fake infinite-scroll illusion.
// Notes, hydration (as a "day total" entry), wellbeing and supplement-taken
// confirmations all gained real Log presence across Steps 12 and this
// follow-up pass -- `supplement_log` in particular is a new table (a
// supplement's roster row isn't itself a loggable event; a taken
// confirmation is). Every row now also selects `id`, needed for Log tab
// edit/delete.
private const val FETCH_LIMIT_PER_SOURCE = 60L
const val LOG_PAGE_SIZE = 20

class LogRepository(private val supabase: SupabaseClient) {

    suspend fun loadAllEntries(): List<LogEntry> {
        val meals = supabase.postgrest.from("meals")
            .select(columns = Columns.list("id,logged_at,description,calories,protein_g,carbs_g,fat_g")) {
                order("logged_at", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogMealRow>()

        val runs = supabase.postgrest.from("runs")
            .select(columns = Columns.list("id,date,distance_km,duration_min,avg_hr")) {
                order("date", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogRunRow>()

        val sleep = supabase.postgrest.from("sleep_daily")
            .select(columns = Columns.list("id,date,hours,score")) {
                order("date", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogSleepRow>()

        val arousal = supabase.postgrest.from("arousal_daily")
            .select(columns = Columns.list("id,date,morning_erection_quality,arousal_level")) {
                order("date", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogArousalRow>()

        val stool = supabase.postgrest.from("stool_log")
            .select(columns = Columns.list("id,occurred_at,bristol_type,discomfort")) {
                order("occurred_at", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogStoolRow>()

        val encounters = supabase.postgrest.from("encounters")
            .select(columns = Columns.list("id,date,status,encounter_type,notes,calendar_event_title")) {
                order("date", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogEncounterRow>()

        val notes = supabase.postgrest.from("notes")
            .select(columns = Columns.list("id,occurred_at,text")) {
                order("occurred_at", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogNoteRow>()

        val hydration = supabase.postgrest.from("hydration_daily")
            .select(columns = Columns.list("id,date,ml")) {
                order("date", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogHydrationRow>()

        val wellbeing = supabase.postgrest.from("wellbeing_daily")
            .select(columns = Columns.list("id,date,energy,mood,stress,soreness")) {
                order("date", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogWellbeingRow>()

        val supplementsTaken = supabase.postgrest.from("supplement_log")
            .select(columns = Columns.list("id,supplement_name,taken_at")) {
                order("taken_at", Order.DESCENDING)
                limit(FETCH_LIMIT_PER_SOURCE)
            }.decodeList<LogSupplementTakenRow>()

        return buildLogEntries(meals, runs, sleep, arousal, stool, encounters, notes, hydration, wellbeing, supplementsTaken)
    }
}
