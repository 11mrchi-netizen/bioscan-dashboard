package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.ProtocolsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.InterventionNameRow
import com.bioscan.fieldterminal.data.model.ProtocolMemberRow
import com.bioscan.fieldterminal.data.model.ProtocolRow
import com.bioscan.fieldterminal.data.model.ProtocolTemplateRow

// The protocols, their members and the templates they start from.
@Stable
internal class ProtocolsState {
    private val repo = ProtocolsRepository(SupabaseClientProvider.client)

    var protocols by mutableStateOf<List<ProtocolRow>>(emptyList())
    var members by mutableStateOf<List<ProtocolMemberRow>>(emptyList())
    var interventions by mutableStateOf<List<InterventionNameRow>>(emptyList())
    var templates by mutableStateOf<List<ProtocolTemplateRow>>(emptyList())

    suspend fun load() {
        protocols = repo.loadProtocols()
        members = repo.loadMembers()
        interventions = repo.loadInterventionNames()
        templates = repo.loadTemplates()
    }
}
