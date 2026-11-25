package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Every encounters column the app reads, with the partner's name embedded through person_id.
const val ENCOUNTER_COLUMNS =
    "id,date,status,occurred_at,encounter_type,location_type,duration_min,activities,my_rating,notes,calendar_event_title," +
        "person_id,place_id,sexual,format,positions,toys,protection,ejaculation_location,rounds,orgasm_count,orgasm_intensity,people(name)"

// A person as the partners browser shows them: every profile column of `people` (PersonRow is the id and name only).
@Serializable
data class PersonProfileRow(
    val id: Long,
    val name: String,
    val period: String? = null,
    val score: Int? = null,
    @SerialName("where_met") val whereMet: String? = null,
    val relationship: String? = null,
    val gender: String? = null,
    val country: String? = null,
    @SerialName("age_range") val ageRange: String? = null,
    @SerialName("body_type") val bodyType: String? = null,
    val activities: List<String>? = null,
    @SerialName("interested_in") val interestedIn: List<String>? = null,
    @SerialName("first_met_date") val firstMetDate: String? = null,
    val availability: String? = null,
    val notes: String? = null,
)

const val PERSON_PROFILE_COLUMNS =
    "id,name,period,score,where_met,relationship,gender,country,age_range,body_type,activities,interested_in,first_met_date,availability,notes"

// One infection's result from one test (supabase/migrations/20261116141602_sti_tests.sql). A panel is several rows on one date.
@Serializable
data class StiTestRow(
    val id: Long,
    @SerialName("test_date") val testDate: String,
    val infection: String,
    val result: String,
    val site: String? = null,
    @SerialName("lab_name") val labName: String? = null,
    @SerialName("treated_on") val treatedOn: String? = null,
    val notes: String? = null,
)

@Serializable
data class NewStiTestRow(
    @SerialName("test_date") val testDate: String,
    val infection: String,
    val result: String,
    val site: String? = null,
    @SerialName("lab_name") val labName: String? = null,
    val notes: String? = null,
)

@Serializable
data class StiTreatedPatch(@SerialName("treated_on") val treatedOn: String?)
