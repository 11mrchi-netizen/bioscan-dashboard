package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Protocols (20261013_protocols.sql): a bundle of interventions around one objective, with its own coarse lifecycle.

@Serializable
data class ProtocolRow(
    val id: String,
    val name: String = "",
    @SerialName("objective_outcome_key") val objectiveOutcomeKey: String = "",
    @SerialName("template_key") val templateKey: String? = null,
    @SerialName("lifecycle_state") val lifecycleState: String = "proposed",
    @SerialName("expected_response_direction") val expectedResponseDirection: String? = null,
)

@Serializable
data class ProtocolTemplateRow(
    @SerialName("template_key") val templateKey: String,
    val name: String = "",
    @SerialName("objective_outcome_key") val objectiveOutcomeKey: String = "",
    val description: String = "",
)

@Serializable
data class ProtocolMemberRow(
    @SerialName("protocol_id") val protocolId: String,
    @SerialName("intervention_id") val interventionId: String,
    val role: String = "primary",
    @SerialName("sequence_order") val sequenceOrder: Int = 0,
)

@Serializable
data class InterventionNameRow(val id: String, val name: String = "", val mode: String = "continuous", @SerialName("lifecycle_state") val lifecycleState: String = "proposed")

@Serializable
data class NewProtocol(
    val id: String,
    val name: String,
    @SerialName("objective_outcome_key") val objectiveOutcomeKey: String,
    @SerialName("template_key") val templateKey: String?,
    @SerialName("lifecycle_state") val lifecycleState: String,
    @SerialName("expected_response_direction") val expectedResponseDirection: String?,
    val source: String,
)

@Serializable
data class NewProtocolMember(
    @SerialName("protocol_id") val protocolId: String,
    @SerialName("intervention_id") val interventionId: String,
    val role: String,
    @SerialName("sequence_order") val sequenceOrder: Int,
)
