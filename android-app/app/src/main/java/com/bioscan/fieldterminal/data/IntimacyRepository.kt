package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ENCOUNTER_COLUMNS
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.NewPersonRow
import com.bioscan.fieldterminal.data.model.NewStiTestRow
import com.bioscan.fieldterminal.data.model.PERSON_PROFILE_COLUMNS
import com.bioscan.fieldterminal.data.model.PersonProfileRow
import com.bioscan.fieldterminal.data.model.StiTestRow
import com.bioscan.fieldterminal.data.model.StiTreatedPatch
import com.bioscan.fieldterminal.data.routine.LocationRepository
import com.bioscan.fieldterminal.data.routine.PlacesRepository
import com.bioscan.fieldterminal.domain.VisitSpan
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// ME › INTIMACY: the encounters, the people behind them and the STI tests. Read straight from the tables (no read
// cache): the page is opened rarely and must show an entry saved a moment ago.
class IntimacyRepository(private val supabase: SupabaseClient) {

    // ponytail: the whole history in one read, capped at the API's 1,000 rows; page it when the table gets there.
    suspend fun loadEncounters(): List<LogEncounterRow> =
        supabase.postgrest.from("encounters")
            .select(Columns.list(ENCOUNTER_COLUMNS)) { order("date", Order.DESCENDING); limit(1000) }
            .decodeList()

    suspend fun loadPeople(): List<PersonProfileRow> =
        supabase.postgrest.from("people").select(Columns.list(PERSON_PROFILE_COLUMNS)) { limit(1000) }.decodeList()

    // NewPersonRow carries only what the partner sheet edits, so the other profile columns are left as they are.
    suspend fun updatePerson(id: Long, row: NewPersonRow) {
        supabase.postgrest.from("people").update(row) { filter { eq("id", id) } }
    }

    suspend fun loadStiTests(): List<StiTestRow> =
        supabase.postgrest.from("sti_tests")
            .select(Columns.list("id,test_date,infection,result,site,lab_name,treated_on,notes")) { order("test_date", Order.DESCENDING) }
            .decodeList()

    suspend fun addStiTests(rows: List<NewStiTestRow>) {
        supabase.postgrest.from("sti_tests").insert(rows)
    }

    suspend fun setStiTreated(id: Long, on: LocalDate?) {
        supabase.postgrest.from("sti_tests").update(StiTreatedPatch(on?.toString())) { filter { eq("id", id) } }
    }

    suspend fun deleteStiTest(id: Long) {
        supabase.postgrest.from("sti_tests").delete { filter { eq("id", id) } }
    }

    /** The stays from the day before [day] on, in local time, each with its place's name where the place has one. */
    suspend fun visitsAround(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Pair<VisitSpan, String?>> {
        val since = day.minusDays(1).atStartOfDay(zone).toInstant()
        val visits = LocationRepository(supabase).recentVisits(since)
        if (visits.isEmpty()) return emptyList()
        val names = PlacesRepository(supabase).loadPlaces().associate { it.id to (it.label ?: it.osmName) }
        return visits.mapNotNull { v ->
            val start = runCatching { OffsetDateTime.parse(v.startAt).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull() ?: return@mapNotNull null
            val end = runCatching { OffsetDateTime.parse(v.endAt).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull() ?: return@mapNotNull null
            VisitSpan(v.placeId, start, end) to v.placeId?.let { names[it] }
        }
    }
}
