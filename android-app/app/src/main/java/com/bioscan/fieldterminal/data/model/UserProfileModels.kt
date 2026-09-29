package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DAV-222/09A Aging Profile. One row per account -- the first place
// chronological age/sex are stored anywhere in this app. Backs
// domain/comparison/PopulationContext(ageYears, sex, ...), which existed but
// was always constructed with nulls before this table.
@Serializable
data class UserProfileRow(
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    // "male" / "female" only -- matches the sex-specific reference tables
    // (KDM, some functional-age norms) this milestone consumes.
    val sex: String? = null,
)
