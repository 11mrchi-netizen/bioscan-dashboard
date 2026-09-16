package com.bioscan.fieldterminal.domain

// Step 12 (Phase D)'s "+" flow's type picker options, revised 2026-09-15 per
// direct user request: Training removed (runs are no longer manually
// loggable -- there's no in-app way to add one now, by design), Food/Drink/
// Supplements collapsed into one FUEL entry point with its own three-way
// sub-picker (see AddEntrySheet.kt's FuelForm), Wellness added (the
// wellbeing_daily questionnaire previously had no add-entry path at all).
// Each remaining type maps to a real table (see data/AddEntryRepository.kt)
// -- no type appears here without a real place to write it.
enum class AddEntryType(val label: String) {
    Fuel("FUEL"),
    Encounter("ENCOUNTER"),
    Stool("STOOL"),
    Arousal("AROUSAL"),
    Wellness("WELLNESS"),
    Note("NOTE"),
    Ostrc("OSTRC"),
}

// FUEL's own three-way sub-picker, shown after FUEL is selected.
enum class FuelSubType(val label: String) {
    Food("FOOD"), Drink("DRINK"), Supplements("SUPPLEMENTS"),
}
