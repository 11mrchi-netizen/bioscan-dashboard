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

// Phase M2. Flamingo (colorId '4') reconciles this project's own previously
// unresolved discrepancy (ROADMAP.md, P4/Push Notifications) between two
// candidate encounter-detection signals -- colorId '4' vs. a "Meet " title
// prefix. Here they're not competing: color gates which events are in scope
// at all, title text sub-classifies what kind. A flamingo event matching
// neither "meet" nor "party"/"munch"/"GB" still needs a real answer, not a
// silent guess -- it defaults to Social ("possible encounter, needs review"
// reads truer than quietly dropping it or treating it as training-adjacent).
private const val FLAMINGO_COLOR_ID = "4"

enum class MapEventCategory { Training, Encounter, Social, Other }

fun classifyMapEvent(colorId: String?, title: String): MapEventCategory = when (colorId) {
    TRAINING_COLOR_ID -> MapEventCategory.Training
    FLAMINGO_COLOR_ID -> {
        val t = title.lowercase()
        when {
            "meet" in t -> MapEventCategory.Encounter
            "party" in t || "munch" in t || "gb" in t -> MapEventCategory.Social
            else -> MapEventCategory.Social
        }
    }
    else -> MapEventCategory.Other
}
