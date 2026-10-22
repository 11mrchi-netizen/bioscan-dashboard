package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogExerciseRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogOstrcRow
import com.bioscan.fieldterminal.data.model.LogSleepRow
import com.bioscan.fieldterminal.data.model.LogGripRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.LogSupplementTakenRow
import com.bioscan.fieldterminal.data.model.SupplementScheduleRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import com.bioscan.fieldterminal.domain.LogEntry
import com.bioscan.fieldterminal.domain.LogMark
import com.bioscan.fieldterminal.domain.buildLogEntries
import com.bioscan.fieldterminal.domain.logCoverage
import java.time.LocalDate
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// The feed loads a window of days, not a number of rows. It used to take the newest 60 rows of every table, which on this
// account reached back only two days for supplements (about 10 a day) and ten for meals (about 6 a day), so everything
// older looked unlogged. Now every table is read from the window's first day (`since`), and LOAD OLDER widens the window
// by LOG_WINDOW_DAYS once the loaded days are all shown. A per-table safety cap stays (the API returns at most 1,000 rows
// a call); a table that hits it is only known back to its oldest row (logCoverage). Every row also selects `id`, needed
// for Log tab edit/delete.
private const val FETCH_CAP_PER_SOURCE = 1000L
// Days revealed per "LOAD OLDER" tap (whole days, so a day is never half-shown).
const val LOG_PAGE_DAYS = 14
// Days each fetch reaches back; the window grows by this much when LOAD OLDER runs past the loaded days.
const val LOG_WINDOW_DAYS = 28

// The merged entries from `since` on, how far back each flaggable mark's source is known to be complete, and the
// supplement roster.
data class LogFeed(
    val entries: List<LogEntry>,
    val coverage: Map<LogMark, LocalDate?>,
    val supplementSchedule: List<SupplementScheduleRow>,
    val since: LocalDate,
)

class LogRepository(private val supabase: SupabaseClient) {

    suspend fun loadFeed(since: LocalDate): LogFeed = coroutineScope {
        // Date columns from `since`; timestamps from the day before, so an entry near midnight is not lost to the stored
        // offset, then trimmed to `since` once parsed (entries are grouped by their own wall-clock day).
        val fromDate = since.toString()
        val fromTimestamp = since.minusDays(1).toString()
        val meals = async {
            supabase.postgrest.from("meals")
                .select(columns = Columns.list("id,logged_at,description,calories,protein_g,carbs_g,fat_g")) {
                    filter { gte("logged_at", fromTimestamp) }
                    order("logged_at", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogMealRow>()
        }
        val exerciseSessions = async {
            supabase.postgrest.from("exercise_sessions")
                .select(columns = Columns.list("id,type,start_time,distance_km,duration_min,avg_hr,source")) {
                    filter { gte("start_time", fromTimestamp) }
                    order("start_time", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogExerciseRow>()
        }
        val sleep = async {
            supabase.postgrest.from("sleep_daily")
                .select(columns = Columns.list("id,date,hours,score")) {
                    filter { gte("date", fromDate) }
                    order("date", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogSleepRow>()
        }
        val stool = async {
            supabase.postgrest.from("stool_log")
                .select(columns = Columns.list("id,occurred_at,bristol_type,discomfort")) {
                    filter { gte("occurred_at", fromTimestamp) }
                    order("occurred_at", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogStoolRow>()
        }
        val encounters = async {
            supabase.postgrest.from("encounters")
                .select(columns = Columns.list("id,date,status,occurred_at,encounter_type,notes,calendar_event_title,my_rating")) {
                    filter { gte("date", fromDate) }
                    order("date", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogEncounterRow>()
        }
        val notes = async {
            supabase.postgrest.from("notes")
                .select(columns = Columns.list("id,occurred_at,text")) {
                    filter { gte("occurred_at", fromTimestamp) }
                    order("occurred_at", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogNoteRow>()
        }
        val hydration = async {
            supabase.postgrest.from("hydration_daily")
                .select(columns = Columns.list("id,date,ml")) {
                    filter { gte("date", fromDate) }
                    order("date", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogHydrationRow>()
        }
        val wellbeing = async {
            supabase.postgrest.from("wellbeing_daily")
                .select(columns = Columns.list("id,date,energy,mood,stress,soreness,morning_erection_quality,arousal_level")) {
                    filter { gte("date", fromDate) }
                    order("date", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogWellbeingRow>()
        }
        val supplementsTaken = async {
            supabase.postgrest.from("supplement_log")
                .select(columns = Columns.list("id,supplement_name,taken_at,dose_value,dose_unit")) {
                    filter { gte("taken_at", fromTimestamp) }
                    order("taken_at", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogSupplementTakenRow>()
        }
        val ostrc = async {
            supabase.postgrest.from("ostrc_checkins")
                .select(columns = Columns.list("id,check_date,body_area,q1,q2,q3,q4,notes")) {
                    filter { gte("check_date", fromDate) }
                    order("check_date", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogOstrcRow>()
        }
        val sexualActivity = async {
            supabase.postgrest.from("sexual_activity_daily")
                .select(columns = Columns.list("id,date,activity_type,instances,notes")) {
                    filter { gte("date", fromDate) }
                    order("date", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogSexualActivityRow>()
        }
        val grip = async {
            supabase.postgrest.from("grip_strength_log")
                .select(columns = Columns.list("id,occurred_at,hand,kg")) {
                    filter { gte("occurred_at", fromTimestamp) }
                    order("occurred_at", Order.DESCENDING)
                    limit(FETCH_CAP_PER_SOURCE)
                }.decodeList<LogGripRow>()
        }
        // A roster that fails to load only costs the supplement gap marks, never the feed (caught inside the child so the
        // failure cannot cancel the sibling queries).
        val schedule = async {
            try {
                supabase.postgrest.from("supplements")
                    .select(columns = Columns.list("id,status,start_date,end_date,every_n_days"))
                    .decodeList<SupplementScheduleRow>()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        val mealRows = meals.await()
        val hydrationRows = hydration.await()
        val supplementRows = supplementsTaken.await()
        val sleepRows = sleep.await()
        val wellbeingRows = wellbeing.await()
        LogFeed(
            entries = buildLogEntries(
                mealRows, exerciseSessions.await(), sleepRows,
                stool.await(), encounters.await(), notes.await(), hydrationRows,
                wellbeingRows, supplementRows, ostrc.await(), sexualActivity.await(), grip.await(),
            ).filter { !it.timestamp.toLocalDate().isBefore(since) },
            coverage = logCoverage(mealRows, hydrationRows, supplementRows, sleepRows, wellbeingRows, FETCH_CAP_PER_SOURCE.toInt(), since),
            supplementSchedule = schedule.await(),
            since = since,
        )
    }
}
