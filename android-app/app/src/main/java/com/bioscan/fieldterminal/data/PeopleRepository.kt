package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.NewPersonRow
import com.bioscan.fieldterminal.data.model.PersonRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns

// Phase M2 (Map tab rework). Backs the Encounter/Social pin detail's partner
// match/search/create flow -- see ROADMAP.md. `people` already has real RLS
// (auth.uid() = user_id, column default auth.uid()) identical to every other
// table this app writes to, so createPerson() needs no user_id handling.
class PeopleRepository(private val supabase: SupabaseClient) {
    // One fetch of all 168 rows, matched client-side -- simpler than a
    // server-side "title contains any of these names" query, and the table
    // is small enough that this costs nothing noticeable.
    //
    // Word-boundary match, not raw substring -- a real on-device test caught
    // a real false positive here: this table has a person named just "C",
    // and a plain `title.contains(name)` matched it against the word
    // "matches" (which contains the letter "c"). `\b` requires "C" to appear
    // as its own token, not embedded inside an unrelated word.
    suspend fun findByNameInTitle(title: String): PersonRow? {
        val all = supabase.postgrest.from("people").select(Columns.list("id,name")).decodeList<PersonRow>()
        return all.firstOrNull { person ->
            val name = person.name.trim()
            name.isNotEmpty() && Regex("(?i)\\b${Regex.escape(name)}\\b").containsMatchIn(title)
        }
    }

    suspend fun search(query: String): List<PersonRow> {
        if (query.isBlank()) return emptyList()
        return supabase.postgrest.from("people")
            .select(Columns.list("id,name")) { filter { ilike("name", "%$query%") } }
            .decodeList()
    }

    suspend fun createPerson(name: String): PersonRow =
        supabase.postgrest.from("people")
            .insert(NewPersonRow(name)) { select(Columns.list("id,name")) }
            .decodeSingle()
}
