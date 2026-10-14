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
    Wellness("WELLNESS"),
    Note("NOTE"),
    Ostrc("OSTRC"),
    SexualActivity("SEXUAL ACTIVITY"),
    Grip("GRIP"),
}

// FUEL's own three-way sub-picker, shown after FUEL is selected.
enum class FuelSubType(val label: String) {
    Food("FOOD"), Drink("DRINK"), Supplements("SUPPS"), Endurance("RUN FUEL"),
}

// Where the add sheet opens when it is not opened on its type picker (UX review P1: the Status hub's quick-log row).
// SayIt opens the one-message form; Fuel opens that Fuel form directly, optionally with one supplement time-of-day bundle
// already ticked (SupplementsToday.suggestedBucket).
sealed interface AddEntryStart {
    data object SayIt : AddEntryStart
    data class Fuel(val subType: FuelSubType, val supplementBundle: String? = null) : AddEntryStart
}
