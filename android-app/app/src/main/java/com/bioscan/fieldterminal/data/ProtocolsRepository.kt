package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.InterventionNameRow
import com.bioscan.fieldterminal.data.model.NewProtocol
import com.bioscan.fieldterminal.data.model.NewProtocolMember
import com.bioscan.fieldterminal.data.model.ProtocolMemberRow
import com.bioscan.fieldterminal.data.model.ProtocolRow
import com.bioscan.fieldterminal.data.model.ProtocolTemplateRow
import com.bioscan.fieldterminal.domain.ProtocolLifecycleState
import com.bioscan.fieldterminal.domain.isValidProtocolTransition
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.util.UUID

// Intervention & Protocol Intelligence 08.3: the write path the tables never had. A protocol groups interventions around one
// objective; the database logs each lifecycle change (protocol_state_history) and the app refuses an illegal one first.
class ProtocolsRepository(private val supabase: SupabaseClient) {

    suspend fun loadProtocols(): List<ProtocolRow> = try {
        supabase.postgrest.from("protocols").select().decodeList<ProtocolRow>().filter { it.lifecycleState != "stopped" }.sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun loadTemplates(): List<ProtocolTemplateRow> = try {
        supabase.postgrest.from("protocol_templates").select().decodeList<ProtocolTemplateRow>().sortedBy { it.name }
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun loadMembers(): List<ProtocolMemberRow> = try {
        supabase.postgrest.from("protocol_interventions").select().decodeList<ProtocolMemberRow>()
    } catch (e: Exception) {
        emptyList()
    }

    /** Every intervention that can join a protocol, continuous or repeatable. */
    suspend fun loadInterventionNames(): List<InterventionNameRow> = try {
        supabase.postgrest.from("interventions").select(columns = Columns.list("id,name,mode,lifecycle_state")).decodeList<InterventionNameRow>()
            .filter { it.lifecycleState != "stopped" }.sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        emptyList()
    }

    /** First member is the primary; the rest are supporting. Rolled back if the members can't be written. */
    suspend fun createProtocol(name: String, objective: String, templateKey: String?, direction: String?, interventionIds: List<String>): String {
        require(name.trim().length >= 2) { "Give the protocol a name" }
        require(objective.isNotBlank()) { "Choose what the protocol is aiming at" }
        val id = UUID.randomUUID().toString()
        supabase.postgrest.from("protocols").insert(
            NewProtocol(id, name.trim().take(80), objective, templateKey, ProtocolLifecycleState.PROPOSED.key, direction, "app"),
        )
        if (interventionIds.isNotEmpty()) {
            try {
                supabase.postgrest.from("protocol_interventions").insert(
                    interventionIds.mapIndexed { i, iid -> NewProtocolMember(id, iid, if (i == 0) "primary" else "supporting", i) },
                )
            } catch (e: Exception) {
                runCatching { supabase.postgrest.from("protocols").delete { filter { eq("id", id) } } }
                throw e
            }
        }
        return id
    }

    suspend fun transition(protocol: ProtocolRow, to: ProtocolLifecycleState) {
        val from = ProtocolLifecycleState.fromKey(protocol.lifecycleState) ?: throw IllegalStateException("Unknown state ${protocol.lifecycleState}")
        require(isValidProtocolTransition(from, to)) { "A ${from.key} protocol can't become ${to.key}" }
        supabase.postgrest.from("protocols").update(mapOf("lifecycle_state" to to.key)) { filter { eq("id", protocol.id) } }
    }
}
