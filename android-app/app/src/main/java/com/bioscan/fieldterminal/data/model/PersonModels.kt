package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.Serializable

// Phase M2. `people` (168 real rows, rich schema -- relationship, where_met,
// activities, ratings, ...) has had no UI anywhere in this project until
// now. Kept to just id/name here, matching every other table's "just the
// columns this app actually uses" convention -- the richer fields stay
// unread until a future phase actually surfaces them.
@Serializable
data class PersonRow(val id: Long, val name: String)

@Serializable
data class NewPersonRow(val name: String)
