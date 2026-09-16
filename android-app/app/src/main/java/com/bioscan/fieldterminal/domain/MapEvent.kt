package com.bioscan.fieldterminal.domain

// Phase M1 (Map tab rework). Replaces the single-event NextSession -- every
// calendar event in the next 24h becomes one of these, not just the one
// training-colored session. Framework-free, per this project's domain-layer
// purity convention; Health Connect and Calendar/Drive specifics stay in
// their own healthconnect/data packages.
data class MapEvent(
    val id: String?,
    val title: String,
    val startIso: String,
    val endIso: String?,
    val description: String,
    val location: String?,
    val colorId: String?,
    val gpxLink: String?,
)

// Where a MapEvent actually gets pinned -- isHomeFallback distinguishes "this
// event genuinely has no location" (or its address couldn't be geocoded)
// from a real address match, so the UI can skip offering DIRECTIONS to a
// pin that's just sitting on the user's own home coordinates.
data class MapPin(val event: MapEvent, val lat: Double, val lon: Double, val isHomeFallback: Boolean)

const val TRAINING_COLOR_ID = "8"
