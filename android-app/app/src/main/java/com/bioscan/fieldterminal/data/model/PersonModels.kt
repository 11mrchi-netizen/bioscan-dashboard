package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PersonRow(val id: Long, val name: String)

@Serializable
data class NewPersonRow(
    val name: String,
    val relationship: String? = null,
    @SerialName("where_met") val whereMet: String? = null,
    val gender: String? = null,
    @SerialName("age_range") val ageRange: String? = null,
    val country: String? = null,
    val score: Int? = null,
    val notes: String? = null,
)
